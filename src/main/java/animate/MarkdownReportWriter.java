package animate;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Renders the run as a human-readable Markdown report -- the counterexample trace, violating state,
 * and violated invariants a developer opens to see why a check failed. Machine consumers should
 * read {@link JsonReportWriter}/{@link JUnitReportWriter} instead; this layout is for people and
 * may be restyled freely. Every ProB-derived string is emitted inside inline code or a fenced
 * block, with a delimiter longer than any backtick run it contains, so identifiers and state dumps
 * (which carry {@code |}, {@code *}, {@code _}, {@code <--}) can neither break the Markdown nor
 * inject markup.
 */
final class MarkdownReportWriter {

  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  private MarkdownReportWriter() {}

  static String render(RunReport.Envelope envelope) {
    RunReport report = envelope.report();
    StringBuilder md = new StringBuilder();

    md.append("# ")
        .append(Animate.TOOL_NAME)
        .append(": ")
        .append(envelope.displayName())
        .append(" — ")
        .append(report.status().name())
        .append("\n\n");

    appendMetadata(md, envelope);

    if (report.message() != null) {
      md.append('\n').append(code(report.message())).append('\n');
    }

    appendChecks(md, report);

    TraceWriter.Counterexample counterexample = report.counterexample();
    if (counterexample != null) {
      appendCounterexample(md, counterexample);
    }

    if (!report.evaluations().isEmpty()) {
      appendEvaluations(md, report.evaluations());
    }

    if (report.traceFile() != null) {
      md.append("\nSaved trace: ").append(code(report.traceFile().toString())).append('\n');
    }

    return md.toString();
  }

  private static void appendMetadata(StringBuilder md, RunReport.Envelope envelope) {
    md.append("- **Command:** ").append(code(envelope.command())).append('\n');
    if (envelope.model() != null) {
      md.append("- **Model:** ").append(code(envelope.model().toString())).append('\n');
    }
    if (envelope.machine() != null) {
      md.append("- **Machine:** ").append(code(envelope.machine())).append('\n');
    }
    if (envelope.probVersion() != null) {
      md.append("- **ProB version:** ").append(code(envelope.probVersion())).append('\n');
    }
    md.append("- **Tool version:** ").append(code(envelope.toolVersion())).append('\n');
    md.append("- **Timestamp:** ")
        .append(code(DateTimeFormatter.ISO_INSTANT.format(envelope.timestamp())))
        .append('\n');
    md.append("- **Duration:** ").append(code(envelope.durationMs() + " ms")).append('\n');
    md.append("- **Status:** ")
        .append(code(envelope.report().status().name().toLowerCase(Locale.ROOT)))
        .append('\n');
    md.append("- **Exit code:** ").append(code(Integer.toString(envelope.exitCode()))).append('\n');
  }

  private static void appendChecks(StringBuilder md, RunReport report) {
    md.append("\n## Checks\n\n| Check | Outcome | Message |\n| --- | --- | --- |\n");
    for (RunReport.Check check : report.checksOrSynthesized()) {
      md.append("| ")
          .append(code(check.name()))
          .append(" | ")
          .append(check.outcome().name())
          .append(" | ")
          .append(check.message() == null ? "" : tableCell(check.message()))
          .append(" |\n");
    }
  }

  private static void appendCounterexample(StringBuilder md, TraceWriter.Counterexample ce) {
    md.append("\n## Counterexample\n");
    if (!ce.violatedInvariants().isEmpty()) {
      md.append("\n### Violated invariants\n\n");
      for (String invariant : ce.violatedInvariants()) {
        md.append("- ").append(code(invariant)).append('\n');
      }
    }
    md.append("\n### Trace\n\n");
    int step = 1;
    for (String transition : ce.transitions()) {
      md.append(step++).append(". ").append(code(transition)).append('\n');
    }
    md.append("\n### Violating state\n").append(fenced(ce.violatingState())).append('\n');
  }

  /**
   * Evaluated formula values, grouped per state. Rendered as inline-code bullet lists (not a table)
   * so a value carrying a pipe -- common in Event-B, e.g. {@code {1|->2}} -- cannot break a row;
   * the {@code code()} helper widens the fence past any backtick run in the value.
   */
  private static void appendEvaluations(
      StringBuilder md, List<RunReport.StateEvaluation> evaluations) {
    md.append("\n## Evaluations\n");
    for (RunReport.StateEvaluation stateEvaluation : evaluations) {
      md.append("\nIn ").append(code(stateEvaluation.state())).append(":\n\n");
      for (RunReport.FormulaValue value : stateEvaluation.values()) {
        md.append("- ").append(code(value.formula()));
        md.append(value.error() ? " — could not evaluate: " : " = ");
        md.append(code(value.value())).append('\n');
      }
    }
  }

  // --- Markdown-safety helpers ---

  /** Collapses line breaks so a value is safe in an inline-code span or a table cell. */
  private static String singleLine(String value) {
    return LINE_BREAK.matcher(value).replaceAll(" ");
  }

  /** Table cells cannot contain a raw pipe or a line break without breaking the row. */
  private static String tableCell(String value) {
    return singleLine(value).replace("|", "\\|");
  }

  /**
   * An inline code span, always single-line (the multi-line ProB version banner is collapsed). The
   * delimiter is a run of backticks longer than any inside the value (so the value cannot terminate
   * the span early), padded with a space when the value would otherwise touch the delimiter -- the
   * CommonMark rule that keeps a backtick-bearing value intact.
   */
  private static String code(String value) {
    String text = singleLine(value);
    String fence = "`".repeat(longestRun(text, '`') + 1);
    String pad = text.isEmpty() || text.startsWith("`") || text.endsWith("`") ? " " : "";
    return fence + pad + text + pad + fence;
  }

  /** A fenced code block whose fence outlives any backtick run inside the body. */
  private static String fenced(String body) {
    String fence = "`".repeat(Math.max(3, longestRun(body, '`') + 1));
    return "\n" + fence + "\n" + body + "\n" + fence + "\n";
  }

  private static int longestRun(String value, char target) {
    int longest = 0;
    int current = 0;
    for (int i = 0; i < value.length(); i++) {
      current = value.charAt(i) == target ? current + 1 : 0;
      longest = Math.max(longest, current);
    }
    return longest;
  }
}
