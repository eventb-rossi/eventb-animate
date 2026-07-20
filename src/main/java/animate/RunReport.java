package animate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The findings of one command run. Every command returns one of these and {@link Animate#finishRun}
 * turns it into the process exit code, so the README's exit-code contract lives in exactly one
 * place instead of being re-encoded as integer literals in each command.
 */
record RunReport(
    Status status,
    String message,
    List<Check> checks,
    Finding finding,
    TraceWriter.Counterexample counterexample,
    List<StateEvaluation> evaluations,
    Path traceFile,
    Completion completion,
    SearchStatistics searchStatistics,
    Integer exitCodeOverride) {

  RunReport {
    checks = List.copyOf(checks);
    evaluations = List.copyOf(evaluations);
  }

  /** Run verdicts, ordered from best to worst; the exit codes follow the README contract. */
  enum Status {
    /** The requested check found nothing wrong (possibly within a stated bound). */
    OK,
    /** A definite negative verdict: violation, disproof, imperfect replay. */
    VIOLATION,
    /**
     * No verdict: the check could not run to completion or obligations remain unproven, so nothing
     * was shown either way.
     */
    INCOMPLETE,
    /** The run failed before producing a verdict: load, input, or conversion failure. */
    ERROR;

    int exitCode() {
      return switch (this) {
        case OK -> 0;
        case VIOLATION, ERROR -> 1;
        case INCOMPLETE -> 2;
      };
    }

    /** The lowercase report label (e.g. "ok"/"violation"); the one spelling every writer emits. */
    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Outcome of one named check within the run (one JUnit testcase later). */
  enum Outcome {
    PASSED,
    FAILED,
    SKIPPED,
    ERROR;

    /** The lowercase report label (e.g. "passed"/"failed"); the one spelling every writer emits. */
    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Machine-readable classification of how a model-checking run finished. */
  enum CompletionClassification {
    COMPLETE,
    COUNTEREXAMPLE,
    INCOMPLETE,
    ERROR;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The terminal phase in which a top-level model check stopped. */
  enum CompletionPhase {
    LOAD,
    CONSTANT_SETUP,
    INITIALIZATION,
    SEARCH;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Stable machine-readable reason for a model-checking completion classification. */
  enum CompletionReason {
    EXHAUSTIVE,
    PROOF,
    PROPERTY_VIOLATION,
    GOAL_REACHED,
    STATE_LIMIT,
    TIME_LIMIT,
    COVERAGE_LIMIT,
    PARTIAL,
    INTERRUPTED,
    INPUT_FAILURE,
    INFEASIBLE,
    EVALUATION_ERROR,
    ENGINE_FAILURE;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }

    CompletionClassification classification() {
      return switch (this) {
        case EXHAUSTIVE, PROOF -> CompletionClassification.COMPLETE;
        case PROPERTY_VIOLATION, GOAL_REACHED -> CompletionClassification.COUNTEREXAMPLE;
        case STATE_LIMIT, TIME_LIMIT, COVERAGE_LIMIT, PARTIAL, INTERRUPTED ->
            CompletionClassification.INCOMPLETE;
        case INPUT_FAILURE, INFEASIBLE, EVALUATION_ERROR, ENGINE_FAILURE ->
            CompletionClassification.ERROR;
      };
    }

    boolean validIn(CompletionPhase phase) {
      return switch (phase) {
        case LOAD -> this == INPUT_FAILURE;
        case CONSTANT_SETUP, INITIALIZATION ->
            this == INFEASIBLE
                || this == EVALUATION_ERROR
                || this == ENGINE_FAILURE
                || this == INTERRUPTED;
        case SEARCH -> this != INPUT_FAILURE && this != INFEASIBLE;
      };
    }
  }

  /** The structured outcome of the top-level model-check command. */
  record Completion(CompletionPhase phase, CompletionReason reason) {
    Completion {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(reason, "reason");
      if (!reason.validIn(phase)) {
        throw new IllegalArgumentException(
            "completion reason " + reason.label() + " is invalid in phase " + phase.label());
      }
    }

    CompletionClassification classification() {
      return reason.classification();
    }
  }

  /** Stable identity for the one definite finding that stopped a check. */
  enum FindingCategory {
    INVARIANT_VIOLATION,
    ASSERTION_VIOLATION,
    DEADLOCK,
    GOAL_REACHED,
    STATE_EVALUATION_ERROR,
    WELL_DEFINEDNESS_ERROR,
    LTL_VIOLATION,
    UNKNOWN;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  record Finding(FindingCategory category, String check) {
    Finding {
      Objects.requireNonNull(category, "category");
      if (check == null || check.isBlank()) {
        throw new IllegalArgumentException("finding check must not be blank");
      }
    }
  }

  /** Final counters supplied by the model-checking engine. */
  record SearchStatistics(int statesDiscovered, int statesProcessed, int transitions) {
    SearchStatistics {
      if (statesDiscovered < 0 || statesProcessed < 0 || transitions < 0) {
        throw new IllegalArgumentException("search statistics must be non-negative");
      }
    }
  }

  record Check(String name, Outcome outcome, String message) {}

  /**
   * One formula's evaluated value in one state. {@code value} is the printed value (an expression's
   * result, or {@code TRUE}/{@code FALSE} for a predicate) when {@code error} is false, or the
   * error text ProB returned when {@code error} is true (a value it could not compute).
   */
  record FormulaValue(String formula, String value, boolean error) {}

  /**
   * The formulas evaluated in one state, labelled by {@code state} -- a state id, or a role such as
   * "initialised state" / "violating state". Evaluations are observations, not pass/fail checks, so
   * they live outside {@link #checks} and never become JUnit testcases.
   */
  record StateEvaluation(String state, List<FormulaValue> values) {
    StateEvaluation {
      values = List.copyOf(values);
    }
  }

  static RunReport of(Status status, String message, Check... checks) {
    return of(status, message, List.of(checks));
  }

  static RunReport of(Status status, String message, List<Check> checks) {
    return new RunReport(status, message, checks, null, null, List.of(), null, null, null, null);
  }

  /**
   * A single open (unproven) finding: the run has no verdict (exit 2), but the gate check itself
   * failed, so CI dashboards show the finding rather than a tool error.
   */
  static RunReport openFinding(String checkName, String message) {
    return of(Status.INCOMPLETE, message, new Check(checkName, Outcome.FAILED, message));
  }

  /**
   * Combines the reports of independent analyses run in one invocation. A found violation outranks
   * an incomplete analysis (the definite verdict is the actionable one), which outranks a clean
   * pass. The message comes from the first part with the merged status; the evidence
   * (counterexample, saved trace) from the first part that carries it.
   */
  static RunReport merge(List<RunReport> parts) {
    if (parts.size() == 1) {
      return parts.get(0);
    }
    Status status = Status.OK;
    for (RunReport part : parts) {
      if (part.status() == Status.VIOLATION) {
        status = Status.VIOLATION;
        break;
      }
      if (part.status() == Status.INCOMPLETE) {
        status = Status.INCOMPLETE;
      }
    }
    String message = null;
    TraceWriter.Counterexample counterexample = null;
    Finding finding = null;
    Path traceFile = null;
    List<Check> checks = new ArrayList<>();
    List<StateEvaluation> evaluations = new ArrayList<>();
    for (RunReport part : parts) {
      if (message == null && part.status() == status) {
        message = part.message();
      }
      if (counterexample == null) {
        counterexample = part.counterexample();
      }
      if (finding == null) {
        finding = part.finding();
      }
      if (traceFile == null) {
        traceFile = part.traceFile();
      }
      checks.addAll(part.checks());
      evaluations.addAll(part.evaluations());
    }
    return new RunReport(
        status, message, checks, finding, counterexample, evaluations, traceFile, null, null, null);
  }

  /**
   * A report whose single check mirrors the run verdict -- the common shape for one-check commands.
   * The check outcome is derived from the status so the pairing exists as one rule here instead of
   * being re-spelled at every call site.
   */
  static RunReport singleCheck(Status status, String checkName, String message) {
    Outcome outcome =
        switch (status) {
          case OK -> Outcome.PASSED;
          case VIOLATION -> Outcome.FAILED;
          case INCOMPLETE, ERROR -> Outcome.ERROR;
        };
    return of(status, message, new Check(checkName, outcome, message));
  }

  /**
   * The checks to surface, or a single synthesized {@code run} row derived from the verdict when a
   * run recorded none (e.g. a load failure). Report writers that must show at least one row -- the
   * JUnit suite and the Markdown table -- share this so an empty run renders identically in both.
   */
  List<Check> checksOrSynthesized() {
    if (!checks.isEmpty()) {
      return checks;
    }
    Outcome outcome = status == Status.OK ? Outcome.PASSED : Outcome.ERROR;
    return List.of(new Check("run", outcome, message));
  }

  RunReport withCounterexample(TraceWriter.Counterexample counterexample) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  /** Attaches the per-state formula evaluations surfaced by --eval and the eval subcommand. */
  RunReport withEvaluations(List<StateEvaluation> evaluations) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  RunReport withTraceFile(Path traceFile) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  RunReport withCompletion(CompletionPhase phase, CompletionReason reason) {
    return withCompletion(new Completion(phase, reason));
  }

  RunReport withCompletion(Completion completion) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  RunReport withSearchStatistics(SearchStatistics searchStatistics) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  /** For commands that must propagate a foreign exit code (convert passes probcli's through). */
  RunReport withExitCode(int exitCode) {
    return new RunReport(
        status,
        message,
        checks,
        finding,
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCode);
  }

  RunReport withFinding(FindingCategory category, String check) {
    return new RunReport(
        status,
        message,
        checks,
        new Finding(category, check),
        counterexample,
        evaluations,
        traceFile,
        completion,
        searchStatistics,
        exitCodeOverride);
  }

  int exitCode() {
    return exitCodeOverride != null ? exitCodeOverride : status.exitCode();
  }

  /**
   * A command's findings plus everything {@link Animate#finishRun} stamps around them when a
   * machine-readable report was requested: what ran, on which model, with which ProB, for how long.
   * {@code machine} and {@code probVersion} are null when the model never loaded.
   */
  record Envelope(
      String command,
      Path model,
      String machine,
      String probVersion,
      String toolVersion,
      Instant timestamp,
      long durationMs,
      int exitCode,
      RunReport report) {

    /**
     * The most specific label for the run: the machine, else the model's file name, else the tool
     * name. Never null or empty -- JUnit needs a non-empty classname and the Markdown title needs a
     * subject.
     */
    String displayName() {
      if (machine != null) {
        return machine;
      }
      Path fileName = model == null ? null : model.getFileName();
      return fileName == null ? Animate.TOOL_NAME : fileName.toString();
    }

    /**
     * The run timestamp as an ISO-8601 instant, the one format the JSON and Markdown reports share.
     */
    String isoTimestamp() {
      return DateTimeFormatter.ISO_INSTANT.format(timestamp);
    }
  }
}
