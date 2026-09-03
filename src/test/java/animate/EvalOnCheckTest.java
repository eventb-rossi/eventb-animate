package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The check command's {@code --eval} flag: it evaluates extra formulas in a counterexample's final
 * state ("the goal was hit -- what were the other variables?") and reports them next to the trace.
 * counter/M0 reaches x=5 against the invariant x&lt;5, giving a deterministic violating state.
 */
public class EvalOnCheckTest {

  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();
  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @Test(timeout = 120000)
  public void testEvaluatesExpressionsInTheViolatingState() {
    TestCli.Result result = TestCli.execute(COUNTER_M0, "--eval", "x", "--eval", "x*x");

    assertEquals(
        "An invariant violation is a violation:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The evaluations follow the trace:\n" + result.output(),
        result.output().contains("Evaluated formulas (violating state):"));
    assertTrue("x is reported:\n" + result.output(), result.output().contains("\tx = 5"));
    assertTrue("x*x is reported:\n" + result.output(), result.output().contains("\tx*x = 25"));
  }

  @Test(timeout = 120000)
  public void testEvaluatesPredicateVerdictAndUnicode() {
    TestCli.Result result = TestCli.execute(COUNTER_M0, "--eval", "x = 5", "--eval", "x ∈ ℕ");

    assertEquals(1, result.exitCode());
    assertTrue(
        "A predicate reports its TRUE/FALSE verdict:\n" + result.output(),
        result.output().contains("x = 5 = TRUE"));
    assertTrue(
        "Unicode operators are accepted:\n" + result.output(),
        result.output().contains("x ∈ ℕ = TRUE"));
  }

  @Test(timeout = 120000)
  public void testEvaluatesInAGoalState() {
    TestCli.Result result =
        TestCli.execute(
            COUNTER_M0,
            "--no-invariant",
            "--no-deadlock",
            "--goal",
            "x = 3",
            "--eval",
            "x",
            "--eval",
            "x + 1");

    assertEquals("A goal hit is a violation:\n" + result.output(), 1, result.exitCode());
    assertTrue(result.output().contains("Goal found"));
    assertTrue(
        "x is evaluated in the goal state:\n" + result.output(),
        result.output().contains("\tx = 3"));
    assertTrue(result.output().contains("\tx + 1 = 4"));
  }

  @Test(timeout = 120000)
  public void testEvaluatesInAnLtlCounterexample() {
    TestCli.Result result =
        TestCli.execute(
            TRAFFIC_LIGHT_M0,
            "--ltl",
            "G {cars_go = FALSE}",
            "--eval",
            "cars_go",
            "--eval",
            "peds_go");

    assertEquals("The LTL formula is violated:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "Formulas are evaluated in the counterexample's final state:\n" + result.output(),
        result.output().contains("Evaluated formulas (violating state):"));
    assertTrue(result.output().contains("\tcars_go = TRUE"));
    assertTrue(result.output().contains("\tpeds_go = FALSE"));
  }

  @Test(timeout = 120000)
  public void testCleanRunEvaluatesNothing() {
    TestCli.Result result = TestCli.execute(TRAFFIC_LIGHT_M2, "--eval", "1 + 1");

    assertEquals("M2 is clean:\n" + result.output(), 0, result.exitCode());
    assertFalse(
        "--eval prints nothing without a counterexample:\n" + result.output(),
        result.output().contains("Evaluated formulas"));
  }

  @Test(timeout = 120000)
  public void testUnevaluableFormulaDoesNotChangeTheVerdict() {
    TestCli.Result result = TestCli.execute(COUNTER_M0, "--eval", "no_such_var", "--eval", "x");

    assertEquals(
        "A best-effort annotation never changes the check verdict:\n" + result.output(),
        1,
        result.exitCode());
    assertTrue(
        "The un-evaluable formula is shown as an error line:\n" + result.output(),
        result.output().contains("no_such_var: "));
    assertTrue(
        "The other formula is still evaluated:\n" + result.output(),
        result.output().contains("\tx = 5"));
  }

  @Test(timeout = 120000)
  public void testJsonReportCarriesEvaluations() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", COUNTER_M0, "--eval", "x", "--eval", "x*x");

    assertEquals(1, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals(4, root.get("formatVersion").asInt());
    JsonNode evaluations = root.get("evaluations");
    assertEquals("one block for the single verdict state", 1, evaluations.size());
    JsonNode block = evaluations.get(0);
    assertEquals("violating state", block.get("state").asText());
    JsonNode values = block.get("values");
    assertEquals("x", values.get(0).get("formula").asText());
    assertEquals("5", values.get(0).get("value").asText());
    assertEquals("x*x", values.get(1).get("formula").asText());
    assertEquals("25", values.get(1).get("value").asText());
  }

  @Test(timeout = 120000)
  public void testDuplicateEvalFormulasBothEvaluate() throws Exception {
    // Equal formulas collapse to one kernel result; the report must still carry one value per
    // --eval
    // (a positional read would crash the check, breaking the "annotation never changes verdict"
    // rule).
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", COUNTER_M0, "--eval", "x", "--eval", "x");

    assertEquals(
        "The real violation verdict is preserved:\n" + result.stderr(), 1, result.exitCode());
    JsonNode values = TestCli.parseJson(result.stdout()).get("evaluations").get(0).get("values");
    assertEquals("Both --eval formulas are reported:\n" + result.stdout(), 2, values.size());
  }

  @Test
  public void testUnparseableEvalIsUsageError() {
    TestCli.Result result = TestCli.execute(COUNTER_M0, "--eval", "x +");

    assertEquals(
        "A bad formula is a usage error before any load:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(result.output().contains("invalid --eval formula"));
  }

  @Test
  public void testAssignmentEvalIsUsageError() {
    TestCli.Result result = TestCli.execute(COUNTER_M0, "--eval", "x := 3");

    assertEquals(2, result.exitCode());
    assertTrue(
        "An assignment is neither a predicate nor an expression:\n" + result.output(),
        result.output().contains("--eval must be a predicate or expression, not ASSIGNMENT"));
  }

  @Test
  public void testSymbolicRejectsEval() {
    TestCli.Result result = TestCli.execute("--symbolic", "bmc", COUNTER_M0, "--eval", "x");

    assertEquals(
        "--symbolic has no counterexample state to evaluate in:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(result.output().contains("--symbolic produces no counterexample state, so --eval"));
  }
}
