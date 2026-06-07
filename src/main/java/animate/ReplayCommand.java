package animate;

import ch.qos.logback.classic.Logger;
import de.prob.check.tracereplay.ReplayedTrace;
import de.prob.check.tracereplay.TraceReplay;
import de.prob.check.tracereplay.TraceReplayStatus;
import de.prob.statespace.StateSpace;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "replay",
    description = "Replay json trace",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class ReplayCommand implements Callable<Integer> {

  private static final Logger logger = (Logger) LoggerFactory.getLogger(ReplayCommand.class);

  @ParentCommand Animate parent;

  @Option(
      names = {"-t", "--trace"},
      required = true,
      paramLabel = "trace.json",
      description = "Path to a json trace")
  Path jsonTrace;

  @Override
  public Integer call() {
    return parent.withStateSpace(this::replay);
  }

  private int replay(StateSpace stateSpace) {
    System.out.println("Starting trace replay. Use --debug to view steps.");
    ReplayedTrace trace;
    try {
      trace = TraceReplay.replayTraceFile(stateSpace, jsonTrace);
    } catch (Exception e) {
      logger.error("Error replaying trace", e);
      System.err.println("Error replaying trace: " + e.getMessage());
      return 1;
    }
    System.out.println("Trace replay status: " + trace.getReplayStatus());
    if (trace.getReplayStatus() != TraceReplayStatus.PERFECT) {
      printReplayDiagnostics(trace);
    }
    return exitCodeFor(trace);
  }

  static int exitCodeFor(ReplayedTrace trace) {
    return trace.getReplayStatus() == TraceReplayStatus.PERFECT ? 0 : 1;
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
}
