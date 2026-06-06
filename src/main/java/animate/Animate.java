package animate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Stage;
import de.prob.animator.command.ComputeCoverageCommand;
import de.prob.animator.command.ComputeCoverageCommand.ComputeCoverageResult;
import de.prob.animator.command.GetVersionCommand;
import de.prob.animator.domainobjects.*;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.check.tracereplay.json.storage.TraceJsonFile;
import de.prob.json.JsonMetadata;
import de.prob.json.JsonMetadataBuilder;
import de.prob.model.eventb.EventBMachine;
import de.prob.scripting.Api;
import de.prob.statespace.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

@Command(
    name = "eventb-animate",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class,
    subcommands = {
      CommandLine.HelpCommand.class,
      ReplayCommand.class,
      InfoCommand.class,
      ConvertCommand.class
    })
public class Animate implements Callable<Integer> {

  static final String SETUP_CONSTANTS_EVENT = "$setup_constants";
  static final String INITIALISE_MACHINE_EVENT = "$initialise_machine";
  static final String INITIALISATION_EVENT = "INITIALISATION";

  // Providers keep construction cheap: resolving Api installs the ProB CLI
  // binaries, which --version/--help invocations should never pay for.
  private final Provider<Api> api;
  private final Provider<TraceManager> traceManager;
  final ModelResolver modelResolver = new ModelResolver();
  private String probVersionString;

  private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

