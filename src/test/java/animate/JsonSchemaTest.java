package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.BeforeClass;
import org.junit.Test;

/** Validates the published v3 schema against examples and reports emitted by the CLI. */
public class JsonSchemaTest {

  private static final Path SCHEMA_PATH = Path.of("docs/json-report-v3.schema.json");
  private static final Path EXAMPLES_PATH = Path.of("docs/examples");
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();
  private static final String TRAFFIC_LIGHT_M0 =
      Paths.get("src/test/resources/models/traffic-light/M0.bum").toString();
  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String CARS_ON_BRIDGE_M3 =
      Paths.get("src/test/resources/models/cars-on-bridge/M3.bum").toString();
  private static final String FILE_SYSTEM_M0 =
      Paths.get("src/test/resources/models/file-system/M0.bum").toString();
  private static final String REPORT_OUTCOMES =
      Paths.get("src/test/resources/models/report-outcomes").toString();
  private static Schema schema;

  @BeforeClass
  public static void loadSchema() throws Exception {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    schema = registry.getSchema(Files.readString(SCHEMA_PATH), InputFormat.JSON);
  }

  @Test
  public void publishedExamplesValidate() throws Exception {
    try (Stream<Path> files = Files.list(EXAMPLES_PATH)) {
      List<Path> examples =
          files
              .filter(path -> path.getFileName().toString().startsWith("json-report-v3-"))
              .sorted()
              .toList();
      assertFalse("at least one v3 example must be published", examples.isEmpty());
      for (Path example : examples) {
        assertValid(schema, example.toString(), Files.readString(example));
      }
    }
  }

  @Test(timeout = 120000)
  public void emittedCheckLoadFailureAndNonCheckReportsValidate() throws Exception {
    TestCli.SplitResult exhaustive = TestCli.executeSplit("--json", "-", TRAFFIC_LIGHT_M2);
    assertValid(schema, "exhaustive check", exhaustive.stdout());

    TestCli.SplitResult loadFailure = TestCli.executeSplit("--json", "-", "missing.bum");
    assertValid(schema, "load failure", loadFailure.stdout());

    TestCli.SplitResult nonCheck = TestCli.executeSplit("--json", "-", "info", TRAFFIC_LIGHT_M2);
    assertValid(schema, "non-check command", nonCheck.stdout());
  }

  @Test(timeout = 240000)
  public void emittedOutcomeMatrixValidates() throws Exception {
    assertCliValid("state limit", "search", "state_limit", null, "--states", "1", TRAFFIC_LIGHT_M2);
    assertCliValid(
        "time limit", "search", "time_limit", null, "--time-limit", "1", "-z", "8", FILE_SYSTEM_M0);
    assertCliValid(
        "coverage limit",
        "search",
        "coverage_limit",
        null,
        "--stop-at-full-coverage",
        TRAFFIC_LIGHT_M2);
    assertCliValid(
        "symbolic partial", "search", "partial", null, "--symbolic", "bmc", TRAFFIC_LIGHT_M0);
    assertCliValid(
        "invariant finding", "search", "property_violation", "invariant_violation", BASE_MODEL_M1);
    assertCliValid(
        "assertion finding",
        "search",
        "property_violation",
        "assertion_violation",
        "--assertions",
        REPORT_OUTCOMES + "/assertion/M0.bum");
    assertCliValid(
        "deadlock finding",
        "search",
        "property_violation",
        "deadlock",
        "--size",
        "3",
        "--states",
        "300",
        CARS_ON_BRIDGE_M3);
    assertCliValid(
        "goal finding",
        "search",
        "goal_reached",
        "goal_reached",
        "--goal",
        "cars_go = TRUE",
        TRAFFIC_LIGHT_M0);
    assertCliValid(
        "constant setup infeasible",
        "constant_setup",
        "infeasible",
        null,
        REPORT_OUTCOMES + "/constant-infeasible/M0.bum");
    assertCliValid(
        "initialization infeasible",
        "initialization",
        "infeasible",
        null,
        REPORT_OUTCOMES + "/initialization-infeasible/M0.bum");
    assertCliValid(
        "state evaluation error",
        "search",
        "evaluation_error",
        "state_evaluation_error",
        REPORT_OUTCOMES + "/state-evaluation/M0.bum");
    assertCliValid(
        "LTL finding",
        "search",
        "property_violation",
        "ltl_violation",
        "--ltl",
        "G {cars_go = FALSE}",
        TRAFFIC_LIGHT_M0);

    assertValid(
        schema,
        "search interruption",
        renderedCheck(
            RunReport.Status.INCOMPLETE,
            null,
            "invariant",
            RunReport.CompletionReason.INTERRUPTED));
    assertValid(
        schema,
        "search engine failure",
        renderedCheck(
            RunReport.Status.INCOMPLETE,
            null,
            "invariant",
            RunReport.CompletionReason.ENGINE_FAILURE));
    assertValid(
        schema,
        "well-definedness finding",
        renderedCheck(
            RunReport.Status.VIOLATION,
            RunReport.FindingCategory.WELL_DEFINEDNESS_ERROR,
            "well-definedness",
            RunReport.CompletionReason.EVALUATION_ERROR));
    assertValid(
        schema,
        "unknown finding",
        renderedCheck(
            RunReport.Status.VIOLATION,
            RunReport.FindingCategory.UNKNOWN,
            "consistency",
            RunReport.CompletionReason.EVALUATION_ERROR));
  }

