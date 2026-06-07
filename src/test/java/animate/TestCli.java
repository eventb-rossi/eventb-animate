package animate;

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
}
