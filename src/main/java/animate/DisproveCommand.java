package animate;

import de.prob.animator.command.AbstractCommand;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.parser.ISimplifiedROMap;
import de.prob.prolog.output.IPrologTermOutput;
import de.prob.prolog.term.ListPrologTerm;
import de.prob.prolog.term.PrologTerm;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks ProB's constraint solver for a counterexample to one proof obligation: a valuation
 * satisfying the hypotheses but not the goal. The wire format and result functors follow the
 * disprover machinery of ProB's Rodin plugin (EPL-1.0). ProB negates the goal internally, so a
 * solution disproves the obligation and a contradiction proves it.
 */
class DisproveCommand extends AbstractCommand {

  enum Verdict {
    /** A counterexample valid under all hypotheses: the obligation is proven false. */
    DISPROVED,
    /** A counterexample under the selected hypotheses only; it may be spurious. */
    DISPROVED_ON_SELECTED,
    /** The solver proved the obligation (no counterexample can exist). */
    PROVED,
    /** The hypotheses are contradictory: the obligation holds vacuously. */
    CONTRADICTORY_HYPOTHESES,
    /** The solver hit the time limit without a verdict. */
    TIMEOUT,
    /** The solver gave up without a verdict (e.g. unfixed deferred sets). */
    NO_SOLUTION_FOUND,
    /** The solver run was interrupted. */
    INTERRUPTED
  }

  private final PoSequentParser.Sequent sequent;
  private final int timeoutMs;

  private Verdict verdict;
  private String detail;

  DisproveCommand(PoSequentParser.Sequent sequent, int timeoutMs) {
    this.sequent = sequent;
    this.timeoutMs = timeoutMs;
  }

  Verdict getVerdict() {
    return verdict;
  }

  /** Counterexample bindings or the solver's reason; empty when there is nothing to add. */
  String getDetail() {
    return detail == null ? "" : detail;
  }

  @Override
  public void writeCommand(IPrologTermOutput pto) {
    pto.openTerm("cbc_disprove");
    sequent.goal().printProlog(pto);
    pto.openList();
    for (IEvalElement hypothesis : sequent.hypotheses()) {
      hypothesis.printProlog(pto);
    }
    pto.closeList();
    pto.openList();
    for (IEvalElement hypothesis : sequent.selectedHypotheses()) {
      hypothesis.printProlog(pto);
    }
    pto.closeList();
    pto.printNumber(timeoutMs);
    pto.emptyList();
    pto.printVariable("Result");
    pto.closeTerm();
  }

  @Override
  public void processResult(ISimplifiedROMap<String, PrologTerm> bindings) {
    PrologTerm result = bindings.get("Result");
    switch (result.getFunctor()) {
      case "solution" -> {
        verdict = Verdict.DISPROVED;
        detail = renderBindings(result.getArgument(1));
      }
      case "solution_on_selected_hypotheses" -> {
        verdict = Verdict.DISPROVED_ON_SELECTED;
        detail = renderBindings(result.getArgument(1));
      }
      case "contradiction_found" -> verdict = Verdict.PROVED;
      case "contradiction_in_hypotheses" -> verdict = Verdict.CONTRADICTORY_HYPOTHESES;
      case "time_out" -> verdict = Verdict.TIMEOUT;
      case "no_solution_found" -> {
        verdict = Verdict.NO_SOLUTION_FOUND;
        detail = renderReason(result.getArgument(1));
      }
      case "interrupted" -> verdict = Verdict.INTERRUPTED;
      default -> throw new IllegalStateException("unexpected cbc_disprove result: " + result);
    }
  }

  /** The solution is a list of binding(Name, Value, PrettyPrintedValue) terms. */
  private static String renderBindings(PrologTerm solution) {
    if (!(solution instanceof ListPrologTerm list)) {
      return solution.toString();
    }
    List<String> bindings = new ArrayList<>();
    for (PrologTerm binding : list) {
      bindings.add(
          binding.getArgument(1).getFunctor() + " = " + binding.getArgument(3).getFunctor());
    }
    return String.join(", ", bindings);
  }

  private static String renderReason(PrologTerm reason) {
    if (reason.hasFunctor("clpfd_overflow", 0)) {
      return "CLPFD integer overflow";
    }
    if (reason.hasFunctor("unfixed_deferred_sets", 0)) {
      return "unfixed deferred sets";
    }
    return reason.toString();
  }
}
