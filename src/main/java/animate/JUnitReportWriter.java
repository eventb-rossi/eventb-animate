package animate;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes the run as JUnit XML, one testcase per RunReport check, so CI systems that ingest the
 * format natively (GitLab artifacts:reports:junit, the GitHub JUnit actions, Jenkins) show which
 * property failed -- with the counterexample as the failure body -- without any custom parsing.
 */
final class JUnitReportWriter {

  private JUnitReportWriter() {}

  static void write(RunReport.Envelope envelope, Path target) throws IOException {
    try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
      try {
        writeDocument(xml, envelope);
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("cannot write JUnit XML: " + e.getMessage(), e);
    }
  }

  private static void writeDocument(XMLStreamWriter xml, RunReport.Envelope envelope)
      throws XMLStreamException {
    RunReport report = envelope.report();
    List<RunReport.Check> testcases = report.checksOrSynthesized();
    long failures = count(testcases, RunReport.Outcome.FAILED);
    long errors = count(testcases, RunReport.Outcome.ERROR);
    long skipped = count(testcases, RunReport.Outcome.SKIPPED);
    // The kernel reports one duration for the whole run; splitting it evenly is the
    // only attribution that does not fabricate per-property precision.
    String caseTime = seconds(envelope.durationMs() / testcases.size());

    xml.writeStartDocument("UTF-8", "1.0");
    xml.writeStartElement("testsuites");
    writeCountAttributes(xml, envelope, testcases.size(), failures, errors, skipped);
    xml.writeStartElement("testsuite");
    xml.writeAttribute("name", Animate.TOOL_NAME + " " + envelope.command());
    writeCountAttributes(xml, envelope, testcases.size(), failures, errors, skipped);
    xml.writeAttribute(
        "timestamp",
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            envelope.timestamp().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)));

    for (RunReport.Check check : testcases) {
      xml.writeStartElement("testcase");
      xml.writeAttribute("name", xmlSafe(check.name()));
      xml.writeAttribute("classname", envelope.displayName());
      xml.writeAttribute("time", caseTime);
      switch (check.outcome()) {
        case PASSED -> {}
        case FAILED -> writeVerdict(xml, "failure", check.message(), report.counterexample());
        case SKIPPED -> writeVerdict(xml, "skipped", check.message(), null);
        case ERROR -> writeVerdict(xml, "error", check.message(), null);
      }
      xml.writeEndElement();
    }

    xml.writeEndElement();
    xml.writeEndElement();
    xml.writeEndDocument();
  }

  private static long count(List<RunReport.Check> checks, RunReport.Outcome outcome) {
    return checks.stream().filter(check -> check.outcome() == outcome).count();
  }

  private static void writeCountAttributes(
      XMLStreamWriter xml,
      RunReport.Envelope envelope,
      int tests,
      long failures,
      long errors,
      long skipped)
      throws XMLStreamException {
    xml.writeAttribute("tests", Long.toString(tests));
    xml.writeAttribute("failures", Long.toString(failures));
    xml.writeAttribute("errors", Long.toString(errors));
    xml.writeAttribute("skipped", Long.toString(skipped));
    xml.writeAttribute("time", seconds(envelope.durationMs()));
  }

  private static void writeVerdict(
      XMLStreamWriter xml, String element, String message, TraceWriter.Counterexample ce)
      throws XMLStreamException {
    if (ce == null) {
      xml.writeEmptyElement(element);
      if (message != null) {
        xml.writeAttribute("message", xmlSafe(message));
      }
      return;
    }
    xml.writeStartElement(element);
    if (message != null) {
      xml.writeAttribute("message", xmlSafe(message));
    }
    xml.writeCharacters(xmlSafe(counterexampleText(ce)));
    xml.writeEndElement();
  }

  /** The same rendering the console prints, so the two never drift apart. */
  private static String counterexampleText(TraceWriter.Counterexample ce) {
    String invariants = TraceWriter.violatedInvariantsBlock(ce);
    String trace = TraceWriter.traceBlock(ce);
    return invariants.isEmpty() ? trace : invariants + "\n" + trace;
  }

  private static String seconds(long millis) {
    return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
  }

  /**
   * XMLStreamWriter escapes markup but does not reject characters outside the XML 1.0 Char
   * production (control characters, unpaired surrogates, U+FFFE/U+FFFF), which ProB messages and
   * mangled identifiers can contain; strip them so strict CI ingesters never reject the report.
   */
  private static String xmlSafe(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder safe = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      int codePoint = value.codePointAt(i);
      i += Character.charCount(codePoint);
      boolean valid =
          codePoint == 0x9
              || codePoint == 0xA
              || codePoint == 0xD
              || (codePoint >= 0x20 && codePoint <= 0xD7FF)
              || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
              || codePoint >= 0x10000;
      if (valid) {
        safe.appendCodePoint(codePoint);
      }
    }
    return safe.toString();
  }
}
