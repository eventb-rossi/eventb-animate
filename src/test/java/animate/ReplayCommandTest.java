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

  @Test(timeout = 30000)
  public void testReplaySameModelTrace() throws Exception {
    Path model = Paths.get("src/test/resources/models/binary-search/M3.bum");
    Path traceFile = Files.createTempFile("animate-trace-", ".json");

    try {
      TestCli.Result saveResult =
          TestCli.execute("--steps", "3", "--save", traceFile.toString(), model.toString());
      assertEquals("Saving trace should succeed", 0, saveResult.exitCode());

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
    assertEquals(0, ReplayCommand.exitCodeFor(replayedTrace(TraceReplayStatus.PERFECT)));
    assertEquals(1, ReplayCommand.exitCodeFor(replayedTrace(TraceReplayStatus.PARTIAL)));
    assertEquals(1, ReplayCommand.exitCodeFor(replayedTrace(TraceReplayStatus.IMPERFECT)));
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
