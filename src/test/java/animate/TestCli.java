package animate;

import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import picocli.CommandLine;

/** Runs the CLI with System.out and System.err captured and restored afterwards. */
final class TestCli {

  /** {@code command} is the executed top-level instance, for asserting on its outcome fields. */
  record Result(int exitCode, String output, Animate command) {}

  /** Like {@link Result}, but with stdout and stderr kept apart (for --json - purity checks). */
  record SplitResult(int exitCode, String stdout, String stderr, Animate command) {}

  private TestCli() {}

  static Result execute(String... args) {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      Run run = run(stream, stream, args);
      return new Result(run.exitCode(), captured.toString(StandardCharsets.UTF_8), run.command());
    }
  }

  static SplitResult executeSplit(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      Run run = run(outStream, errStream, args);
      return new SplitResult(
          run.exitCode(),
          out.toString(StandardCharsets.UTF_8),
          err.toString(StandardCharsets.UTF_8),
          run.command());
    }
  }

  /** DOM-parses a JUnit report; parsing doubles as the well-formedness check. */
  static Document parseXml(Path report) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile());
  }

  /** Parses a --json report document; parsing doubles as the well-formedness check. */
  static JsonNode parseJson(String document) throws Exception {
    return MAPPER.readTree(document);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record Run(int exitCode, Animate command) {}

  /** Runs the CLI with the given sinks installed as System.out/err, restoring the originals. */
  private static Run run(PrintStream out, PrintStream err, String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try {
      System.setOut(out);
      System.setErr(err);
      CommandLine commandLine = Animate.commandLine();
      return new Run(commandLine.execute(args), commandLine.getCommand());
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
