package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** The --ltl/--ltl-file mode replaces the consistency check with an LTL check. */
public class LtlCheckTest {

  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

  @Test(timeout = 120000)
  public void testLtlFormulaHolds() {
    TestCli.Result result =
        TestCli.execute("--ltl", "G not({cars_go = TRUE & peds_go = TRUE})", TRAFFIC_LIGHT_M0);

    assertEquals("A satisfied formula exits 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The verdict should report the formula holds:\n" + result.output(),
        result.output().contains("LTL formula holds"));
  }

  @Test(timeout = 120000)
  public void testLtlCounterexampleExitsOne() {
    TestCli.Result result = TestCli.execute("--ltl", "G {cars_go = FALSE}", TRAFFIC_LIGHT_M0);

    assertEquals("A violated formula exits 1:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The counterexample trace should be printed:\n" + result.output(),
        result.output().contains("Counterexample trace:"));
  }

  @Test
  public void testLtlParseErrorIsAUsageErrorWithoutLoading() {
    TestCli.Result result = TestCli.execute("--ltl", "G (", TRAFFIC_LIGHT_M0);

    assertEquals("A parse error is a usage error:\n" + result.output(), 2, result.exitCode());
    assertFalse(
        "The model must not be loaded for an unparseable formula:\n" + result.output(),
        result.output().contains("Machine:"));
  }

  @Test(timeout = 120000)
  public void testLtlErrorInFormulaIsNonVerdict() {
    // The formula parses but names an identifier the machine does not have.
    TestCli.Result result = TestCli.execute("--ltl", "G {no_such_var = TRUE}", TRAFFIC_LIGHT_M0);

    assertEquals("A formula error is a non-verdict:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The ProB error should be reported:\n" + result.output(),
        result.output().contains("LTL checking did not complete"));
  }

  @Test(timeout = 120000)
  public void testLtlFileIsRead() throws Exception {
    Path formula = Files.createTempFile("animate-formula-", ".ltl");
    try {
      Files.writeString(formula, "G not({cars_go = TRUE & peds_go = TRUE})\n");

      TestCli.Result result = TestCli.execute("--ltl-file", formula.toString(), TRAFFIC_LIGHT_M0);

      assertEquals("The formula from the file holds:\n" + result.output(), 0, result.exitCode());
    } finally {
      Files.deleteIfExists(formula);
    }
  }

  @Test
  public void testLtlFileWithInvalidEncodingReportsCleanError() throws Exception {
    Path formula = Files.createTempFile("animate-formula-", ".ltl");
    try {
      // The unicode negation sign in Latin-1 is the single byte 0xAC -- not valid UTF-8.
      Files.write(
          formula, "G ¬({cars_go = TRUE})".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

      TestCli.Result result = TestCli.execute("--ltl-file", formula.toString(), TRAFFIC_LIGHT_M0);

      assertEquals("An undecodable file exits 1:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "The error should explain the encoding problem:\n" + result.output(),
          result.output().contains("not valid UTF-8"));
    } finally {
      Files.deleteIfExists(formula);
    }
  }

  @Test
  public void testLtlAndGoalAreMutuallyExclusive() {
    TestCli.Result result =
        TestCli.execute(
            "--ltl", "G {cars_go = FALSE}", "--goal", "cars_go = TRUE", TRAFFIC_LIGHT_M0);

    assertEquals(
        "Combining --ltl and --goal is a usage error:\n" + result.output(), 2, result.exitCode());
  }

  @Test
  public void testLtlRejectsUnsupportedConsistencyFlags() {
    // The kernel's LTL checker cannot honor a time bound, so the flag must be
    // rejected instead of silently ignored.
    TestCli.Result result =
        TestCli.execute("--ltl", "G {cars_go = FALSE}", "--time-limit", "300", TRAFFIC_LIGHT_M0);

    assertEquals(
        "An unenforceable flag is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should name the unsupported flag:\n" + result.output(),
        result.output().contains("--time-limit"));
  }

  @Test(timeout = 120000)
  public void testProgressPrintsFinalLine() {
    // ProB reports no intermediate LTL progress, but the flag must not be silent.
    TestCli.Result result =
        TestCli.execute(
            "--ltl", "G not({cars_go = TRUE & peds_go = TRUE})", "--progress", TRAFFIC_LIGHT_M0);

    assertEquals("The check still passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "A final progress line should be printed:\n" + result.output(),
        result.output().contains("Progress: finished after"));
  }
}
