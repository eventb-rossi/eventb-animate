package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class RunReportTest {

  @Test
  public void testStatusExitCodesFollowReadmeContract() {
    assertEquals(0, RunReport.of(RunReport.Status.OK, "clean").exitCode());
    assertEquals(1, RunReport.of(RunReport.Status.VIOLATION, "violated").exitCode());
    assertEquals(1, RunReport.of(RunReport.Status.ERROR, "failed").exitCode());
    assertEquals(2, RunReport.of(RunReport.Status.INCOMPLETE, "no verdict").exitCode());
  }

  @Test
  public void testExitCodeOverrideWinsOverStatus() {
    RunReport report = RunReport.of(RunReport.Status.ERROR, "probcli failed").withExitCode(3);
    assertEquals(3, report.exitCode());
    assertEquals(RunReport.Status.ERROR, report.status());
  }

  @Test
  public void testChecksListIsImmutable() {
    RunReport report =
        RunReport.of(
            RunReport.Status.OK,
            "clean",
            new RunReport.Check("invariant", RunReport.Outcome.PASSED, null));
    assertThrows(
        UnsupportedOperationException.class,
        () -> report.checks().add(new RunReport.Check("x", RunReport.Outcome.FAILED, null)));
  }

  @Test
  public void testWithersPreserveTheOtherFields() {
    RunReport.Check check = new RunReport.Check("invariant", RunReport.Outcome.FAILED, "boom");
    TraceWriter.Counterexample counterexample =
        new TraceWriter.Counterexample(List.of("init"), "state", List.of("inv1"));

    RunReport report =
        RunReport.of(RunReport.Status.VIOLATION, "boom", check)
            .withCounterexample(counterexample)
            .withTraceFile(Path.of("trace.json"));

    assertEquals(RunReport.Status.VIOLATION, report.status());
    assertEquals("boom", report.message());
    assertEquals(List.of(check), report.checks());
    assertEquals(counterexample, report.counterexample());
    assertEquals(Path.of("trace.json"), report.traceFile());
    assertNull(report.exitCodeOverride());
    assertEquals(1, report.exitCode());
  }

  @Test
  public void testSingleCheckDerivesTheOutcomeFromTheStatus() {
    assertEquals(RunReport.Outcome.PASSED, singleCheckOutcome(RunReport.Status.OK));
    assertEquals(RunReport.Outcome.FAILED, singleCheckOutcome(RunReport.Status.VIOLATION));
    assertEquals(RunReport.Outcome.ERROR, singleCheckOutcome(RunReport.Status.INCOMPLETE));
    assertEquals(RunReport.Outcome.ERROR, singleCheckOutcome(RunReport.Status.ERROR));
  }

  private static RunReport.Outcome singleCheckOutcome(RunReport.Status status) {
    RunReport report = RunReport.singleCheck(status, "check", "message");
    assertEquals(1, report.checks().size());
    assertEquals("check", report.checks().get(0).name());
    assertEquals("message", report.checks().get(0).message());
    return report.checks().get(0).outcome();
  }
}
