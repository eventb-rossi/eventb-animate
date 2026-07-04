package animate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Stage;
import de.be4.ltl.core.parser.LtlParseException;
import de.prob.animator.command.ComputeCoverageCommand;
import de.prob.animator.command.ComputeCoverageCommand.ComputeCoverageResult;
import de.prob.animator.command.GetVersionCommand;
import de.prob.animator.domainobjects.*;
import de.prob.check.ConsistencyChecker;
import de.prob.check.IModelCheckListener;
import de.prob.check.IModelCheckingResult;
import de.prob.check.LTLChecker;
import de.prob.check.LTLCounterExample;
import de.prob.check.LTLOk;
import de.prob.check.ModelCheckGoalFound;
import de.prob.check.ModelCheckLimitReached;
import de.prob.check.ModelCheckOk;
import de.prob.check.ModelCheckingOptions;
import de.prob.check.ModelCheckingSearchStrategy;
import de.prob.check.StateSpaceStats;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.model.eventb.EventBModel;
import de.prob.scripting.Api;
import de.prob.scripting.EventBFactory;
import de.prob.statespace.*;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Command(
    name = Animate.TOOL_NAME,
    description =
        "Model-check an Event-B model using ProB: deadlocks and invariants by default,"
            + " plus assertions, goal reachability, and LTL formulas",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class,
    subcommands = {
      CommandLine.HelpCommand.class,
      ReplayCommand.class,
      InfoCommand.class,
      ConvertCommand.class,
      WdCommand.class,
      PoCommand.class
    })
public class Animate implements Callable<Integer> {

  static final String TOOL_NAME = "eventb-animate";

  static final String SETUP_CONSTANTS_EVENT = "$setup_constants";
  static final String INITIALISE_MACHINE_EVENT = "$initialise_machine";
  static final String INITIALISATION_EVENT = "INITIALISATION";

  // Providers keep construction cheap: resolving Api installs the ProB CLI
  // binaries, which --version/--help invocations should never pay for.
  private final Provider<Api> api;
  private final Provider<EventBFactory> eventBFactory;
  private final TraceWriter traceWriter;
  final ModelResolver modelResolver = new ModelResolver();
  private String probVersionString;
  private String resolvedMachineName;
  private Path resolvedModelPath;
  private String loadErrorMessage;
  private EventB parsedGoal;

  // The last finished run's findings, consumed by the report emission in executeRun
  // and by tests via TestCli.Result.command().
  RunReport lastReport;

  @Spec CommandSpec spec;

  private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

  /** The release version from the jar manifest, or "dev" when running from classes. */
  static String toolVersion() {
    Package pkg = Animate.class.getPackage();
    String version = pkg == null ? null : pkg.getImplementationVersion();
    return version == null || version.isBlank() ? "dev" : version;
  }

  public static class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {TOOL_NAME + " " + toolVersion()};
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
              + " DEFAULT_SETSIZE from -z/--size (see 'info --prefs' for the available"
              + " preferences)",
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

  @Option(
      names = "--stop-at-full-coverage",
      description = "stop model-checking once every event has been covered")
  boolean stopAtFullCoverage;

  @Option(names = "--assertions", description = "also check assertions (theorems) for violations")
  boolean assertions;

  @Option(names = "--no-deadlock", description = "do not check for deadlocks")
  boolean noDeadlock;

  @Option(names = "--no-invariant", description = "do not check for invariant violations")
  boolean noInvariant;

  @Option(
      names = "--goal",
      paramLabel = "predicate",
      description =
          "also search for a reachable state satisfying the Event-B predicate; a hit is reported"
              + " as a violation with a trace (combine with --no-deadlock --no-invariant for a"
              + " pure reachability search)")
  String goal;

  @Option(
      names = "--ltl",
      paramLabel = "formula",
      description =
          "check an LTL formula instead of running the consistency check (ProB LTL syntax;"
              + " wrap Event-B predicates in {...}, e.g. \"G not({x = TRUE & y = TRUE})\")")
  String ltlFormula;

