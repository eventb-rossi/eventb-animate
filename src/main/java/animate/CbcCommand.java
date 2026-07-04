package animate;

import de.prob.check.CBCInvariantChecker;
import de.prob.check.CBCInvariantViolationFound;
import de.prob.check.IModelCheckingResult;
import de.prob.check.InvariantCheckCounterExample;
import de.prob.check.ModelCheckOk;
import de.prob.model.eventb.EventBMachine;
import de.prob.model.representation.BEvent;
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
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "cbc",
    description =
        "Prove per-event invariant preservation with ProB's constraint solver, without"
            + " exploring the state space: for each event, search for an invariant-satisfying"
            + " state (reachable or not) from which one step violates the invariant",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class CbcCommand implements Callable<Integer> {

  @ParentCommand Animate parent;

  @Option(
      names = "--events",
      split = ",",
      paramLabel = "event",
      description =
          "restrict the invariant check to these events (comma-separated or repeated;"
              + " default: every event of the machine)")
  List<String> events;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save the counterexample trace in json to a file (when a violation is found)")
  Path jsonTrace;

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CbcCommand.class);

  @Override
  public Integer call() {
    // The check must prove preservation from scratch: with PROOF_INFO on, ProB trusts
    // the Rodin discharge info shipped with the model and skips proven obligations,
    // which would make this gate circular with the po command.
    parent.commandPrefs.put("PROOF_INFO", "false");
    return parent.finishRun(parent.withStateSpace(this::runCbc));
  }

  private RunReport runCbc(StateSpace stateSpace) {
    if (!(stateSpace.getMainComponent() instanceof EventBMachine machine)) {
      String message = "cbc requires a machine, but the loaded component is a context";
      System.err.println("Error: " + message);
      return RunReport.singleCheck(RunReport.Status.ERROR, "invariant", message);
    }
    List<String> machineEvents = preservationEvents(machine);
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

    return checkInvariantPreservation(stateSpace, scope);
  }

  /**
   * The events eligible for a preservation check: every machine event except INITIALISATION, whose
   * obligation is establishment, not preservation (the model-checking command covers it).
   */
  private static List<String> preservationEvents(EventBMachine machine) {
    List<String> names = new ArrayList<>();
    for (BEvent event : machine.getEvents()) {
      if (!Animate.INITIALISATION_EVENT.equals(event.getName())) {
        names.add(event.getName());
      }
    }
    return names;
  }

  private RunReport checkInvariantPreservation(StateSpace stateSpace, List<String> scope) {
    System.out.println(
        "Constraint-based invariant check (" + scope.size() + " event" + plural(scope) + ")...");
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
              + (violating.size() == 1 ? "" : "s")
              + ": "
              + String.join(", ", violating);
      System.err.println("Error: " + message);
      // One trace is evidence enough for the report and --save; the per-event checks
      // carry the full list of violating events.
      Trace counterexample = violation.getTrace(stateSpace);
      TraceWriter.Counterexample described = TraceWriter.describe(stateSpace, counterexample, true);
      TraceWriter.printViolatedInvariants(described);
      TraceWriter.printTrace(described);
      return parent.withSavedTrace(
          RunReport.of(RunReport.Status.VIOLATION, message, checks(scope, violating, null))
              .withCounterexample(described),
          counterexample,
          stateSpace,
          jsonTrace);
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

  private static String plural(List<String> items) {
    return items.size() == 1 ? "" : "s";
  }
}
