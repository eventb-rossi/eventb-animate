package animate;

import de.prob.model.eventb.EventBMachine;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import de.prob.statespace.Transition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "testgen",
    description =
        "Generate operation-coverage test traces without exploring the state space: for each"
            + " operation, the constraint solver searches for a short feasible trace that executes"
            + " it. Covered operations are written as ProB json traces (replayable with 'replay');"
            + " uncovered and infeasible (dead) operations are reported",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class TestgenCommand implements Callable<Integer> {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TestgenCommand.class);

  @ParentCommand Animate parent;
  @Spec CommandSpec spec;

  @Option(
      names = "--out",
      paramLabel = "dir",
      description =
          "directory to write one json trace per covered operation (created if missing);"
              + " omit to only report coverage without saving traces")
  Path out;

  @Option(
      names = "--operations",
      split = ",",
      paramLabel = "op",
      description =
          "restrict test generation to these target operations (comma-separated or repeated;"
              + " default: every operation of the machine)")
  List<String> operations;

  @Option(
      names = "--depth",
      paramLabel = "N",
      description =
          "maximum number of operation steps in a generated trace, i.e. how far the search extends"
              + " prefixes to enable an operation (default: ${DEFAULT-VALUE})")
  int depth = 5;

  @Option(
      names = "--final-ops",
      split = ",",
      paramLabel = "op",
      description =
          "operations after which a trace is considered complete and is not extended further"
              + " (comma-separated or repeated)")
  List<String> finalOps;

  @Option(
      names = "--fail-on-uncovered",
      description =
          "exit 2 (incomplete) when any target operation has no witness trace within the depth"
              + " bound (advisory otherwise)")
  boolean failOnUncovered;

  @Option(
      names = "--fail-on-infeasible",
      description =
          "exit 1 (violation) when any target operation is statically infeasible -- a dead"
              + " operation whose guard is unsatisfiable under the invariant (advisory otherwise)")
  boolean failOnInfeasible;

  // The kernel command's own default is 200 ms, short enough to spuriously miss coverage on
  // non-trivial models; a couple of seconds is a better default for a batch tool where each attempt
  // is one bounded solver call, and complex operations may need more.
  @Option(
      names = "--timeout",
      paramLabel = "ms",
      description =
          "per-attempt solver time bound in milliseconds; raise it when operations are reported"
              + " uncovered on complex models (default: ${DEFAULT-VALUE})")
  int timeout = 2000;

  @Option(names = "--force", description = "overwrite existing trace files in --out")
  boolean force;

  @Override
  public Integer call() {
    validateTestgenOptions();
    return parent.finishRun(parent.withStateSpace(this::runTestgen));
  }

  /** Usage errors, raised before the model load like the root check's validation. */
  private void validateTestgenOptions() {
    if (depth < 1) {
      throw usageError("--depth must be at least 1, got: " + depth);
    }
    if (timeout < 1) {
      throw usageError("--timeout must be at least 1 millisecond, got: " + timeout);
    }
    if (force && out == null) {
      throw usageError("--force only applies when writing traces with --out");
    }
    if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
      throw usageError("--out is not a directory: " + out);
    }
  }

  private ParameterException usageError(String message) {
    return Animate.usageError(spec, message);
  }

  private RunReport runTestgen(StateSpace stateSpace) {
    if (!(stateSpace.getMainComponent() instanceof EventBMachine machine)) {
      String message = "testgen requires a machine, but the loaded component is a context";
      System.err.println("Error: " + message);
      return RunReport.singleCheck(RunReport.Status.ERROR, "coverage", message);
    }

    List<String> allOps = Animate.operationNames(machine);

    RunReport unknown = rejectUnknownOperations(allOps);
    if (unknown != null) {
      return unknown;
    }

    // Deduplicate --operations so a repeated name does not inflate the coverage denominator or emit
    // two identical coverage/<op> checks; allOps is already distinct.
    List<String> targets =
        operations != null ? new ArrayList<>(new LinkedHashSet<>(operations)) : allOps;
    if (targets.isEmpty()) {
      String message =
          "No operations to cover: the machine only has INITIALISATION, which test generation"
              + " never targets.";
      System.out.println(message);
      return RunReport.singleCheck(RunReport.Status.OK, "coverage", message);
    }

    Set<String> finalSet = finalOps == null ? Set.of() : new LinkedHashSet<>(finalOps);

    // Dead operations are classified up front, guarded: a feasibility failure is not fatal here.
    // Every operation is then searched, and dead ones come back uncovered instead of infeasible.
    Set<String> infeasible;
    try {
      infeasible =
          new LinkedHashSet<>(Feasibility.analyse(stateSpace, machine, allOps).infeasible());
    } catch (RuntimeException e) {
      logger.debug("Feasibility analysis failed; treating every operation as feasible", e);
      infeasible = Set.of();
    }

    System.out.println("Operation-coverage test generation (depth " + depth + ")...");
    TestgenGenerator.Result result =
        new TestgenGenerator(stateSpace, allOps, targets, infeasible, finalSet, depth, timeout)
            .generate();

    return report(stateSpace, machine, targets, result);
  }

  /**
   * An unknown operation in {@code --operations}/{@code --final-ops} is an input error, not a usage
   * error: whether a name exists depends on the model, and the requested reports must still be
   * written. Mirrors the cbc command's handling of {@code --events}.
   */
  private RunReport rejectUnknownOperations(List<String> allOps) {
    List<String> requested = new ArrayList<>();
    if (operations != null) {
      requested.addAll(operations);
    }
    if (finalOps != null) {
      requested.addAll(finalOps);
    }
    List<String> unknown = new ArrayList<>();
    for (String op : requested) {
      if (!allOps.contains(op) && !unknown.contains(op)) {
        unknown.add(op);
      }
    }
    if (unknown.isEmpty()) {
      return null;
    }
    String message =
        "unknown operation(s): "
            + String.join(", ", unknown)
            + " (the machine's operations are: "
            + String.join(", ", allOps)
            + ")";
    System.err.println("Error: " + message);
    return RunReport.singleCheck(RunReport.Status.ERROR, "coverage", message);
  }

  private RunReport report(
      StateSpace stateSpace,
      EventBMachine machine,
      List<String> targets,
      TestgenGenerator.Result result) {
    boolean writeFailed = false;
    int written = 0;
    if (out != null && !result.covered().isEmpty()) {
      Map<String, Path> paths = new LinkedHashMap<>();
      Set<String> usedNames = new LinkedHashSet<>();
      for (String op : result.covered().keySet()) {
        // Distinct operations whose sanitized names collide must not share a file, or one witness
        // would silently overwrite the other; disambiguate so every covered operation is preserved.
        Path path = out.resolve(uniqueTraceFileName(machine.getName(), op, usedNames));
        paths.put(op, path);
        try {
          // Reuse the shared overwrite policy (refuse an existing file without --force) and let it
          // create the --out directory as the file's parent along the way.
          Animate.validateWritableOutput(path, "Trace file", force);
        } catch (IOException e) {
          logger.debug("Cannot write trace file {}", path, e);
          System.err.println("Error: " + e.getMessage());
          return RunReport.singleCheck(RunReport.Status.ERROR, "coverage", e.getMessage());
        }
      }
      for (Map.Entry<String, Trace> entry : result.covered().entrySet()) {
        Path path = paths.get(entry.getKey());
        try {
          if (parent.saveTrace(entry.getValue(), stateSpace, path).isPresent()) {
            written++;
          } else {
            writeFailed = true;
          }
        } catch (RuntimeException e) {
          logger.debug("Could not serialise trace for {}", entry.getKey(), e);
          System.err.println("Error saving trace " + path + ": " + e.getMessage());
          writeFailed = true;
        }
      }
    }

    printSummary(targets, result, written);

    List<RunReport.Check> checks = buildChecks(targets, result);
    RunReport.Status status;
    String message;
    if (writeFailed) {
      status = RunReport.Status.ERROR;
      message = "failed to write one or more test traces";
      System.err.println("Error: " + message);
    } else if (failOnInfeasible && !result.infeasible().isEmpty()) {
      status = RunReport.Status.VIOLATION;
      message =
          result.infeasible().size()
              + " infeasible (dead) target operation"
              + Animate.plural(result.infeasible().size())
              + ": "
              + String.join(", ", result.infeasible());
      System.err.println("Error: " + message);
    } else if (result.interrupted()) {
      status = RunReport.Status.INCOMPLETE;
      message = "test generation was interrupted before completing.";
      System.err.println(message);
    } else if (failOnUncovered && !result.uncovered().isEmpty()) {
      status = RunReport.Status.INCOMPLETE;
      message =
          result.uncovered().size()
              + " uncovered target operation"
              + Animate.plural(result.uncovered().size())
              + " within depth "
              + depth
              + ": "
              + String.join(", ", result.uncovered());
      System.err.println(message);
    } else {
      status = RunReport.Status.OK;
      message = summaryMessage(result, targets.size());
    }
    return RunReport.of(status, message, checks);
  }

  /**
   * One {@code coverage/<op>} check per target, so JUnit/JSON reports carry per-operation results.
   */
  private List<RunReport.Check> buildChecks(List<String> targets, TestgenGenerator.Result result) {
    List<RunReport.Check> checks = new ArrayList<>(targets.size());
    for (String op : targets) {
      String name = "coverage/" + op;
      if (result.covered().containsKey(op)) {
        int steps = operationSteps(result.covered().get(op));
        checks.add(
            new RunReport.Check(
                name, RunReport.Outcome.PASSED, "covered by a " + steps + "-step trace"));
      } else if (result.infeasible().contains(op)) {
        checks.add(
            failOnInfeasible
                ? new RunReport.Check(
                    name, RunReport.Outcome.FAILED, "statically infeasible (dead operation)")
                : new RunReport.Check(
                    name,
                    RunReport.Outcome.SKIPPED,
                    "statically infeasible (dead operation); advisory"));
      } else {
        checks.add(
            failOnUncovered
                ? new RunReport.Check(
                    name, RunReport.Outcome.ERROR, "no witness within depth " + depth)
                : new RunReport.Check(
                    name,
                    RunReport.Outcome.SKIPPED,
                    "no witness within depth " + depth + "; advisory"));
      }
    }
    return checks;
  }

  private void printSummary(List<String> targets, TestgenGenerator.Result result, int written) {
    int covered = result.covered().size();
    System.out.println(coveredHeadline(covered, targets.size()) + (covered > 0 ? ":" : "."));
    for (String op : targets) {
      Trace trace = result.covered().get(op);
      if (trace != null) {
        int steps = operationSteps(trace);
        System.out.println("\t - " + op + " (" + steps + " step" + Animate.plural(steps) + ")");
      }
    }
    if (!result.uncovered().isEmpty()) {
      System.out.println(
          "Uncovered (no witness within depth "
              + depth
              + "):\n\t - "
              + String.join("\n\t - ", result.uncovered()));
    }
    if (!result.infeasible().isEmpty()) {
      System.out.println(
          "Infeasible (dead) operations:\n\t - " + String.join("\n\t - ", result.infeasible()));
    }
    if (out != null) {
      System.out.println(
          "Wrote " + written + " test trace" + Animate.plural(written) + " to " + out);
    }
  }

  private static String summaryMessage(TestgenGenerator.Result result, int targetCount) {
    StringBuilder message =
        new StringBuilder(coveredHeadline(result.covered().size(), targetCount));
    List<String> notes = new ArrayList<>();
    if (!result.uncovered().isEmpty()) {
      notes.add(result.uncovered().size() + " uncovered");
    }
    if (!result.infeasible().isEmpty()) {
      notes.add(result.infeasible().size() + " infeasible");
    }
    if (!notes.isEmpty()) {
      message.append(" (").append(String.join(", ", notes)).append("; advisory)");
    }
    return message.append('.').toString();
  }

  /**
   * Operation steps only: the auto-inserted {@code $initialise_machine}/{@code $setup_constants}
   * transitions carry a leading {@code $} and are not counted.
   */
  private static int operationSteps(Trace trace) {
    int steps = 0;
    for (Transition transition : trace.getTransitionList()) {
      if (!transition.getName().startsWith("$")) {
        steps++;
      }
    }
    return steps;
  }

  /**
   * A filesystem-safe {@code <machine>_<op>.json} name (any unusual character becomes '_'), made
   * unique against {@code used} by appending a counter so two operations that sanitize alike still
   * map to distinct files. {@code used} accumulates the names already handed out this run.
   */
  private static String uniqueTraceFileName(String machine, String op, Set<String> used) {
    String base = (machine + "_" + op).replaceAll("[^A-Za-z0-9_.-]", "_");
    String candidate = base + ".json";
    for (int n = 2; !used.add(candidate); n++) {
      candidate = base + "_" + n + ".json";
    }
    return candidate;
  }

  /**
   * The shared "Covered X/Y target operation(s)" phrasing for the console and the report message.
   */
  private static String coveredHeadline(int covered, int targetCount) {
    return "Covered "
        + covered
        + "/"
        + targetCount
        + " target operation"
        + Animate.plural(targetCount);
  }
}
