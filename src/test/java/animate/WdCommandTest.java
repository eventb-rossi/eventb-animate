package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WdCommandTest {

  @Test(timeout = 120000)
  public void testAllDischargedExitsZero() {
    TestCli.Result result = TestCli.execute("wd", "src/test/resources/models/traffic-light/M2.bum");

    assertEquals("A model without WD problems exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The discharged/total summary should be printed:\n" + result.output(),
        result.output().contains("WD proof obligations: "));
  }

  @Test(timeout = 120000)
  public void testCheckOnlyFlagsDoNotAffectSubcommands() {
    // The model-check toggles are meaningless for wd and must not fail its model load.
    TestCli.Result result =
        TestCli.execute(
            "--no-deadlock",
            "--no-invariant",
            "wd",
            "src/test/resources/models/traffic-light/M2.bum");

    assertEquals(
        "wd must ignore the model-check toggles:\n" + result.output(), 0, result.exitCode());
  }

  @Test(timeout = 120000)
  public void testUndischargedObligationsExitTwo() {
    // file-system M0 applies partial functions, leaving some WD obligations open.
    // Open obligations are unproven, not disproven, so this is the no-verdict exit.
    TestCli.Result result = TestCli.execute("wd", "src/test/resources/models/file-system/M0.bum");

    assertEquals("Undischarged WD obligations exit 2:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The failure should name the undischarged count:\n" + result.output(),
        result.output().contains("not discharged"));
  }
}
