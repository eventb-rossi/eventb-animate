package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The testgen command generates one witness trace per covered operation with constraint-based
 * sequence search. traffic-light M2 covers every operation in a single step; file-system M0 needs
 * multi-step prefixes (delete_hard_link takes three), so it exercises the breadth-first extension
 * and -- at depth 1 -- leaves operations uncovered; gate M0 has a dead operation (never).
 */
public class TestgenCommandTest {

  private static final String TRAFFIC =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();
  private static final String FILESYSTEM =
      Paths.get("src/test/resources/models/file-system/M0.bum").toString();
  private static final String COUNTER =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();
  private static final String GATE = Paths.get("src/test/resources/models/gate/M0.bum").toString();

  @Test(timeout = 120000)
  public void testCoversEveryOperationAndWritesTraces() throws Exception {
    Path dir = Files.createTempDirectory("animate-testgen-");
    try {
      TestCli.Result result = TestCli.execute("testgen", "--out", dir.toString(), TRAFFIC);

      assertEquals("Full coverage is a clean run:\n" + result.output(), 0, result.exitCode());
      assertTrue(
          "Every operation should be covered:\n" + result.output(),
          result.output().contains("Covered 4/4 target operations"));
      assertEquals(
          "One trace file per covered operation should be written", 4, countTraceFiles(dir));
    } finally {
      deleteRecursively(dir);
    }
  }

  @Test(timeout = 120000)
  public void testGeneratedTraceReplaysPerfectly() throws Exception {
    // file-system delete_hard_link is only reachable after a three-step prefix, so this also proves
    // the breadth-first search builds real multi-step witnesses that round-trip through replay.
    Path dir = Files.createTempDirectory("animate-testgen-");
    try {
      TestCli.Result gen = TestCli.execute("testgen", "--out", dir.toString(), FILESYSTEM);
      assertEquals("Generation should succeed:\n" + gen.output(), 0, gen.exitCode());

      Path trace = dir.resolve("M0_delete_hard_link.json");
      assertTrue("A multi-step witness should be written:\n" + gen.output(), Files.exists(trace));

      TestCli.Result replay = TestCli.execute("replay", "-t", trace.toString(), FILESYSTEM);
      assertEquals("The generated trace must replay:\n" + replay.output(), 0, replay.exitCode());
      assertTrue(
          "The generated trace must replay perfectly:\n" + replay.output(),
          replay.output().contains("Trace replay status: PERFECT"));
    } finally {
      deleteRecursively(dir);
    }
  }

  @Test(timeout = 120000)
  public void testOperationsRestrictsTargets() {
    TestCli.Result result = TestCli.execute("testgen", "--operations", "inc", COUNTER);

    assertEquals("A restricted run still passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "Only the requested operation is a target:\n" + result.output(),
        result.output().contains("Covered 1/1 target operation"));
  }

  @Test(timeout = 120000)
  public void testDuplicateOperationsAreDeduplicated() {
    TestCli.Result result = TestCli.execute("testgen", "--operations", "inc,inc", COUNTER);

    assertEquals(
        "A run over duplicated targets still passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "A repeated operation must not inflate the target count:\n" + result.output(),
        result.output().contains("Covered 1/1 target operation"));
  }

  @Test(timeout = 120000)
  public void testUnknownOperationIsAnInputError() {
    TestCli.Result result = TestCli.execute("testgen", "--operations", "nope", COUNTER);

    assertEquals(
        "An unknown operation is an input error:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The error should list the machine's operations:\n" + result.output(),
        result.output().contains("unknown operation(s): nope (the machine's operations are: "));
  }

  @Test(timeout = 120000)
  public void testInfeasibleOperationIsAdvisoryByDefault() {
    // gate: never is guarded by y > 5, unsatisfiable under the invariant y <= 2.
    TestCli.Result result = TestCli.execute("testgen", GATE);

    assertEquals(
        "A dead operation is advisory by default:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The dead operation should be reported:\n" + result.output(),
        result.output().contains("Infeasible (dead) operations:\n\t - never"));
  }

