package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The cbc command proves per-event invariant preservation with the constraint solver. The counter
 * fixture has a deliberately non-inductive invariant: inc can step from x=4 to x=5 against x&lt;5,
 * while reset always preserves it.
 */
public class CbcCommandTest {

  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();
  private static final String GATE_M0 =
      Paths.get("src/test/resources/models/gate/M0.bum").toString();

  @Test(timeout = 120000)
  public void testNonInductiveInvariantIsAViolation() {
    TestCli.Result result = TestCli.execute("cbc", COUNTER_M0);

    assertEquals("A violating step is a violation:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The violating event should be named:\n" + result.output(),
        result.output().contains("Error: invariant preservation fails for 1 event: inc"));
    assertTrue(
        "The counterexample trace should be printed:\n" + result.output(),
        result.output().contains("Counterexample trace:"));
    assertTrue(
        "The violated invariant should be listed:\n" + result.output(),
        result.output().contains("Violated invariants:"));
  }

  @Test(timeout = 120000)
  public void testPreservingEventPasses() {
    TestCli.Result result = TestCli.execute("cbc", "--events", "reset", COUNTER_M0);

    assertEquals("reset preserves the invariant:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The pass must state its caveats:\n" + result.output(),
        result
            .output()
            .contains(
                "No event can violate the invariant (constraint-based preservation proof;"
                    + " initialisation is not checked, and a solver timeout can mask a"
                    + " violation)."));
  }

  @Test(timeout = 120000)
  public void testUnknownEventIsAnInputError() {
    TestCli.Result result = TestCli.execute("cbc", "--events", "nope", COUNTER_M0);

    assertEquals("An unknown event is an input error:\n" + result.output(), 66, result.exitCode());
    assertTrue(
        "The error should list the machine's events:\n" + result.output(),
        result
            .output()
            .contains(
                "unknown event(s) for --events: nope (the machine's events are: inc, reset)"));
  }

  @Test(timeout = 120000)
  public void testSaveWritesTheCounterexampleTrace() throws Exception {
    Path trace = Files.createTempFile("animate-cbc-trace-", ".json");
    try {
      Files.delete(trace);
      TestCli.Result result = TestCli.execute("cbc", "--save", trace.toString(), COUNTER_M0);

      assertEquals(1, result.exitCode());
      assertTrue("The trace file should be written:\n" + result.output(), Files.exists(trace));
      assertTrue("The trace file should not be empty", Files.size(trace) > 0);
    } finally {
      Files.deleteIfExists(trace);
    }
  }

  @Test(timeout = 120000)
  public void testDeadlockSearchFindsTheStuckState() {
    // gate: step is guarded by y < 2, so y = 2 satisfies the invariant and deadlocks.
    TestCli.Result result = TestCli.execute("cbc", "--deadlock", GATE_M0);

    assertEquals("A found deadlock is a violation:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The invariant part of the run stays clean:\n" + result.output(),
        result.output().contains("No event can violate the invariant"));
    assertTrue(
        "The deadlock should be reported with its caveat:\n" + result.output(),
        result
            .output()
            .contains(
                "Error: a deadlocking state satisfying the invariant was found"
                    + " (it may be unreachable)."));
    assertTrue(
        "The deadlocking state should be printed:\n" + result.output(),
        result.output().contains("y=2"));
  }

  @Test(timeout = 120000)
  public void testWhereRestrictsTheDeadlockSearch() {
    TestCli.Result result = TestCli.execute("cbc", "--deadlock", "--where", "y < 2", GATE_M0);

    assertEquals(
        "No deadlock exists among the y < 2 states:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The pass should mention the restriction:\n" + result.output(),
        result
            .output()
            .contains("No deadlocking state satisfies the invariant and the --where predicate."));
  }

  @Test
  public void testWhereWithoutDeadlockIsAUsageError() {
    TestCli.Result result = TestCli.execute("cbc", "--where", "y < 2", GATE_M0);

    assertEquals(
        "--where without --deadlock is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should point at --deadlock:\n" + result.output(),
        result.output().contains("--where only restricts the --deadlock search"));
  }

  @Test
  public void testNoInvariantAloneIsAUsageError() {
    TestCli.Result result = TestCli.execute("cbc", "--no-invariant", GATE_M0);

    assertEquals(
        "Disabling the only check is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should say there is nothing to check:\n" + result.output(),
        result.output().contains("nothing to check"));
  }

  @Test(timeout = 120000)
  public void testFeasibilityReportsDeadEventsAsAdvisory() {
    // gate: never is guarded by y > 5, unsatisfiable under the invariant y <= 2.
    TestCli.Result result = TestCli.execute("cbc", "--no-invariant", "--feasibility", GATE_M0);

    assertEquals("Advisory findings keep exit 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The dead event should be listed with the advisory note:\n" + result.output(),
        result
            .output()
            .contains("Infeasible (dead) events (advisory; use --strict to fail):\n\t - never"));
  }

  @Test(timeout = 120000)
  public void testStrictEscalatesAdvisoryFindings() {
    TestCli.Result result =
        TestCli.execute("cbc", "--no-invariant", "--feasibility", "--strict", GATE_M0);

    assertEquals(
        "--strict turns findings into failures:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The finding should be an error under --strict:\n" + result.output(),
        result.output().contains("Error: 1 infeasible (dead) event: never"));
  }

  @Test(timeout = 120000)
  public void testRedundantInvariantsAreReported() {
    // gate: inv3 (y <= 5) is implied by inv2 (y <= 2).
    TestCli.Result result =
        TestCli.execute("cbc", "--no-invariant", "--redundant-invariants", GATE_M0);

    assertEquals("Advisory findings keep exit 0:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The implied invariant should be listed:\n" + result.output(),
        result.output().contains("y <= 5"));
  }

  @Test
  public void testStrictWithoutAdvisoriesIsAUsageError() {
    TestCli.Result result = TestCli.execute("cbc", "--strict", GATE_M0);

    assertEquals(
        "--strict without advisory checks is a usage error:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name the advisory flags:\n" + result.output(),
        result.output().contains("--strict has nothing to escalate"));
  }

  @Test(timeout = 120000)
  public void testJsonReportCarriesPerEventChecks() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("cbc", "--json", "-", COUNTER_M0);

    assertEquals(1, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("cbc", root.get("command").asText());
    assertEquals("violation", root.get("status").asText());
    assertEquals(2, root.get("checks").size());
    assertEquals("invariant/inc", root.get("checks").get(0).get("name").asText());
    assertEquals("failed", root.get("checks").get(0).get("outcome").asText());
    assertEquals("invariant/reset", root.get("checks").get(1).get("name").asText());
    assertEquals("passed", root.get("checks").get(1).get("outcome").asText());
    assertTrue(
        "The counterexample should be embedded:\n" + result.stdout(),
        root.get("counterexample").get("transitions").size() > 0);
  }
}
