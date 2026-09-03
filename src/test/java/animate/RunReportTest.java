package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import de.prob.animator.CommandInterruptedException;
import de.prob.animator.command.SymbolicModelcheckCommand;
import de.prob.animator.domainobjects.ErrorItem;
import de.prob.animator.domainobjects.LTL;
import de.prob.check.CheckError;
import de.prob.check.CheckInterrupted;
import de.prob.check.LTLNotYetFinished;
import de.prob.check.LTLOk;
import de.prob.check.ModelCheckErrorUncovered;
import de.prob.check.ModelCheckLimitReached;
import de.prob.check.ModelCheckOk;
import de.prob.exception.ProBError;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RunReportTest {

  /** Exit 1 is reserved for a verdict about the model; every failure gets its own code. */
  @Test
  public void testStatusExitCodesFollowReadmeContract() {
    assertEquals(0, RunReport.of(RunReport.Status.OK, "clean").exitCode());
    assertEquals(1, RunReport.of(RunReport.Status.VIOLATION, "violated").exitCode());
    assertEquals(2, RunReport.of(RunReport.Status.INCOMPLETE, "no verdict").exitCode());
    assertEquals(66, RunReport.of(RunReport.Status.INPUT_ERROR, "unusable input").exitCode());
    assertEquals(70, RunReport.of(RunReport.Status.INTERNAL_ERROR, "tool failed").exitCode());
  }

  /** Both failure statuses stay spelled "error" outside the tool, so v4 keeps the v3 vocabulary. */
  @Test
  public void testBothFailureStatusesReportAsError() {
    assertEquals("error", RunReport.Status.INPUT_ERROR.label());
    assertEquals("error", RunReport.Status.INTERNAL_ERROR.label());
    assertEquals("incomplete", RunReport.Status.INCOMPLETE.label());
  }

  /** The reverse lookup emitReports relies on, and the distinctness that makes it well-defined. */
  @Test
  public void testForExitCodeInvertsExitCode() {
    for (RunReport.Status status : RunReport.Status.values()) {
      assertEquals(status, RunReport.Status.forExitCode(status.exitCode()));
    }
    assertEquals(RunReport.Status.INTERNAL_ERROR, RunReport.Status.forExitCode(99));
  }

  /** Distinct codes are what lets a caller tell the statuses apart from $? alone. */
  @Test
  public void testEveryStatusHasItsOwnExitCode() {
    long distinct =
        Arrays.stream(RunReport.Status.values())
            .mapToInt(RunReport.Status::exitCode)
            .distinct()
            .count();
    assertEquals(RunReport.Status.values().length, distinct);
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
        new TraceWriter.Counterexample(List.of("init"), "state", List.of("inv1"), List.of());

    RunReport report =
        RunReport.of(RunReport.Status.VIOLATION, "boom", check)
            .withFinding(RunReport.FindingCategory.INVARIANT_VIOLATION)
            .withCounterexample(counterexample)
            .withCompletion(
                RunReport.CompletionPhase.SEARCH, RunReport.CompletionReason.PROPERTY_VIOLATION)
            .withSearchStatistics(new RunReport.SearchStatistics(3, 2, 4))
            .withTraceFile(Path.of("trace.json"));

    assertEquals(RunReport.Status.VIOLATION, report.status());
    assertEquals("boom", report.message());
    assertEquals(List.of(check), report.checks());
    assertEquals(RunReport.FindingCategory.INVARIANT_VIOLATION, report.finding().category());
    assertEquals(counterexample, report.counterexample());
    assertEquals(Path.of("trace.json"), report.traceFile());
    assertEquals(RunReport.CompletionReason.PROPERTY_VIOLATION, report.completion().reason());
    assertEquals(new RunReport.SearchStatistics(3, 2, 4), report.searchStatistics());
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
        RunReport.CompletionReason.ENGINE_FAILURE);
  }

  @Test
  public void testInitializationPhaseExceptionDistinguishesInfeasibilityFromFailure() {
    RuntimeException unsatisfiable =
        new IllegalStateException(setupError("AXIOMS are unsatisfiable (but some valued)"));
    assertEquals(
        RunReport.CompletionReason.INFEASIBLE,
        Animate.initializationPhaseFailureReason(
            RunReport.CompletionPhase.CONSTANT_SETUP, unsatisfiable));

    assertEquals(
        RunReport.CompletionReason.ENGINE_FAILURE,
        Animate.initializationPhaseFailureReason(
            RunReport.CompletionPhase.CONSTANT_SETUP,
            setupError("AXIOMS are unknown (but some valued)")));

    assertEquals(
        RunReport.CompletionReason.INFEASIBLE,
        Animate.initializationPhaseFailureReason(
            RunReport.CompletionPhase.INITIALIZATION,
            new IllegalStateException("INITIALISATION FAILS")));

    assertEquals(
        RunReport.CompletionReason.INTERRUPTED,
        Animate.initializationPhaseFailureReason(
            RunReport.CompletionPhase.CONSTANT_SETUP,
            new CommandInterruptedException("interrupted", List.of())));
  }

  @Test
  public void testLtlCompletionNormalizesSuccessLimitInterruptionAndFailure() throws Exception {
    LTL formula = LTL.parseEventB("G {TRUE}");
    assertLtlCompletion(new LTLOk(formula), false, RunReport.CompletionReason.EXHAUSTIVE);
    assertLtlCompletion(
        new LTLNotYetFinished(formula), true, RunReport.CompletionReason.STATE_LIMIT);
    assertLtlCompletion(new LTLNotYetFinished(formula), false, RunReport.CompletionReason.PARTIAL);
    assertLtlCompletion(new CheckInterrupted(), false, RunReport.CompletionReason.INTERRUPTED);
    assertLtlCompletion(
        new CheckError("formula failed"), false, RunReport.CompletionReason.ENGINE_FAILURE);
  }

  @Test
  public void testSymbolicCompletionNormalizesEveryResult() {
    assertSymbolicCompletion(
        SymbolicModelcheckCommand.ResultType.SUCCESSFUL, RunReport.CompletionReason.PROOF);
    assertSymbolicCompletion(
        SymbolicModelcheckCommand.ResultType.COUNTER_EXAMPLE,
        RunReport.CompletionReason.PROPERTY_VIOLATION);
    assertSymbolicCompletion(
        SymbolicModelcheckCommand.ResultType.TIMEOUT, RunReport.CompletionReason.PARTIAL);
    assertSymbolicCompletion(
        SymbolicModelcheckCommand.ResultType.LIMIT_REACHED, RunReport.CompletionReason.PARTIAL);
    assertSymbolicCompletion(
        SymbolicModelcheckCommand.ResultType.INTERRUPTED, RunReport.CompletionReason.INTERRUPTED);
    assertSymbolicCompletion(null, RunReport.CompletionReason.ENGINE_FAILURE);
  }

  @Test
  public void testSymbolicExceptionDistinguishesInterruptionFromFailure() {
    RunReport.Completion interrupted =
        Animate.symbolicFailureCompletion(
            new CommandInterruptedException("interrupted", List.of()));
    assertCompletion(interrupted, RunReport.CompletionReason.INTERRUPTED);

    RunReport.Completion failed =
        Animate.symbolicFailureCompletion(new IllegalStateException("failed"));
    assertCompletion(failed, RunReport.CompletionReason.ENGINE_FAILURE);
  }

  @Test
  public void testCompletionDerivesClassificationFromReason() {
    for (RunReport.CompletionReason reason : RunReport.CompletionReason.values()) {
      RunReport.CompletionPhase phase =
          switch (reason) {
            case INPUT_FAILURE -> RunReport.CompletionPhase.LOAD;
            case INFEASIBLE -> RunReport.CompletionPhase.INITIALIZATION;
            default -> RunReport.CompletionPhase.SEARCH;
          };
      assertCompletion(new RunReport.Completion(phase, reason), reason);
    }
  }

  @Test
  public void testCompletionRejectsContradictoryPhaseAndReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunReport.Completion(
                RunReport.CompletionPhase.INITIALIZATION, RunReport.CompletionReason.EXHAUSTIVE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunReport.Completion(
                RunReport.CompletionPhase.SEARCH, RunReport.CompletionReason.INFEASIBLE));
  }

  @Test
  public void testBuiltInFindingCategoriesUseExactKernelResults() {
    assertFinding(
        "Invariant violation found.", RunReport.FindingCategory.INVARIANT_VIOLATION, "invariant");
    assertFinding(
        "Assertion violation found.", RunReport.FindingCategory.ASSERTION_VIOLATION, "assertions");
    assertFinding("Deadlock found.", RunReport.FindingCategory.DEADLOCK, "deadlock");
    assertFinding(
        "A state error occured.",
        RunReport.FindingCategory.STATE_EVALUATION_ERROR,
        "state-evaluation");
    assertFinding(
        "XTL error (unsafe state) found.",
        RunReport.FindingCategory.STATE_EVALUATION_ERROR,
        "state-evaluation");
    assertFinding(
        "A well definedness error occured.",
        RunReport.FindingCategory.WELL_DEFINEDNESS_ERROR,
        "well-definedness");
    assertFinding(
        "prefix: Invariant violation found.", RunReport.FindingCategory.UNKNOWN, "consistency");
  }

  @Test
  public void testCounterexampleEvidenceFailurePreservesDefiniteReport() {
    RunReport report =
        RunReport.singleCheck(RunReport.Status.VIOLATION, "invariant", "violated")
            .withCompletion(
                RunReport.CompletionPhase.SEARCH, RunReport.CompletionReason.PROPERTY_VIOLATION);

    RunReport preserved =
        Animate.withCounterexampleEvidence(
            report,
            "simulated evidence failure",
            () -> {
              throw new IllegalStateException("trace unavailable");
            });

    assertSame(report, preserved);
  }

  @Test
  public void testLtsminCompletionNormalizesEveryVerdict() {
    assertLtsminCompletion(LtsminSupport.Verdict.OK, RunReport.CompletionReason.EXHAUSTIVE);
    assertLtsminCompletion(
        LtsminSupport.Verdict.VIOLATION, RunReport.CompletionReason.PROPERTY_VIOLATION);
    assertLtsminCompletion(
        LtsminSupport.Verdict.INTERRUPTED, RunReport.CompletionReason.INTERRUPTED);
    assertLtsminCompletion(
        LtsminSupport.Verdict.INCOMPLETE, RunReport.CompletionReason.ENGINE_FAILURE);
    assertLtsminCompletion(LtsminSupport.Verdict.TIMED_OUT, RunReport.CompletionReason.TIME_LIMIT);
  }

  @Test
  public void testSingleCheckDerivesTheOutcomeFromTheStatus() {
    assertEquals(RunReport.Outcome.PASSED, singleCheckOutcome(RunReport.Status.OK));
    assertEquals(RunReport.Outcome.FAILED, singleCheckOutcome(RunReport.Status.VIOLATION));
    assertEquals(RunReport.Outcome.ERROR, singleCheckOutcome(RunReport.Status.INCOMPLETE));
    assertEquals(RunReport.Outcome.ERROR, singleCheckOutcome(RunReport.Status.INPUT_ERROR));
    assertEquals(RunReport.Outcome.ERROR, singleCheckOutcome(RunReport.Status.INTERNAL_ERROR));
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

  private static void assertFinding(
      String message, RunReport.FindingCategory category, String check) {
    RunReport.Finding finding =
        Animate.findingFor(new ModelCheckErrorUncovered(message, "state-id"));
    assertEquals(category, finding.category());
    assertEquals(check, finding.check());
  }

  private static void assertCompletion(
      RunReport.Completion completion, RunReport.CompletionReason reason) {
    assertEquals(reason, completion.reason());
    assertEquals(
        switch (reason) {
          case INPUT_FAILURE -> RunReport.CompletionPhase.LOAD;
          case INFEASIBLE -> RunReport.CompletionPhase.INITIALIZATION;
          default -> RunReport.CompletionPhase.SEARCH;
        },
        completion.phase());
    assertEquals(reason.classification(), completion.classification());
  }

  private static void assertLtlCompletion(
      de.prob.check.IModelCheckingResult result,
      boolean stateLimitConfigured,
      RunReport.CompletionReason reason) {
    assertCompletion(Animate.ltlCompletion(result, stateLimitConfigured), reason);
  }

  private static void assertSymbolicCompletion(
      SymbolicModelcheckCommand.ResultType result, RunReport.CompletionReason reason) {
    assertCompletion(Animate.symbolicCompletion(result), reason);
  }

  private static void assertLtsminCompletion(
      LtsminSupport.Verdict verdict, RunReport.CompletionReason reason) {
    assertCompletion(Animate.ltsminCompletion(verdict), reason);
  }
}