  @Option(
      names = "--ltl-file",
      paramLabel = "file.ltl",
      description = "read the LTL formula to check from a file")
  Path ltlFile;

  // Only the three orders ProB's do_modelchecking supports; the kernel enum lists
  // more, but they are not accepted by the Prolog side.
  @Option(
      names = "--search-strategy",
      paramLabel = "mixed|bf|df",
      description =
          "state-space exploration order: mixed breadth/depth, breadth-first, or depth-first"
              + " (default: ${DEFAULT-VALUE})")
  SearchStrategy searchStrategy = SearchStrategy.mixed;

  /** CLI names for the exploration orders supported by ProB. */
  enum SearchStrategy {
    mixed(ModelCheckingSearchStrategy.MIXED_BF_DF),
    bf(ModelCheckingSearchStrategy.BREADTH_FIRST),
    df(ModelCheckingSearchStrategy.DEPTH_FIRST);

    final ModelCheckingSearchStrategy kernelStrategy;

    SearchStrategy(ModelCheckingSearchStrategy kernelStrategy) {
      this.kernelStrategy = kernelStrategy;
    }
  }

  @Option(
      names = "--progress",
      description = "print model-checking progress to stderr about once per second")
  boolean progress;

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
      names = "--json",
      paramLabel = "<file|->",
      description =
          "write a machine-readable JSON report of the run; '-' writes the report to stdout"
              + " and moves all other output to stderr",
      scope = ScopeType.INHERIT)
  Path jsonReport;

  @Option(
      names = "--junit",
      paramLabel = "report.xml",
      description = "write a JUnit XML report (one testcase per checked property)",
      scope = ScopeType.INHERIT)
  Path junitReport;

  @Option(
      names = "--debug",
      description = "enable debug log (default: ${DEFAULT-VALUE})",
      scope = ScopeType.INHERIT)
  boolean debug;

  @Inject
  public Animate(
      Provider<Api> api,
      Provider<TraceManager> traceManager,
      Provider<EventBFactory> eventBFactory) {
    this.api = api;
    this.eventBFactory = eventBFactory;
    this.traceWriter = new TraceWriter(traceManager);
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
  }

  /**
   * Usage errors for the default check are raised here, before any model load, as {@link
   * ParameterException} so picocli reports them with usage help and exit code 2. They must not live
   * in {@link #validateInput()}: that runs inside every subcommand's model load, where the
   * check-only flags are meaningless and must not fail the run.
   */
  private void validateCheckOptions() {
    if (states == 0 || states < -1) {
      throw usageError(
          "--states must be a positive number of states (or omitted for an exhaustive check),"
              + " got: "
              + states);
    }
    if (timeLimit == 0 || timeLimit < -1) {
      throw usageError(
          "--time-limit must be a positive number of seconds (or omitted for no limit), got: "
              + timeLimit);
    }
    if (isLtlRun()) {
      if (ltlFormula != null && ltlFile != null) {
        throw usageError("--ltl and --ltl-file are mutually exclusive");
      }
      // Rejected rather than silently ignored: the kernel's LTL checker only honors a
      // state limit, so accepting these flags would promise bounds and checks it never
      // enforces.
      List<String> unsupported = new ArrayList<>();
      if (goal != null) {
        unsupported.add("--goal");
      }
      if (assertions) {
        unsupported.add("--assertions");
      }
      if (noDeadlock) {
        unsupported.add("--no-deadlock");
      }
      if (noInvariant) {
        unsupported.add("--no-invariant");
      }
      if (timeLimit > 0) {
        unsupported.add("--time-limit");
      }
      if (stopAtFullCoverage) {
        unsupported.add("--stop-at-full-coverage");
      }
      if (searchStrategy != SearchStrategy.mixed) {
        unsupported.add("--search-strategy");
      }
      if (!unsupported.isEmpty()) {
        throw usageError(
            "the LTL check does not support "
                + String.join(", ", unsupported)
                + " (only --states bounds it)");
      }
      return;
    }
    if (noDeadlock && noInvariant && !assertions && goal == null) {
      throw usageError(
          "nothing to check: --no-deadlock and --no-invariant disable every check"
              + " (enable another one, e.g. --assertions or --goal)");
    }
    if (goal != null) {
      try {
        parsedGoal = new EventB(goal);
        if (parsedGoal.getKind() != EvalElementType.PREDICATE) {
          throw usageError("--goal must be a predicate, not " + parsedGoal.getKind());
        }
      } catch (EvaluationException e) {
        throw usageError("invalid --goal predicate: " + e.getMessage());
      }
    }
  }

  private ParameterException usageError(String message) {
    return new ParameterException(spec.commandLine(), "Error: " + message);
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

    resolvedModelPath = modelResolver.resolve(model, machineName);
    resolvedMachineName = resolvedModelPath.getFileName().toString().replaceFirst("\\.bum$", "");
    System.out.println("Machine: " + resolvedMachineName);
    StateSpace stateSpace = api.get().eventb_load(resolvedModelPath.toString(), prefs);

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
      // The trace-save confirmation was an Animate INFO line before it moved to
      // TraceWriter; keep it visible at the same level.
      ((Logger) LoggerFactory.getLogger(TraceWriter.class)).setLevel(Level.INFO);
    }
  }

  StateSpace initAndLoadModel() {
    initLogging();
    try {
      return loadModel();
    } catch (Exception e) {
      modelResolver.cleanupTempDir();
      logger.debug("Error loading model", e);
      loadErrorMessage = "Error loading model: " + e.getMessage();
      System.err.println(loadErrorMessage);
      return null;
    }
  }

  /**
   * Loads the model and runs {@code body} with the resulting StateSpace, guaranteeing ProB shutdown
   * and temp-directory cleanup. A model that could not be loaded is an ERROR report (exit 1).
   */
  RunReport withStateSpace(Function<StateSpace, RunReport> body) {
    StateSpace stateSpace = initAndLoadModel();
    if (stateSpace == null) {
      return RunReport.of(RunReport.Status.ERROR, loadErrorMessage);
    }
    try {
      return body.apply(stateSpace);
    } finally {
      releaseStateSpace(stateSpace);
    }
  }

  /**
   * Resolves and extracts the model without starting ProB (pure XML parsing of the Rodin .bcm/.bcc
   * database and the .bpo/.bps proof files), guaranteeing temp-directory cleanup. Mirrors {@link
   * #withStateSpace}: a model that could not be extracted is an ERROR report (exit 1). The report
   * envelope carries no probVersion for such a run.
   */
  RunReport withExtractedModel(Function<EventBModel, RunReport> body) {
    initLogging();
    EventBModel extracted;
    try {
      extracted = extractModel();
    } catch (Exception e) {
      modelResolver.cleanupTempDir();
      logger.debug("Error loading model", e);
      loadErrorMessage = "Error loading model: " + e.getMessage();
      System.err.println(loadErrorMessage);
      return RunReport.of(RunReport.Status.ERROR, loadErrorMessage);
    }
    try {
      return body.apply(extracted);
    } finally {
      modelResolver.cleanupTempDir();
    }
  }

  private EventBModel extractModel() throws IOException {
    validateInput();
    resolvedModelPath = modelResolver.resolve(model, machineName);
    // The resolver always returns a component file, never a filesystem root.
    resolvedMachineName =
        Objects.requireNonNull(resolvedModelPath.getFileName())
            .toString()
            .replaceFirst("\\.(bum|buc)$", "");
    System.out.println("Machine: " + resolvedMachineName);
    return eventBFactory.get().extract(resolvedModelPath.toString()).getModel();
  }

  /** Where the resolved component file lives; its siblings are the Rodin project files. */
  Path resolvedModelPath() {
    return resolvedModelPath;
  }

  /** Single exit path for every command: records the findings and maps them to the exit code. */
  int finishRun(RunReport report) {
    lastReport = report;
    return report.exitCode();
  }

  private boolean jsonToStdout() {
    return jsonReport != null && "-".equals(jsonReport.toString());
  }

  /** The subcommand that ran, with the root's default action reported as "check". */
  private String executedCommandName(CommandLine.ParseResult parseResult) {
    while (parseResult.hasSubcommand()) {
      parseResult = parseResult.subcommand();
    }
    String name = parseResult.commandSpec().name();
    return spec.name().equals(name) ? "check" : name;
  }

  /**
   * Cross-cutting run wiring that must surround every command: report option validation, the wall
   * clock, the --json - stdout diversion (with its guaranteed restore), and report emission.
   * Emission lives here rather than in the commands so a future subcommand cannot forget it; a
   * ParameterException from parsing or from command code propagates past emission to picocli's
   * usage-error handler, so no report is ever written for usage errors.
   */
  private int executeRun(CommandLine.ParseResult parseResult) {
    Integer helpExit = CommandLine.executeHelpRequest(parseResult);
    if (helpExit != null) {
      return helpExit;
    }
    validateReportOptions();
    long startNanos = System.nanoTime();
    int exitCode;
    if (jsonToStdout()) {
      // With --json -, stdout must carry exactly one JSON document, so all human
      // output moves to stderr for this run; the report itself is printed after
      // the restore below.
      PrintStream originalOut = System.out;
      System.setOut(System.err);
      try {
        exitCode = new CommandLine.RunLast().execute(parseResult);
      } finally {
        System.setOut(originalOut);
      }
    } else {
      exitCode = new CommandLine.RunLast().execute(parseResult);
    }
    return emitReports(parseResult, exitCode, (System.nanoTime() - startNanos) / 1_000_000L);
  }

  private int emitReports(CommandLine.ParseResult parseResult, int exitCode, long durationMs) {
    if (jsonReport == null && junitReport == null) {
      return exitCode;
    }
    RunReport report = lastReport;
    if (report == null) {
      // Nothing recorded findings (e.g. the help subcommand), but a report was
      // requested: emit one that carries at least the verdict.
      report =
          RunReport.of(
              exitCode == 0
                  ? RunReport.Status.OK
                  : exitCode == 2 ? RunReport.Status.INCOMPLETE : RunReport.Status.ERROR,
              null);
    }
    String command = executedCommandName(parseResult);
    Instant timestamp = Instant.now();
    // Each report is written independently: a failed write must not cost the other
    // report, but it must fail CI even when the check itself was clean (already-
    // failing exits keep their code). JUnit goes first so the JSON document, which
    // records the exit code, is rendered after any escalation and never contradicts
    // the actual process exit.
    if (junitReport != null) {
      try {
        JUnitReportWriter.write(
            envelope(command, report, exitCode, timestamp, durationMs), junitReport);
      } catch (IOException e) {
        exitCode = reportWriteFailed(e, exitCode);
      }
    }
    if (jsonReport != null) {
      try {
        String json =
            JsonReportWriter.render(envelope(command, report, exitCode, timestamp, durationMs));
        if (jsonToStdout()) {
          // PrintStream swallows I/O errors, so probe explicitly -- a document
          // truncated by a closed pipe or a full disk must not pass as written.
          System.out.print(json);
          System.out.flush();
          if (System.out.checkError()) {
            throw new IOException("cannot write the JSON report to stdout");
          }
        } else {
          Files.writeString(jsonReport, json, StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        exitCode = reportWriteFailed(e, exitCode);
      }
    }
    return exitCode;
  }

  private RunReport.Envelope envelope(
      String command, RunReport report, int exitCode, Instant timestamp, long durationMs) {
    return new RunReport.Envelope(
        command,
        model,
        resolvedMachineName,
        probVersionString,
        toolVersion(),
        timestamp,
        durationMs,
        exitCode,
        report);
  }

  private int reportWriteFailed(IOException e, int exitCode) {
    logger.debug("Error writing report", e);
    System.err.println("Error writing report: " + e.getMessage());
    return Math.max(exitCode, 1);
  }

  /**
   * Validated centrally for every command (the report options are inherited), before the run
   * starts, so a bad report destination is a usage error and never wastes a model load. Report
   * files deliberately overwrite without --force: they are per-run telemetry that CI regenerates,
   * not derived artifacts like convert/info outputs.
   */
  private void validateReportOptions() {
    if (junitReport != null && "-".equals(junitReport.toString())) {
      throw usageError("--junit does not support '-'; only --json can write to stdout");
    }
    if (jsonReport != null && !jsonToStdout()) {
      if (junitReport != null
          && jsonReport
              .toAbsolutePath()
              .normalize()
              .equals(junitReport.toAbsolutePath().normalize())) {
        throw usageError("--json and --junit must not write to the same file: " + jsonReport);
      }
      validateReportTarget(jsonReport, "--json");
    }
    if (junitReport != null) {
      validateReportTarget(junitReport, "--junit");
    }
  }

  /** Rejects a bad report destination up front, before it can cost a model load. */
  private void validateReportTarget(Path path, String option) {
    if (Files.isDirectory(path)) {
      throw usageError(option + " target is a directory: " + path);
    }
    try {
      // force=true: reports overwrite by default, so this only ensures the parent directory.
      validateWritableOutput(path, option, true);
    } catch (IOException e) {
      throw usageError("cannot create the " + option + " parent directory: " + e.getMessage());
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
    validateCheckOptions();
    if (isLtlRun()) {
      return finishRun(runLtl());
    }
    return finishRun(withStateSpace(this::runModelCheck));
  }

  private boolean isLtlRun() {
    return ltlFormula != null || ltlFile != null;
  }

  /** Parses the LTL formula before paying for a model load. */
  private RunReport runLtl() {
    String formulaText;
    try {
      formulaText = ltlFormula != null ? ltlFormula : Files.readString(ltlFile).trim();
    } catch (MalformedInputException e) {
      // Files.readString decodes strictly; its own message is the cryptic "Input length = 1".
      String message =
          "Error reading --ltl-file: "
              + ltlFile
              + " is not valid UTF-8 text (re-save the file"
              + " as UTF-8)";
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.ERROR, "ltl", message);
    } catch (IOException e) {
      String message = "Error reading --ltl-file: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.ERROR, "ltl", message);
    }
    LTL formula;
    try {
      formula = LTL.parseEventB(formulaText);
    } catch (LtlParseException e) {
      // The check never ran, so nothing was proven.
      String message = "invalid LTL formula: " + e.getMessage();
      System.err.println("Error: " + message);
      return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "ltl", message);
    }
    return withStateSpace(stateSpace -> runLtlCheck(stateSpace, formula));
  }

  private RunReport runLtlCheck(StateSpace stateSpace, LTL formula) {
    System.out.println("LTL checking...");
    IModelCheckingResult result;
    try {
      // validateCheckOptions rejected 0 and < -1; the checker treats any negative as unbounded.
      result =
          new LTLChecker(stateSpace, formula, progress ? new ProgressPrinter() : null, states)
              .call();
    } catch (RuntimeException e) {
      // The kernel's CheckerBase re-throws mid-check failures (e.g. a probcli error)
      // after recording them; without this catch they would escape as a stack trace
      // with exit 1, the code reserved for real violations.
      logger.debug("LTL checking failed", e);
      String message = "LTL checking did not complete: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "ltl", message);
    }

    if (result instanceof LTLOk) {
      String message = "LTL formula holds (full state space explored).";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "ltl", message);
    }

    if (result instanceof LTLCounterExample) {
      String message = "the LTL formula is violated.";
      System.err.println("Error: " + message);
      Trace counterexample = ((LTLCounterExample) result).getTrace(stateSpace);
      TraceWriter.Counterexample described =
          TraceWriter.describe(stateSpace, counterexample, false);
      TraceWriter.printTrace(described);
      return withSavedTrace(
          RunReport.singleCheck(RunReport.Status.VIOLATION, "ltl", message)
              .withCounterexample(described),
          counterexample,
          stateSpace);
    }

    // LTLError or LTLNotYetFinished (e.g. the --states limit): a bounded LTL check
    // proves nothing about temporal properties, so unlike the consistency check a
    // limited run is a non-verdict, not a pass.
    String message = "LTL checking did not complete: " + result.getMessage();
    System.err.println(message);
    return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "ltl", message);
  }

  private RunReport runModelCheck(StateSpace stateSpace) {
    ModelCheckingOptions options =
        new ModelCheckingOptions()
            .searchStrategy(searchStrategy.kernelStrategy)
            .checkDeadlocks(!noDeadlock)
            .checkInvariantViolations(!noInvariant)
            .checkAssertions(assertions);
    if (states > 0) {
      options = options.stateLimit(states);
    }
    if (timeLimit > 0) {
      options = options.timeLimit(Duration.ofSeconds(timeLimit));
    }
    if (stopAtFullCoverage) {
      options = options.stopAtFullCoverage(true);
    }
    if (parsedGoal != null) {
      // Parsed and kind-checked by validateCheckOptions before the model load.
      options = options.customGoal(parsedGoal);
    }

    System.out.println("Model checking...");
    IModelCheckingResult result;
    try {
      result =
          new ConsistencyChecker(stateSpace, options, progress ? new ProgressPrinter() : null)
              .call();
    } catch (RuntimeException e) {
      // Same contract as the LTL path: a mid-check kernel failure is a non-verdict.
      logger.debug("Model checking failed", e);
      String message = "Model checking did not complete: " + e.getMessage();
      System.err.println(message);
      return incompleteConsistencyReport(message);
    }

    if (result instanceof ModelCheckOk || result instanceof ModelCheckLimitReached) {
      String message = noViolationMessage(result);
      System.out.println(message);
      printCoverage(stateSpace);
      return RunReport.of(
          RunReport.Status.OK, message, enabledChecks(RunReport.Outcome.PASSED, null));
    }

    if (result instanceof ITraceDescription) {
      // A real counterexample: an invariant violation, a reachable deadlock, or a goal hit.
      // A found goal is what the user asked for, not a model error, so no "Error:" prefix.
      String message;
      if (result instanceof ModelCheckGoalFound) {
        message = "Goal found: a reachable state satisfies the --goal predicate.";
        System.out.println(message);
      } else {
        message = result.getMessage();
        System.err.println("Error: " + message);
      }
      Trace counterexample = ((ITraceDescription) result).getTrace(stateSpace);
      TraceWriter.Counterexample described = TraceWriter.describe(stateSpace, counterexample, true);
      TraceWriter.printViolatedInvariants(described);
      TraceWriter.printTrace(described);
      return withSavedTrace(
          violationReport(result, message).withCounterexample(described),
          counterexample,
          stateSpace);
    }

    // Neither a clean result nor a counterexample: the check did not complete
    // (e.g. interrupted or errored), so nothing was proven. Report it distinctly
    // rather than as a violation, and exit 2 to keep it separate from a real one.
    String message = "Model checking did not complete: " + result.getMessage();
    System.err.println(message);
    return incompleteConsistencyReport(message);
  }

  /** Saves the counterexample when --save was given and records the written path. */
  private RunReport withSavedTrace(RunReport report, Trace counterexample, StateSpace stateSpace) {
    if (jsonTrace == null) {
      return report;
    }
    return report.withTraceFile(
        traceWriter.save(counterexample, stateSpace, jsonTrace, probVersionString).orElse(null));
  }

  /** The checks this consistency run performs, in a fixed report order. */
  private List<String> enabledCheckNames() {
    List<String> names = new ArrayList<>();
    if (!noInvariant) {
      names.add("invariant");
    }
    if (!noDeadlock) {
      names.add("deadlock");
    }
    if (assertions) {
      names.add("assertions");
    }
    if (goal != null) {
      names.add("goal");
    }
    return names;
  }

  private RunReport.Check[] enabledChecks(RunReport.Outcome outcome, String message) {
    return enabledCheckNames().stream()
        .map(name -> new RunReport.Check(name, outcome, message))
        .toArray(RunReport.Check[]::new);
  }

  private RunReport incompleteConsistencyReport(String message) {
    return RunReport.of(
        RunReport.Status.INCOMPLETE, message, enabledChecks(RunReport.Outcome.ERROR, message));
  }

  /**
   * Names the check a counterexample fired. Apart from goals, which have their own result type, the
   * kernel encodes the violated property only in the result message ("Invariant violation found.",
   * "Deadlock found.", "Assertion violation found."); unrecognized wordings (state or
   * well-definedness errors) degrade to a synthetic "consistency" check so the report stays valid.
   */
  private static String firedCheck(IModelCheckingResult result) {
    if (result instanceof ModelCheckGoalFound) {
      return "goal";
    }
    String message = result.getMessage();
    if (message.contains("Invariant violation")) {
      return "invariant";
    }
    if (message.contains("Deadlock")) {
      return "deadlock";
    }
    if (message.contains("Assertion violation")) {
      return "assertions";
    }
    return "consistency";
  }

  /**
   * The fired check fails with the verdict message; the other enabled checks are skipped, not
   * passed -- the search stopped at the first violation, so nothing was proven about them.
   */
  private RunReport violationReport(IModelCheckingResult result, String message) {
    String fired = firedCheck(result);
    List<String> names = enabledCheckNames();
    List<RunReport.Check> checks = new ArrayList<>();
    for (String name : names) {
      checks.add(
          name.equals(fired)
              ? new RunReport.Check(name, RunReport.Outcome.FAILED, message)
              : new RunReport.Check(
                  name, RunReport.Outcome.SKIPPED, "search stopped at the first violation"));
    }
    if (!names.contains(fired)) {
      checks.add(new RunReport.Check(fired, RunReport.Outcome.FAILED, message));
    }
    return RunReport.of(RunReport.Status.VIOLATION, message, checks);
  }

  /**
   * The success wording must distinguish a genuinely exhaustive run from one stopped by a bound, so
   * CI logs never overstate what was proven. The checker result carries which stop condition fired,
   * so derive the caveat from it instead of guessing from the requested options.
   */
  private String noViolationMessage(IModelCheckingResult result) {
    String noViolation = "No " + checkedProperties() + " found ";
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

  /**
   * Prints processed/total state counts to stderr, throttled to roughly one line per second. The
   * final line is always emitted so even sub-second runs show what was explored. Progress goes to
   * stderr to keep stdout parseable.
   */
  private static final class ProgressPrinter implements IModelCheckListener {
    private static final long INTERVAL_NANOS = 1_000_000_000L;
    private long lastPrintedAt = System.nanoTime();

    @Override
    public void updateStats(
        String jobId, long timeElapsed, IModelCheckingResult result, StateSpaceStats stats) {
      long now = System.nanoTime();
      if (now - lastPrintedAt < INTERVAL_NANOS) {
        return;
      }
      lastPrintedAt = now;
      print(timeElapsed, stats);
    }

    @Override
    public void isFinished(
        String jobId, long timeElapsed, IModelCheckingResult result, StateSpaceStats stats) {
      // The LTL checker (and some error paths) finish without statistics; still
      // emit a final line so --progress is never completely silent.
      if (stats == null) {
        System.err.printf("Progress: finished after %d ms%n", timeElapsed);
        return;
      }
      print(timeElapsed, stats);
    }

    private static void print(long timeElapsed, StateSpaceStats stats) {
      if (stats == null) {
        return;
      }
      System.err.printf(
          "Progress: %d/%d states processed, %d transitions, %d ms%n",
          stats.getNrProcessedNodes(),
          stats.getNrTotalNodes(),
          stats.getNrTotalTransitions(),
          timeElapsed);
    }
  }

  /** Names exactly the properties this run checked, so the verdict never overclaims. */
  private String checkedProperties() {
    List<String> properties = new ArrayList<>();
    if (!noInvariant) {
      properties.add("invariant violation");
    }
    if (!noDeadlock) {
      properties.add("deadlock");
    }
    if (assertions) {
      properties.add("assertion violation");
    }
    if (goal != null) {
      properties.add("goal state");
    }
    return String.join(" or ", properties);
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
    CommandLine commandLine = new CommandLine(Animate.class, new LazyGuiceFactory());
    Animate root = commandLine.getCommand();
    commandLine.setExecutionStrategy(root::executeRun);
    return commandLine;
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
