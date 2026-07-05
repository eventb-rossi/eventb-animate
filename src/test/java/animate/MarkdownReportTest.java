package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/** The --markdown report: a human-readable document whose layout never breaks on ProB output. */
public class MarkdownReportTest {

  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();

  private static RunReport.Envelope envelope(
      RunReport report, String machine, Path model, String probVersion) {
    return new RunReport.Envelope(
        "check",
        model,
        machine,
        probVersion,
        "test",
        Instant.parse("2026-07-06T12:00:00Z"),
        5,
        1,
        report);
  }

  /**
   * ProB reps and identifiers carry the characters Markdown treats as markup ({@code | * _ `} and
   * line breaks); every one of them must land inside inline code or a fenced block, with the fence
   * widened past any backtick run, so none can break a table row or escape a code span.
   */
  @Test
  public void testProbOutputCannotBreakTheMarkdown() {
    TraceWriter.Counterexample counterexample =
        new TraceWriter.Counterexample(
            List.of("INITIALISATION", "inc(x_1 := 6)", "weird `backtick` op"),
            "x = 6\ny = *bad*\n```danger```",
            List.of("x ≤ 5", "a_1 | a_2"));
    RunReport report =
        RunReport.of(
                RunReport.Status.VIOLATION,
                "Invariant violation found.",
                new RunReport.Check("invariant", RunReport.Outcome.FAILED, "x_1 | y > 0"),
                new RunReport.Check("deadlock", RunReport.Outcome.SKIPPED, "search stopped"))
            .withCounterexample(counterexample)
            .withTraceFile(Paths.get("trace.json"));

    String md =
        MarkdownReportWriter.render(
            envelope(report, "M1", Paths.get("we_ird.bum"), "1.2.3\nLast changed: today"));

    assertTrue(
        "title carries the machine and verdict:\n" + md,
        md.contains("# eventb-animate: M1 — VIOLATION"));
    assertTrue("status is the lowercase verdict:\n" + md, md.contains("- **Status:** `violation`"));
    assertTrue("exit code is rendered:\n" + md, md.contains("- **Exit code:** `1`"));

    // The multi-line ProB version banner collapses onto a single inline-code line.
    assertTrue(
        "multi-line version is collapsed:\n" + md,
        md.contains("- **ProB version:** `1.2.3 Last changed: today`"));

    // A pipe inside a table cell is escaped, so the row keeps its three columns.
    assertTrue("table pipe is escaped:\n" + md, md.contains("x_1 \\| y > 0"));
    assertFalse("no raw pipe leaks into the message cell:\n" + md, md.contains("x_1 | y > 0"));

    // The violating state (multi-line, containing a 3-backtick run) is fenced with 4 backticks.
    assertTrue("state fence outlives its backtick run:\n" + md, md.contains("````"));
    assertTrue("state text is preserved:\n" + md, md.contains("y = *bad*"));

    // A transition carrying a backtick is wrapped in a widened inline-code span.
    assertTrue("backtick transition is wrapped:\n" + md, md.contains("``weird `backtick` op``"));
    assertTrue("trace is an ordered list:\n" + md, md.contains("1. `INITIALISATION`"));

    assertTrue("invariants list keeps unicode operators:\n" + md, md.contains("- `x ≤ 5`"));
    assertTrue("saved trace is linked:\n" + md, md.contains("Saved trace: `trace.json`"));
  }

  /** Absent facts are omitted (not rendered as null), and a checkless run still gets one row. */
  @Test
  public void testLoadFailureOmitsMissingFactsAndSynthesizesARow() {
    RunReport report = RunReport.of(RunReport.Status.ERROR, "Error loading model: missing.bum");

    String md = MarkdownReportWriter.render(envelope(report, null, null, null));

    assertTrue(
        "subject falls back to the tool name:\n" + md,
        md.contains("# eventb-animate: eventb-animate — ERROR"));
    assertFalse("no model line when the model is unknown:\n" + md, md.contains("**Model:**"));
    assertFalse("no machine line before load:\n" + md, md.contains("**Machine:**"));
    assertFalse("no ProB version before load:\n" + md, md.contains("**ProB version:**"));
    assertTrue("a checkless run gets a synthesized row:\n" + md, md.contains("| `run` | ERROR |"));
    assertFalse(
        "no counterexample section when there is none:\n" + md, md.contains("Counterexample"));
  }

  @Test(timeout = 120000)
  public void testViolationRunWritesAReadableReport() throws Exception {
    Path report = Files.createTempFile("animate-markdown-", ".md");
    try {
      TestCli.Result result = TestCli.execute("--markdown", report.toString(), BASE_MODEL_M1);

      assertEquals("M1 violates its invariant:\n" + result.output(), 1, result.exitCode());

      String md = Files.readString(report);
      assertTrue(
          "the report names the machine and verdict:\n" + md,
          md.contains("# eventb-animate: M1 — VIOLATION"));
      assertTrue("the counterexample section is present:\n" + md, md.contains("## Counterexample"));
      assertTrue(
          "the violated invariants are listed:\n" + md, md.contains("### Violated invariants"));
      assertTrue("the trace has a first step:\n" + md, md.contains("1. `"));
      assertTrue("the violating state is fenced:\n" + md, md.contains("### Violating state"));
    } finally {
      Files.deleteIfExists(report);
    }
  }
}
