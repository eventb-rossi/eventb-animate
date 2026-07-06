package animate;

import static org.junit.Assert.*;

import de.prob.check.tracereplay.ReplayedTrace;
import de.prob.check.tracereplay.TraceReplayStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

public class ReplayCommandTest {

  private static final Path TRAFFIC_LIGHT = Paths.get("src/test/resources/models/traffic-light");

  @Test(timeout = 60000)
  public void testReplaySameModelTrace() throws Exception {
    // base-model/M1 reaches an invariant-violating state, so model-checking finds a counterexample
    // and --save writes it; that saved trace must then replay perfectly against the same model.
    Path model = Paths.get("src/test/resources/models/base-model/M1.bum");
    Path traceFile = Files.createTempFile("animate-trace-", ".json");

    try {
      TestCli.Result saveResult = TestCli.execute("--save", traceFile.toString(), model.toString());
      assertEquals(
          "Model-checking should find a counterexample:\n" + saveResult.output(),
          1,
          saveResult.exitCode());
      assertTrue("Counterexample trace should be written", Files.size(traceFile) > 0);
      assertTrue(
          "The save confirmation must stay visible without --debug:\n" + saveResult.output(),
          saveResult.output().contains("Saving counterexample trace to"));

      TestCli.Result replayResult =
          TestCli.execute("replay", "-t", traceFile.toString(), model.toString());

      assertEquals("Replay should succeed", 0, replayResult.exitCode());
      assertTrue(
          "Replay output should include the final status",
          replayResult.output().contains("Trace replay status: PERFECT"));
    } finally {
      Files.deleteIfExists(traceFile);
    }
  }

  @Test
  public void testReplayExitCodesFollowStatus() {
    RunReport perfect = ReplayCommand.reportFor(replayedTrace(TraceReplayStatus.PERFECT));
    assertEquals(RunReport.Status.OK, perfect.status());
    assertEquals(0, perfect.exitCode());

    RunReport partial = ReplayCommand.reportFor(replayedTrace(TraceReplayStatus.PARTIAL));
    assertEquals(RunReport.Status.VIOLATION, partial.status());
    assertEquals(1, partial.exitCode());

    RunReport imperfect = ReplayCommand.reportFor(replayedTrace(TraceReplayStatus.IMPERFECT));
    assertEquals(RunReport.Status.VIOLATION, imperfect.status());
    assertEquals(1, imperfect.exitCode());
  }

  @Test
  public void testRefineReportVerdict() {
    // A result reproducing at least the source's steps is a pass; a shorter one means the target
    // could not reproduce the trace.
    RunReport ok = ReplayCommand.refineReport(2, 2, "M1");
    assertEquals(RunReport.Status.OK, ok.status());
    assertEquals(0, ok.exitCode());
    assertTrue(ok.message().contains("M1"));

    assertEquals(RunReport.Status.OK, ReplayCommand.refineReport(3, 2, "M1").status());

    RunReport shortResult = ReplayCommand.refineReport(1, 2, "M1");
    assertEquals(RunReport.Status.VIOLATION, shortResult.status());
    assertEquals(1, shortResult.exitCode());
    assertEquals(ReplayCommand.NO_ADAPTATION_MESSAGE, shortResult.message());

    assertEquals(RunReport.Status.VIOLATION, ReplayCommand.refineReport(0, 2, "M1").status());
    // Reproducing nothing is never a success, even when the source itself was empty.
    assertEquals(RunReport.Status.VIOLATION, ReplayCommand.refineReport(0, 0, "M1").status());
  }

