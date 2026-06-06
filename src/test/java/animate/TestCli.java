package animate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Runs the CLI with System.out and System.err captured and restored afterwards. */
final class TestCli {

  record Result(int exitCode, String output) {}

  private TestCli() {}

  static Result execute(String... args) {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      System.setErr(stream);
      int exitCode = Animate.execute(args);
      return new Result(exitCode, captured.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
