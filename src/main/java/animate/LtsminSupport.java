package animate;

import com.google.common.base.Throwables;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import de.prob.animator.CommandInterruptedException;
import de.prob.check.CheckInterrupted;
import de.prob.check.IModelCheckingResult;
import de.prob.check.LTSminModelChecker;
import de.prob.check.LTSminModelCheckingOptions;
import de.prob.check.ModelCheckOk;
import de.prob.statespace.ITraceDescription;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The small compatibility layer between the command-line contract and ProB's LTSmin API.
 *
 * <p>ProB owns the actual PINS/LTSmin protocol and replays sequential counterexamples into its
 * state space. This class only selects the API options, locates the external executables, and
 * normalizes the one known symbolic-backend failure where LTSmin found a counterexample but could
 * not write a replayable trace.
 */
final class LtsminSupport {

  private static final String PRINT_TRACE = "ltsmin-printtrace";
  private static final String SYMBOLIC_EXECUTABLE = "prob2lts-sym";
  private static final String REAL_SYMBOLIC_EXECUTABLE = SYMBOLIC_EXECUTABLE + ".real";
  private static final String EMPTY_SYMBOLIC_TRACE = "LTSmin trace file is empty";
  private static final String SYMBOLIC_WRAPPER =
      """
      #!/bin/sh
      if [ "$#" -lt 5 ] || [ "$4" != "--trace" ]; then
        echo "Unexpected ProB symbolic LTSmin command line" >&2
        exit 2
      fi
      endpoint=$1
      stats=$2
      when=$3
      shift 5
      exec "$(dirname "$0")/prob2lts-sym.real" "$endpoint" "$stats" "$when" "$@"
      """;
  private static final Logger LOGGER = LoggerFactory.getLogger(LtsminSupport.class);

  private LtsminSupport() {}

  enum Verdict {
    OK,
    VIOLATION,
    INCOMPLETE,
    INTERRUPTED
  }

  record Result(Verdict verdict, String detail, Trace trace) {
    static Result ok() {
      return new Result(Verdict.OK, null, null);
    }

    static Result violation(Trace trace) {
      return new Result(Verdict.VIOLATION, null, trace);
    }

    static Result incomplete(String detail) {
      return new Result(Verdict.INCOMPLETE, detail, null);
    }

    static Result interrupted(String detail) {
      return new Result(Verdict.INTERRUPTED, detail, null);
    }
  }

  record Discovery(Path directory, String error) {
    static Discovery found(Path directory) {
      return new Discovery(directory.toAbsolutePath().normalize(), null);
    }

    static Discovery missing(String error) {
      return new Discovery(null, error);
    }

    boolean available() {
      return directory != null;
    }
  }

  /**
   * The tool directory handed to ProB. Symbolic checks use a temporary compatibility directory;
   * sequential checks use the discovered installation directly.
   */
  static final class ToolDirectory implements AutoCloseable {
    private final Path directory;
    private final boolean temporary;

    private ToolDirectory(Path directory, boolean temporary) {
      this.directory = directory;
      this.temporary = temporary;
    }

    Path directory() {
      return directory;
    }

    @Override
    public void close() {
      if (!temporary) {
        return;
      }
      try {
        MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException e) {
        LOGGER.warn("Could not remove temporary LTSmin compatibility directory {}", directory, e);
      }
    }
  }

  /**
   * Runs exactly one LTSmin property check. Combined-check orchestration stays in {@link Animate}.
   */
  static Result check(
      StateSpace stateSpace,
      Animate.CheckBackend backend,
      Animate.CheckProperty property,
      boolean por) {
    LTSminModelCheckingOptions options =
        LTSminModelCheckingOptions.DEFAULT
            .backend(backend.kernelBackend())
            .checkInvariantViolations(property == Animate.CheckProperty.INVARIANT)
            .checkDeadlocks(property == Animate.CheckProperty.DEADLOCK)
            .partialOrderReduction(por);

    IModelCheckingResult result;
    try {
      result = new LTSminModelChecker(stateSpace, options).call();
    } catch (RuntimeException e) {
      // prob2lts-sym deliberately ignores --trace. On exit 1 ProB nevertheless invokes
      // ltsmin-printtrace and tries to replay the resulting empty CSV, so the API throws instead
      // of returning counter_example_found. In ProB's control flow this exact error is reachable
      // only after LTSmin reported a counterexample.
      if (!backend.replayableTrace() && causedByEmptySymbolicTrace(e)) {
        return Result.violation(null);
      }
      LOGGER.debug("LTSmin model checking failed", e);
      if (causedBy(e, CommandInterruptedException.class)) {
        return Result.interrupted(message(e));
      }
      return Result.incomplete(message(e));
    }

    if (result instanceof ModelCheckOk) {
      return Result.ok();
    }
    if (result instanceof ITraceDescription traceDescription) {
      try {
        return Result.violation(traceDescription.getTrace(stateSpace));
      } catch (RuntimeException e) {
        // The model-checking verdict is already definite; retain it even if ProB cannot
        // materialize the optional replay trace used for diagnostics and --save/--eval.
        LOGGER.debug("Could not reconstruct the LTSmin counterexample trace", e);
        return Result.violation(null);
      }
    }
    if (result instanceof CheckInterrupted) {
      return Result.interrupted(result.getMessage());
    }
    return Result.incomplete(result == null ? "no result returned" : result.getMessage());
  }

