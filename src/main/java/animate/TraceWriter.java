package animate;

import ch.qos.logback.classic.Logger;
import com.google.inject.Provider;
import de.prob.animator.domainobjects.AbstractEvalResult;
import de.prob.animator.domainobjects.EvalResult;
import de.prob.animator.domainobjects.FormulaExpand;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.check.tracereplay.json.storage.TraceJsonFile;
import de.prob.json.JsonMetadata;
import de.prob.json.JsonMetadataBuilder;
import de.prob.model.eventb.EventBMachine;
import de.prob.statespace.State;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import de.prob.statespace.Transition;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/**
 * Renders counterexamples once into an immutable {@link Counterexample} shared by the console
 * output and the run reports, and persists traces as ProB trace JSON.
 */
final class TraceWriter {

  private static final Logger logger = (Logger) LoggerFactory.getLogger(TraceWriter.class);

  private final Provider<TraceManager> traceManager;

  TraceWriter(Provider<TraceManager> traceManager) {
    this.traceManager = traceManager;
  }

  record Counterexample(
      List<String> transitions, String violatingState, List<String> violatedInvariants) {
    Counterexample {
      transitions = List.copyOf(transitions);
      violatedInvariants = List.copyOf(violatedInvariants);
    }
  }

  /**
   * {@code evalInvariants} is false on the LTL path: it never enumerated invariants, and doing so
   * would add ProB evaluation calls to a path that has none today. A ProB failure while rendering
   * one element degrades that element instead of throwing, so a hiccup mid-trace cannot discard the
   * diagnostics (and the reports) the user needs most on a failing run.
   */
  static Counterexample describe(StateSpace stateSpace, Trace trace, boolean evalInvariants) {
    List<String> transitions = new ArrayList<>();
    for (Transition transition : trace.getTransitionList()) {
      String rendered;
      try {
        rendered = transition.evaluate(FormulaExpand.EXPAND).getPrettyRep();
      } catch (RuntimeException e) {
        logger.debug("Error rendering transition {}", transition.getId(), e);
        rendered = transition.getName() + " (could not render: " + e.getMessage() + ")";
      }
      transitions.add(rendered);
    }
    State violatingState = trace.getCurrentState();
    List<String> violatedInvariants =
        evalInvariants ? violatedInvariants(stateSpace, violatingState) : List.of();
    String stateRep;
    try {
      stateRep = violatingState.getStateRep();
    } catch (RuntimeException e) {
      logger.debug("Error rendering state {}", violatingState.getId(), e);
      stateRep =
          "(state " + violatingState.getId() + " could not be rendered: " + e.getMessage() + ")";
    }
    return new Counterexample(transitions, stateRep, violatedInvariants);
  }

  private static List<String> violatedInvariants(StateSpace stateSpace, State state) {
    // Deadlock counterexamples still satisfy the invariant, so only enumerate
    // predicates when the reported state actually breaks one.
    if (state.isInvariantOk()
        || !(stateSpace.getMainComponent() instanceof EventBMachine machine)) {
      return List.of();
    }
    List<IEvalElement> invariants = new ArrayList<>();
    for (var invariant : machine.getAllInvariants()) {
      invariants.add(invariant.getPredicate());
    }
    List<AbstractEvalResult> results = state.eval(invariants);
    List<String> violated = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) != EvalResult.TRUE) {
        violated.add(invariants.get(i).toString());
      }
    }
    return violated;
  }

  /** "Violated invariants" block shared by the console and the JUnit failure body; "" if none. */
  static String violatedInvariantsBlock(Counterexample counterexample) {
    if (counterexample.violatedInvariants().isEmpty()) {
      return "";
    }
    return "Violated invariants:\n\t - "
        + String.join("\n\t - ", counterexample.violatedInvariants());
  }

  /** Trace-plus-state block shared by the console and the JUnit failure body. */
  static String traceBlock(Counterexample counterexample) {
    StringBuilder block = new StringBuilder("Counterexample trace:");
    for (String transition : counterexample.transitions()) {
      block.append("\n\t").append(transition);
    }
    block.append("\n\nViolating state:\n").append(counterexample.violatingState());
    return block.toString();
  }

  static void printViolatedInvariants(Counterexample counterexample) {
    String block = violatedInvariantsBlock(counterexample);
    if (!block.isEmpty()) {
      System.err.println(block);
    }
  }

  static void printTrace(Counterexample counterexample) {
    System.out.println(traceBlock(counterexample));
  }

  /**
   * Reads a ProB trace JSON file; the counterpart to {@link #save}, keeping trace I/O in one place.
   */
  TraceJsonFile load(Path source) throws IOException {
    return traceManager.get().load(source);
  }

  /** Returns the written path, or empty when saving failed (reported on stderr, exit unchanged). */
  Optional<Path> save(Trace trace, StateSpace stateSpace, Path target, String probVersion) {
    JsonMetadata metadata =
        new JsonMetadataBuilder("Trace", 6)
            .withSavedNow()
            .withCreator(Animate.TOOL_NAME)
            .withProBCliVersion(probVersion)
            .withModelName(Objects.toString(stateSpace.getMainComponent(), "unknown"))
            .build();
    TraceJsonFile traceJsonFile = new TraceJsonFile(trace, metadata);
    logger.info("Saving counterexample trace to {}", target);
    try {
      traceManager.get().save(target, traceJsonFile);
      return Optional.of(target);
    } catch (IOException e) {
      logger.debug("Error saving trace", e);
      System.err.println("Error saving trace: " + e.getMessage());
      return Optional.empty();
    }
  }
}
