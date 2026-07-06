package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The eval subcommand evaluates Event-B formulas in explored states: the initialised state by
 * default, or every state satisfying --where. traffic-light/M0 starts with peds_go=FALSE,
 * cars_go=FALSE; counter/M0 explores x=0..10 (inc is guarded by x&lt;10), giving a numeric sweep.
 */
public class EvalCommandTest {

  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();
  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();

  @Test(timeout = 120000)
  public void testInitialisedStateEvaluatesExpressionsAndPredicates() {
    TestCli.Result result =
        TestCli.execute("eval", "-e", "peds_go", "-e", "cars_go = FALSE", TRAFFIC_LIGHT_M0);

    assertEquals(
        "Evaluating in the initial state succeeds:\n" + result.output(), 0, result.exitCode());
    assertTrue(result.output().contains("Evaluated formulas (initialised state):"));
    assertTrue(
        "An expression is reported:\n" + result.output(),
        result.output().contains("peds_go = FALSE"));
    assertTrue(
        "A predicate reports its TRUE/FALSE verdict:\n" + result.output(),
        result.output().contains("cars_go = FALSE = TRUE"));
  }

  @Test(timeout = 120000)
  public void testFalsePredicateIsStillSuccess() {
    TestCli.Result result = TestCli.execute("eval", "-e", "cars_go = TRUE", TRAFFIC_LIGHT_M0);

    assertEquals(
        "A predicate that computes to FALSE is a successful evaluation:\n" + result.output(),
        0,
        result.exitCode());
    assertTrue(result.output().contains("cars_go = TRUE = FALSE"));
  }

  @Test(timeout = 120000)
  public void testUnevaluableFormulaIsANonVerdict() {
    TestCli.Result result =
        TestCli.execute("eval", "-e", "no_such_var", "-e", "cars_go = FALSE", TRAFFIC_LIGHT_M0);

    assertEquals(
        "A formula that cannot be evaluated is a non-verdict (exit 2):\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The un-evaluable formula is named:\n" + result.output(),
        result.output().contains("could not be evaluated: no_such_var"));
    assertTrue(
        "The remaining formula is still evaluated:\n" + result.output(),
        result.output().contains("cars_go = FALSE = TRUE"));
  }

  @Test(timeout = 120000)
  public void testWhereSweepEvaluatesEveryMatchingState() {
    TestCli.Result result =
        TestCli.execute("eval", "--where", "x > 7", "-e", "x", "-e", "x mod 2", COUNTER_M0);

    assertEquals("A completed sweep succeeds:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The number of matching states is reported:\n" + result.output(),
        result.output().contains("3 states satisfy the --where predicate:"));
    assertTrue("x=8 is reached:\n" + result.output(), result.output().contains("x = 8"));
    assertTrue("x=10 is reached:\n" + result.output(), result.output().contains("x = 10"));
    assertTrue(
        "Per-state expressions are evaluated:\n" + result.output(),
        result.output().contains("x mod 2 = 0"));
  }

  @Test(timeout = 120000)
  public void testWhereWithNoMatchIsEmptyButSucceeds() {
    TestCli.Result result = TestCli.execute("eval", "--where", "x > 100", "-e", "x", COUNTER_M0);

    assertEquals(
        "An empty query result is still success:\n" + result.output(), 0, result.exitCode());
    assertTrue(result.output().contains("No explored state satisfies the --where predicate"));
  }

  @Test(timeout = 120000)
  public void testWhereBoundedByStates() {
    // --states caps exploration, so the sweep only sees the bounded prefix of the state space.
    TestCli.Result result =
        TestCli.execute("eval", "--where", "x >= 0", "--states", "3", "-e", "x", COUNTER_M0);

    assertEquals(0, result.exitCode());
    assertTrue(
        "Only the bounded states are searched:\n" + result.output(),
        result.output().contains("3 states satisfy the --where predicate:"));
  }