  public static class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      Package pkg = Animate.class.getPackage();
      String version = pkg == null ? null : pkg.getImplementationVersion();
      return new String[] {
        "eventb-animate " + (version == null || version.isBlank() ? "dev" : version)
      };
    }
  }

  @Parameters(description = "path to model.bum or .zip file", scope = ScopeType.INHERIT)
  Path model;

  @Option(
      names = {"-s", "--steps"},
      defaultValue = "5",
      description = "number of random steps (default: ${DEFAULT-VALUE})")
  int steps;

  @Option(
      names = {"-z", "--size"},
      defaultValue = "4",
      description = "default size for ProB sets (default: ${DEFAULT-VALUE})")
  int size;

  @Option(
      names = {"-i", "--invariants"},
      description = "check invariants (default: ${DEFAULT-VALUE})")
  boolean checkInv;

  @Option(names = "--perf", description = "print ProB performance info (default: ${DEFAULT-VALUE})")
  boolean perf;

  @Option(
      names = {"-m", "--machine"},
      paramLabel = "<name>",
      description = "machine to animate (default: auto-select most refined)")
  String machineName;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save animation trace in json to a file")
  Path jsonTrace;

  @Option(
      names = "--debug",
      description = "enable debug log (default: ${DEFAULT-VALUE})",
      scope = ScopeType.INHERIT)
  boolean debug;

  @Inject
  public Animate(Provider<Api> api, Provider<TraceManager> traceManager) {
    this.api = api;
    this.traceManager = traceManager;
  }

  Api api() {
    return api.get();
  }

  private void printCoverage(StateSpace stateSpace) {
    ComputeCoverageCommand cmd = new ComputeCoverageCommand();
    stateSpace.execute(cmd);
    ComputeCoverageResult coverage = cmd.getResult();
    List<String> ops = coverage.getOps();
    List<String> uncovered = coverage.getUncovered();

    System.out.println("Coverage properties:\n\t - " + String.join("\n\t - ", coverage.getNodes()));
    if (!ops.isEmpty()) {
      System.out.println("Covered operations:\n\t - " + String.join("\n\t - ", ops));
    }
    if (!uncovered.isEmpty()) {
      System.out.println("Uncovered operations:\n\t - " + String.join("\n\t - ", uncovered));
    }
  }

  private List<String> findViolatedInvariants(StateSpace stateSpace, State state) {
    Object mainComponent = stateSpace.getMainComponent();
    if (mainComponent == null) {
      logger.warn("Main component is null, cannot check invariants");
      return Collections.emptyList();
    }
    if (!(mainComponent instanceof EventBMachine)) {
      logger.warn(
          "Main component is not an EventBMachine: {}, cannot check invariants",
          mainComponent.getClass().getName());
      return Collections.emptyList();
    }

    List<IEvalElement> invariants =
        ((EventBMachine) mainComponent)
            .getAllInvariants().stream().map(i -> i.getPredicate()).collect(Collectors.toList());
    List<AbstractEvalResult> results = state.eval(invariants);

    List<String> violatedInvariants =
        IntStream.range(0, results.size())
            .filter(i -> results.get(i) != EvalResult.TRUE)
            .mapToObj(i -> invariants.get(i).toString())
            .collect(Collectors.toList());

    return violatedInvariants;
  }

  private void validateInput() throws IllegalArgumentException {
    if (model == null) {
      throw new IllegalArgumentException("Model file is required");
    }
    if (!Files.exists(model)) {
      throw new IllegalArgumentException("Model file does not exist: " + model);
    }
    if (!Files.isRegularFile(model) && !Files.isDirectory(model)) {
      throw new IllegalArgumentException("Model path is not a file or directory: " + model);
    }
    if (!Files.isReadable(model)) {
      throw new IllegalArgumentException("Model path is not readable: " + model);
    }
    if (steps <= 0) {
      throw new IllegalArgumentException("Number of steps must be positive, got: " + steps);
    }
    if (size <= 0) {
      throw new IllegalArgumentException("Default set size must be positive, got: " + size);
    }
  }

  private StateSpace loadModel() throws IOException {
    validateInput();

    logger.info("Load Event-B Machine");

    Map<String, String> prefs = new HashMap<>();
    prefs.put("MEMOIZE_FUNCTIONS", "true");
    prefs.put("SYMBOLIC", "true");
    prefs.put("TRACE_INFO", "true");
    prefs.put("TRY_FIND_ABORT", "true");
    prefs.put("SYMMETRY_MODE", "hash");
    prefs.put("DEFAULT_SETSIZE", String.valueOf(size));
    prefs.put("COMPRESSION", "true");
    prefs.put("CLPFD", "true");
    prefs.put("PROOF_INFO", "true");
    prefs.put("OPERATION_REUSE", "true");
    if (perf) {
      prefs.put("PERFORMANCE_INFO", "true");
    }

    Path resolvedModel = modelResolver.resolve(model, machineName);
    String resolvedMachineName = resolvedModel.getFileName().toString().replaceFirst("\\.bum$", "");
    System.out.println("Machine: " + resolvedMachineName);
    StateSpace stateSpace = api.get().eventb_load(resolvedModel.toString(), prefs);

    GetVersionCommand version = new GetVersionCommand();
    stateSpace.execute(version);
    probVersionString = version.getVersionString();
    logger.info("ProB Version: " + probVersionString + "\n");

    return stateSpace;
  }

  private void initLogging() {
    if (!debug) {
      Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
      root.setLevel(Level.WARN);
      logger.setLevel(Level.INFO);
    }
  }

  StateSpace initAndLoadModel() {
    initLogging();
    try {
      return loadModel();
    } catch (Exception e) {
      modelResolver.cleanupTempDir();
      logger.error("Error loading model", e);
      System.err.println("Error loading model: " + e.getMessage());
      return null;
    }
  }

  /**
   * Loads the model and runs {@code body} with the resulting StateSpace, guaranteeing ProB shutdown
   * and temp-directory cleanup. Returns 1 if the model could not be loaded.
   */
  int withStateSpace(ToIntFunction<StateSpace> body) {
    StateSpace stateSpace = initAndLoadModel();
    if (stateSpace == null) {
      return 1;
    }
    try {
      return body.applyAsInt(stateSpace);
    } finally {
      stateSpace.kill();
      modelResolver.cleanupTempDir();
    }
  }

  boolean invariantViolated;
  boolean deadlocked;

  Trace initializeTrace(final StateSpace stateSpace) {
    return initializeTrace(stateSpace, true);
  }

  Trace initializeTrace(final StateSpace stateSpace, boolean failOnInitializationError) {
    Trace trace = new Trace(stateSpace);
    trace.getCurrentState().exploreIfNeeded();
    if (!trace.getCurrentState().isConstantsSetUp()) {
      trace = initializeOnce(stateSpace, trace, failOnInitializationError, SETUP_CONSTANTS_EVENT);
    }
    if (invariantViolated) {
      return trace;
    }
    if (!trace.getCurrentState().isInitialised()) {
      trace =
          initializeOnce(
              stateSpace,
              trace,
              failOnInitializationError,
              INITIALISE_MACHINE_EVENT,
              INITIALISATION_EVENT);
    }
    return trace;
  }

  private Trace initializeOnce(
      StateSpace stateSpace, Trace trace, boolean failOnInitializationError, String... eventNames) {
    for (String eventName : eventNames) {
      if (findInitializationTransition(trace, eventName).isEmpty()) {
        logger.debug("Skipping unavailable initialization event {}", eventName);
        continue;
      }
      try {
        Trace initializedTrace = runInitializationEvent(trace, eventName);
        initializedTrace.getCurrentState().exploreIfNeeded();
        checkTraceInvariants(stateSpace, initializedTrace);
        return initializedTrace;
      } catch (RuntimeException e) {
        if (failOnInitializationError) {
          throw e;
        }
        logger.warn("Could not fully initialize model via {}", eventName, e);
        System.err.println("Warning: Could not fully initialize model: " + e.getMessage());
        return trace;
      }
    }
    return trace;
  }

  Trace runInitializationEvent(Trace trace, String eventName) {
    Transition transition =
        findInitializationTransition(trace, eventName)
            .orElseThrow(() -> new IllegalArgumentException("Could not execute " + eventName));
    return trace.add(transition);
  }

  private Optional<Transition> findInitializationTransition(Trace trace, String eventName) {
    return trace.getCurrentState().getOutTransitions().stream()
        .filter(transition -> eventName.equals(transition.getName()))
        .findFirst();
  }

  boolean checkTraceInvariants(StateSpace stateSpace, Trace trace) {
    if (!checkInv || trace.getCurrentState().isInvariantOk()) {
      return false;
    }

    List<String> inv = findViolatedInvariants(stateSpace, trace.getCurrentState());
    System.err.println("Error: violated invariants:\n\t - " + String.join("\n\t - ", inv));
    invariantViolated = true;
    return true;
  }

  public Trace start(final StateSpace stateSpace) {
    stateSpace.startTransaction();
    invariantViolated = false;
    deadlocked = false;

    try {
      Trace trace = initializeTrace(stateSpace);
      if (!invariantViolated && !checkTraceInvariants(stateSpace, trace)) {
        System.out.println("Animation steps:");
        for (int i = 0; i < steps; i++) {
          Trace newTrace = trace.anyEvent(null);
          if (newTrace == trace) {
            System.err.println("Error: Can't find an event to execute from this state (deadlock)");
            deadlocked = true;
            break;
          }
          trace = newTrace;

          Transition transition = trace.getCurrent().getTransition().evaluate(FormulaExpand.EXPAND);
          System.out.println(transition.getPrettyRep());
          if (checkTraceInvariants(stateSpace, trace)) {
            break;
          }
        }
      }
      System.out.println();

      System.out.println("Current state:\n" + trace.getCurrentState().getStateRep());
      System.out.println();
      printCoverage(stateSpace);
      return trace;
    } finally {
      stateSpace.endTransaction();
    }
  }

  @Override
  public Integer call() {
    return withStateSpace(this::animate);
  }

  private int animate(StateSpace stateSpace) {
    Trace trace = start(stateSpace);

    if (jsonTrace != null) {
      JsonMetadata metadata =
          new JsonMetadataBuilder("Trace", 6)
              .withSavedNow()
              .withCreator("eventb-animate")
              .withProBCliVersion(probVersionString)
              .withModelName(Objects.toString(stateSpace.getMainComponent(), "unknown"))
              .build();
      TraceJsonFile abstractJsonFile = new TraceJsonFile(trace, metadata);
      logger.info("Saving animation trace to {}", jsonTrace);

      try {
        traceManager.get().save(jsonTrace, abstractJsonFile);
      } catch (IOException e) {
        logger.error("Error saving trace", e);
        System.err.println("Error saving trace: " + e.getMessage());
        return 1;
      }
    }

    return invariantViolated || deadlocked ? 1 : 0;
  }

  static final class LazyGuiceFactory implements CommandLine.IFactory {
    private Injector injector;

    @Override
    public <K> K create(Class<K> cls) throws Exception {
      try {
        return CommandLine.defaultFactory().create(cls);
      } catch (Exception e) {
        if (injector == null) {
          // DEVELOPMENT keeps singletons lazy: ProB's CLI binaries are only
          // installed when a model is actually loaded, not for --version/--help.
          injector = Guice.createInjector(Stage.DEVELOPMENT, new Config());
        }
        return injector.getInstance(cls);
      }
    }
  }

  public static int execute(String[] args) {
    return new CommandLine(Animate.class, new LazyGuiceFactory()).execute(args);
  }

  public static void main(String[] args) {
    System.exit(execute(args));
  }
}
