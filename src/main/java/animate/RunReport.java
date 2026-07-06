package animate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The findings of one command run. Every command returns one of these and {@link Animate#finishRun}
 * turns it into the process exit code, so the README's exit-code contract lives in exactly one
 * place instead of being re-encoded as integer literals in each command.
 */
record RunReport(
    Status status,
    String message,
    List<Check> checks,
    TraceWriter.Counterexample counterexample,
    List<StateEvaluation> evaluations,
    Path traceFile,
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
  }

  /** Outcome of one named check within the run (one JUnit testcase later). */
  enum Outcome {
    PASSED,
    FAILED,
    SKIPPED,
    ERROR
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
    return new RunReport(status, message, checks, null, List.of(), null, null);
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
      if (traceFile == null) {
        traceFile = part.traceFile();
      }
      checks.addAll(part.checks());
      evaluations.addAll(part.evaluations());
    }
    return new RunReport(status, message, checks, counterexample, evaluations, traceFile, null);
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
        status, message, checks, counterexample, evaluations, traceFile, exitCodeOverride);
  }

  /** Attaches the per-state formula evaluations surfaced by --eval and the eval subcommand. */
  RunReport withEvaluations(List<StateEvaluation> evaluations) {
    return new RunReport(
        status, message, checks, counterexample, evaluations, traceFile, exitCodeOverride);
  }

  RunReport withTraceFile(Path traceFile) {
    return new RunReport(
        status, message, checks, counterexample, evaluations, traceFile, exitCodeOverride);
  }

  /** For commands that must propagate a foreign exit code (convert passes probcli's through). */
  RunReport withExitCode(int exitCode) {
    return new RunReport(status, message, checks, counterexample, evaluations, traceFile, exitCode);
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
  }
}
