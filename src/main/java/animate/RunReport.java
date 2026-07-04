package animate;

import java.nio.file.Path;
import java.time.Instant;
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
    Path traceFile,
    Integer exitCodeOverride) {

  RunReport {
    checks = List.copyOf(checks);
  }

  /** Run verdicts, ordered from best to worst; the exit codes follow the README contract. */
  enum Status {
    /** The requested check found nothing wrong (possibly within a stated bound). */
    OK,
    /** A definite negative verdict: violation, undischarged obligation, imperfect replay. */
    VIOLATION,
    /** No verdict: the check could not run to completion, so nothing was proven. */
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

  static RunReport of(Status status, String message, Check... checks) {
    return of(status, message, List.of(checks));
  }

  static RunReport of(Status status, String message, List<Check> checks) {
    return new RunReport(status, message, checks, null, null, null);
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

  RunReport withCounterexample(TraceWriter.Counterexample counterexample) {
    return new RunReport(status, message, checks, counterexample, traceFile, exitCodeOverride);
  }

  RunReport withTraceFile(Path traceFile) {
    return new RunReport(status, message, checks, counterexample, traceFile, exitCodeOverride);
  }

  /** For commands that must propagate a foreign exit code (convert passes probcli's through). */
  RunReport withExitCode(int exitCode) {
    return new RunReport(status, message, checks, counterexample, traceFile, exitCode);
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
      RunReport report) {}
}
