package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The --json report: one versioned document describing the whole run. */
public class JsonReportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @Test
  public void testLoadFailureReportIsWrittenWithErrorStatus() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", "missing.bum");

    assertEquals("A load failure keeps exit 1:\n" + result.stderr(), 1, result.exitCode());
    assertTrue(
        "The human error stays on stderr:\n" + result.stderr(),
        result.stderr().contains("Error loading model:"));

    JsonNode root = MAPPER.readTree(result.stdout());
    assertEquals(1, root.get("formatVersion").asInt());
    assertEquals("eventb-animate", root.get("tool").asText());
    assertEquals("check", root.get("command").asText());
    assertEquals("missing.bum", root.get("model").asText());
    assertEquals("error", root.get("status").asText());
    assertEquals(1, root.get("exitCode").asInt());
    assertTrue(root.get("message").asText().contains("Error loading model"));
    assertEquals(0, root.get("checks").size());
    assertTrue(root.get("durationMs").asLong() >= 0);
    assertNull("no machine was resolved", root.get("machine"));
    assertNull("ProB never ran", root.get("probVersion"));
    assertNull("there is no counterexample", root.get("counterexample"));
  }

  /**
   * The top-level key set (and its order) is the formatVersion=1 contract; extending it is fine,
   * but any change here must be deliberate.
   */
  @Test
  public void testDocumentKeysAreStable() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", "missing.bum");

    JsonNode root = MAPPER.readTree(result.stdout());
    List<String> keys = new ArrayList<>();
    root.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        List.of(
            "formatVersion",
            "tool",
            "toolVersion",
            "command",
            "model",
            "timestamp",
            "durationMs",
            "status",
            "exitCode",
            "message",
            "checks"),
        keys);
  }

  @Test
  public void testReportFileOverwritesWithoutForce() throws Exception {
    Path report = Files.createTempFile("animate-report-", ".json");
    try {
      Files.writeString(report, "stale content from the previous CI run");

      TestCli.Result result = TestCli.execute("--json", report.toString(), "missing.bum");

      assertEquals(1, result.exitCode());
      JsonNode root = MAPPER.readTree(Files.readString(report));
      assertEquals("error", root.get("status").asText());
      assertEquals(result.command().lastReport.message(), root.get("message").asText());
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test(timeout = 120000)
  public void testViolationReportCarriesChecksAndCounterexample() throws Exception {
    Path dir = Files.createTempDirectory("animate-report-");
    Path report = dir.resolve("report.json");
    Path trace = dir.resolve("trace.json");
    try {
      TestCli.Result result =
          TestCli.execute("--json", report.toString(), "--save", trace.toString(), BASE_MODEL_M1);

      assertEquals("M1 violates its invariant:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "The console counterexample is unchanged:\n" + result.output(),
          result.output().contains("Counterexample trace:"));

      JsonNode root = MAPPER.readTree(Files.readString(report));
      assertEquals("violation", root.get("status").asText());
      assertEquals("M1", root.get("machine").asText());
      assertTrue(root.get("probVersion").asText().length() > 0);
      assertTrue(root.get("durationMs").asLong() > 0);
      assertEquals(trace.toString(), root.get("traceFile").asText());

      JsonNode checks = root.get("checks");
      assertEquals("invariant", checks.get(0).get("name").asText());
      assertEquals("failed", checks.get(0).get("outcome").asText());
      assertEquals("deadlock", checks.get(1).get("name").asText());
      assertEquals("skipped", checks.get(1).get("outcome").asText());

      JsonNode counterexample = root.get("counterexample");
      assertTrue(counterexample.get("transitions").size() > 0);
      assertTrue(counterexample.get("violatedInvariants").size() > 0);
      assertTrue(counterexample.get("violatingState").asText().length() > 0);

    } finally {
      Files.deleteIfExists(report);
      Files.deleteIfExists(trace);
      Files.deleteIfExists(dir);
    }
  }

  @Test(timeout = 120000)
  public void testCleanRunWithJsonOnStdoutKeepsStdoutPure() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", TRAFFIC_LIGHT_M2);

    assertEquals("M2 is clean:\n" + result.stderr(), 0, result.exitCode());
    assertTrue(
        "Human output is diverted to stderr:\n" + result.stderr(),
        result.stderr().contains("Machine: M2"));
    assertTrue(
        "The coverage block moves to stderr too:\n" + result.stderr(),
        result.stderr().contains("Coverage properties:"));

    JsonNode root = MAPPER.readTree(result.stdout());
    assertEquals("ok", root.get("status").asText());
    assertEquals(0, root.get("exitCode").asInt());
    for (JsonNode check : root.get("checks")) {
      assertEquals("passed", check.get("outcome").asText());
    }
    assertEquals(RunReport.Status.OK, result.command().lastReport.status());
  }

  @Test(timeout = 120000)
  public void testLtlViolationReportHasSingleLtlCheck() throws Exception {
    Path report = Files.createTempFile("animate-report-", ".json");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--json", report.toString(), "--ltl", "G {cars_go = FALSE}", TRAFFIC_LIGHT_M0);

      assertEquals("The formula is violated:\n" + result.output(), 1, result.exitCode());

      JsonNode root = MAPPER.readTree(Files.readString(report));
      assertEquals("violation", root.get("status").asText());
      assertEquals(1, root.get("checks").size());
      assertEquals("ltl", root.get("checks").get(0).get("name").asText());
      assertEquals("failed", root.get("checks").get(0).get("outcome").asText());
      assertNull(
          "the LTL path never evaluates invariants",
          root.get("counterexample").get("violatedInvariants"));
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test(timeout = 120000)
  public void testLtlNonVerdictReportsIncomplete() throws Exception {
    Path report = Files.createTempFile("animate-report-", ".json");
    try {
      // The formula parses but names an identifier the machine does not have, so
      // the check errors out without a verdict (same fixture as LtlCheckTest).
      TestCli.Result result =
          TestCli.execute(
              "--json", report.toString(), "--ltl", "G {no_such_var = TRUE}", TRAFFIC_LIGHT_M0);

      assertEquals("A formula error is a non-verdict:\n" + result.output(), 2, result.exitCode());

      JsonNode root = MAPPER.readTree(Files.readString(report));
      assertEquals("incomplete", root.get("status").asText());
      assertEquals(2, root.get("exitCode").asInt());
      assertEquals("error", root.get("checks").get(0).get("outcome").asText());
    } finally {
      Files.deleteIfExists(report);
    }
  }
}
