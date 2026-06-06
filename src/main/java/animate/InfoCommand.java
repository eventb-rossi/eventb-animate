package animate;

import com.google.common.io.MoreFiles;
import de.prob.animator.domainobjects.DotVisualizationCommand;
import de.prob.model.eventb.EventBModel;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "info", description = "Dump information about the model")
class InfoCommand implements Callable<Integer> {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InfoCommand.class);

  @ParentCommand Animate parent;

  // No short flags for the two options below: at the top level -m/--machine
  // selects a machine by name and -i/--invariants toggles invariant checking,
  // so reusing those letters here for graph files invited confusion.
  @Option(
      names = "--machine-graph",
      paramLabel = "machine.dot",
      description = "save machine hierarchy graph in dot or svg")
  Path machineGraph;

  @Option(
      names = {"-e", "--events"},
      paramLabel = "events.dot",
      description = "save events hierarchy graph in dot or svg")
  Path events;

  @Option(
      names = {"-p", "--properties"},
      paramLabel = "properties.dot",
      description = "save properties graph in dot or svg")
  Path properties;

  @Option(
      names = "--invariant-graph",
      paramLabel = "invariant.dot",
      description = "save invariant graph in dot or svg")
  Path invariantGraph;

  @Option(
      names = {"-b", "--bmodel"},
      paramLabel = "model.eventb",
      description = "dump prolog model to .eventb file")
  Path eventb;

  @Override
  public Integer call() {
    return parent.withStateSpace(this::dumpInfo);
  }

  private int dumpInfo(StateSpace stateSpace) {
    int err = 0;

    boolean hasVisualizationCmd =
        machineGraph != null || events != null || properties != null || invariantGraph != null;

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
      err |= saveVisualization("event_hierarchy", events, trace);
      err |= saveVisualization("properties", properties, trace);
      err |= saveVisualization("invariant", invariantGraph, trace);
    }

    if (eventb != null) {
      logger.info("Saving B model to {}", eventb);
      try {
        EventBPackageWriter.write(stateSpace, eventb);
      } catch (IOException e) {
        logger.error("Error saving model", e);
        System.err.println("Error saving model: " + e.getMessage());
        err = 1;
      }
    }

    if (!hasVisualizationCmd && eventb == null) {
      EventBModel model = (EventBModel) stateSpace.getModel();
      System.out.print(model.calculateDependencies().getGraph());
    }

    return err;
  }

  private int saveVisualization(String name, Path path, Trace trace) {
    if (path == null) return 0;
    logger.info("Saving {} to {}", name, path);
    DotVisualizationCommand cmd = DotVisualizationCommand.getByName(name, trace);
    String extension = MoreFiles.getFileExtension(path);
    if (extension.equals("dot")) {
      cmd.visualizeAsDotToFile(path, new ArrayList<>());
    } else if (extension.equals("svg")) {
      cmd.visualizeAsSvgToFile(path, new ArrayList<>());
    } else {
      System.err.println("Unknown extension " + extension);
      return 1;
    }
    return 0;
  }
}
