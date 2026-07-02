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

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InfoCommand.class);

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
      System.err.println("Error: " + e.getMessage());
      return 1;
    }
    return parent.withStateSpace(this::dumpInfo);
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

  private int dumpInfo(StateSpace stateSpace) {
    int err = 0;

    boolean hasVisualizationCmd =
        machineGraph != null
            || eventGraph != null
            || propertyGraph != null
            || invariantGraph != null;

    if (hasVisualizationCmd) {
      logger.info("Initializing model");
      stateSpace.startTransaction();
      Trace trace;
      try {
        trace = parent.initializeTrace(stateSpace, false);
      } finally {
        stateSpace.endTransaction();
      }

      err |= saveVisualization("machine_hierarchy", machineGraph, trace);
      err |= saveVisualization("event_hierarchy", eventGraph, trace);
      err |= saveVisualization("properties", propertyGraph, trace);
      err |= saveVisualization("invariant", invariantGraph, trace);
    }

    if (prefs) {
      printPreferences(stateSpace);
    }

    if (!hasVisualizationCmd && !prefs) {
      EventBModel model = (EventBModel) stateSpace.getModel();
      System.out.print(model.calculateDependencies().getGraph());
    }

    return err;
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

  private int saveVisualization(String name, Path path, Trace trace) {
    if (path == null) return 0;
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
      System.err.println("Error saving " + name + " to " + path + ": " + e.getMessage());
      return 1;
    }
    return 0;
  }
}
