package animate;

import de.prob.animator.command.GetRedundantInvariantsCommand;
import de.prob.animator.domainobjects.EventB;
import de.prob.check.CBCDeadlockChecker;
import de.prob.check.CBCDeadlockFound;
import de.prob.check.CBCInvariantChecker;
import de.prob.check.CBCInvariantViolationFound;
import de.prob.check.IModelCheckingResult;
import de.prob.check.InvariantCheckCounterExample;
import de.prob.check.ModelCheckOk;
import de.prob.model.eventb.EventBMachine;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "cbc",
    description =
        "Constraint-based checks without exploring the state space: prove per-event invariant"
            + " preservation (for each event, search for an invariant-satisfying state --"
            + " reachable or not -- from which one step violates the invariant), and optionally"
            + " search for invariant-satisfying deadlocks",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class CbcCommand implements Callable<Integer> {

  @ParentCommand Animate parent;
  @Spec CommandSpec spec;

  @Option(
      names = "--events",
      split = ",",
      paramLabel = "event",
      description =
          "restrict the invariant check to these events (comma-separated or repeated;"
              + " default: every event of the machine)")
  List<String> events;

  @Option(
      names = "--deadlock",
      description =
          "also search for a deadlocking state that satisfies the invariant (the state need"
              + " not be reachable); a hit is a violation with the state as its trace")
  boolean deadlock;

  @Option(
      names = "--where",
      paramLabel = "predicate",
      description =
          "restrict the --deadlock search to states also satisfying this Event-B" + " predicate")
  String where;

  @Option(
      names = "--no-invariant",
      description = "skip the invariant preservation check (e.g. to search only for deadlocks)")
  boolean noInvariant;

  @Option(
      names = "--feasibility",
      description =
          "also report events whose guard can never be satisfied under the invariant"
              + " (dead events); advisory unless --strict")
  boolean feasibility;

  @Option(
      names = "--redundant-invariants",
      description =
          "also report invariants implied by the remaining ones; advisory unless" + " --strict")
  boolean redundantInvariants;

  @Option(
      names = "--strict",
      description =
          "turn the advisory --feasibility and --redundant-invariants findings into failures"
              + " (exit 1)")
  boolean strict;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save the counterexample trace in json to a file (when a violation is found)")
  Path jsonTrace;

  private EventB parsedWhere;

  // The first violation of the run owns the --save slot; later ones only report.
  private boolean traceSaved;

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CbcCommand.class);

  @Override
  public Integer call() {
    validateCbcOptions();
    // The check must prove preservation from scratch: with PROOF_INFO on, ProB trusts
    // the Rodin discharge info shipped with the model and skips proven obligations,
    // which would make this gate circular with the po command.
    parent.commandPrefs.put("PROOF_INFO", "false");
    return parent.finishRun(parent.withStateSpace(this::runCbc));
  }

  /** Usage errors, raised before the model load like the root check's validation. */
  private void validateCbcOptions() {
    if (noInvariant && !deadlock && !feasibility && !redundantInvariants) {
      throw usageError(
          "nothing to check: --no-invariant disables the only requested check (add --deadlock,"
              + " --feasibility, or --redundant-invariants)");
    }
    if (noInvariant && events != null) {
      throw usageError("--events restricts the invariant check, which --no-invariant disables");
    }
    if (strict && !feasibility && !redundantInvariants) {
      throw usageError(
          "--strict has nothing to escalate (add --feasibility or --redundant-invariants)");
    }
    if (where != null && !deadlock) {
      throw usageError("--where only restricts the --deadlock search (add --deadlock)");
    }
    if (where != null) {
      parsedWhere = Animate.parsePredicateOption(spec, where, "--where");
    }
  }

  private ParameterException usageError(String message) {
    return Animate.usageError(spec, message);
  }

  private RunReport runCbc(StateSpace stateSpace) {
    if (!(stateSpace.getMainComponent() instanceof EventBMachine machine)) {
      String message = "cbc requires a machine, but the loaded component is a context";
      System.err.println("Error: " + message);
      return RunReport.singleCheck(RunReport.Status.ERROR, "invariant", message);
    }
    List<RunReport> parts = new ArrayList<>();
    if (!noInvariant) {
      List<String> machineEvents = Animate.operationNames(machine);
      List<String> scope = events != null ? events : machineEvents;
      // An unknown event is an input error, not a usage error: whether the name exists
      // depends on the model, and the requested JSON/JUnit reports must still be written.
      List<String> unknown = new ArrayList<>(scope);
      unknown.removeAll(machineEvents);
      if (!unknown.isEmpty()) {
        String message =
            "unknown event(s) for --events: "
                + String.join(", ", unknown)
                + " (the machine's events are: "
                + String.join(", ", machineEvents)
                + ")";
        System.err.println("Error: " + message);
        return RunReport.singleCheck(RunReport.Status.ERROR, "invariant", message);
      }
      parts.add(checkInvariantPreservation(stateSpace, scope));
    }
    if (deadlock) {
      // Every requested analysis runs even after a violation: they are independent
      // solver queries, so one finding proves nothing about the others.
      parts.add(checkDeadlockFreedom(stateSpace));
    }
    if (feasibility) {
      parts.add(checkFeasibility(stateSpace, machine));
    }
    if (redundantInvariants) {
      parts.add(checkRedundantInvariants(stateSpace));
    }
    return RunReport.merge(parts);
  }

  private RunReport checkDeadlockFreedom(StateSpace stateSpace) {
    System.out.println("Constraint-based deadlock check...");
    IModelCheckingResult result;
    try {
      result = new CBCDeadlockChecker(stateSpace, parsedWhere).call();
    } catch (RuntimeException e) {
      logger.debug("Constraint-based deadlock check failed", e);
      return incomplete("deadlock", "Constraint-based deadlock check", e.getMessage());
    }

    if (result instanceof ModelCheckOk) {
      String message =
          where != null
              ? "No deadlocking state satisfies the invariant and the --where predicate."
              : "No deadlocking state satisfies the invariant.";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "deadlock", message);
    }

    if (result instanceof CBCDeadlockFound found) {
      String message =
          "a deadlocking state satisfying the invariant was found (it may be unreachable).";
      System.err.println("Error: " + message);
      Trace counterexample = found.getTrace(stateSpace);
      // The deadlock state satisfies the invariants, so there is nothing to evaluate.
      TraceWriter.Counterexample described =
          TraceWriter.describe(stateSpace, counterexample, false);
      TraceWriter.printTrace(described);
      return saveFirstTrace(
          RunReport.singleCheck(RunReport.Status.VIOLATION, "deadlock", message)
              .withCounterexample(described),
          counterexample,
          stateSpace);
    }

    // CheckError or NotYetFinished (interrupted): nothing was proven.
    return incomplete("deadlock", "Constraint-based deadlock check", result.getMessage());
  }

  private RunReport saveFirstTrace(RunReport report, Trace counterexample, StateSpace stateSpace) {
    if (traceSaved) {
      return report;
    }
    traceSaved = true;
    return parent.withSavedTrace(report, counterexample, stateSpace, jsonTrace);
  }

  /** Prints and reports an analysis that produced no verdict. */
  private static RunReport incomplete(String checkName, String what, String reason) {
    return Animate.incomplete(checkName, what + " did not complete: " + reason);
  }

  private RunReport checkFeasibility(StateSpace stateSpace, EventBMachine machine) {
    System.out.println("Feasibility check...");
    // The kernel's FeasibilityAnalysis lumps solver timeouts in with proven-dead events;
    // Feasibility
    // solves per event so "no verdict" is never reported as "dead". INITIALISATION is excluded like
    // the preservation check does: it has no guard, so it is never a dead event.
    Feasibility.Result result;
    try {
      result = Feasibility.analyse(stateSpace, machine, Animate.operationNames(machine));
    } catch (RuntimeException e) {
      logger.debug("Feasibility check failed", e);
      return incomplete("feasibility", "Feasibility check", e.getMessage());
    }
    if (result.errored()) {
      // A solver error (not a mere timeout) means the check could not run to completion, so it is a
      // non-verdict rather than a clean pass -- even without --strict.
      return incomplete(
          "feasibility", "Feasibility check", "the constraint solver failed on one or more events");
    }
    List<String> infeasible = result.infeasible();
    List<String> undecided = result.undecided();
    String noVerdict =
        undecided.isEmpty() ? null : "no solver verdict for " + String.join(", ", undecided);
    if (noVerdict != null) {
      System.out.println(
          "Feasibility undecided (no solver verdict):\n\t - " + String.join("\n\t - ", undecided));
    }
    if (infeasible.isEmpty()) {
      if (noVerdict != null && strict) {
        // Under --strict a clean pass is a claim; an unanswered query cannot back it.
        return incomplete("feasibility", "Feasibility check", noVerdict);
      }
      String message =
          noVerdict == null
              ? "Every event is feasible (each guard is satisfiable under the invariant)."
              : "No infeasible event found (" + noVerdict + ").";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "feasibility", message);
    }
    return advisoryFinding(
        "feasibility",
        infeasible.size()
            + " infeasible (dead) event"
            + Animate.plural(infeasible.size())
            + ": "
            + String.join(", ", infeasible)
            + (noVerdict == null ? "" : " (" + noVerdict + ")"),
        "Infeasible (dead) events"
            + advisoryNote()
            + ":\n\t - "
            + String.join("\n\t - ", infeasible));
  }

  private RunReport checkRedundantInvariants(StateSpace stateSpace) {
    System.out.println("Redundant-invariant check...");
    GetRedundantInvariantsCommand cmd = new GetRedundantInvariantsCommand();
    try {
      stateSpace.execute(cmd);
    } catch (RuntimeException e) {
      logger.debug("Redundant-invariant check failed", e);
      return incomplete("redundant-invariants", "Redundant-invariant check", e.getMessage());
    }
    List<String> redundant = cmd.getRedundantInvariants();
    String timeoutNote = cmd.isTimeout() ? " (solver timeout: the list may be incomplete)" : "";
    if (redundant.isEmpty()) {
      if (cmd.isTimeout() && strict) {
        // Under --strict a clean pass is a claim; a timed-out solver cannot back it.
        return incomplete("redundant-invariants", "Redundant-invariant check", "solver timeout");
      }
      String message = "No invariant is implied by the others" + timeoutNote + ".";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "redundant-invariants", message);
    }
    return advisoryFinding(
        "redundant-invariants",
        redundant.size()
            + " redundant invariant"
            + Animate.plural(redundant.size())
            + " implied by the others"
            + timeoutNote
            + ": "
            + String.join(", ", redundant),
        "Redundant invariants"
            + advisoryNote()
            + timeoutNote
            + ":\n\t - "
            + String.join("\n\t - ", redundant));
  }

  /** Advisory findings inform by default and only fail the run under --strict. */
  private RunReport advisoryFinding(String checkName, String message, String listing) {
    if (strict) {
      System.out.println(listing);
      System.err.println("Error: " + message);
      return RunReport.singleCheck(RunReport.Status.VIOLATION, checkName, message);
    }
    System.out.println(listing);
    return RunReport.singleCheck(RunReport.Status.OK, checkName, message + advisoryNote());
  }

  private String advisoryNote() {
    return strict ? "" : " (advisory; use --strict to fail)";
  }

  private RunReport checkInvariantPreservation(StateSpace stateSpace, List<String> scope) {
    if (scope.isEmpty()) {
      // The kernel command treats an empty event list as "all", so never pass one on.
      String message =
          "No events to check: the machine only has INITIALISATION, whose establishment"
              + " obligation is covered by model checking.";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "invariant", message);
    }
    System.out.println(
        "Constraint-based invariant check ("
            + scope.size()
            + " event"
            + Animate.plural(scope.size())
            + ")...");
    IModelCheckingResult result;
    try {
      result = new CBCInvariantChecker(stateSpace, scope, null).call();
    } catch (RuntimeException e) {
      // Same contract as the model-check path: a mid-check kernel failure is a non-verdict.
      logger.debug("Constraint-based invariant check failed", e);
      String message = "Constraint-based invariant check did not complete: " + e.getMessage();
      System.err.println(message);
      return RunReport.of(
          RunReport.Status.INCOMPLETE, message, checks(scope, Set.of(), RunReport.Outcome.ERROR));
    }

    if (result instanceof ModelCheckOk) {
      String message =
          "No event can violate the invariant (constraint-based preservation proof;"
              + " initialisation is not checked, and a solver timeout can mask a violation).";
      System.out.println(message);
      return RunReport.of(RunReport.Status.OK, message, checks(scope, Set.of(), null));
    }

    if (result instanceof CBCInvariantViolationFound violation) {
      Set<String> violating = new LinkedHashSet<>();
      for (InvariantCheckCounterExample example : violation.getCounterexamples()) {
        violating.add(example.getEventName());
      }
      String message =
          "invariant preservation fails for "
              + violating.size()
              + " event"
              + Animate.plural(violating.size())
              + ": "
              + String.join(", ", violating);
      System.err.println("Error: " + message);
      // One trace is evidence enough for the report and --save; the per-event checks
      // carry the full list of violating events.
      Trace counterexample = violation.getTrace(stateSpace);
      TraceWriter.Counterexample described = TraceWriter.describe(stateSpace, counterexample, true);
      TraceWriter.printViolatedInvariants(described);
      TraceWriter.printTrace(described);
      return saveFirstTrace(
          RunReport.of(RunReport.Status.VIOLATION, message, checks(scope, violating, null))
              .withCounterexample(described),
          counterexample,
          stateSpace);
    }

    // NotYetFinished (interrupted) or any other non-verdict: nothing was proven.
    String message = "Constraint-based invariant check did not complete: " + result.getMessage();
    System.err.println(message);
    return RunReport.of(
        RunReport.Status.INCOMPLETE, message, checks(scope, Set.of(), RunReport.Outcome.ERROR));
  }

  /**
   * One check per scoped event, named {@code invariant/<event>}. With {@code forcedOutcome} null,
   * events in {@code violating} fail and the rest pass.
   */
  private static List<RunReport.Check> checks(
      List<String> scope, Set<String> violating, RunReport.Outcome forcedOutcome) {
    List<RunReport.Check> checks = new ArrayList<>(scope.size());
    for (String event : scope) {
      RunReport.Outcome outcome =
          forcedOutcome != null
              ? forcedOutcome
              : violating.contains(event) ? RunReport.Outcome.FAILED : RunReport.Outcome.PASSED;
      String message =
          switch (outcome) {
            case PASSED -> "no violating step exists";
            case FAILED -> "a step from an invariant-satisfying state violates the invariant";
            default -> "the check did not complete";
          };
      checks.add(new RunReport.Check("invariant/" + event, outcome, message));
    }
    return checks;
  }
}