  /**
   * Resolves the directory ProB should use for its LTSmin preference. A configured value wins;
   * otherwise every PATH entry is checked for the selected backend and the trace printer.
   */
  static Discovery discover(
      Animate.CheckBackend backend, String configuredDirectory, String pathEnvironment) {
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      return Discovery.missing("not available on Windows");
    }

    if (configuredDirectory != null) {
      if (configuredDirectory.isBlank()) {
        return Discovery.missing("-p LTSMIN directory must not be empty");
      }
      try {
        Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        return validateDirectory(backend, directory);
      } catch (InvalidPathException e) {
        return Discovery.missing("invalid -p LTSMIN directory: " + e.getMessage());
      }
    }

    for (Path directory : pathEntries(pathEnvironment)) {
      if (hasTools(backend, directory)) {
        return Discovery.found(directory);
      }
    }
    return Discovery.missing(
        "could not find "
            + backend.executable()
            + " and "
            + PRINT_TRACE
            + " in one PATH directory; install LTSmin or set -p LTSMIN=/absolute/path");
  }

  /**
   * Prepares the directory ProB will execute. The symbolic LTSmin backend does not produce traces,
   * but ProB 1.15.1 still supplies {@code --trace}; with LTSmin 3.0.2 a counterexample then closes
   * the client without terminating the PINS session, leaving ProB blocked. The wrapper removes that
   * unsupported argument so ProB receives the exit-1 verdict normally.
   */
  static ToolDirectory prepareTools(Animate.CheckBackend backend, Path sourceDirectory)
      throws IOException {
    if (backend.replayableTrace()) {
      return new ToolDirectory(sourceDirectory, false);
    }

    Path directory = Files.createTempDirectory("eventb-animate-ltsmin-");
    try {
      Files.createSymbolicLink(
          directory.resolve(REAL_SYMBOLIC_EXECUTABLE),
          sourceDirectory.resolve(SYMBOLIC_EXECUTABLE));
      Files.createSymbolicLink(
          directory.resolve(PRINT_TRACE), sourceDirectory.resolve(PRINT_TRACE));

      Path wrapper = directory.resolve(SYMBOLIC_EXECUTABLE);
      Files.writeString(wrapper, SYMBOLIC_WRAPPER);
      if (!wrapper.toFile().setExecutable(true, true)) {
        throw new IOException("could not make symbolic LTSmin compatibility wrapper executable");
      }
      return new ToolDirectory(directory, true);
    } catch (IOException e) {
      try {
        MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException cleanupError) {
        e.addSuppressed(cleanupError);
      }
      throw e;
    }
  }

  private static Discovery validateDirectory(Animate.CheckBackend backend, Path directory) {
    if (hasTools(backend, directory)) {
      return Discovery.found(directory);
    }
    return Discovery.missing(
        "-p LTSMIN directory does not contain executable "
            + backend.executable()
            + " and "
            + PRINT_TRACE
            + ": "
            + directory);
  }

  private static boolean hasTools(Animate.CheckBackend backend, Path directory) {
    return isExecutable(directory.resolve(backend.executable()))
        && isExecutable(directory.resolve(PRINT_TRACE));
  }

  private static boolean isExecutable(Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }

  static List<Path> pathEntries(String pathEnvironment) {
    if (pathEnvironment == null || pathEnvironment.isBlank()) {
      return List.of();
    }
    List<Path> paths = new ArrayList<>();
    for (String entry : pathEnvironment.split(java.io.File.pathSeparator, -1)) {
      // Do not honor legacy empty-component-as-current-directory semantics: PATH is inherited
      // across a trust boundary, and silently executing a checkout-local tool is unsafe.
      if (entry.isEmpty()) {
        continue;
      }
      try {
        paths.add(Path.of(entry).toAbsolutePath().normalize());
      } catch (InvalidPathException ignored) {
        // One malformed PATH entry must not hide a valid LTSmin installation later in PATH.
      }
    }
    return paths;
  }

  private static boolean causedByEmptySymbolicTrace(Throwable error) {
    for (Throwable cause : Throwables.getCausalChain(error)) {
      if (cause.getMessage() != null && cause.getMessage().contains(EMPTY_SYMBOLIC_TRACE)) {
        return true;
      }
    }
    return false;
  }

  private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
    return Throwables.getCausalChain(error).stream().anyMatch(type::isInstance);
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
    return message;
  }
}