  @Test
  public void schemaRejectsInvalidAvailabilityAndCompletionPairs() throws Exception {
    ObjectNode exhaustive =
        (ObjectNode)
            TestCli.parseJson(
                Files.readString(EXAMPLES_PATH.resolve("json-report-v3-exhaustive.json")));

    ObjectNode mismatchedReason = exhaustive.deepCopy();
    ((ObjectNode) mismatchedReason.get("completion")).put("reason", "state_limit");
    assertInvalid(schema, "complete/state_limit", mismatchedReason);

    ObjectNode mismatchedPhase = exhaustive.deepCopy();
    ((ObjectNode) mismatchedPhase.get("completion")).put("phase", "initialization");
    assertInvalid(schema, "initialization/exhaustive", mismatchedPhase);

    ObjectNode contradictoryStatus = exhaustive.deepCopy();
    contradictoryStatus.put("status", "violation");
    assertInvalid(schema, "violation status with complete completion", contradictoryStatus);

    ObjectNode contradictoryExit = exhaustive.deepCopy();
    contradictoryExit.put("exitCode", 2);
    assertInvalid(schema, "ok check with incomplete exit code", contradictoryExit);

    ObjectNode reportWriteFailure = exhaustive.deepCopy();
    reportWriteFailure.put("exitCode", 1);
    assertValid(schema, "clean check with report-write failure", reportWriteFailure.toString());

    ObjectNode interruptedSuccess = exhaustive.deepCopy();
    ObjectNode interruptedCompletion = (ObjectNode) interruptedSuccess.get("completion");
    interruptedCompletion.put("classification", "incomplete");
    interruptedCompletion.put("reason", "interrupted");
    assertInvalid(schema, "ok check cannot be interrupted", interruptedSuccess);

    ObjectNode malformedTimestamp = exhaustive.deepCopy();
    malformedTimestamp.put("timestamp", "not-a-date");
    assertInvalid(schema, "malformed timestamp", malformedTimestamp);

    ObjectNode checkFailure = exhaustive.deepCopy();
    checkFailure.put("status", "incomplete");
    checkFailure.put("exitCode", 2);
    ObjectNode failureCompletion = (ObjectNode) checkFailure.get("completion");
    failureCompletion.put("classification", "error");
    failureCompletion.put("reason", "engine_failure");
    assertValid(schema, "incomplete checker failure", checkFailure.toString());

    ObjectNode missingCompletion = exhaustive.deepCopy();
    missingCompletion.remove("completion");
    assertInvalid(schema, "check without completion", missingCompletion);

    ObjectNode nonCheck =
        (ObjectNode)
            TestCli.parseJson(
                Files.readString(EXAMPLES_PATH.resolve("json-report-v3-non-check.json")));
    nonCheck.set("completion", exhaustive.get("completion"));
    assertInvalid(schema, "non-check with completion", nonCheck);

    ObjectNode mismatchedFinding =
        (ObjectNode)
            TestCli.parseJson(
                Files.readString(EXAMPLES_PATH.resolve("json-report-v3-counterexample.json")));
    ((ObjectNode) mismatchedFinding.get("finding")).put("check", "deadlock");
    assertInvalid(schema, "finding does not match failed check", mismatchedFinding);

    ObjectNode validFinding =
        (ObjectNode)
            TestCli.parseJson(
                Files.readString(EXAMPLES_PATH.resolve("json-report-v3-counterexample.json")));
    ObjectNode findingOnSuccess = exhaustive.deepCopy();
    findingOnSuccess.set("finding", validFinding.get("finding"));
    findingOnSuccess.set("checks", validFinding.get("checks"));
    assertInvalid(schema, "successful check cannot carry a finding", findingOnSuccess);

    ObjectNode mismatchedFindingReason = validFinding.deepCopy();
    ((ObjectNode) mismatchedFindingReason.get("completion")).put("reason", "goal_reached");
    assertInvalid(schema, "finding does not match completion reason", mismatchedFindingReason);

    ObjectNode unsupportedStatistics =
        (ObjectNode)
            TestCli.parseJson(
                Files.readString(EXAMPLES_PATH.resolve("json-report-v3-non-check.json")));
    unsupportedStatistics.set("searchStatistics", exhaustive.get("searchStatistics"));
    assertInvalid(schema, "non-check with search statistics", unsupportedStatistics);
  }

