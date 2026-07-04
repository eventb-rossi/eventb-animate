package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();

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

    assertEquals("An unknown event is an input error:\n" + result.output(), 1, result.exitCode());
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
  public void testJsonReportCarriesPerEventChecks() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("cbc", "--json", "-", COUNTER_M0);

    assertEquals(1, result.exitCode());
    JsonNode root = MAPPER.readTree(result.stdout());
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
