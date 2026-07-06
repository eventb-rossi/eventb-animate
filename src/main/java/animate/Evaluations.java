package animate;

import de.prob.animator.domainobjects.AbstractEvalResult;
import de.prob.animator.domainobjects.ErrorItem;
import de.prob.animator.domainobjects.EvalOptions;
import de.prob.animator.domainobjects.EvalResult;
import de.prob.animator.domainobjects.EvaluationErrorResult;
import de.prob.animator.domainobjects.EventB;
import de.prob.animator.domainobjects.FormulaExpand;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.statespace.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Shared formula-evaluation core for the {@code --eval} check flag and the {@code eval} subcommand:
 * parse a user formula, evaluate it in a given state, classify the result, and render it. Kept in
 * one place so both surfaces accept the same syntax (ASCII or Unicode, predicate or expression) and
 * report values identically.
 */
final class Evaluations {

  // Fully expand set values in the printed result (no "..." truncation), so a variable's value is
  // shown in full; EventB parses to EXPAND already, but state the intent explicitly.
  private static final EvalOptions EXPAND = EvalOptions.DEFAULT.withExpand(FormulaExpand.EXPAND);

  private Evaluations() {}

  /** A parsed formula paired with the exact text the user typed, used verbatim as its label. */
  record Formula(String source, EventB element) {}

  /**
   * Parses an {@code -e}/{@code --eval} formula, accepting a predicate or an expression (an
   * assignment is rejected). A bad formula is a usage error (exit 2) raised before any model load,
   * exactly like {@code --goal}.
   */
  static Formula parseFormula(CommandSpec spec, String value, String option) {
    return new Formula(value, Animate.parseFormulaOption(spec, value, option, true));
  }

  /**
   * Evaluates every formula in {@code state} and returns the labelled block. A formula ProB cannot
   * compute (type/WD error, uninitialised identifier, enumeration warning) becomes an {@code error}
   * value rather than throwing, so one bad formula never discards the others; a failure of the
   * whole batch degrades every formula in the block instead of propagating. Results are looked up
   * by element, not by position, because the kernel collapses equal formulas (duplicates, or ASCII
   * and Unicode spellings of the same predicate) into a single entry -- a positional list would
   * then be shorter than the input and misalign.
   */
  static RunReport.StateEvaluation evaluate(String label, State state, List<Formula> formulas) {
    List<IEvalElement> elements = new ArrayList<>(formulas.size());
    for (Formula formula : formulas) {
      elements.add(formula.element());
    }
    Map<IEvalElement, AbstractEvalResult> results;
    try {
      results = state.evalFormulas(elements, EXPAND);
    } catch (RuntimeException e) {
      String message = messageOf(e);
      List<RunReport.FormulaValue> degraded = new ArrayList<>(formulas.size());
      for (Formula formula : formulas) {
        degraded.add(new RunReport.FormulaValue(formula.source(), message, true));
      }
      return new RunReport.StateEvaluation(label, degraded);
    }
    List<RunReport.FormulaValue> values = new ArrayList<>(formulas.size());
    for (Formula formula : formulas) {
      values.add(classify(formula.source(), results.get(formula.element())));
    }
    return new RunReport.StateEvaluation(label, values);
  }

  private static RunReport.FormulaValue classify(String source, AbstractEvalResult result) {
    if (result instanceof EvalResult evalResult) {
      return new RunReport.FormulaValue(source, orUnknown(evalResult.getValue()), false);
    }
    return new RunReport.FormulaValue(source, errorText(result), true);
  }

  /** The error text for a non-{@link EvalResult}: the error type plus its items; never null. */
  private static String errorText(AbstractEvalResult result) {
    if (result == null) {
      return "no result returned";
    }
    if (result instanceof EvaluationErrorResult error) {
      List<String> items = new ArrayList<>();
      for (ErrorItem item : error.getErrorItems()) {
        String rendered = String.valueOf(item);
        if (!rendered.isBlank()) {
          items.add(rendered);
        }
      }
      String head = orUnknown(error.getResult());
      return items.isEmpty() ? head : head + " (" + String.join("; ", items) + ")";
    }
    // EnumerationWarning and any other kernel result render their own message (e.g. "UNKNOWN").
    return orUnknown(result.toString());
  }

  /** A non-null message for an exception whose getMessage() is null (e.g. a bare NPE from ProB). */
  private static String messageOf(RuntimeException e) {
    String message = e.getMessage();
    return message != null ? message : e.getClass().getSimpleName();
  }

  /**
   * Coalesces a null/blank kernel string so a FormulaValue never carries null -- the Markdown
   * writer feeds every value through a regex that throws on null.
   */
  private static String orUnknown(String value) {
    return value == null || value.isBlank() ? "(unknown)" : value;
  }

  /**
   * One rendered line: {@code formula = value} for a value, {@code formula: error} for a failure.
   */
  static String line(RunReport.FormulaValue value) {
    return value.formula() + (value.error() ? ": " : " = ") + value.value();
  }

  /**
   * Prints the indented value lines of a block (no header); the one place a value line is spelled.
   */
  private static void printValues(RunReport.StateEvaluation block) {
    for (RunReport.FormulaValue value : block.values()) {
      System.out.println("\t" + line(value));
    }
  }

  /** Prints a block under {@code header}, then its indented value lines. */
  static void printBlock(String header, RunReport.StateEvaluation block) {
    System.out.println(header);
    printValues(block);
  }

  /** Prints a single block headed by the state it was evaluated in (the --eval path). */
  static void printBlock(RunReport.StateEvaluation block) {
    printBlock("Evaluated formulas (" + block.state() + "):", block);
  }
}
