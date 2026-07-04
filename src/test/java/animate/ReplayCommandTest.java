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

  private static ReplayedTrace replayedTrace(TraceReplayStatus status) {
    return new ReplayedTrace(
        status,
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList());
  }
}