  @Test(timeout = 90000)
  public void testRefineAdaptsAcrossRefinement() throws Exception {
    // The abstract event set_peds_go is renamed to set_peds_green in M1; --refine must rename it,
    // and the adapted trace must then replay perfectly against M1.
    Path source = saveAbstractTrace("M0", "peds_go=TRUE");
    Path adapted = Files.createTempFile("animate-refined-", ".json");
    try {
      TestCli.Result refine =
          TestCli.execute(
              "replay",
              "--refine",
              "-m",
              "M1",
              "-t",
              source.toString(),
              "--save",
              adapted.toString(),
              TRAFFIC_LIGHT.toString());
      assertEquals("Refinement should succeed:\n" + refine.output(), 0, refine.exitCode());
      assertTrue(
          "Adapted trace should use the M1 event name:\n" + refine.output(),
          refine.output().contains("set_peds_green"));
      assertTrue("Adapted trace should be written", Files.size(adapted) > 0);

      TestCli.Result replay =
          TestCli.execute("replay", "-m", "M1", "-t", adapted.toString(), TRAFFIC_LIGHT.toString());
      assertEquals("Adapted trace should replay on M1:\n" + replay.output(), 0, replay.exitCode());
      assertTrue(replay.output().contains("Trace replay status: PERFECT"));
    } finally {
      Files.deleteIfExists(source);
      Files.deleteIfExists(adapted);
    }
  }

  @Test(timeout = 90000)
  public void testRefineFailsWhenTargetCannotReproduce() throws Exception {
    // M2 guards set_peds_green behind a push_button the abstract trace has no source for, so no
    // adaptation exists: a definite negative verdict (exit 1), not a crash.
    Path source = saveAbstractTrace("M0", "peds_go=TRUE");
    try {
      TestCli.Result refine =
          TestCli.execute(
              "replay", "--refine", "-m", "M2", "-t", source.toString(), TRAFFIC_LIGHT.toString());
      assertEquals("Unreproducible trace is exit 1:\n" + refine.output(), 1, refine.exitCode());
      assertTrue(
          "Failure should be the no-adaptation message:\n" + refine.output(),
          refine.output().contains("No adaptation found within the configured search bounds"));
    } finally {
      Files.deleteIfExists(source);
    }
  }

  @Test(timeout = 30000)
  public void testRefineOnlyOptionsRequireRefine() {
    TestCli.Result result =
        TestCli.execute(
            "replay",
            "--save",
            "/tmp/should-not-write.json",
            "-m",
            "M1",
            "-t",
            "/tmp/whatever.json",
            TRAFFIC_LIGHT.toString());
    assertEquals("Misusing a refine-only option is a usage error", 2, result.exitCode());
    assertTrue(
        "Should explain that --save needs --refine:\n" + result.output(),
        result.output().contains("--save may be used only with --refine"));
  }

  @Test(timeout = 30000)
  public void testRefineBoundsMustBePositive() {
    TestCli.Result result =
        TestCli.execute(
            "replay",
            "--refine",
            "--refine-depth",
            "0",
            "-m",
            "M1",
            "-t",
            "/tmp/whatever.json",
            TRAFFIC_LIGHT.toString());
    assertEquals(2, result.exitCode());
    assertTrue(
        "Should reject non-positive depth:\n" + result.output(),
        result.output().contains("--refine-depth must be positive"));
  }

  @Test(timeout = 30000)
  public void testRefineSaveRejectsDirectory() {
    // A bad --save destination must fail up front (exit 2), before the adaptation runs, so a found
    // adaptation is never computed only to be lost.
    TestCli.Result result =
        TestCli.execute(
            "replay",
            "--refine",
            "--save",
            TRAFFIC_LIGHT.toString(),
            "-m",
            "M1",
            "-t",
            "/tmp/whatever.json",
            TRAFFIC_LIGHT.toString());
    assertEquals(2, result.exitCode());
    assertTrue(
        "Should reject a directory --save target:\n" + result.output(),
        result.output().contains("--save target is a directory"));
  }

  /** Forces a short abstract trace by searching for a reachable state, saved as ProB trace JSON. */
  private static Path saveAbstractTrace(String machine, String goal) throws Exception {
    Path trace = Files.createTempFile("animate-abstract-", ".json");
    TestCli.Result save =
        TestCli.execute(
            "--save",
            trace.toString(),
            "-m",
            machine,
            "--goal",
            goal,
            "--no-invariant",
            "--no-deadlock",
            TRAFFIC_LIGHT.toString());
    assertEquals(
        "The goal search should hit and save a trace:\n" + save.output(), 1, save.exitCode());
    assertTrue("An abstract trace should be written", Files.size(trace) > 0);
    return trace;
  }

  private static ReplayedTrace replayedTrace(TraceReplayStatus status) {
    return new ReplayedTrace(
        status,
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList());
  }
}
