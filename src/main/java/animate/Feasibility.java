package animate;

import ch.qos.logback.classic.Logger;
import de.prob.animator.command.CbcSolveCommand;
import de.prob.animator.domainobjects.EvalResult;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.animator.domainobjects.Join;
import de.prob.model.eventb.EventBMachine;
import de.prob.model.representation.Extraction;
import de.prob.statespace.StateSpace;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Per-operation feasibility: for each event, ask the constraint solver whether the invariant and
 * the event's guard are jointly satisfiable. An unsatisfiable conjunction means the event is dead
 * -- its guard can never hold in an invariant-satisfying state. A solver that neither proves nor
 * refutes it (a timeout, or a well-definedness error such as a partial function applied outside its
 * domain) leaves it undecided rather than reporting it, falsely, as dead.
 *
 * <p>This is the single source of the feasibility classification, shared by {@code cbc
 * --feasibility} and {@code testgen}. It supersedes the kernel's {@code FeasibilityAnalysis}, which
 * reports every non-TRUE verdict (timeouts included) as infeasible and -- fatal for a batch tool --
 * lets a single operation's solver error abort the whole analysis instead of degrading that one
 * operation.
 */
final class Feasibility {

  private static final Logger logger = (Logger) LoggerFactory.getLogger(Feasibility.class);

  private Feasibility() {}

  /**
   * The dead operations and those the solver could not decide (each in the order {@code events}
   * lists), plus whether any per-event solve <em>failed outright</em>. A failure is kept distinct
   * from a plain timeout: both leave the event undecided, but an error signals something went
   * wrong, so a caller that gates on feasibility can refuse to pass silently rather than treat it
   * as clean.
   */
  record Result(List<String> infeasible, List<String> undecided, boolean errored) {}

  static Result analyse(StateSpace stateSpace, EventBMachine machine, List<String> events) {
    List<IEvalElement> invariants = Extraction.getInvariantPredicates(machine);
    List<String> infeasible = new ArrayList<>();
    List<String> undecided = new ArrayList<>();
    boolean errored = false;
    for (String event : events) {
      String verdict;
      try {
        verdict = solve(stateSpace, machine, invariants, event);
      } catch (RuntimeException e) {
        // One awkward event does not abort the analysis (the kernel's FeasibilityAnalysis would),
        // but it is flagged so a gate does not read a solver crash as "feasible".
        logger.debug("Feasibility solve failed for {}", event, e);
        undecided.add(event);
        errored = true;
        continue;
      }
      if ("FALSE".equals(verdict)) {
        infeasible.add(event);
      } else if (!"TRUE".equals(verdict)) {
        undecided.add(event);
      }
    }
    return new Result(infeasible, undecided, errored);
  }

  /** "TRUE"/"FALSE" when the solver decided, null when it ran but returned no verdict (timeout). */
  private static String solve(
      StateSpace stateSpace, EventBMachine machine, List<IEvalElement> invariants, String event) {
    List<IEvalElement> predicates = new ArrayList<>(invariants);
    predicates.addAll(Extraction.getGuardPredicates(machine, event));
    CbcSolveCommand solve = new CbcSolveCommand(Join.conjunct(stateSpace.getModel(), predicates));
    stateSpace.execute(solve);
    return solve.getValue() instanceof EvalResult result ? result.getValue() : null;
  }
}
