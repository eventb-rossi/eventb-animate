package animate;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/** Runs the CLI with System.out and System.err captured and restored afterwards. */
final class TestCli {

  /** {@code command} is the executed top-level instance, for asserting on its outcome fields. */
  record Result(int exitCode, String output, Animate command) {}

  private TestCli() {}

  static Result execute(String... args) {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      System.setErr(stream);
      CommandLine commandLine = Animate.commandLine();
      int exitCode = commandLine.execute(args);
      return new Result(
          exitCode, captured.toString(StandardCharsets.UTF_8), commandLine.getCommand());
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  /**
   * Asserts the run reached ProB's model-checking phase (so the model loaded and initialised) and
   * returned a verdict: exit 0 (no violation, or a bounded limit reached) or 1 (a real violation or
   * deadlock) -- never a CLI/usage error.
   */
  static void assertModelChecked(Result result, String what) {
    assertTrue(
        what + " should be loaded and model-checked:\n" + result.output(),
        result.output().contains("Model checking..."));
    assertTrue(
        what + " should reach a verdict (0) or a real violation (1):\n" + result.output(),
        result.exitCode() == 0 || result.exitCode() == 1);
  }
}
