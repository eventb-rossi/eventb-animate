package animate;

import de.prob.animator.command.StartAnimationCommand;
import de.prob.model.eventb.Context;
import de.prob.model.eventb.EventBMachine;
import de.prob.model.eventb.EventBModel;
import de.prob.model.eventb.ProofObligation;
import de.prob.statespace.StateSpace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "po",
    description =
        "Gate on the Rodin proof status: report every proof obligation of the model (all"
            + " machines and contexts of the refinement chain) and fail unless each one is"
            + " discharged. Reads the Rodin proof files (.bpo/.bps) next to the model;"
            + " ProB is not started",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class PoCommand implements Callable<Integer> {

  @ParentCommand Animate parent;

  @Option(
      names = "--allow-reviewed",
      description =
          "accept obligations marked as reviewed in Rodin (manually inspected but not proven);"
              + " by default they count as not discharged")
  boolean allowReviewed;

  @Option(
      names = "--filter",
      paramLabel = "GLOB",
      description =
          "check only obligations whose qualified name matches the glob (repeatable; an"
              + " obligation is kept when any filter matches). Names are"
              + " <component>/<obligation>, e.g. 'M1/INITIALISATION/inv4/INV'; '*' also matches"
              + " across '/', so 'M1/*' selects one machine and '*/INV' all"
              + " invariant-preservation obligations")
  List<String> filters = new ArrayList<>();

  @Option(
      names = {"-v", "--verbose"},
      description = "list every obligation with its status, not only the failing ones")
  boolean verbose;

  @Option(
      names = "--disprove",
      description =
          "run ProB's constraint solver on each obligation that is not discharged, looking"
              + " for a counterexample to its sequent; a found counterexample is a definite"
              + " failure (exit 1)")
  boolean disprove;

  @Option(
      names = "--disprove-timeout",
      paramLabel = "ms",
      defaultValue = "1000",
      description = "per-obligation solver time limit for --disprove (default: ${DEFAULT-VALUE})")
  int disproveTimeoutMs;

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    validatePoOptions();
    if (disprove) {
      // The solver setup mirrors the Rodin disprover's: double-check counterexamples
      // against all hypotheses, and enable CHR for stronger propagation.
      parent.commandPrefs.put("DOUBLE_EVALUATION", "true");
      parent.commandPrefs.put("CHR", "true");
      return parent.finishRun(
          parent.withStateSpace(
              stateSpace ->
                  checkProofObligations((EventBModel) stateSpace.getModel(), stateSpace)));
    }
    return parent.finishRun(parent.withExtractedModel(model -> checkProofObligations(model, null)));
  }

  private void validatePoOptions() {
    if (!disprove && spec.commandLine().getParseResult().hasMatchedOption("--disprove-timeout")) {
      throw Animate.usageError(spec, "--disprove-timeout only tunes --disprove (add --disprove)");
    }
    if (disproveTimeoutMs <= 0) {
      throw Animate.usageError(
          spec,
          "--disprove-timeout must be a positive number of milliseconds, got: "
              + disproveTimeoutMs);
    }
  }

  /** One obligation with its component-qualified name, e.g. "M1/INITIALISATION/inv4/INV". */
  private record QualifiedPo(String component, ProofObligation po) {
    String name() {
      return component + "/" + po.getName();
    }
  }

  private RunReport checkProofObligations(EventBModel model, StateSpace disproverSession) {
    List<String> missingProofFiles = missingProofFiles(model);
    if (!missingProofFiles.isEmpty()) {
      // Rodin writes one .bpo per component, even when it holds no obligations. A
      // component without one means the export lacks the proof database, and gating
      // on the zero obligations the kernel then reports would silently pass.
      String message =
          "no proof information for "
              + String.join(", ", missingProofFiles)
              + " (missing .bpo file next to the model; re-export the project from Rodin"
              + " after a build)";
      System.err.println("Error: " + message);
      return RunReport.of(
          RunReport.Status.INPUT_ERROR,
          message,
          new RunReport.Check("proof-obligations", RunReport.Outcome.ERROR, message));
    }

    List<QualifiedPo> all = collectProofObligations(model);
    if (all.isEmpty()) {
      String message = "No proof obligations found (Rodin generated none for this model).";
      System.out.println(message);
      return RunReport.of(
          RunReport.Status.OK,
          message,
          new RunReport.Check("proof-obligations", RunReport.Outcome.PASSED, message));
    }

    List<QualifiedPo> kept = applyFilters(all);
    if (kept.isEmpty()) {
      // A typo'd filter must not pass the gate, and it is not a usage error either:
      // whether a pattern matches depends on the model, not on the command line.
      String message =
          "no proof obligations match --filter "
              + filters.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "))
              + " ("
              + all.size()
              + " obligations exist; check the pattern)";
      System.err.println("Error: " + message);
      return RunReport.of(
          RunReport.Status.INCOMPLETE,
          message,
          new RunReport.Check("proof-obligations", RunReport.Outcome.ERROR, message));
    }

    Set<String> broken = brokenObligations(model);
    List<RunReport.Check> checks = new ArrayList<>(kept.size());
    List<String> reviewedOpen = new ArrayList<>();
    List<String> brokenOpen = new ArrayList<>();
    List<String> undischarged = new ArrayList<>();
    List<OpenPo> openPos = new ArrayList<>();
    int discharged = 0;
    int reviewed = 0;
    int brokenCount = 0;
    for (QualifiedPo qualified : kept) {
      ProofObligation po = qualified.po();
      if (broken.contains(qualified.name())) {
        // A broken proof is stale whatever its recorded confidence: Rodin marks the
        // obligation as needing a replay and does not count it discharged, so neither
        // does the gate. The kernel drops the flag, so it is read back from the .bps.
        brokenCount++;
        brokenOpen.add(qualified.name());
        checks.add(
            new RunReport.Check(
                qualified.name(),
                RunReport.Outcome.FAILED,
                withDescription("broken: the recorded proof is stale (replay it in Rodin)", po)));
        if (disproverSession != null) {
          openPos.add(new OpenPo(qualified, checks.size() - 1));
        }
      } else if (po.isDischarged()) {
        discharged++;
        checks.add(new RunReport.Check(qualified.name(), RunReport.Outcome.PASSED, "discharged"));
      } else if (po.isReviewed()) {
        reviewed++;
        if (allowReviewed) {
          checks.add(
              new RunReport.Check(
                  qualified.name(),
                  RunReport.Outcome.PASSED,
                  "reviewed (accepted by --allow-reviewed)"));
        } else {
          reviewedOpen.add(qualified.name());
          checks.add(
              new RunReport.Check(
                  qualified.name(),
                  RunReport.Outcome.FAILED,
                  withDescription(
                      "reviewed, not proven (rerun with --allow-reviewed to accept)", po)));
          if (disproverSession != null) {
            openPos.add(new OpenPo(qualified, checks.size() - 1));
          }
        }
      } else {
        undischarged.add(qualified.name());
        checks.add(
            new RunReport.Check(
                qualified.name(), RunReport.Outcome.FAILED, withDescription("undischarged", po)));
        if (disproverSession != null) {
          openPos.add(new OpenPo(qualified, checks.size() - 1));
        }
      }
    }

    if (verbose) {
      for (QualifiedPo qualified : kept) {
        System.out.println("\t - " + qualified.name() + ": " + statusWord(qualified, broken));
      }
    }
    printSummary(
        kept.size(),
        discharged,
        reviewed,
        brokenCount,
        undischarged.size(),
        all.size() - kept.size());

    // The disprover refines each open obligation's check in place: a counterexample is
    // a definite failure, a solver proof passes the gate, everything else stays open.
    int disproved = 0;
    int solverProved = 0;
    if (disproverSession != null && !openPos.isEmpty()) {
      System.out.println(
          "Disproving "
              + openPos.size()
              + " open obligation(s) (timeout "
              + disproveTimeoutMs
              + " ms each)...");
      Map<String, PoSequentParser> sequentCache = new HashMap<>();
      for (OpenPo open : openPos) {
        QualifiedPo qualified = open.po();
        DisproveOutcome outcome = attemptDisproof(qualified, sequentCache, disproverSession);
        System.out.println("\t - " + qualified.name() + ": " + outcome.message());
        checks.set(
            open.checkIndex(),
            new RunReport.Check(
                qualified.name(), outcome.outcome(), outcome.message(), outcome.bindings()));
        if (outcome.disproved()) {
          disproved++;
        } else if (outcome.outcome() == RunReport.Outcome.PASSED) {
          solverProved++;
        }
      }
    }

    if (disproved > 0) {
      String message =
          disproved
              + " of "
              + kept.size()
              + " proof obligations are disproved (counterexample"
              + " found)";
      System.err.println("Error: " + message);
      return RunReport.of(RunReport.Status.VIOLATION, message, checks);
    }
    long stillOpen =
        checks.stream().filter(check -> check.outcome() != RunReport.Outcome.PASSED).count();
    if (stillOpen == 0) {
      String message;
      if (solverProved > 0) {
        message = "All proof obligations are discharged, reviewed, or proven by the solver.";
      } else if (reviewed > 0) {
        message = "All proof obligations are discharged or reviewed.";
      } else {
        message = "All proof obligations are discharged.";
      }
      System.out.println(message);
      return RunReport.of(RunReport.Status.OK, message, checks);
    }
    if (disproverSession == null) {
      // Without the disprover's per-obligation lines above, group the open ones here.
      if (!reviewedOpen.isEmpty()) {
        System.err.println(
            "Reviewed (not proven; rerun with --allow-reviewed to accept):\n\t - "
                + String.join("\n\t - ", reviewedOpen));
      }
      if (!brokenOpen.isEmpty()) {
        System.err.println(
            "Broken (stored proof is stale; replay it in Rodin):\n\t - "
                + String.join("\n\t - ", brokenOpen));
      }
      if (!undischarged.isEmpty()) {
        System.err.println("Undischarged:\n\t - " + String.join("\n\t - ", undischarged));
      }
    }
    String message = stillOpen + " of " + kept.size() + " proof obligations are not discharged";
    System.err.println("Error: " + message);
    // Open obligations are unproven, not disproven: the no-verdict exit (2), like wd.
    return RunReport.of(RunReport.Status.INCOMPLETE, message, checks);
  }

  /** An open obligation queued for the disprover, with its slot in the checks list. */
  private record OpenPo(QualifiedPo po, int checkIndex) {}

  /** What one disprover run means for the obligation's check and the run verdict. */
  private record DisproveOutcome(
      RunReport.Outcome outcome,
      String message,
      boolean disproved,
      List<TraceWriter.Binding> bindings) {

    DisproveOutcome(RunReport.Outcome outcome, String message, boolean disproved) {
      this(outcome, message, disproved, List.of());
    }
  }

  private DisproveOutcome attemptDisproof(
      QualifiedPo qualified, Map<String, PoSequentParser> sequentCache, StateSpace session) {
    PoSequentParser.Sequent sequent;
    try {
      PoSequentParser sequents = sequentCache.get(qualified.component());
      if (sequents == null) {
        sequents =
            PoSequentParser.parse(
                projectDirectory().resolve(qualified.component() + RodinNames.BPO));
        sequentCache.put(qualified.component(), sequents);
      }
      sequent = sequents.sequent(qualified.po().getName());
    } catch (IOException | RuntimeException e) {
      // The parser raises unchecked exceptions on malformed references; like a solver
      // failure below, a broken .bpo must not abort the gate for the other components.
      return new DisproveOutcome(
          RunReport.Outcome.ERROR, "solver error: " + solverErrorSummary(e.getMessage()), false);
    }
    if (sequent == null || sequent.goal() == null) {
      return new DisproveOutcome(
          RunReport.Outcome.ERROR,
          "solver error: no sequent for this obligation in "
              + qualified.component()
              + RodinNames.BPO,
          false);
    }
    try {
      session.execute(new ClearLoadedMachinesCommand());
      session.execute(new DisproverContextLoadCommand(sequent));
      session.execute(new StartAnimationCommand());
      DisproveCommand command = new DisproveCommand(sequent, disproveTimeoutMs);
      session.execute(command);
      return classify(command);
    } catch (RuntimeException e) {
      // One obligation's solver failure (a heavy sequent, a formula the parser cannot
      // handle) must not abort the gate; the run continues with the next obligation.
      return new DisproveOutcome(
          RunReport.Outcome.ERROR, "solver error: " + solverErrorSummary(e.getMessage()), false);
    }
  }

  private DisproveOutcome classify(DisproveCommand command) {
    String detail = command.getDetail();
    return switch (command.getVerdict()) {
      case DISPROVED ->
          new DisproveOutcome(
              RunReport.Outcome.FAILED,
              "disproved" + (detail.isEmpty() ? "" : " (counterexample: " + detail + ")"),
              true,
              command.getSolutionBindings());
      case DISPROVED_ON_SELECTED ->
          new DisproveOutcome(
              RunReport.Outcome.FAILED,
              "counterexample under the selected hypotheses only (may be spurious)"
                  + (detail.isEmpty() ? "" : ": " + detail),
              false,
              command.getSolutionBindings());
      case PROVED ->
          new DisproveOutcome(
              RunReport.Outcome.PASSED,
              "proven by the constraint solver (not recorded in the Rodin proof status)",
              false);
      case CONTRADICTORY_HYPOTHESES ->
          new DisproveOutcome(
              RunReport.Outcome.PASSED,
              "hypotheses are contradictory: the obligation holds vacuously; check the model",
              false);
      case TIMEOUT ->
          new DisproveOutcome(
              RunReport.Outcome.FAILED,
              "no counterexample found (solver timeout after " + disproveTimeoutMs + " ms)",
              false);
      case NO_SOLUTION_FOUND ->
          new DisproveOutcome(
              RunReport.Outcome.FAILED, "no counterexample found (" + detail + ")", false);
      case INTERRUPTED ->
          new DisproveOutcome(RunReport.Outcome.ERROR, "the solver run was interrupted", false);
    };
  }

  /** ProBError messages open with boilerplate; surface the first informative line, shortened. */
  private static String solverErrorSummary(String message) {
    if (message == null || message.isBlank()) {
      return "unknown error";
    }
    for (String line : message.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()
          || "Prolog said no.".equals(trimmed)
          || "ProB returned error messages:".equals(trimmed)) {
        continue;
      }
      return trimmed.length() > 160 ? trimmed.substring(0, 157) + "..." : trimmed;
    }
    return "unknown error";
  }

  /**
   * Qualified names of obligations Rodin flagged as broken (stale proof) in the .bps files. The
   * bundled kernel keeps only the confidence, so these are read straight from the status files and
   * folded back into the classification.
   */
  private Set<String> brokenObligations(EventBModel model) {
    Path projectDir = projectDirectory();
    Set<String> broken = new LinkedHashSet<>();
    for (String component : componentNames(model)) {
      Path bps = projectDir.resolve(component + RodinNames.BPS);
      if (!Files.exists(bps)) {
        // A component with obligations but no status file has nothing proved (confidence 0),
        // so its obligations are already open; there is no broken flag to recover.
        continue;
      }
      try {
        for (String name : ProofStatusReader.brokenObligations(bps)) {
          broken.add(component + "/" + name);
        }
      } catch (IOException e) {
        // A malformed .bps must not abort the gate; without its flags the obligations fall
        // back to their kernel confidence, which is the behaviour before broken detection.
        System.err.println(
            "Warning: cannot read proof status from "
                + component
                + RodinNames.BPS
                + ": "
                + e.getMessage());
      }
    }
    return broken;
  }

  /** The chain's components whose .bpo file is absent next to the resolved model file. */
  private List<String> missingProofFiles(EventBModel model) {
    Path projectDir = projectDirectory();
    return componentNames(model).stream()
        .filter(name -> !Files.exists(projectDir.resolve(name + RodinNames.BPO)))
        .toList();
  }

  /** The resolved component file's directory; its siblings are the Rodin project files. */
  private Path projectDirectory() {
    // The resolver always returns a component file, never a filesystem root.
    return Objects.requireNonNull(parent.resolvedModelPath().toAbsolutePath().getParent());
  }

  private static List<String> componentNames(EventBModel model) {
    List<String> names = new ArrayList<>();
    for (EventBMachine machine : model.getMachines()) {
      names.add(machine.getName());
    }
    for (Context context : model.getContexts()) {
      names.add(context.getName());
    }
    return names;
  }

  private static List<QualifiedPo> collectProofObligations(EventBModel model) {
    List<QualifiedPo> obligations = new ArrayList<>();
    for (EventBMachine machine : model.getMachines()) {
      for (ProofObligation po : machine.getProofs()) {
        obligations.add(new QualifiedPo(machine.getName(), po));
      }
    }
    for (Context context : model.getContexts()) {
      for (ProofObligation po : context.getProofs()) {
        obligations.add(new QualifiedPo(context.getName(), po));
      }
    }
    return obligations;
  }

  private List<QualifiedPo> applyFilters(List<QualifiedPo> all) {
    if (filters.isEmpty()) {
      return all;
    }
    List<Pattern> patterns = filters.stream().map(PoCommand::globToRegex).toList();
    return all.stream()
        .filter(po -> patterns.stream().anyMatch(pattern -> pattern.matcher(po.name()).matches()))
        .toList();
  }

  /** PO names contain '/' as an ordinary character, so '*' deliberately matches across it. */
  private static Pattern globToRegex(String glob) {
    StringBuilder regex = new StringBuilder();
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        default -> regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.toString());
  }

  private static String withDescription(String message, ProofObligation po) {
    String description = po.getDescription();
    return description == null || description.isBlank() ? message : message + ": " + description;
  }

  private static String statusWord(QualifiedPo qualified, Set<String> broken) {
    if (broken.contains(qualified.name())) {
      return "broken";
    }
    ProofObligation po = qualified.po();
    return po.isDischarged() ? "discharged" : po.isReviewed() ? "reviewed" : "undischarged";
  }

  private static void printSummary(
      int total, int discharged, int reviewed, int broken, int undischarged, int filteredOut) {
    StringBuilder summary =
        new StringBuilder("Proof obligations: ")
            .append(total)
            .append(" total, ")
            .append(discharged)
            .append(" discharged");
    if (reviewed > 0) {
      summary.append(", ").append(reviewed).append(" reviewed");
    }
    if (broken > 0) {
      summary.append(", ").append(broken).append(" broken");
    }
    if (undischarged > 0) {
      summary.append(", ").append(undischarged).append(" undischarged");
    }
    if (filteredOut > 0) {
      summary.append(" (").append(filteredOut).append(" filtered out by --filter)");
    }
    System.out.println(summary);
  }
}
