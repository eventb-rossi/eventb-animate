package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assume;
import org.junit.Test;
import picocli.CommandLine;

/**
 * The exit-code contract as a caller sees it. Exit 1 is reserved for a definite negative verdict
 * about the model, so a run that failed before reaching one is distinguishable from {@code $?}
 * alone: 66 when what was supplied could not be used, 70 when the tool itself could not finish. The
 * 0 and 1 legs are pinned by the suites that own those runs (JsonReportTest, ModelCheckTest); this
 * file covers only the separation the codes exist for.
 */
public class ExitCodeTest {

  private static final String CLEAN =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();
  private static final String VIOLATING =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();

  @Test
  public void testUnloadableModelExitsSixtySix() {
    TestCli.Result result = TestCli.execute("missing.bum");

    assertEquals(
        "A model that cannot be loaded is not a violation:\n" + result.output(),
        66,
        result.exitCode());
  }

  /** The report is the artifact CI asked for, so an unwritable one fails an otherwise clean run. */
  @Test(timeout = 120000)
  public void testUnwritableReportEscalatesACleanRunToSeventy() throws Exception {
    TestCli.Result result = runWithUnwritableReport(CLEAN);

    assertEquals(
        "A report that cannot be written is a tool failure:\n" + result.output(),
        70,
        result.exitCode());
    assertTrue(
        "The write failure is reported:\n" + result.output(),
        result.output().contains("Error writing report:"));
  }

  /**
   * A failing run keeps the code that says why. The codes are no longer ordered by severity, so an
   * escalation written as a max() would silently overwrite this verdict with 70.
   */
  @Test(timeout = 120000)
  public void testUnwritableReportKeepsAnExistingVerdict() throws Exception {
    TestCli.Result result = runWithUnwritableReport("--states", "1", VIOLATING);

    assertEquals(
        "The violation verdict survives a report-write failure:\n" + result.output(),
        1,
        result.exitCode());
  }

  /** An exception escaping a command is a tool failure, not picocli's default verdict of 1. */
  @Test
  public void testUnhandledExceptionExitsSeventy() {
    CommandLine commandLine = Animate.commandLine();

    assertEquals(70, commandLine.getCommandSpec().exitCodeOnExecutionException());
    for (CommandLine subcommand : commandLine.getSubcommands().values()) {
      assertEquals(
          subcommand.getCommandName() + " must report a crash as a tool failure",
          70,
          subcommand.getCommandSpec().exitCodeOnExecutionException());
    }
  }

  /**
   * Runs the CLI with a report target whose parent exists but rejects writes -- the one shape that
   * passes the up-front destination validation and still fails at write time.
   */
  private static TestCli.Result runWithUnwritableReport(String... args) throws Exception {
    Path directory = Files.createTempDirectory("animate-report-target-");
    Path report = directory.resolve("report.json");
    assertTrue("the temp directory should be read-only", directory.toFile().setWritable(false));
    // A root-run build can write anywhere, which would defeat the test.
    Assume.assumeFalse("running as a user that ignores the write bit", Files.isWritable(directory));
    String[] command = new String[args.length + 2];
    command[0] = "--json";
    command[1] = report.toString();
    System.arraycopy(args, 0, command, 2, args.length);
    try {
      return TestCli.execute(command);
    } finally {
      directory.toFile().setWritable(true);
      Files.deleteIfExists(report);
      Files.deleteIfExists(directory);
    }
  }
}
