package animate;

import static org.junit.Assert.assertEquals;
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
  public void testUserPrefsWinOverDefaultsAndSize() {
    Animate animate = new Animate(null, null);
    animate.size = 4;
    animate.userPrefs.put("DEFAULT_SETSIZE", "2");
    animate.userPrefs.put("COMPRESSION", "false");

    Map<String, String> prefs = animate.buildProBPreferences();

    assertEquals("2", prefs.get("DEFAULT_SETSIZE"));
    assertEquals("false", prefs.get("COMPRESSION"));
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

  @Test
  public void testAllChecksDisabledRejected() {
    TestCli.Result result = TestCli.execute("--no-deadlock", "--no-invariant", TRAFFIC_LIGHT_M2);

    assertEquals("Disabling every check is an error:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The error should say there is nothing to check:\n" + result.output(),
        result.output().contains("nothing to check"));
  }

  @Test
  public void testTimeLimitZeroRejected() {
    TestCli.Result result = TestCli.execute("--time-limit", "0", TRAFFIC_LIGHT_M2);

    assertEquals("--time-limit 0 is rejected:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The error should name the option:\n" + result.output(),
        result.output().contains("--time-limit"));
  }
}
