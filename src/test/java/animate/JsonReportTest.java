package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** The --json report: one versioned document describing the whole run. */
public class JsonReportTest {

  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();
  private static final String FILE_SYSTEM_M0 =
      Paths.get("src/test/resources/models/file-system/M0.bum").toString();
  private static final String REPORT_OUTCOMES =
      Paths.get("src/test/resources/models/report-outcomes").toString();

  @Test
  public void testLoadFailureReportIsWrittenWithErrorStatus() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", "missing.bum");

    assertEquals("A load failure keeps exit 1:\n" + result.stderr(), 1, result.exitCode());
    assertTrue(
        "The human error stays on stderr:\n" + result.stderr(),
        result.stderr().contains("Error loading model:"));

    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals(3, root.get("formatVersion").asInt());
    assertEquals("eventb-animate", root.get("tool").asText());
    assertEquals("check", root.get("command").asText());
    assertEquals("missing.bum", root.get("model").asText());
    assertEquals("error", root.get("status").asText());
    assertCompletion(root, "error", "load", "input_failure");
    assertEquals(1, root.get("exitCode").asInt());
    assertTrue(root.get("message").asText().contains("Error loading model"));
    assertEquals(0, root.get("checks").size());
    assertTrue(root.get("durationMs").asLong() >= 0);
    assertNull("no machine was resolved", root.get("machine"));
    assertNull("ProB never ran", root.get("probVersion"));
    assertNull("there is no counterexample", root.get("counterexample"));
    assertNull("search never started", root.get("searchStatistics"));
  }

  /**
   * The top-level key set (and its order) is the format-version contract; extending it (as v2 did
   * with the optional "evaluations" array, omitted here) is fine, but any change must be
   * deliberate.
   */
  @Test
  public void testDocumentKeysAreStable() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", "missing.bum");

    JsonNode root = TestCli.parseJson(result.stdout());
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
            "completion",
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
      JsonNode root = TestCli.parseJson(Files.readString(report));
      assertEquals("error", root.get("status").asText());
      assertEquals(result.command().lastReport.message(), root.get("message").asText());
    } finally {
      Files.deleteIfExists(report);
    }
  }

  /**
   * One ProB run covers the JSON contract, the JUnit contract, and the two-reports emitter path.
   */
  @Test(timeout = 120000)
  public void testViolationRunEmitsBothReports() throws Exception {
    Path dir = Files.createTempDirectory("animate-report-");
    Path report = dir.resolve("report.json");
    Path junit = dir.resolve("report.xml");
    Path trace = dir.resolve("trace.json");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--json",
              report.toString(),
              "--junit",
              junit.toString(),
              "--save",
              trace.toString(),
              "--states",
              "1",
              BASE_MODEL_M1);

      assertEquals("M1 violates its invariant:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "The console counterexample is unchanged:\n" + result.output(),
          result.output().contains("Counterexample trace:"));

      JsonNode root = TestCli.parseJson(Files.readString(report));
      assertEquals("violation", root.get("status").asText());
      assertCompletion(root, "counterexample", "search", "property_violation");
      assertFinding(root, "invariant_violation", "invariant");
      JsonNode statistics = assertSearchStatistics(root);
      assertEquals(1, statistics.get("statesProcessed").asInt());
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

      Document doc = TestCli.parseXml(junit);
      Element suite = (Element) doc.getElementsByTagName("testsuite").item(0);
      assertEquals("2", suite.getAttribute("tests"));
      assertEquals("1", suite.getAttribute("failures"));
      assertEquals("1", suite.getAttribute("skipped"));
      assertEquals("0", suite.getAttribute("errors"));
      Element invariant = (Element) doc.getElementsByTagName("testcase").item(0);
      assertEquals("invariant", invariant.getAttribute("name"));
      assertEquals("M1", invariant.getAttribute("classname"));
      Element failure = (Element) invariant.getElementsByTagName("failure").item(0);
      assertTrue(
          "The failure body carries the counterexample:\n" + failure.getTextContent(),
          failure.getTextContent().contains("Counterexample trace:"));
      assertTrue(failure.getTextContent().contains("Violated invariants:"));
      Element deadlock = (Element) doc.getElementsByTagName("testcase").item(1);
      assertEquals("deadlock", deadlock.getAttribute("name"));
      assertEquals(1, deadlock.getElementsByTagName("skipped").getLength());
    } finally {
      Files.deleteIfExists(report);
      Files.deleteIfExists(junit);
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

    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("ok", root.get("status").asText());
    assertCompletion(root, "complete", "search", "exhaustive");
    assertSearchStatistics(root);
    assertFalse(
        "statistics must not require --progress:\n" + result.stderr(),
        result.stderr().contains("Progress:"));
    assertEquals(0, root.get("exitCode").asInt());
    for (JsonNode check : root.get("checks")) {
      assertEquals("passed", check.get("outcome").asText());
    }
    assertEquals(RunReport.Status.OK, result.command().lastReport.status());
  }

  @Test(timeout = 120000)
  public void testStateBoundHasIncompleteCompletionAndFinalStatistics() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--states", "1", TRAFFIC_LIGHT_M2);

    assertEquals(0, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("ok", root.get("status").asText());
    assertCompletion(root, "incomplete", "search", "state_limit");
    JsonNode statistics = assertSearchStatistics(root);
    assertEquals(1, statistics.get("statesProcessed").asInt());
  }

  @Test(timeout = 120000)
  public void testTimeBoundHasDistinctCompletionReason() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--time-limit", "1", "-z", "8", FILE_SYSTEM_M0);

    assertEquals(0, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertCompletion(root, "incomplete", "search", "time_limit");
    assertSearchStatistics(root);
  }

  @Test(timeout = 120000)
  public void testCoverageBoundHasDistinctCompletionReason() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--stop-at-full-coverage", TRAFFIC_LIGHT_M2);

    assertEquals(0, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertCompletion(root, "incomplete", "search", "coverage_limit");
    assertSearchStatistics(root);
  }

  @Test(timeout = 120000)
  public void testJsonStatisticsMatchFinalProgressCounters() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--states", "3", "--progress", TRAFFIC_LIGHT_M2);

    assertEquals(0, result.exitCode());
    Matcher matcher =
        Pattern.compile("Progress: (\\d+)/(\\d+) states processed, (\\d+) transitions")
            .matcher(result.stderr());
    MatchResult finalLine = null;
    while (matcher.find()) {
      finalLine = matcher.toMatchResult();
    }
    assertTrue("a final progress line should contain engine counters", finalLine != null);

    JsonNode root = TestCli.parseJson(result.stdout());
    assertCompletion(root, "incomplete", "search", "state_limit");
    JsonNode statistics = assertSearchStatistics(root);
    assertEquals(3, statistics.get("statesProcessed").asInt());
    assertEquals(Integer.parseInt(finalLine.group(1)), statistics.get("statesProcessed").asInt());
    assertEquals(Integer.parseInt(finalLine.group(2)), statistics.get("statesDiscovered").asInt());
    assertEquals(Integer.parseInt(finalLine.group(3)), statistics.get("transitions").asInt());
  }

  @Test(timeout = 120000)
  public void testLtlViolationReportHasSingleLtlCheck() throws Exception {
    Path report = Files.createTempFile("animate-report-", ".json");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--json", report.toString(), "--ltl", "G {cars_go = FALSE}", TRAFFIC_LIGHT_M0);

      assertEquals("The formula is violated:\n" + result.output(), 1, result.exitCode());

      JsonNode root = TestCli.parseJson(Files.readString(report));
      assertEquals("violation", root.get("status").asText());
      assertCompletion(root, "counterexample", "search", "property_violation");
      assertFinding(root, "ltl_violation", "ltl");
      assertNull("LTL does not expose compatible counters", root.get("searchStatistics"));
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

      JsonNode root = TestCli.parseJson(Files.readString(report));
      assertEquals("incomplete", root.get("status").asText());
      assertCompletion(root, "error", "search", "engine_failure");
      assertNull("the LTL check failed before returning counters", root.get("searchStatistics"));
      assertEquals(2, root.get("exitCode").asInt());
      assertEquals("error", root.get("checks").get(0).get("outcome").asText());
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test(timeout = 120000)
  public void testLtlCleanReportHasStructuredCompletion() throws Exception {
    TestCli.SplitResult clean =
        TestCli.executeSplit("--json", "-", "--ltl", "G {cars_go = cars_go}", TRAFFIC_LIGHT_M0);

    assertEquals(0, clean.exitCode());
    JsonNode cleanRoot = TestCli.parseJson(clean.stdout());
    assertCompletion(cleanRoot, "complete", "search", "exhaustive");
    assertNull("LTL does not expose compatible counters", cleanRoot.get("searchStatistics"));
  }

  @Test(timeout = 120000)
  public void testSetupInitializationAndEvaluationFailuresAreStructured() throws Exception {
    JsonNode constantFailure = runJson(REPORT_OUTCOMES + "/constant-infeasible/M0.bum");
    assertCompletion(constantFailure, "error", "constant_setup", "infeasible");
    assertNull("constant setup never enters search", constantFailure.get("searchStatistics"));

    JsonNode initializationFailure = runJson(REPORT_OUTCOMES + "/initialization-infeasible/M0.bum");
    assertCompletion(initializationFailure, "error", "initialization", "infeasible");
    assertNull("initialization never enters search", initializationFailure.get("searchStatistics"));

    JsonNode stateError = runJson(REPORT_OUTCOMES + "/state-evaluation/M0.bum");
    assertCompletion(stateError, "error", "search", "evaluation_error");
    assertFinding(stateError, "state_evaluation_error", "state-evaluation");
  }

  @Test(timeout = 120000)
  public void testAssertionFindingHasStableIdentity() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("--json", "-", "--assertions", REPORT_OUTCOMES + "/assertion/M0.bum");

    assertEquals(1, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertCompletion(root, "counterexample", "search", "property_violation");
    assertFinding(root, "assertion_violation", "assertions");
  }

  private static JsonNode runJson(String model) throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", model);
    assertEquals(
        "The fixture should produce a definite failure:\n" + result.stderr(), 1, result.exitCode());
    return TestCli.parseJson(result.stdout());
  }

  private static void assertCompletion(
      JsonNode root, String classification, String phase, String reason) {
    assertEquals(classification, root.get("completion").get("classification").asText());
    assertEquals(phase, root.get("completion").get("phase").asText());
    assertEquals(reason, root.get("completion").get("reason").asText());
  }

  private static void assertFinding(JsonNode root, String category, String check) {
    assertEquals(category, root.get("finding").get("category").asText());
    assertEquals(check, root.get("finding").get("check").asText());
  }

  private static JsonNode assertSearchStatistics(JsonNode root) {
    JsonNode statistics = root.get("searchStatistics");
    assertTrue("search statistics should be present", statistics != null);
    assertTrue(statistics.get("statesDiscovered").asInt() >= 0);
    assertTrue(statistics.get("statesProcessed").asInt() >= 0);
    assertTrue(
        statistics.get("statesDiscovered").asInt() >= statistics.get("statesProcessed").asInt());
    assertTrue(statistics.get("transitions").asInt() >= 0);
    return statistics;
  }
}
