package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.prob.animator.domainobjects.ErrorItem;
import de.prob.check.CheckError;
import de.prob.check.CheckInterrupted;
import de.prob.check.ModelCheckLimitReached;
import de.prob.check.ModelCheckOk;
import de.prob.exception.ProBError;
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
            .withCompletion(RunReport.CompletionReason.PROPERTY_VIOLATION)
            .withSearchStatistics(new RunReport.SearchStatistics(3, 2, 4))
            .withTraceFile(Path.of("trace.json"));

    assertEquals(RunReport.Status.VIOLATION, report.status());
    assertEquals("boom", report.message());
    assertEquals(List.of(check), report.checks());
    assertEquals(counterexample, report.counterexample());
    assertEquals(Path.of("trace.json"), report.traceFile());
    assertEquals(RunReport.CompletionReason.PROPERTY_VIOLATION, report.completion().reason());
    assertEquals(new RunReport.SearchStatistics(3, 2, 4), report.searchStatistics());
    assertNull(report.exitCodeOverride());
    assertEquals(1, report.exitCode());
  }

  @Test
  public void testConsistencyCompletionNormalizesEveryStopCategory() {
    assertCompletion(
        Animate.consistencyCompletion(
            new ModelCheckOk("Model Checking complete. No more error nodes found.")),
        RunReport.CompletionReason.EXHAUSTIVE);
    assertCompletion(
        Animate.consistencyCompletion(
            new ModelCheckOk("Model Checking complete. All operations were covered.")),
        RunReport.CompletionReason.COVERAGE_LIMIT);
    assertCompletion(
        Animate.consistencyCompletion(
            new ModelCheckOk(
                "Model Checking complete. No more error nodes found."
                    + " Not all nodes were considered.")),
        RunReport.CompletionReason.PARTIAL);
    assertCompletion(
        Animate.consistencyCompletion(new ModelCheckOk("A future partial result")),
        RunReport.CompletionReason.PARTIAL);
    assertCompletion(
        Animate.consistencyCompletion(
            new ModelCheckOk("model checking complete. all operations were covered.")),
        RunReport.CompletionReason.COVERAGE_LIMIT);
    assertCompletion(
        Animate.consistencyCompletion(new ModelCheckOk(null)), RunReport.CompletionReason.PARTIAL);
    assertCompletion(
        Animate.consistencyCompletion(new ModelCheckLimitReached("State limit reached")),
        RunReport.CompletionReason.STATE_LIMIT);
    assertCompletion(
        Animate.consistencyCompletion(new ModelCheckLimitReached("Time limit reached")),
        RunReport.CompletionReason.TIME_LIMIT);
    assertCompletion(
        Animate.consistencyCompletion(new CheckInterrupted()),
        RunReport.CompletionReason.INTERRUPTED);
    assertCompletion(
        Animate.consistencyCompletion(new CheckError("kernel failed")),
        RunReport.CompletionReason.MODEL_CHECK_FAILURE);
  }

  @Test
  public void testConstantSetupExceptionDistinguishesInfeasibilityFromFailure() {
    assertTrue(
        Animate.hasUnsatisfiableAxioms(
            new IllegalStateException(
                setupError("AXIOMS are unsatisfiable (but some valued)"))));
    assertFalse(
        Animate.hasUnsatisfiableAxioms(setupError("AXIOMS are unknown (but some valued)")));
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

  private static ProBError setupError(String headline) {
    return new ProBError(
        "ProB reported Errors",
        List.of(
            new ErrorItem(headline, ErrorItem.Type.ERROR, List.of()),
            new ErrorItem(
                "AXIOMS are not true and no value was found", ErrorItem.Type.MESSAGE, List.of())));
  }

  private static void assertCompletion(
      RunReport.Completion completion, RunReport.CompletionReason reason) {
    assertEquals(reason, completion.reason());
    assertEquals(reason.classification(), completion.classification());
  }
}