  private static void assertCliValid(
      String label, String phase, String reason, String findingCategory, String... args)
      throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of("--json", "-"));
    command.addAll(List.of(args));
    TestCli.SplitResult result = TestCli.executeSplit(command.toArray(String[]::new));
    JsonNode report = TestCli.parseJson(result.stdout());
    assertEquals(label + " terminal phase", phase, report.get("completion").get("phase").asText());
    assertEquals(
        label + " completion reason", reason, report.get("completion").get("reason").asText());
    if (findingCategory == null) {
      assertFalse(label + " must not carry a finding", report.has("finding"));
    } else {
      assertEquals(
          label + " finding category",
          findingCategory,
          report.get("finding").get("category").asText());
    }
    assertValid(schema, label, result.stdout());
  }

  private static String renderedCheck(
      RunReport.Status status,
      RunReport.FindingCategory findingCategory,
      String check,
      RunReport.CompletionReason reason)
      throws Exception {
    RunReport report = RunReport.singleCheck(status, check, "fixture result");
    if (findingCategory != null) {
      report = report.withFinding(findingCategory);
    }
    report = report.withCompletion(RunReport.CompletionPhase.SEARCH, reason);
    return JsonReportWriter.render(
        new RunReport.Envelope(
            "check",
            Path.of("fixture.bum"),
            "Fixture",
            "test",
            "test",
            Instant.parse("2026-07-20T00:00:00Z"),
            1,
            report.exitCode(),
            report));
  }

  private static void assertValid(Schema schema, String label, String document) {
    List<Error> errors = schema.validate(document, InputFormat.JSON);
    assertTrue(label + " does not validate: " + errors, errors.isEmpty());
  }

  private static void assertInvalid(Schema schema, String label, JsonNode document) {
    List<Error> errors = schema.validate(document.toString(), InputFormat.JSON);
    assertFalse(label + " should not validate", errors.isEmpty());
  }
}
