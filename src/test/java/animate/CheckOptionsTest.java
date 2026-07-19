package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.Map;
import org.junit.Test;

/** Options that tune the model-checking run and the ProB preferences behind it. */
public class CheckOptionsTest {

  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @Test(timeout = 120000)
  public void testPrefReachesProB() {
    TestCli.Result result =
        TestCli.execute("-p", "SYMMETRY_MODE=off", "--states", "300", TRAFFIC_LIGHT_M2);

    TestCli.assertModelChecked(result, "The model with -p SYMMETRY_MODE=off");
  }

  @Test
  public void testSymmetryDefaultDependsOnBackend() {
    Animate animate = new Animate(null, null, null);

    assertEquals("hash", animate.buildProBPreferences().get("SYMMETRY_MODE"));

    animate.backend = Animate.CheckBackend.LTSMIN_SEQUENTIAL;
    assertEquals("off", animate.buildProBPreferences().get("SYMMETRY_MODE"));

    animate.backend = Animate.CheckBackend.LTSMIN_SYMBOLIC;
    assertEquals("hash", animate.buildProBPreferences().get("SYMMETRY_MODE"));
  }

  @Test
  public void testUserPrefsWinOverDefaultsAndSize() {
    Animate animate = new Animate(null, null, null);
    animate.size = 4;
    animate.backend = Animate.CheckBackend.LTSMIN_SEQUENTIAL;
    animate.userPrefs.put("DEFAULT_SETSIZE", "2");
    animate.userPrefs.put("COMPRESSION", "false");
    animate.userPrefs.put("SYMMETRY_MODE", "hash");

    Map<String, String> prefs = animate.buildProBPreferences();

    assertEquals("2", prefs.get("DEFAULT_SETSIZE"));
    assertEquals("false", prefs.get("COMPRESSION"));
    assertEquals("hash", prefs.get("SYMMETRY_MODE"));
  }

  @Test
  public void testPrefWithoutValueIsUsageError() {
    TestCli.Result result = TestCli.execute("-p", "FOO", TRAFFIC_LIGHT_M2);

    assertEquals(
        "A -p value without '=' is a usage error:\n" + result.output(), 2, result.exitCode());
  }

  @Test(timeout = 120000)
  public void testTimeLimitBoundsTheCheck() {
    // file-system at -z 6 has far more states than one second can explore, so the
    // time limit normally fires; on an extreme machine full exploration is also fine.
    String model = Paths.get("src/test/resources/models/file-system/M0.bum").toString();

    TestCli.Result result = TestCli.execute("--time-limit", "1", "-z", "6", model);

    assertEquals("A time-limited clean run exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The outcome should report no violation:\n" + result.output(),
        result.output().contains("No invariant violation or deadlock found"));
  }

  @Test(timeout = 120000)
  public void testStopAtFullCoverageEndsEarly() {
    TestCli.Result result = TestCli.execute("--stop-at-full-coverage", TRAFFIC_LIGHT_M2);

    assertEquals("A coverage-stopped clean run exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The outcome should flag the non-exhaustive coverage stop:\n" + result.output(),
        result.output().contains("all events covered; not an exhaustive check"));
  }

  @Test(timeout = 120000)
  public void testAssertionsAreChecked() {
    // cars-on-bridge M0 declares theorems, so the run exercises the assertion check.
    String model = Paths.get("src/test/resources/models/cars-on-bridge/M0.bum").toString();

    TestCli.Result result = TestCli.execute("--assertions", "--states", "300", model);

    TestCli.assertModelChecked(result, "The model with --assertions");
    assertTrue(
        "The verdict should say assertions were checked:\n" + result.output(),
        result.output().contains("assertion violation"));
  }

  @Test(timeout = 120000)
  public void testNoDeadlockSkipsDeadlockCheck() {
    // At -z 3 the leaf machine reaches a deadlock quickly (see ModelCheckTest); with
    // the deadlock check disabled the same run passes and reports only what it checked.
    String model = Paths.get("src/test/resources/models/cars-on-bridge/M3.bum").toString();

    TestCli.Result result =
        TestCli.execute("--no-deadlock", "--size", "3", "--states", "300", model);

    assertEquals(
        "A deadlocking model passes without the deadlock check:\n" + result.output(),
        0,
        result.exitCode());
    assertTrue(
        "The verdict should not claim a deadlock check:\n" + result.output(),
        result.output().contains("No invariant violation found"));
  }

  @Test(timeout = 120000)
  public void testGoalFoundReportsWitnessTrace() {
    // cars_go = TRUE is reachable in the abstract traffic light via set_cars(TRUE).
    String model = Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

    TestCli.Result result = TestCli.execute("--goal", "cars_go = TRUE", model);

    assertEquals("A reachable goal exits 1:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The outcome should report the goal hit:\n" + result.output(),
        result.output().contains("Goal found"));
    assertTrue(
        "The trace to the goal state should be printed:\n" + result.output(),
        result.output().contains("Counterexample trace:"));
  }

  @Test(timeout = 120000)
  public void testUnreachableGoalPasses() {
    // The searched state is exactly what invariant inv3 forbids, so it is unreachable.
    String model = Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

    TestCli.Result result = TestCli.execute("--goal", "cars_go = TRUE & peds_go = TRUE", model);

    assertEquals("An unreachable goal exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The verdict should say the goal state was searched for:\n" + result.output(),
        result.output().contains("goal state"));
  }

  @Test(timeout = 120000)
  public void testGoalMustBeAPredicate() {
    String model = Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

    TestCli.Result result = TestCli.execute("--goal", "cars_go", model);

    assertEquals(
        "An expression goal is rejected without a verdict:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name --goal:\n" + result.output(), result.output().contains("--goal"));
    assertFalse(
        "Goal validation must fail before the model is loaded:\n" + result.output(),
        result.output().contains("Machine:"));
  }

  @Test(timeout = 120000)
  public void testSearchStrategyDepthFirst() {
    TestCli.Result result = TestCli.execute("--search-strategy", "df", TRAFFIC_LIGHT_M2);

    assertEquals(
        "The exploration order must not change the verdict:\n" + result.output(),
        0,
        result.exitCode());
    assertTrue(
        "A depth-first run still explores everything:\n" + result.output(),
        result.output().contains("full state space explored"));
  }

  @Test(timeout = 120000)
  public void testProgressPrintsStats() {
    TestCli.Result result = TestCli.execute("--progress", TRAFFIC_LIGHT_M2);

    assertEquals("A clean run with --progress exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "At least the final progress line should be printed:\n" + result.output(),
        result.output().contains("Progress: "));
  }

  @Test
  public void testSearchStrategyBadValueIsUsageError() {
    TestCli.Result result = TestCli.execute("--search-strategy", "random", TRAFFIC_LIGHT_M2);

    assertEquals("An unknown strategy is a usage error:\n" + result.output(), 2, result.exitCode());
  }

  @Test
  public void testAllChecksDisabledRejected() {
    TestCli.Result result = TestCli.execute("--no-deadlock", "--no-invariant", TRAFFIC_LIGHT_M2);

    assertEquals(
        "Disabling every check is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should say there is nothing to check:\n" + result.output(),
        result.output().contains("nothing to check"));
  }

  @Test
  public void testTimeLimitZeroRejected() {
    TestCli.Result result = TestCli.execute("--time-limit", "0", TRAFFIC_LIGHT_M2);

    assertEquals("--time-limit 0 is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should name the option:\n" + result.output(),
        result.output().contains("--time-limit"));
    assertFalse(
        "Flag validation must fail before the model is loaded:\n" + result.output(),
        result.output().contains("Machine:"));
  }
}
