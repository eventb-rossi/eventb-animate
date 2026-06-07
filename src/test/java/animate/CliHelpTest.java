package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The standard help and version options must work uniformly on every subcommand. */
public class CliHelpTest {

  private static final String[] SUBCOMMANDS = {"replay", "info", "convert"};

  @Test
  public void testHelpExitsZeroOnEverySubcommand() {
    for (String subcommand : SUBCOMMANDS) {
      TestCli.Result result = TestCli.execute(subcommand, "--help");

      assertEquals(
          subcommand + " --help should succeed:\n" + result.output(), 0, result.exitCode());
      assertTrue(
          subcommand + " --help should print its usage:\n" + result.output(),
          result.output().contains("Usage: eventb-animate " + subcommand));
    }
  }

  @Test
  public void testVersionPrintsToolVersionOnEverySubcommand() {
    for (String subcommand : SUBCOMMANDS) {
      TestCli.Result result = TestCli.execute(subcommand, "--version");

      assertEquals(
          subcommand + " --version should succeed:\n" + result.output(), 0, result.exitCode());
      assertTrue(
          subcommand + " --version should print the tool version:\n" + result.output(),
          result.output().contains("eventb-animate"));
    }
  }
}
