package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** CLI validation for the LTSmin-specific option surface; none of these tests starts ProB. */
public class LtsminOptionsTest {

  private static final String MODEL =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

  @Test
  public void rejectsUnknownBackendBeforeLoading() {
    TestCli.Result result = TestCli.execute("--backend", "ltsmin-fast", MODEL);

    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("ltsmin-sequential"));
    assertTrue(result.output().contains("ltsmin-symbolic"));
  }

  @Test
  public void rejectsPorWithProbBackend() {
    TestCli.Result result = TestCli.execute("--ltsmin-por", MODEL);

    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("--ltsmin-por"));
  }

  @Test
  public void rejectsPorWithSymbolicBackendRegardlessOfOptionOrder() {
    TestCli.Result backendFirst =
        TestCli.execute("--backend", "ltsmin-symbolic", "--ltsmin-por", MODEL);
    TestCli.Result porFirst =
        TestCli.execute("--ltsmin-por", "--backend", "ltsmin-symbolic", MODEL);

    assertSymbolicPorRejected(backendFirst);
    assertSymbolicPorRejected(porFirst);
  }

  @Test
  public void rejectsUnsupportedLtsminOptionsTogether() {
    TestCli.Result result =
        TestCli.execute(
            "--backend",
            "ltsmin-sequential",
            "--assertions",
            "--goal",
            "cars_go = TRUE",
            "--states",
            "10",
            "--progress",
            MODEL);

    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("--assertions"));
    assertTrue(result.output().contains("--goal"));
    assertTrue(result.output().contains("--states"));
    assertTrue(result.output().contains("--progress"));
  }

  @Test
  public void rejectsBothPropertiesDisabled() {
    TestCli.Result result =
        TestCli.execute("--backend", "ltsmin-sequential", "--no-invariant", "--no-deadlock", MODEL);

    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("nothing to check"));
  }

  @Test
  public void rejectsSymbolicTraceAndEvaluationOptions() {
    TestCli.Result save =
        TestCli.execute("--backend", "ltsmin-symbolic", "--save", "trace.json", MODEL);
    TestCli.Result eval =
        TestCli.execute("--backend", "ltsmin-symbolic", "--eval", "cars_go", MODEL);

    assertRejectedBeforeLoading(save);
    assertTrue(save.output().contains("--save"));
    assertTrue(save.output().contains("ltsmin-sequential"));
    assertRejectedBeforeLoading(eval);
    assertTrue(eval.output().contains("--eval"));
    assertTrue(eval.output().contains("ltsmin-sequential"));
  }

  @Test
  public void rejectsProBSymbolicModeWithLtsminBackend() {
    TestCli.Result result =
        TestCli.execute("--backend", "ltsmin-sequential", "--symbolic", "ic3", MODEL);

    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("--symbolic"));
  }

  @Test
  public void unavailableConfiguredBackendIsAnIncompleteReportedRun() throws Exception {
    Path emptyDirectory = Files.createTempDirectory("ltsmin-unavailable-");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--backend", "ltsmin-sequential", "-p", "LTSMIN=" + emptyDirectory, MODEL);

      assertEquals(2, result.exitCode());
      assertTrue(result.output().contains("LTSmin sequential backend unavailable"));
      assertFalse(result.output().contains("Machine:"));
      assertEquals(RunReport.Status.INCOMPLETE, result.command().lastReport.status());
      assertEquals(2, result.command().lastReport.checks().size());
      assertEquals(RunReport.Outcome.ERROR, result.command().lastReport.checks().get(0).outcome());
      assertEquals(RunReport.Outcome.ERROR, result.command().lastReport.checks().get(1).outcome());
    } finally {
      Files.deleteIfExists(emptyDirectory);
    }
  }

  @Test
  public void acceptsTimeLimitForLtsminChecks() throws Exception {
    Path emptyDirectory = Files.createTempDirectory("ltsmin-unavailable-");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--backend",
              "ltsmin-sequential",
              "--time-limit",
              "1",
              "-p",
              "LTSMIN=" + emptyDirectory,
              MODEL);

      assertEquals(2, result.exitCode());
      assertTrue(result.output().contains("backend unavailable"));
      assertFalse(result.output().contains("does not support --time-limit"));
    } finally {
      Files.deleteIfExists(emptyDirectory);
    }
  }

  @Test
  public void invalidModelIsReportedBeforeBackendAvailability() throws Exception {
    Path emptyDirectory = Files.createTempDirectory("ltsmin-unavailable-");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--backend", "ltsmin-sequential", "-p", "LTSMIN=" + emptyDirectory, "missing.bum");

      assertEquals(1, result.exitCode());
      assertTrue(result.output().contains("Model file does not exist"));
      assertFalse(result.output().contains("backend unavailable"));
      assertEquals(RunReport.Status.ERROR, result.command().lastReport.status());
    } finally {
      Files.deleteIfExists(emptyDirectory);
    }
  }

  private static void assertRejectedBeforeLoading(TestCli.Result result) {
    assertEquals(2, result.exitCode());
    assertFalse(result.output().contains("Machine:"));
  }

  private static void assertSymbolicPorRejected(TestCli.Result result) {
    assertRejectedBeforeLoading(result);
    assertTrue(result.output().contains("--ltsmin-por"));
    assertTrue(result.output().contains("incompatible"));
    assertTrue(result.output().contains("ltsmin-sequential"));
  }
}
