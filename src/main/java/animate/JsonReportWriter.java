package animate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders the JSON run report. The document is built field by field instead of data-binding
 * RunReport, so the external contract cannot drift when the internal model or the kernel-provided
 * Jackson defaults change; any breaking change to the shape must bump {@link #FORMAT_VERSION}.
 * Optional facts are omitted rather than emitted as null.
 */
final class JsonReportWriter {

  // v3 adds structured model-check completion and final search statistics.
  static final int FORMAT_VERSION = 3;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonReportWriter() {}

  static String render(RunReport.Envelope envelope) throws JsonProcessingException {
    RunReport report = envelope.report();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("formatVersion", FORMAT_VERSION);
    root.put("tool", Animate.TOOL_NAME);
    root.put("toolVersion", envelope.toolVersion());
    root.put("command", envelope.command());
    if (envelope.model() != null) {
      // The path exactly as given on the command line (so with '\' separators on Windows).
      root.put("model", envelope.model().toString());
    }
    if (envelope.machine() != null) {
      root.put("machine", envelope.machine());
    }
    if (envelope.probVersion() != null) {
      root.put("probVersion", envelope.probVersion());
    }
    root.put("timestamp", envelope.isoTimestamp());
    root.put("durationMs", envelope.durationMs());
    root.put("status", report.status().label());
    if (report.completion() != null) {
      ObjectNode completion = root.putObject("completion");
      completion.put("classification", report.completion().classification().label());
      completion.put("phase", report.completion().phase().label());
      completion.put("reason", report.completion().reason().label());
    }
    if (report.searchStatistics() != null) {
      ObjectNode statistics = root.putObject("searchStatistics");
      statistics.put("statesDiscovered", report.searchStatistics().statesDiscovered());
      statistics.put("statesProcessed", report.searchStatistics().statesProcessed());
      statistics.put("transitions", report.searchStatistics().transitions());
    }
    root.put("exitCode", envelope.exitCode());
    if (report.message() != null) {
      root.put("message", report.message());
    }
    ArrayNode checks = root.putArray("checks");
    for (RunReport.Check check : report.checks()) {
      ObjectNode checkNode = checks.addObject();
      checkNode.put("name", check.name());
      checkNode.put("outcome", check.outcome().label());
      if (check.message() != null) {
        checkNode.put("message", check.message());
      }
    }
    if (report.finding() != null) {
      ObjectNode finding = root.putObject("finding");
      finding.put("category", report.finding().category().label());
      finding.put("check", report.finding().check());
    }
    TraceWriter.Counterexample counterexample = report.counterexample();
    if (counterexample != null) {
      ObjectNode counterexampleNode = root.putObject("counterexample");
      ArrayNode transitions = counterexampleNode.putArray("transitions");
      counterexample.transitions().forEach(transitions::add);
      counterexampleNode.put("violatingState", counterexample.violatingState());
      if (!counterexample.violatedInvariants().isEmpty()) {
        ArrayNode violated = counterexampleNode.putArray("violatedInvariants");
        counterexample.violatedInvariants().forEach(violated::add);
      }
      if (!counterexample.bindings().isEmpty()) {
        ArrayNode bindings = counterexampleNode.putArray("bindings");
        for (TraceWriter.Binding binding : counterexample.bindings()) {
          ObjectNode bindingNode = bindings.addObject();
          bindingNode.put("name", binding.name());
          bindingNode.put("value", binding.value());
        }
      }
    }
    if (!report.evaluations().isEmpty()) {
      ArrayNode evaluations = root.putArray("evaluations");
      for (RunReport.StateEvaluation stateEvaluation : report.evaluations()) {
        ObjectNode stateNode = evaluations.addObject();
        stateNode.put("state", stateEvaluation.state());
        ArrayNode values = stateNode.putArray("values");
        for (RunReport.FormulaValue value : stateEvaluation.values()) {
          ObjectNode valueNode = values.addObject();
          valueNode.put("formula", value.formula());
          valueNode.put("value", value.value());
          if (value.error()) {
            valueNode.put("error", true);
          }
        }
      }
    }
    if (report.traceFile() != null) {
      root.put("traceFile", report.traceFile().toString());
    }
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
  }
}
