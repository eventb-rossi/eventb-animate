package animate;

import de.prob.animator.domainobjects.EventB;
import de.prob.check.ConsistencyChecker;
import de.prob.check.IModelCheckListener;
import de.prob.check.ModelCheckingOptions;
import de.prob.statespace.State;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Evaluates Event-B expressions and predicates in explored states. Without {@code --where} the
 * formulas are evaluated in the initialised state (a quick probe of the model's start); with {@code
 * --where PRED} they are evaluated in every explored state satisfying {@code PRED}, so one run can
 * report a variable's value across the reachable states. ProB evaluation is state-addressed, so no
 * state is special -- {@code --where} is the CLI form of "evaluate over the state space".
 */
@Command(
    name = "eval",
    description =
        "Evaluate Event-B expressions or predicates in explored states: in the initialised state by"
            + " default, or -- with --where -- in every explored state satisfying a predicate",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class EvalCommand implements Callable<Integer> {

  @ParentCommand Animate parent;
  @Spec CommandSpec spec;

  @Option(
      names = {"-e", "--expr"},
      required = true,
      paramLabel = "formula",
      description =
          "Event-B expression or predicate to evaluate (ASCII or Unicode operators; repeatable)")
  List<String> formulas;

  @Option(
      names = "--where",
      paramLabel = "predicate",
      description =
          "evaluate in every explored state satisfying this Event-B predicate, bounded by"
              + " --states/--time-limit (default: the initialised state only)")
  String where;

  // -1 means no limit, mirroring the root check's --states/--time-limit.
  @Option(
      names = "--states",
      paramLabel = "N",
      description =
          "bound the --where state-space exploration to at most N states (default: exhaustive)")
  int states = -1;

  @Option(
      names = "--time-limit",
      paramLabel = "seconds",
      description =
          "bound the --where state-space exploration to the given wall-clock time (default:"
              + " unlimited)")
  int timeLimit = -1;

  private List<Evaluations.Formula> parsedFormulas;
  private EventB parsedWhere;

  // Initialised states carry unique integer ids as strings (the uninitialised "root" is filtered
  // out before sorting), so order them numerically rather than by the kernel's hash-map order. A
  // non-numeric id would throw, but that lands in evaluateWhere's try/catch as a clean non-verdict.
  private static final Comparator<State> BY_ID =
      Comparator.comparingLong(state -> Long.parseLong(state.getId()));

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EvalCommand.class);

  @Override
  public Integer call() {
    validate();
    return parent.finishRun(parent.withStateSpace(this::runEval));
  }

  /** Usage errors, raised before the model load like the root check's validation. */
  private void validate() {
    Animate.validatePositiveBound(spec, states, "--states", "states", "an exhaustive search");
    Animate.validatePositiveBound(spec, timeLimit, "--time-limit", "seconds", "no limit");
    if (where == null && states != -1) {
      throw usageError("--states only bounds the --where state-space search (add --where)");
    }
    if (where == null && timeLimit != -1) {
      throw usageError("--time-limit only bounds the --where state-space search (add --where)");
    }
    parsedFormulas = new ArrayList<>();
    for (String formula : formulas) {
      parsedFormulas.add(Evaluations.parseFormula(spec, formula, "-e/--expr"));
    }
    if (where != null) {
      parsedWhere = Animate.parsePredicateOption(spec, where, "--where");
    }
  }

  private ParameterException usageError(String message) {
    return Animate.usageError(spec, message);
  }

  private RunReport runEval(StateSpace stateSpace) {
    return where == null ? evaluateInitialised(stateSpace) : evaluateWhere(stateSpace);
  }

  private RunReport evaluateInitialised(StateSpace stateSpace) {
    Trace trace = parent.initializeInTransaction(stateSpace);
    RunReport.StateEvaluation block =
        Evaluations.evaluate("initialised state", trace.getCurrentState(), parsedFormulas);
    Evaluations.printBlock(block);
    return reportOne(List.of(block), evaluatedMessage("the initialised state"));
  }

  private RunReport evaluateWhere(StateSpace stateSpace) {
    // Explore with every check disabled so exploration is never truncated at a first violation --
    // eval selects over the whole (bounded) reachable space, it does not look for a verdict.
    ModelCheckingOptions options =
        Animate.applyBounds(
            new ModelCheckingOptions()
                .checkDeadlocks(false)
                .checkInvariantViolations(false)
                .checkAssertions(false),
            states,
            timeLimit);
    System.out.println("Exploring the state space...");
    List<State> matching;
    try {
      // Cast disambiguates from the goal-predicate constructor; no listener and no goal.
      new ConsistencyChecker(stateSpace, options, (IModelCheckListener) null).call();
      // get_states_for_predicate also returns uninitialised states (root/constants-setup); a
      // formula over the variables is only meaningful once the machine is initialised, so drop
      // them. This selection stays inside the try: an ill-typed or well-definedness-unsound --where
      // predicate parses fine but errors only here, and that is a non-verdict, not a crash.
      matching =
          stateSpace.getStatesFromPredicate(parsedWhere).stream()
              .filter(State::isInitialised)
              .sorted(BY_ID)
              .toList();
    } catch (RuntimeException e) {
      // A mid-search kernel failure -- exploration, or a --where predicate ProB cannot evaluate --
      // is a non-verdict, not an empty result.
      logger.debug("eval --where did not complete", e);
      String message = "eval --where did not complete: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "eval", message);
    }

    if (matching.isEmpty()) {
      String message =
          "No explored state satisfies the --where predicate (within the searched state space).";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "eval", message);
    }

    System.out.println(Animate.count(matching.size(), "state") + " satisfy the --where predicate:");
    List<RunReport.StateEvaluation> blocks = new ArrayList<>();
    for (State state : matching) {
      RunReport.StateEvaluation block =
          Evaluations.evaluate("state " + state.getId(), state, parsedFormulas);
      blocks.add(block);
      Evaluations.printBlock("  " + block.state() + ":", block);
    }
    return reportOne(blocks, evaluatedMessage(Animate.count(matching.size(), "state")));
  }

  /** "Evaluated N formula(s) in {location}." -- the shared summary of both eval paths. */
  private String evaluatedMessage(String location) {
    return "Evaluated " + Animate.count(parsedFormulas.size(), "formula") + " in " + location + ".";
  }

  /**
   * One {@code eval} check carrying the run's verdict: OK when every formula evaluated, INCOMPLETE
   * (exit 2) when any formula could not be -- a query that could not be answered is a non-verdict,
   * not a violation. The values themselves ride in the report's evaluations block, not in checks.
   */
  private RunReport reportOne(List<RunReport.StateEvaluation> blocks, String okMessage) {
    List<String> failed =
        blocks.stream()
            .flatMap(block -> block.values().stream())
            .filter(RunReport.FormulaValue::error)
            .map(RunReport.FormulaValue::formula)
            .distinct()
            .toList();
    if (!failed.isEmpty()) {
      String message =
          Animate.count(failed.size(), "formula")
              + " could not be evaluated: "
              + String.join(", ", failed);
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "eval", message)
          .withEvaluations(blocks);
    }
    System.out.println(okMessage);
    return RunReport.singleCheck(RunReport.Status.OK, "eval", okMessage).withEvaluations(blocks);
  }
}
