package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/** End-to-end coverage of ProB's LTSmin adapter against a real LTSmin installation. */
public class LtsminIntegrationTest {

  private static LtsminSupport.Discovery sequential;
  private static LtsminSupport.Discovery symbolic;

  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String CARS_ON_BRIDGE_M3 =
      Paths.get("src/test/resources/models/cars-on-bridge/M3.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @BeforeClass
  public static void requireLtsminWhenRequested() {
    String path = System.getenv("PATH");
    sequential = LtsminSupport.discover(Animate.CheckBackend.LTSMIN_SEQUENTIAL, null, path);
    symbolic = LtsminSupport.discover(Animate.CheckBackend.LTSMIN_SYMBOLIC, null, path);
    boolean available = sequential.available() && symbolic.available();
    String detail =
        "LTSmin integration tools unavailable: sequential="
            + sequential.error()
            + ", symbolic="
            + symbolic.error();

    if ("true".equalsIgnoreCase(System.getenv("EVENTB_ANIMATE_REQUIRE_LTSMIN"))) {
      assertTrue(detail, available);
    }
  }

  private static void assumeLtsmin(Animate.CheckBackend backend) {
    LtsminSupport.Discovery discovery =
        backend == Animate.CheckBackend.LTSMIN_SEQUENTIAL ? sequential : symbolic;
    Assume.assumeTrue(
        backend.cliName() + " unavailable: " + discovery.error(), discovery.available());
  }

  @Test(timeout = 120000)
  public void sequentialBackendRunsBothChecksWithPor() {
    assumeLtsmin(Animate.CheckBackend.LTSMIN_SEQUENTIAL);
    TestCli.Result result =
        TestCli.execute("--backend", "ltsmin-sequential", "--ltsmin-por", TRAFFIC_LIGHT_M2);

    assertEquals("M2 is clean:\n" + result.output(), 0, result.exitCode());
    assertTrue(result.output().contains("LTSmin sequential invariant checking..."));
    assertTrue(result.output().contains("LTSmin sequential deadlock checking..."));
    assertTrue(result.output().contains("Coverage unavailable for external LTSmin exploration"));
    assertFalse(result.output().contains("Coverage properties:"));
    assertEquals(RunReport.Status.OK, result.command().lastReport.status());
    assertEquals(2, result.command().lastReport.checks().size());
    assertEquals(RunReport.Outcome.PASSED, result.command().lastReport.checks().get(0).outcome());
    assertEquals(RunReport.Outcome.PASSED, result.command().lastReport.checks().get(1).outcome());
    assertTrue(
        result
            .command()
            .lastReport
            .message()
            .contains("two complete partial-order-reduced passes"));
  }

  @Test(timeout = 120000)
  public void sequentialInvariantViolationCarriesTraceEvaluationAndReports() throws Exception {
    assumeLtsmin(Animate.CheckBackend.LTSMIN_SEQUENTIAL);
    Path directory = Files.createTempDirectory("animate-ltsmin-");
    Path report = directory.resolve("report.json");
    Path trace = directory.resolve("trace.json");
    try {
      TestCli.Result result =
          TestCli.execute(
              "--backend",
              "ltsmin-sequential",
              "--json",
              report.toString(),
              "--save",
              trace.toString(),
              "--eval",
              "1 + 1",
              BASE_MODEL_M1);

      assertEquals("M1 violates its invariant:\n" + result.output(), 1, result.exitCode());
      assertTrue(result.output().contains("Counterexample trace:"));
      assertTrue(result.output().contains("1 + 1 = 2"));
      assertTrue(Files.isRegularFile(trace));

      JsonNode root = TestCli.parseJson(Files.readString(report));
      assertEquals(2, root.get("formatVersion").asInt());
      assertEquals("violation", root.get("status").asText());
      assertEquals("failed", root.get("checks").get(0).get("outcome").asText());
      assertEquals("skipped", root.get("checks").get(1).get("outcome").asText());
      assertTrue(root.get("counterexample").get("transitions").size() > 0);
      assertEquals("2", root.get("evaluations").get(0).get("values").get(0).get("value").asText());
    } finally {
      MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
    }
  }

  @Test(timeout = 120000)
  public void sequentialDeadlockOnlyReturnsAReplayableTrace() {
    assumeLtsmin(Animate.CheckBackend.LTSMIN_SEQUENTIAL);
    TestCli.Result result =
        TestCli.execute(
            "--backend", "ltsmin-sequential", "--size", "3", "--no-invariant", CARS_ON_BRIDGE_M3);

    assertEquals("M3 deadlocks at -z 3:\n" + result.output(), 1, result.exitCode());
    assertTrue(result.output().contains("Deadlock found using LTSmin sequential"));
    assertTrue(result.output().contains("Counterexample trace:"));
    assertEquals(1, result.command().lastReport.checks().size());
    assertEquals("deadlock", result.command().lastReport.checks().get(0).name());
    assertEquals(RunReport.Outcome.FAILED, result.command().lastReport.checks().get(0).outcome());
  }

  @Test(timeout = 120000)
  public void symbolicBackendReturnsACleanVerdict() {
    assumeLtsmin(Animate.CheckBackend.LTSMIN_SYMBOLIC);
    TestCli.Result result =
        TestCli.execute("--backend", "ltsmin-symbolic", "--no-deadlock", TRAFFIC_LIGHT_M2);

    assertEquals("M2 is clean:\n" + result.output(), 0, result.exitCode());
    String success = "No invariant violation found using LTSmin symbolic";
    assertTrue(result.output().contains(success));
    assertEquals(
        "A one-property run prints its success once:\n" + result.output(),
        result.output().indexOf(success),
        result.output().lastIndexOf(success));
    assertEquals(RunReport.Status.OK, result.command().lastReport.status());
  }

  @Test(timeout = 120000)
  public void symbolicViolationIsAVerdictWithoutATrace() {
    assumeLtsmin(Animate.CheckBackend.LTSMIN_SYMBOLIC);
    TestCli.Result result =
        TestCli.execute("--backend", "ltsmin-symbolic", "--no-deadlock", BASE_MODEL_M1);

    assertEquals("M1 violates its invariant:\n" + result.output(), 1, result.exitCode());
    assertTrue(result.output().contains("Invariant violation found using LTSmin symbolic"));
    assertTrue(result.output().contains("--backend ltsmin-sequential"));
    assertFalse(result.output().contains("Counterexample trace:"));
    assertEquals(RunReport.Status.VIOLATION, result.command().lastReport.status());
    assertNull(result.command().lastReport.counterexample());
  }
}