  @Test(timeout = 120000)
  public void testFailOnInfeasibleEscalates() {
    TestCli.Result result = TestCli.execute("testgen", "--fail-on-infeasible", GATE);

    assertEquals(
        "--fail-on-infeasible turns a dead operation into a violation:\n" + result.output(),
        1,
        result.exitCode());
    assertTrue(
        "The finding should name the dead operation:\n" + result.output(),
        result.output().contains("Error: 1 infeasible (dead) target operation: never"));
  }

  @Test(timeout = 120000)
  public void testUncoveredIsAdvisoryByDefault() {
    // At depth 1 the file-system operations that need a prefix stay uncovered, but without a flag
    // that is advisory: an uncovered target is a non-verdict, not a failure.
    TestCli.Result result = TestCli.execute("testgen", "--depth", "1", FILESYSTEM);

    assertEquals(
        "Uncovered targets are advisory by default:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The uncovered operations should be reported:\n" + result.output(),
        result.output().contains("Uncovered (no witness within depth 1):"));
  }

  @Test(timeout = 120000)
  public void testFailOnUncoveredEscalates() {
    TestCli.Result result =
        TestCli.execute("testgen", "--depth", "1", "--fail-on-uncovered", FILESYSTEM);

    assertEquals(
        "--fail-on-uncovered makes an uncovered target a non-verdict:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The uncovered operations should be reported:\n" + result.output(),
        result.output().contains("uncovered target operation"));
  }

  @Test(timeout = 120000)
  public void testExistingTraceIsRejectedWithoutForce() throws Exception {
    Path dir = Files.createTempDirectory("animate-testgen-");
    try {
      assertEquals(0, TestCli.execute("testgen", "--out", dir.toString(), COUNTER).exitCode());

      TestCli.Result rerun = TestCli.execute("testgen", "--out", dir.toString(), COUNTER);
      assertEquals(
          "Re-running without --force must not clobber traces:\n" + rerun.output(),
          1,
          rerun.exitCode());
      assertTrue(
          "The error should point at --force:\n" + rerun.output(),
          rerun.output().contains("already exists, use --force to overwrite"));

      TestCli.Result forced =
          TestCli.execute("testgen", "--out", dir.toString(), "--force", COUNTER);
      assertEquals("--force overwrites:\n" + forced.output(), 0, forced.exitCode());
    } finally {
      deleteRecursively(dir);
    }
  }

  @Test
  public void testDepthMustBePositive() {
    TestCli.Result result = TestCli.execute("testgen", "--depth", "0", COUNTER);

    assertEquals("--depth 0 is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should explain the bound:\n" + result.output(),
        result.output().contains("--depth must be at least 1"));
  }

  @Test
  public void testForceWithoutOutIsAUsageError() {
    TestCli.Result result = TestCli.execute("testgen", "--force", COUNTER);

    assertEquals(
        "--force without --out is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should point at --out:\n" + result.output(),
        result.output().contains("--force only applies when writing traces with --out"));
  }

  @Test(timeout = 120000)
  public void testJsonReportCarriesPerOperationChecks() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("testgen", "--json", "-", COUNTER);

    assertEquals(0, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("testgen", root.get("command").asText());
    assertEquals("ok", root.get("status").asText());
    assertEquals(2, root.get("checks").size());
    assertEquals("coverage/inc", root.get("checks").get(0).get("name").asText());
    assertEquals("passed", root.get("checks").get(0).get("outcome").asText());
    assertEquals("coverage/reset", root.get("checks").get(1).get("name").asText());
    assertEquals("passed", root.get("checks").get(1).get("outcome").asText());
  }

  private static long countTraceFiles(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.toString().endsWith(".json")).count();
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : ordered) {
        Files.deleteIfExists(path);
      }
    }
  }
}
