package animate;

import de.prob.cli.Installer;
import de.prob.cli.OsSpecificInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "convert",
    description = "Convert an Event-B model to a Classical B machine",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class ConvertCommand implements Callable<Integer> {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConvertCommand.class);

  @ParentCommand Animate parent;

  @Parameters(index = "0", paramLabel = "<output.mch>", description = "Classical B output file")
  Path output;

  @Option(names = "--force", description = "overwrite existing output files")
  boolean force;

  @Override
  public Integer call() {
    try {
      normalizePositionalArguments();
      Animate.validateWritableOutput(output, "Output", force);

      boolean inputIsPackage = isProBEventBPackage(parent.model);
      Path eventbPackage = inputIsPackage ? parent.model : writeEventBPackage();
      try {
        int convertExit = runConversion(eventbPackage);
        if (convertExit != 0) {
          return convertExit;
        }
      } finally {
        if (!inputIsPackage) {
          Files.deleteIfExists(eventbPackage);
        }
      }
      if (!Files.isRegularFile(output) || Files.size(output) == 0) {
        System.err.println("Error: conversion did not create a non-empty output file: " + output);
        return 1;
      }

      System.out.println("Wrote Classical B machine: " + output);
      return 0;
    } catch (IllegalArgumentException | IOException e) {
      logger.debug("Conversion failed", e);
      System.err.println("Error: " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("Error: interrupted while running ProB CLI");
      return 1;
    }
  }

  private void normalizePositionalArguments() {
    if (looksLikeInputModel(output) && !looksLikeInputModel(parent.model)) {
      Path requestedOutput = parent.model;
      parent.model = output;
      output = requestedOutput;
    }
  }

  private boolean looksLikeInputModel(Path path) {
    if (path == null) {
      return false;
    }
    // Decide by extension so argument interpretation does not depend on which
    // files happen to exist; directories (Rodin projects) carry no extension.
    String name = path.toString();
    return name.endsWith(".bum")
        || name.endsWith(".zip")
        || name.endsWith(".eventb")
        || Files.isDirectory(path);
  }

  private Path writeEventBPackage() throws IOException {
    var stateSpace = parent.initAndLoadModel();
    if (stateSpace == null) {
      throw new IOException("could not load Event-B model");
    }

    try {
      Path eventbPackage = Files.createTempFile("animate-convert-", ".eventb");
      EventBPackageWriter.write(stateSpace, eventbPackage);
      logger.info("Wrote intermediate ProB Event-B package to {}", eventbPackage);
      return eventbPackage;
    } finally {
      parent.releaseStateSpace(stateSpace);
    }
  }

  private boolean isProBEventBPackage(Path input) {
    if (input == null || !input.toString().endsWith(".eventb") || !Files.isRegularFile(input)) {
      return false;
    }
    try (var in = Files.newInputStream(input)) {
      String head = new String(in.readNBytes(256), StandardCharsets.UTF_8).stripLeading();
      return head.startsWith("package(load_event_b_project");
    } catch (IOException e) {
      logger.debug("Could not read {} to check for an Event-B package header", input, e);
      return false;
    }
  }

  // Only probcli's -ppB performs the real Event-B to Classical B translation
  // (typing predicates, events to operations); the kernel's pretty-print
  // commands produce a display-only format. The binary is bundled in the
  // prob-java jar, so this is still self-contained.
  private int runConversion(Path eventbPackage) throws IOException, InterruptedException {
    OsSpecificInfo osSpecificInfo = OsSpecificInfo.detect();
    Path proBHome = Installer.ensureInstalled(osSpecificInfo);
    Path probcli = proBHome.resolve(osSpecificInfo.getCliName());

    List<String> command =
        List.of(probcli.toString(), eventbPackage.toString(), "-ppB", output.toString());
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put("PROB_HOME", proBHome.toString());

    Process process = processBuilder.start();
    String processOutput =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      if (!processOutput.isBlank()) {
        System.err.print(processOutput);
      }
      System.err.println("ProB conversion failed (exit code " + exitCode + ")");
    } else if (parent.debug && !processOutput.isBlank()) {
      System.err.print(processOutput);
    }
    return exitCode;
  }
}
