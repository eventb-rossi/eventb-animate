package animate;

import ch.qos.logback.classic.Logger;
import de.prob.animator.command.ConstraintBasedSequenceCheckCommand;
import de.prob.animator.command.ConstraintBasedSequenceCheckCommand.ResultType;
import de.prob.animator.domainobjects.ClassicalB;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;

/**
 * Constraint-based operation-coverage test generation for Event-B.
 *
 * <p>ProB's own {@code ConstraintBasedTestCaseGenerator} cannot run on Event-B models: it derives
 * every target's guard through {@code (ClassicalB) Join.conjunct(model, guards)}, but an {@code
 * EventBModel} parses formulas to {@link de.prob.animator.domainobjects.EventB}, so that cast
 * throws {@code ClassCastException} before a single target is built (its {@code MCDCIdentifier} has
 * the same flaw). This driver instead works directly on {@link ConstraintBasedSequenceCheckCommand}
 * (ProB's {@code prob2_find_test_path}), which takes operation <em>names</em> and never translates
 * guards, so it is Event-B-safe.
 *
 * <p>The search reproduces the upstream algorithm's shape: a breadth-first extension of feasible
 * operation-name prefixes. The command has exact-sequence semantics -- it finds a feasible path
 * running <em>exactly</em> the listed operations (with initialisation solved in), inserting nothing
 * between them -- so covering an operation that is only enabled after others requires supplying
 * that prefix, which the breadth-first extension builds up. Each operation is used as an extension
 * at most once ({@link #generate() visited}); that bound is what keeps the frontier from exploding,
 * so the search yields at most one witness trace per operation and stays roughly quadratic in the
 * operation count.
 */
final class TestgenGenerator {

  private static final Logger logger = (Logger) LoggerFactory.getLogger(TestgenGenerator.class);

  /** The trivial end predicate: we only ask whether the operation sequence itself is feasible. */
  private static final IEvalElement ANY_STATE = new ClassicalB("1=1");

  private final StateSpace stateSpace;
  private final List<String> allOps;
  private final List<String> targets;
  private final Set<String> infeasible;
  private final Set<String> finalOps;
  private final int maxDepth;
  private final int timeoutMs;

  /**
   * @param allOps every machine event except INITIALISATION
   * @param targets the operations to cover (a subset of {@code allOps})
   * @param infeasible the statically dead operations ({@link Feasibility}), reported apart and
   *     never searched; the remaining operations are treated as feasible
   * @param finalOps operations after which a trace is not extended further
   * @param maxDepth the maximum number of operation steps in a witness trace (excluding
   *     initialisation)
   * @param timeoutMs the per-attempt solver timeout handed to {@code prob2_find_test_path}
   */
  TestgenGenerator(
      StateSpace stateSpace,
      List<String> allOps,
      List<String> targets,
      Set<String> infeasible,
      Set<String> finalOps,
      int maxDepth,
      int timeoutMs) {
    this.stateSpace = stateSpace;
    this.allOps = allOps;
    this.targets = targets;
    this.infeasible = infeasible;
    this.finalOps = finalOps;
    this.maxDepth = maxDepth;
    this.timeoutMs = timeoutMs;
  }

  /**
   * The outcome of a generation run: one witness trace per covered target (in discovery order), the
   * feasible targets left without a witness within the depth bound, the statically infeasible
   * (dead) targets, and whether the search was interrupted before finishing.
   */
  record Result(
      Map<String, Trace> covered,
      List<String> uncovered,
      List<String> infeasible,
      boolean interrupted) {}

  Result generate() {
    // Statically dead operations (guard unsatisfiable under the invariant) can never be covered, so
    // they are reported apart and never searched.
    List<String> infeasibleTargets = new ArrayList<>();
    Set<String> uncovered = new LinkedHashSet<>();
    for (String target : targets) {
      if (infeasible.contains(target)) {
        infeasibleTargets.add(target);
      } else {
        uncovered.add(target);
      }
    }

    // Feasible non-target operations serve only as stepping stones: prefixes that reach a target
    // which is enabled only after them. Extending by an already-covered target is unnecessary --
    // its own witness prefix is already in the frontier.
    List<String> steppingStones = new ArrayList<>();
    for (String op : allOps) {
      if (!infeasible.contains(op) && !targets.contains(op)) {
        steppingStones.add(op);
      }
    }

    Map<String, Trace> covered = new LinkedHashMap<>();
    Set<String> visited = new HashSet<>();
    List<List<String>> frontier = new ArrayList<>();
    frontier.add(new ArrayList<>()); // the empty prefix: just initialisation
    boolean interrupted = false;

    for (int depth = 0;
        depth < maxDepth && !uncovered.isEmpty() && !frontier.isEmpty() && !interrupted;
        depth++) {
      List<List<String>> nextFrontier = new ArrayList<>();
      prefixes:
      for (List<String> prefix : frontier) {
        // Phase 1: try to cover each remaining target by running it after this prefix.
        for (String op : new ArrayList<>(uncovered)) {
          List<String> sequence = extend(prefix, op);
          ConstraintBasedSequenceCheckCommand cmd = runSequence(sequence);
          if (cmd == null) {
            continue;
          }
          if (cmd.getResult() == ResultType.INTERRUPTED) {
            interrupted = true;
            break prefixes;
          }
          if (cmd.getResult() == ResultType.PATH_FOUND) {
            covered.put(op, cmd.getTrace());
            // uncovered.remove already prevents re-covering this target; visited only guards the
            // stepping-stone loop below, which never revisits a target (targets are excluded
            // there).
            uncovered.remove(op);
            if (!finalOps.contains(op)) {
              nextFrontier.add(sequence);
            }
          }
        }
        // Phase 2: extend by stepping stones so deeper targets become reachable next round. Each
        // stepping stone is used once across the whole search, which bounds the frontier growth.
        for (String op : steppingStones) {
          if (visited.contains(op)) {
            continue;
          }
          List<String> sequence = extend(prefix, op);
          ConstraintBasedSequenceCheckCommand cmd = runSequence(sequence);
          if (cmd == null) {
            continue;
          }
          if (cmd.getResult() == ResultType.INTERRUPTED) {
            interrupted = true;
            break prefixes;
          }
          if (cmd.getResult() == ResultType.PATH_FOUND) {
            visited.add(op);
            if (!finalOps.contains(op)) {
              nextFrontier.add(sequence);
            }
          }
        }
      }
      frontier = nextFrontier;
    }

    return new Result(covered, new ArrayList<>(uncovered), infeasibleTargets, interrupted);
  }

  /**
   * Asks ProB for a feasible path running exactly {@code sequence}. Returns null when the solver
   * call itself fails (a malformed setup for a particular operation), which is treated as "not
   * covered via this prefix" so one awkward operation cannot abort the whole run.
   */
  private ConstraintBasedSequenceCheckCommand runSequence(List<String> sequence) {
    ConstraintBasedSequenceCheckCommand cmd =
        new ConstraintBasedSequenceCheckCommand(stateSpace, sequence, ANY_STATE, timeoutMs);
    try {
      stateSpace.execute(cmd);
    } catch (RuntimeException e) {
      logger.debug("Sequence check failed for {}", sequence, e);
      return null;
    }
    return cmd;
  }

  private static List<String> extend(List<String> prefix, String op) {
    List<String> extended = new ArrayList<>(prefix.size() + 1);
    extended.addAll(prefix);
    extended.add(op);
    return extended;
  }
}
