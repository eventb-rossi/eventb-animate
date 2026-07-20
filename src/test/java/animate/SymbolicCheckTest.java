package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The --symbolic mode replaces the explicit consistency check with ProB's symbolic model checker.
 * It yields only a verdict (no trace), so --save and the consistency-check flags are rejected, and
 * a counterexample points the user back at the default check. Behavioral assertions are pinned to
 * algorithm/fixture pairs that are deterministic: ic3 proves safety on a fully-proven machine, bmc
 * finds a reachable violation, and bmc cannot prove safety (it is inconclusive on a safe machine).
 */
public class SymbolicCheckTest {

  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();
  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();

  @Test(timeout = 120000)
  public void testInductionProvesSafeMachine() throws Exception {
    // ic3/k-induction can prove invariant safety, unlike the explicit check they replace.
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--symbolic", "ic3", TRAFFIC_LIGHT_M0);

    assertEquals("A proven-safe machine exits 0:\n" + result.stderr(), 0, result.exitCode());
    assertTrue(
        "The verdict should report no reachable violation:\n" + result.stderr(),
        result.stderr().contains("No invariant violation reachable"));
    assertCompletion(result, "complete", "proof");
  }

  @Test(timeout = 120000)
  public void testCounterexampleExitsOneWithTraceHint() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--symbolic", "bmc", COUNTER_M0);

    assertEquals("A reachable violation exits 1:\n" + result.stderr(), 1, result.exitCode());
    assertTrue(
        "The verdict should report a reachable violation:\n" + result.stderr(),
        result.stderr().contains("Invariant violation reachable"));
    assertTrue(
        "A counterexample should point back at the default check for a trace:\n" + result.stderr(),
        result.stderr().contains("rerun the default check"));
    assertCompletion(result, "counterexample", "property_violation");
  }

  @Test(timeout = 120000)
  public void testInconclusiveIsNonVerdict() throws Exception {
    // BMC cannot prove safety, so on a safe machine it is inconclusive -- a non-verdict (exit 2),
    // never a false pass.
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--symbolic", "bmc", TRAFFIC_LIGHT_M0);

    assertEquals("An inconclusive run is a non-verdict:\n" + result.stderr(), 2, result.exitCode());
    assertTrue(
        "The verdict should say the run was inconclusive:\n" + result.stderr(),
        result.stderr().contains("inconclusive"));
    assertCompletion(result, "incomplete", "partial");
  }

  @Test
  public void testUnknownAlgorithmExitsTwoWithoutLoading() {
    TestCli.Result result = TestCli.execute("--symbolic", "nope", TRAFFIC_LIGHT_M0);

    assertEquals(
        "An unknown algorithm is a usage error:\n" + result.output(), 2, result.exitCode());
    assertFalse(
        "The model must not be loaded for a bad algorithm:\n" + result.output(),
        result.output().contains("Machine:"));
  }

  @Test
  public void testSaveIsRejectedWithTraceHint() {
    TestCli.Result result =
        TestCli.execute("--symbolic", "bmc", "--save", "trace.json", TRAFFIC_LIGHT_M0);

    assertEquals(
        "--symbolic cannot produce a trace, so --save is a usage error:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name --save and point at the default check:\n" + result.output(),
        result.output().contains("--save") && result.output().contains("default check"));
  }

  @Test
  public void testConsistencyFlagsAreRejected() {
    // The symbolic checker checks invariants only, with no state bound, so these are refused
    // rather than silently ignored.
    TestCli.Result result =
        TestCli.execute(
            "--symbolic", "bmc", "--goal", "counter > 3", "--states", "100", COUNTER_M0);

    assertEquals(
        "Unsupported consistency flags are a usage error:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name the unsupported flags:\n" + result.output(),
        result.output().contains("--goal") && result.output().contains("--states"));
  }

  @Test
  public void testProgressIsRejected() {
    // The symbolic command is a single blocking call with no incremental output, so --progress is
    // rejected rather than silently ignored (unlike the explicit and LTL checks, which honor it).
    TestCli.Result result = TestCli.execute("--symbolic", "bmc", "--progress", TRAFFIC_LIGHT_M0);

    assertEquals(
        "--progress cannot be honored in symbolic mode:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should name --progress:\n" + result.output(),
        result.output().contains("--progress"));
  }

  @Test
  public void testSymbolicAndLtlAreMutuallyExclusive() {
    TestCli.Result result =
        TestCli.execute("--symbolic", "bmc", "--ltl", "G {counter >= 0}", COUNTER_M0);

    assertEquals(
        "Combining --symbolic and --ltl is a usage error:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name --ltl:\n" + result.output(), result.output().contains("--ltl"));
  }

  private static void assertCompletion(
      TestCli.SplitResult result, String classification, String reason) throws Exception {
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals(classification, root.get("completion").get("classification").asText());
    assertEquals("search", root.get("completion").get("phase").asText());
    assertEquals(reason, root.get("completion").get("reason").asText());
    assertNull("symbolic checking exposes no search counters", root.get("searchStatistics"));
  }
}
