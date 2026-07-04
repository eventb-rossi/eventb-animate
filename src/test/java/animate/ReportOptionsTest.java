package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Validation and side-effect rules for the inherited report options. */
public class ReportOptionsTest {

  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();

  @Test
  public void testUsageErrorWritesNoReport() throws Exception {
    Path dir = Files.createTempDirectory("animate-report-");
    Path report = dir.resolve("report.json");
    try {
      TestCli.Result result =
          TestCli.execute("--time-limit", "0", "--json", report.toString(), BASE_MODEL_M1);

      assertEquals("An invalid flag is a usage error:\n" + result.output(), 2, result.exitCode());
      assertTrue(
          "The usage error should name the flag:\n" + result.output(),
          result.output().contains("--time-limit"));
      assertTrue("A usage error must not write a report", Files.notExists(report));
    } finally {
      Files.deleteIfExists(report);
      Files.deleteIfExists(dir);
    }
  }

  @Test
  public void testHelpWinsOverJsonStdout() {
    TestCli.SplitResult result = TestCli.executeSplit("--json", "-", "--help");

    assertEquals(0, result.exitCode());
    assertTrue(
        "Help stays on stdout, undiverted:\n" + result.stdout(),
        result.stdout().contains("Usage:"));
    assertFalse(
        "No report document is emitted for help", result.stdout().contains("formatVersion"));
  }

  @Test
  public void testJsonTargetDirectoryIsRejected() throws Exception {
    Path dir = Files.createTempDirectory("animate-report-");
    try {
      TestCli.Result result = TestCli.execute("--json", dir.toString(), BASE_MODEL_M1);

      assertEquals(
          "A directory target is a usage error:\n" + result.output(), 2, result.exitCode());
      assertTrue(
          "The error should say the target is a directory:\n" + result.output(),
          result.output().contains("target is a directory"));
      assertFalse(
          "Validation must fail before any model load:\n" + result.output(),
          result.output().contains("Machine:"));
    } finally {
      Files.deleteIfExists(dir);
    }
  }

  @Test
  public void testInvalidJsonPathIsAUsageError() {
    // A NUL byte is invalid in paths on every supported platform; picocli's Path
    // conversion must surface it as a usage error, not a stack trace.
    TestCli.Result result = TestCli.execute("--json", "report\0.json", BASE_MODEL_M1);

    assertEquals("An unparseable path is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should name the option:\n" + result.output(),
        result.output().contains("Invalid value for option '--json'"));
  }

  @Test
  public void testJunitToStdoutIsRejected() {
    TestCli.Result result = TestCli.execute("--junit", "-", BASE_MODEL_M1);

    assertEquals("--junit - is a usage error:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The error should name the option:\n" + result.output(),
        result.output().contains("--junit"));
    assertFalse(
        "Validation must fail before any model load:\n" + result.output(),
        result.output().contains("Machine:"));
  }

  @Test
  public void testJsonAndJunitToTheSameFileIsRejected() throws Exception {
    Path dir = Files.createTempDirectory("animate-report-");
    Path report = dir.resolve("report.out");
    try {
      TestCli.Result result =
          TestCli.execute("--json", report.toString(), "--junit", report.toString(), BASE_MODEL_M1);

      assertEquals("A shared path is a usage error:\n" + result.output(), 2, result.exitCode());
      assertTrue(
          "The error should explain the collision:\n" + result.output(),
          result.output().contains("must not write to the same file"));
      assertTrue("Nothing must be written", Files.notExists(report));
    } finally {
      Files.deleteIfExists(report);
      Files.deleteIfExists(dir);
    }
  }

  @Test
  public void testJsonReportOnSubcommandBindsToTheRun() throws Exception {
    // The option is inherited: a subcommand invocation may place it after the subcommand.
    Path dir = Files.createTempDirectory("animate-report-");
    Path report = dir.resolve("report.json");
    try {
      TestCli.Result result =
          TestCli.execute("wd", "--json", report.toString(), "no-such-model.bum");

      assertEquals("The load failure keeps exit 1:\n" + result.output(), 1, result.exitCode());
      assertTrue("The report is written for the failed run", Files.exists(report));
      String document = Files.readString(report);
      assertTrue(
          "The report names the executed subcommand:\n" + document,
          document.contains("\"command\" : \"wd\""));
    } finally {
      Files.deleteIfExists(report);
      Files.deleteIfExists(dir);
    }
  }
}
