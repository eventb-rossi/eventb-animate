package animate;

import de.prob.model.eventb.Context;
import de.prob.model.eventb.EventBMachine;
import de.prob.model.eventb.EventBModel;
import de.prob.model.eventb.ProofObligation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

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

  @Override
  public Integer call() {
    return parent.finishRun(parent.withExtractedModel(this::checkProofObligations));
  }

  /** One obligation with its component-qualified name, e.g. "M1/INITIALISATION/inv4/INV". */
  private record QualifiedPo(String name, ProofObligation po) {}

  private RunReport checkProofObligations(EventBModel model) {
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
          RunReport.Status.ERROR,
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

    List<RunReport.Check> checks = new ArrayList<>(kept.size());
    List<String> reviewedOpen = new ArrayList<>();
    List<String> undischarged = new ArrayList<>();
    int discharged = 0;
    int reviewed = 0;
    for (QualifiedPo qualified : kept) {
      ProofObligation po = qualified.po();
      if (po.isDischarged()) {
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
        }
      } else {
        undischarged.add(qualified.name());
        checks.add(
            new RunReport.Check(
                qualified.name(), RunReport.Outcome.FAILED, withDescription("undischarged", po)));
      }
    }

    if (verbose) {
      for (QualifiedPo qualified : kept) {
        System.out.println("\t - " + qualified.name() + ": " + statusWord(qualified.po()));
      }
    }
    printSummary(kept.size(), discharged, reviewed, undischarged.size(), all.size() - kept.size());

    int open = reviewedOpen.size() + undischarged.size();
    if (open == 0) {
      String message =
          reviewed == 0
              ? "All proof obligations are discharged."
              : "All proof obligations are discharged or reviewed.";
      System.out.println(message);
      return RunReport.of(RunReport.Status.OK, message, checks);
    }
    if (!reviewedOpen.isEmpty()) {
      System.err.println(
          "Reviewed (not proven; rerun with --allow-reviewed to accept):\n\t - "
              + String.join("\n\t - ", reviewedOpen));
    }
    if (!undischarged.isEmpty()) {
      System.err.println("Undischarged:\n\t - " + String.join("\n\t - ", undischarged));
    }
    String message = open + " of " + kept.size() + " proof obligations are not discharged";
    System.err.println("Error: " + message);
    // Open obligations are unproven, not disproven: the no-verdict exit (2), like wd.
    return RunReport.of(RunReport.Status.INCOMPLETE, message, checks);
  }

  /** The chain's components whose .bpo file is absent next to the resolved model file. */
  private List<String> missingProofFiles(EventBModel model) {
    Path projectDir = parent.resolvedModelPath().toAbsolutePath().getParent();
    return componentNames(model).stream()
        .filter(name -> !Files.exists(projectDir.resolve(name + ".bpo")))
        .toList();
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
        obligations.add(new QualifiedPo(machine.getName() + "/" + po.getName(), po));
      }
    }
    for (Context context : model.getContexts()) {
      for (ProofObligation po : context.getProofs()) {
        obligations.add(new QualifiedPo(context.getName() + "/" + po.getName(), po));
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

  private static String statusWord(ProofObligation po) {
    return po.isDischarged() ? "discharged" : po.isReviewed() ? "reviewed" : "undischarged";
  }

  private static void printSummary(
      int total, int discharged, int reviewed, int undischarged, int filteredOut) {
    StringBuilder summary =
        new StringBuilder("Proof obligations: ")
            .append(total)
            .append(" total, ")
            .append(discharged)
            .append(" discharged");
    if (reviewed > 0) {
      summary.append(", ").append(reviewed).append(" reviewed");
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
