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
import de.prob.check.ConsistencyChecker;
import de.prob.check.IModelCheckingResult;
import de.prob.check.ModelCheckLimitReached;
import de.prob.check.ModelCheckOk;
import de.prob.check.ModelCheckingOptions;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

@Command(
    name = "eventb-animate",
    description = "Model-check an Event-B model (deadlocks and invariants) using ProB",
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
      names = {"-z", "--size"},
      defaultValue = "4",
      description = "default size for ProB sets (default: ${DEFAULT-VALUE})",
      scope = ScopeType.INHERIT)
  int size;

  @Option(
      names = {"-p", "--pref"},
      paramLabel = "KEY=VALUE",
      description =
          "set a ProB preference (repeatable); overrides the built-in defaults, including"
              + " DEFAULT_SETSIZE from -z/--size",
      scope = ScopeType.INHERIT)
  Map<String, String> userPrefs = new LinkedHashMap<>();

  // -1 (the field default, applied both by picocli and by direct construction in tests) means no
  // limit -- ConsistencyChecker explores the full state space.
  @Option(
      names = "--states",
      paramLabel = "N",
      description = "bound model-checking to at most N explored states (default: exhaustive)")
  int states = -1;

  // -1 means no limit, mirroring --states.
  @Option(
      names = "--time-limit",
      paramLabel = "seconds",
      description = "bound model-checking to the given wall-clock time (default: unlimited)")
  int timeLimit = -1;

  @Option(names = "--perf", description = "print ProB performance info (default: ${DEFAULT-VALUE})")
  boolean perf;

  @Option(
      names = {"-m", "--machine"},
      paramLabel = "[<project>/]<name>",
      description =
          "machine to model-check (default: auto-select most refined). For a multi-project archive,"
              + " qualify with the project as <project>/<machine>, or <project>/ to auto-select the"
              + " most refined machine within that project",
      scope = ScopeType.INHERIT)
  String machineName;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save the counterexample trace in json to a file (when a violation is found)")
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

  private void validateInput() {
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
    if (size <= 0) {
      throw new IllegalArgumentException("Default set size must be positive, got: " + size);
    }
    if (states == 0 || states < -1) {
      throw new IllegalArgumentException(
          "--states must be a positive number of states (or omitted for an exhaustive check), got: "
              + states);
    }
    if (timeLimit == 0 || timeLimit < -1) {
      throw new IllegalArgumentException(
          "--time-limit must be a positive number of seconds (or omitted for no limit), got: "
              + timeLimit);
    }
  }

  /**
   * Refuses to overwrite {@code path} unless {@code force} is set, and creates missing parent
   * directories so the later write only has to deal with the file itself. Shared by the subcommands
   * so every output file follows the same overwrite policy.
   */
  static void validateWritableOutput(Path path, String label, boolean force) throws IOException {
    if (Files.exists(path) && !force) {
      throw new IOException(label + " already exists, use --force to overwrite: " + path);
    }
    Path parentDir = path.toAbsolutePath().getParent();
    if (parentDir != null) {
      Files.createDirectories(parentDir);
    }
  }

  /** Built-in defaults first; user-supplied -p/--pref values override any of them. */
  Map<String, String> buildProBPreferences() {
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
    prefs.putAll(userPrefs);
    return prefs;
  }

  private StateSpace loadModel() throws IOException {
    validateInput();

    logger.info("Load Event-B Machine");

    Map<String, String> prefs = buildProBPreferences();

    Path resolvedModel = modelResolver.resolve(model, machineName);
    String resolvedMachineName = resolvedModel.getFileName().toString().replaceFirst("\\.bum$", "");
    System.out.println("Machine: " + resolvedMachineName);
    StateSpace stateSpace = api.get().eventb_load(resolvedModel.toString(), prefs);

    GetVersionCommand version = new GetVersionCommand();
    stateSpace.execute(version);
    probVersionString = version.getVersionString();
    logger.info("ProB Version: {}", probVersionString);

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
      logger.debug("Error loading model", e);
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
      releaseStateSpace(stateSpace);
    }
  }

  /** Shuts down the ProB instance and removes any extracted temp directory. */
  void releaseStateSpace(StateSpace stateSpace) {
    stateSpace.kill();
    modelResolver.cleanupTempDir();
  }

  Trace initializeTrace(final StateSpace stateSpace, boolean failOnInitializationError) {
    Trace trace = new Trace(stateSpace);
    trace.getCurrentState().exploreIfNeeded();
    if (!trace.getCurrentState().isConstantsSetUp()) {
      trace = initializeOnce(trace, failOnInitializationError, SETUP_CONSTANTS_EVENT);
    }
    if (!trace.getCurrentState().isInitialised()) {
      trace =
          initializeOnce(
              trace, failOnInitializationError, INITIALISE_MACHINE_EVENT, INITIALISATION_EVENT);
    }
    return trace;
  }

  private Trace initializeOnce(
      Trace trace, boolean failOnInitializationError, String... eventNames) {
    for (String eventName : eventNames) {
      if (findInitializationTransition(trace, eventName).isEmpty()) {
        logger.debug("Skipping unavailable initialization event {}", eventName);
        continue;
      }
      try {
        Trace initializedTrace = runInitializationEvent(trace, eventName);
        initializedTrace.getCurrentState().exploreIfNeeded();
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

  @Override
  public Integer call() {
    return withStateSpace(this::runModelCheck);
  }

  private int runModelCheck(StateSpace stateSpace) {
    ModelCheckingOptions options =
        new ModelCheckingOptions().checkDeadlocks(true).checkInvariantViolations(true);
    if (states > 0) {
      options = options.stateLimit(states);
    }
    if (timeLimit > 0) {
      options = options.timeLimit(Duration.ofSeconds(timeLimit));
    }

    System.out.println("Model checking...");
    IModelCheckingResult result = new ConsistencyChecker(stateSpace, options).call();

    if (result instanceof ModelCheckOk || result instanceof ModelCheckLimitReached) {
      System.out.println(noViolationMessage(result));
      printCoverage(stateSpace);
      return 0;
    }

    if (result instanceof ITraceDescription) {
      // A real counterexample: an invariant violation or a reachable deadlock.
      System.err.println("Error: " + result.getMessage());
      Trace counterexample = ((ITraceDescription) result).getTrace(stateSpace);
      printViolatedInvariants(stateSpace, counterexample.getCurrentState());
      printCounterexample(counterexample);
      if (jsonTrace != null) {
        saveTrace(counterexample, stateSpace);
      }
      return 1;
    }

    // Neither a clean result nor a counterexample: the check did not complete
    // (e.g. interrupted or errored), so nothing was proven. Report it distinctly
    // rather than as a violation, and exit 2 to keep it separate from a real one.
    System.err.println("Model checking did not complete: " + result.getMessage());
    return 2;
  }

  /**
   * The success wording must distinguish a genuinely exhaustive run from one stopped by a bound, so
   * CI logs never overstate what was proven. The checker result carries which stop condition fired,
   * so derive the caveat from it instead of guessing from the requested options.
   */
  private String noViolationMessage(IModelCheckingResult result) {
    String noViolation = "No invariant violation or deadlock found ";
    if (result instanceof ModelCheckLimitReached) {
      // ProB's message names the bound: "State limit reached" or "Time limit reached".
      return noViolation
          + "("
          + result.getMessage().toLowerCase(Locale.ROOT)
          + "; not an exhaustive check).";
    }
    String message = result.getMessage();
    if (message.contains("All operations were covered")) {
      return noViolation + "(all events covered; not an exhaustive check).";
    }
    if (message.contains("Not all nodes were considered")) {
      return noViolation + "(not all states were considered; not an exhaustive check).";
    }
    return noViolation + "(full state space explored).";
  }

  private void printViolatedInvariants(StateSpace stateSpace, State state) {
    // Deadlock counterexamples still satisfy the invariant, so only enumerate
    // predicates when the reported state actually breaks one.
    if (state.isInvariantOk()
        || !(stateSpace.getMainComponent() instanceof EventBMachine machine)) {
      return;
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
    if (!violated.isEmpty()) {
      System.err.println("Violated invariants:\n\t - " + String.join("\n\t - ", violated));
    }
  }

  private void printCounterexample(Trace trace) {
    System.out.println("Counterexample trace:");
    for (Transition transition : trace.getTransitionList()) {
      System.out.println("\t" + transition.evaluate(FormulaExpand.EXPAND).getPrettyRep());
    }
    System.out.println();
    System.out.println("Violating state:\n" + trace.getCurrentState().getStateRep());
  }

  private void saveTrace(Trace trace, StateSpace stateSpace) {
    JsonMetadata metadata =
        new JsonMetadataBuilder("Trace", 6)
            .withSavedNow()
            .withCreator("eventb-animate")
            .withProBCliVersion(probVersionString)
            .withModelName(Objects.toString(stateSpace.getMainComponent(), "unknown"))
            .build();
    TraceJsonFile traceJsonFile = new TraceJsonFile(trace, metadata);
    logger.info("Saving counterexample trace to {}", jsonTrace);
    try {
      traceManager.get().save(jsonTrace, traceJsonFile);
    } catch (IOException e) {
      logger.debug("Error saving trace", e);
      System.err.println("Error saving trace: " + e.getMessage());
    }
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

  // Package-visible so tests can inspect the command instance after a run.
  static CommandLine commandLine() {
    return new CommandLine(Animate.class, new LazyGuiceFactory());
  }

  public static int execute(String[] args) {
    return commandLine().execute(args);
  }

  public static void main(String[] args) {
    // Reuse a persistent ProB home (~/.prob/prob2-<version>) instead of extracting ProB's CLI
    // binaries to a fresh temp dir every run. The default per-run temp dir is deleted on JVM
    // shutdown, which fails on Windows because probcli.exe is still locked -- leaking a prob-java*
    // dir each run. A static home avoids the temp dir entirely (and skips re-extraction on launch).
    // Respect an explicit override if the user set the property themselves.
    if (System.getProperty("prob.home.temp") == null) {
      System.setProperty("prob.home.temp", "false");
    }
    System.exit(execute(args));
  }
}
