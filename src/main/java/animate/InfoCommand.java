package animate;

import com.google.common.io.MoreFiles;
import de.prob.animator.command.GetCurrentPreferencesCommand;
import de.prob.animator.command.GetDefaultPreferencesCommand;
import de.prob.animator.domainobjects.DotVisualizationCommand;
import de.prob.animator.domainobjects.ProBPreference;
import de.prob.model.eventb.EventBModel;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "info",
    description = "Dump information about the model",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class InfoCommand implements Callable<Integer> {

  private static final Logger logger = LoggerFactory.getLogger(InfoCommand.class);

  @ParentCommand Animate parent;

  // The graph options are long-only: the short letters are taken by top-level
  // options such as -m/--machine and -z/--size, so short letters for graph files
  // would have invited confusion.
  @Option(
      names = "--machine-graph",
      paramLabel = "machine.dot",
      description = "save machine hierarchy graph in dot or svg")
  Path machineGraph;

  @Option(
      names = "--event-graph",
      paramLabel = "events.dot",
      description = "save events hierarchy graph in dot or svg")
  Path eventGraph;

  @Option(
      names = "--property-graph",
      paramLabel = "properties.dot",
      description = "save properties graph in dot or svg")
  Path propertyGraph;

  @Option(
      names = "--invariant-graph",
      paramLabel = "invariant.dot",
      description = "save invariant graph in dot or svg")
  Path invariantGraph;

  @Option(names = "--force", description = "overwrite existing output files")
  boolean force;

  @Option(
      names = "--prefs",
      description = "list all ProB preferences (name, current value, default, description)")
  boolean prefs;

  @Override
  public Integer call() {
    try {
      validateOutputs();
    } catch (IllegalArgumentException | IOException e) {
      String message = e.getMessage();
      System.err.println("Error: " + message);
      return parent.finishRun(RunReport.of(RunReport.Status.ERROR, message));
    }
    return parent.finishRun(parent.withStateSpace(this::dumpInfo));
  }

  /** Rejects bad output paths before they cost a full ProB model load. */
  private void validateOutputs() throws IOException {
    for (Path path : new Path[] {machineGraph, eventGraph, propertyGraph, invariantGraph}) {
      if (path == null) {
        continue;
      }
      String extension = MoreFiles.getFileExtension(path).toLowerCase(Locale.ROOT);
      if (!extension.equals("dot") && !extension.equals("svg")) {
        throw new IllegalArgumentException(
            "unsupported extension for " + path + " (expected .dot or .svg)");
      }
      Animate.validateWritableOutput(path, "Output", force);
    }
  }

  private RunReport dumpInfo(StateSpace stateSpace) {
    List<RunReport.Check> checks = new ArrayList<>();

    boolean hasVisualizationCmd =
        machineGraph != null
            || eventGraph != null
            || propertyGraph != null
            || invariantGraph != null;

    if (hasVisualizationCmd) {
      logger.info("Initializing model");
      Trace trace = parent.initializeInTransaction(stateSpace);

      addVisualizationCheck(checks, "machine_hierarchy", "machine-graph", machineGraph, trace);
      addVisualizationCheck(checks, "event_hierarchy", "event-graph", eventGraph, trace);
      addVisualizationCheck(checks, "properties", "property-graph", propertyGraph, trace);
      addVisualizationCheck(checks, "invariant", "invariant-graph", invariantGraph, trace);
    }

    if (prefs) {
      printPreferences(stateSpace);
      checks.add(new RunReport.Check("prefs", RunReport.Outcome.PASSED, null));
    }

    if (!hasVisualizationCmd && !prefs) {
      EventBModel model = (EventBModel) stateSpace.getModel();
      System.out.print(model.calculateDependencies().getGraph());
      checks.add(new RunReport.Check("dependencies", RunReport.Outcome.PASSED, null));
    }

    String firstError =
        checks.stream()
            .filter(check -> check.outcome() == RunReport.Outcome.ERROR)
            .map(RunReport.Check::message)
            .findFirst()
            .orElse(null);
    RunReport.Status status = firstError == null ? RunReport.Status.OK : RunReport.Status.ERROR;
    return RunReport.of(status, firstError, checks);
  }

  /** One line per preference, sorted by name, with the effective value of this run first. */
  private void printPreferences(StateSpace stateSpace) {
    GetDefaultPreferencesCommand defaults = new GetDefaultPreferencesCommand();
    GetCurrentPreferencesCommand current = new GetCurrentPreferencesCommand();
    stateSpace.execute(defaults);
    stateSpace.execute(current);

    Map<String, String> currentValues = current.getPreferences();
    List<ProBPreference> sorted = new ArrayList<>(defaults.getPreferences());
    sorted.sort(Comparator.comparing(pref -> pref.name));
    for (ProBPreference pref : sorted) {
      String value = currentValues.getOrDefault(pref.name, pref.defaultValue);
      System.out.println(
          pref.name + " = " + value + " (default: " + pref.defaultValue + ") " + pref.description);
    }
  }

  private void addVisualizationCheck(
      List<RunReport.Check> checks, String name, String checkName, Path path, Trace trace) {
    if (path == null) {
      return;
    }
    logger.info("Saving {} to {}", name, path);
    // validateOutputs already rejected anything that is not .dot or .svg.
    String extension = MoreFiles.getFileExtension(path).toLowerCase(Locale.ROOT);
    try {
      DotVisualizationCommand cmd = DotVisualizationCommand.getByName(name, trace);
      if (extension.equals("dot")) {
        cmd.visualizeAsDotToFile(path, new ArrayList<>());
      } else {
        cmd.visualizeAsSvgToFile(path, new ArrayList<>());
      }
    } catch (RuntimeException e) {
      logger.debug("Error saving {} to {}", name, path, e);
      String message = "Error saving " + name + " to " + path + ": " + e.getMessage();
      System.err.println(message);
      checks.add(new RunReport.Check(checkName, RunReport.Outcome.ERROR, message));
      return;
    }
    checks.add(new RunReport.Check(checkName, RunReport.Outcome.PASSED, null));
  }
}
