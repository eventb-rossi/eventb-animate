package animate;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import de.prob.animator.CommandInterruptedException;
import de.prob.check.tracereplay.PersistentTransition;
import de.prob.check.tracereplay.ReplayedTrace;
import de.prob.check.tracereplay.TraceReplay;
import de.prob.check.tracereplay.TraceReplayStatus;
import de.prob.check.tracereplay.check.traceConstruction.AdvancedTraceConstructor;
import de.prob.check.tracereplay.check.traceConstruction.TraceConstructionError;
import de.prob.exception.ProBError;
import de.prob.model.eventb.EventBModel;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import de.prob.statespace.Transition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "replay",
    description = "Replay a json trace, optionally adapting it to another refinement level",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class ReplayCommand implements Callable<Integer> {

  private static final Logger logger = LoggerFactory.getLogger(ReplayCommand.class);

  /** Shared by the exception path and the short-result guard so the verdict reads identically. */
  static final String NO_ADAPTATION_MESSAGE =
      "No adaptation found within the configured search bounds"
          + " (try larger --refine-breadth/--refine-depth).";

  @ParentCommand Animate parent;
  @Spec CommandSpec spec;

  @Option(
      names = {"-t", "--trace"},
      required = true,
      paramLabel = "trace.json",
      description = "Path to a json trace (the source trace when --refine is given)")
  Path jsonTrace;

  @Option(
      names = "--refine",
      description =
          "adapt the trace to the -m/--machine refinement level instead of replaying it verbatim"
              + " (e.g. an M1 trace onto M2): events are renamed and skip-refining steps inserted."
              + " A non-zero exit means the target could not reproduce the source trace")
  boolean refine;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save the adapted trace in json to a file (requires --refine; overwrites)")
  Path saveTarget;

  @Option(
      names = "--refine-breadth",
      paramLabel = "N",
      description =
          "max alternatives explored per step during adaptation (requires --refine; default:"
              + " ${DEFAULT-VALUE})")
  int refineBreadth = 10;

  @Option(
      names = "--refine-depth",
      paramLabel = "N",
      description =
          "max steps the adaptation search descends between two matched transitions (requires"
              + " --refine; default: ${DEFAULT-VALUE})")
  int refineDepth = 5;

  @Override
  public Integer call() {
    validateRefineOptions();
    Function<StateSpace, RunReport> body = refine ? this::refine : this::replay;
    return parent.finishRun(parent.withStateSpace(body));
  }

  /** Rejects the refine-only options when --refine is absent, before any model load (exit 2). */
  private void validateRefineOptions() {
    var parseResult = spec.commandLine().getParseResult();
    if (!refine) {
      List<String> misused = new ArrayList<>();
      if (saveTarget != null) {
        misused.add("--save");
      }
      if (parseResult.hasMatchedOption("--refine-breadth")) {
        misused.add("--refine-breadth");
      }
      if (parseResult.hasMatchedOption("--refine-depth")) {
        misused.add("--refine-depth");
      }
      if (!misused.isEmpty()) {
        throw Animate.usageError(
            spec, String.join(", ", misused) + " may be used only with --refine");
      }
      return;
    }
    if (refineBreadth <= 0) {
      throw Animate.usageError(spec, "--refine-breadth must be positive, got: " + refineBreadth);
    }
    if (refineDepth <= 0) {
      throw Animate.usageError(spec, "--refine-depth must be positive, got: " + refineDepth);
    }
    if (saveTarget != null) {
      // The adapted trace is the deliverable, so reject a bad --save destination up front (exit 2),
      // before the expensive adaptation, rather than computing it only to fail the write.
      Animate.validateWritableTarget(spec, saveTarget, "--save");
    }
  }

  private RunReport replay(StateSpace stateSpace) {
    System.out.println("Starting trace replay. Use --debug to view steps.");
    ReplayedTrace trace;
    try {
      trace = TraceReplay.replayTraceFile(stateSpace, jsonTrace);
    } catch (Exception e) {
      logger.debug("Error replaying trace", e);
      String message = "Error replaying trace: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INPUT_ERROR, "replay", message);
    }
    RunReport report = reportFor(trace);
    System.out.println(report.message());
    if (trace.getReplayStatus() != TraceReplayStatus.PERFECT) {
      printReplayDiagnostics(trace);
    }
    return report;
  }

  /** PERFECT is the only passing replay; PARTIAL and IMPERFECT are verdict failures (exit 1). */
  static RunReport reportFor(ReplayedTrace trace) {
    RunReport.Status status =
        trace.getReplayStatus() == TraceReplayStatus.PERFECT
            ? RunReport.Status.OK
            : RunReport.Status.VIOLATION;
    return RunReport.singleCheck(
        status, "replay", "Trace replay status: " + trace.getReplayStatus());
  }

  private static void printReplayDiagnostics(ReplayedTrace trace) {
    trace.getErrors().stream()
        .map(error -> error.getMessage())
        .filter(message -> message != null && !message.isBlank())
        .forEach(message -> System.err.println("Replay error: " + message));

    var transitionErrors = trace.getTransitionErrorMessages();
    for (int i = 0; i < transitionErrors.size(); i++) {
      for (String message : transitionErrors.get(i)) {
        if (message != null && !message.isBlank()) {
          System.err.println("Replay step " + (i + 1) + ": " + message);
        }
      }
    }
  }

  /**
   * Adapts the source trace to the loaded (target) refinement level. The target StateSpace is the
   * one {@link Animate#withStateSpace} loads and kills, so exactly one animator runs.
   */
  private RunReport refine(StateSpace target) {
    List<PersistentTransition> source;
    try {
      source = parent.loadTrace(jsonTrace);
    } catch (Exception e) {
      logger.debug("Error reading source trace", e);
      String message = "Error reading trace: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INPUT_ERROR, "refine", message);
    }

    String targetName = String.valueOf(target.getMainComponent());
    System.out.println(
        "Adapting trace to "
            + targetName
            + " (breadth "
            + refineBreadth
            + ", depth "
            + refineDepth
            + ")...");

    List<Transition> adapted;
    try {
      adapted = adaptTrace(target, source, refineBreadth, refineDepth);
    } catch (CommandInterruptedException e) {
      // An interrupt proves nothing either way; kept distinct from a genuine no-adaptation (exit
      // 2).
      return refineIncomplete("Refinement did not complete: the search was interrupted.", e);
    } catch (ProBError | TraceConstructionError e) {
      // The Event-B refiner reports no clean TraceConstructionError: a genuine no-adaptation
      // surfaces
      // as a ProBError ("Prolog said no."). Either way no adaptation was found (--debug shows the
      // cause); the empty result drives the VIOLATION verdict below.
      logger.debug("No adaptation found", e);
      adapted = List.of();
    } catch (RuntimeException e) {
      // A probcli/kernel failure (e.g. CliError) is a non-verdict too, matching the check paths.
      return refineIncomplete("Refinement did not complete: " + e.getMessage(), e);
    }

    RunReport report = refineReport(adapted.size(), source.size(), targetName);
    if (report.status() != RunReport.Status.OK) {
      // It also reports partial success silently (success is hard-coded and the error list a stub),
      // so a result shorter than the source -- not only an exception -- is a failed adaptation.
      System.err.println("Error: " + report.message());
      return report;
    }

    printAdapted(adapted);
    Trace adaptedTrace = new Trace(target).addTransitions(adapted);
    return parent.withSavedTrace(report, adaptedTrace, target, saveTarget);
  }

  /**
   * A refinement that could not run to completion (interrupt, probcli failure): exit 2, no verdict.
   */
  private RunReport refineIncomplete(String message, Throwable cause) {
    logger.debug(message, cause);
    return Animate.incomplete("refine", message);
  }

  /**
   * OK only when the adaptation reproduced at least the source's steps; a shorter (or empty) result
   * means the target could not reproduce the source trace (VIOLATION, exit 1). Kept static and free
   * of ProB so the verdict boundary is unit-testable.
   */
  static RunReport refineReport(int adaptedSize, int sourceSize, String targetName) {
    // adaptedSize == 0 also covers an empty source: a real adaptation always has at least the
    // initialisation, so reproducing nothing is never a success.
    if (adaptedSize == 0 || adaptedSize < sourceSize) {
      return RunReport.singleCheck(RunReport.Status.VIOLATION, "refine", NO_ADAPTATION_MESSAGE);
    }
    return RunReport.singleCheck(
        RunReport.Status.OK,
        "refine",
        "Adapted trace to " + targetName + " (" + adaptedSize + " steps).");
  }

  /**
   * Mirrors {@code de.prob.check.tracereplay.check.refinement.TraceRefinerEventB
   * .refineTraceExtendedFeedback}: it derives the event-alternatives map from the model and
   * delegates to the same static {@link AdvancedTraceConstructor} (the real search is ProB's Prolog
   * engine). We cannot call that class directly because it loads its own StateSpace and never
   * releases it (no getter/close), leaking a probcli process per call; running the static
   * constructor against the StateSpace {@link Animate#withStateSpace} already manages keeps the
   * target loaded and killed once.
   */
  private static List<Transition> adaptTrace(
      StateSpace target, List<PersistentTransition> source, int breadth, int depth)
      throws TraceConstructionError {
    EventBModel model = (EventBModel) target.getModel();
    Map<String, List<String>> alternatives =
        model.pairNameChanges().entrySet().stream()
            .collect(groupingBy(Map.Entry::getValue, mapping(Map.Entry::getKey, toList())));
    alternatives.remove(Animate.INITIALISATION_EVENT);
    alternatives.put(
        Transition.INITIALISE_MACHINE_NAME, List.of(Transition.INITIALISE_MACHINE_NAME));
    alternatives.put(Transition.SETUP_CONSTANTS_NAME, List.of(Transition.SETUP_CONSTANTS_NAME));
    return AdvancedTraceConstructor.constructTraceEventB(
            source,
            target,
            alternatives,
            model.extendEvents(),
            model.introducedBySkip(),
            breadth,
            depth)
        .resultTrace;
  }

  private static void printAdapted(List<Transition> adapted) {
    StringBuilder block = new StringBuilder("Adapted trace:");
    for (Transition transition : adapted) {
      block.append("\n\t").append(transition.getName());
    }
    System.out.println(block);
  }
}