  @Test(timeout = 120000)
  public void testJsonReportCarriesPerStateEvaluations() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "eval", "--where", "x > 8", "-e", "x", COUNTER_M0);

    assertEquals(0, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("eval", root.get("command").asText());
    assertEquals("ok", root.get("status").asText());
    JsonNode evaluations = root.get("evaluations");
    assertEquals("one block per matching state (x=9, x=10)", 2, evaluations.size());
    assertEquals("state 9", evaluations.get(0).get("state").asText());
    assertEquals("x", evaluations.get(0).get("values").get(0).get("formula").asText());
    assertEquals("9", evaluations.get(0).get("values").get(0).get("value").asText());
    assertEquals("state 10", evaluations.get(1).get("state").asText());
    assertEquals("10", evaluations.get(1).get("values").get(0).get("value").asText());
  }

  @Test(timeout = 120000)
  public void testDuplicateFormulasAreBothEvaluated() throws Exception {
    // The kernel collapses equal formulas into one result; the block must still carry one value
    // per requested -e, not fewer (a positional read would crash or drop the duplicate).
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "eval", "-e", "x = 5", "-e", "x = 5", COUNTER_M0);

    assertEquals("Duplicate formulas evaluate cleanly:\n" + result.stderr(), 0, result.exitCode());
    JsonNode values = TestCli.parseJson(result.stdout()).get("evaluations").get(0).get("values");
    assertEquals("Both requested formulas are reported:\n" + result.stdout(), 2, values.size());
  }

  @Test(timeout = 120000)
  public void testWherePredicateThatCannotBeEvaluatedIsANonVerdict() {
    // `1/0 = 0` parses as a predicate but is well-definedness-unsound, so ProB errors while
    // selecting states; that is a clean non-verdict, not an uncaught crash.
    TestCli.Result result = TestCli.execute("eval", "--where", "1 / 0 = 0", "-e", "x", COUNTER_M0);

    assertEquals(
        "An un-evaluable --where predicate is exit 2:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The failure is reported, not thrown:\n" + result.output(),
        result.output().contains("eval --where did not complete"));
  }

  @Test(timeout = 120000)
  public void testMarkdownRendersAnEvaluationError() throws Exception {
    Path report = Files.createTempFile("animate-eval-", ".md");
    try {
      TestCli.Result result =
          TestCli.execute("eval", "-e", "no_such_var", "--markdown", report.toString(), COUNTER_M0);

      assertEquals(2, result.exitCode());
      String md = Files.readString(report);
      assertTrue("The evaluations section is written:\n" + md, md.contains("## Evaluations"));
      assertTrue(
          "An un-evaluable formula renders without crashing the writer:\n" + md,
          md.contains("could not evaluate"));
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test
  public void testMissingFormulaIsUsageError() {
    TestCli.Result result = TestCli.execute("eval", COUNTER_M0);

    assertEquals("-e is required:\n" + result.output(), 2, result.exitCode());
    assertTrue(result.output().contains("Missing required option"));
  }

  @Test
  public void testUnparseableFormulaIsUsageError() {
    TestCli.Result result = TestCli.execute("eval", "-e", "x +", COUNTER_M0);

    assertEquals(
        "A bad formula is a usage error before any load:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(result.output().contains("invalid -e/--expr formula"));
  }

  @Test
  public void testWhereMustBeAPredicate() {
    TestCli.Result result = TestCli.execute("eval", "--where", "x + 1", "-e", "x", COUNTER_M0);

    assertEquals(2, result.exitCode());
    assertTrue(
        "An expression is not a valid --where predicate:\n" + result.output(),
        result.output().contains("--where must be a predicate"));
  }

  @Test
  public void testStatesWithoutWhereIsUsageError() {
    TestCli.Result result = TestCli.execute("eval", "--states", "3", "-e", "x", COUNTER_M0);

    assertEquals(2, result.exitCode());
    assertTrue(
        "--states only makes sense with --where:\n" + result.output(),
        result.output().contains("--states only bounds the --where state-space search"));
  }

  @Test
  public void testTimeLimitWithoutWhereIsUsageError() {
    TestCli.Result result = TestCli.execute("eval", "--time-limit", "5", "-e", "x", COUNTER_M0);

    assertEquals(2, result.exitCode());
    assertTrue(result.output().contains("--time-limit only bounds the --where state-space search"));
  }
}
