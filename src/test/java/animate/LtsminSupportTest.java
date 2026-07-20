package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** Environment-independent tests for locating the optional external LTSmin executables. */
public class LtsminSupportTest {

  @Test
  public void discoversSelectedBackendAndTracePrinterOnPath() throws Exception {
    Path incomplete = Files.createTempDirectory("ltsmin-incomplete-");
    Path complete = Files.createTempDirectory("ltsmin-complete-");
    try {
      executable(incomplete, "prob2lts-seq");
      executable(complete, "prob2lts-seq");
      executable(complete, "ltsmin-printtrace");

      String path = incomplete + java.io.File.pathSeparator + complete;
      LtsminSupport.Discovery discovery =
          LtsminSupport.discover(Animate.CheckBackend.LTSMIN_SEQUENTIAL, null, path);

      assertTrue(discovery.error(), discovery.available());
      assertEquals(complete.toAbsolutePath().normalize(), discovery.directory());
    } finally {
      deleteToolDirectory(incomplete);
      deleteToolDirectory(complete);
    }
  }

  @Test
  public void explicitDirectoryWinsAndBecomesAbsolute() throws Exception {
    Path configured = Files.createTempDirectory("ltsmin-configured-");
    Path onPath = Files.createTempDirectory("ltsmin-path-");
    try {
      executable(configured, "prob2lts-sym");
      executable(configured, "ltsmin-printtrace");
      executable(onPath, "prob2lts-sym");
      executable(onPath, "ltsmin-printtrace");

      LtsminSupport.Discovery discovery =
          LtsminSupport.discover(
              Animate.CheckBackend.LTSMIN_SYMBOLIC, configured.toString(), onPath.toString());

      assertTrue(discovery.error(), discovery.available());
      assertEquals(configured.toAbsolutePath().normalize(), discovery.directory());
    } finally {
      deleteToolDirectory(configured);
      deleteToolDirectory(onPath);
    }
  }

  @Test
  public void rejectsDirectoryWithoutTracePrinter() throws Exception {
    Path configured = Files.createTempDirectory("ltsmin-missing-");
    try {
      executable(configured, "prob2lts-seq");

      LtsminSupport.Discovery discovery =
          LtsminSupport.discover(
              Animate.CheckBackend.LTSMIN_SEQUENTIAL, configured.toString(), null);

      assertFalse(discovery.available());
      assertTrue(discovery.error().contains("ltsmin-printtrace"));
      assertTrue(discovery.error().contains(configured.toString()));
    } finally {
      deleteToolDirectory(configured);
    }
  }

  @Test
  public void rejectsEmptyExplicitDirectory() {
    LtsminSupport.Discovery discovery =
        LtsminSupport.discover(Animate.CheckBackend.LTSMIN_SEQUENTIAL, "", "/usr/bin");

    assertFalse(discovery.available());
    assertTrue(discovery.error().contains("must not be empty"));
  }

  @Test
  public void ignoresEmptyPathComponentsInsteadOfSearchingTheWorkingDirectory() throws Exception {
    Path trusted = Files.createTempDirectory("ltsmin-path-");
    try {
      String path = java.io.File.pathSeparator + trusted + java.io.File.pathSeparator;

      assertEquals(List.of(trusted.toAbsolutePath().normalize()), LtsminSupport.pathEntries(path));
    } finally {
      deleteToolDirectory(trusted);
    }
  }

  @Test
  public void symbolicCompatibilityWrapperRemovesTraceArguments() throws Exception {
    Path source = Files.createTempDirectory("ltsmin-source-");
    LtsminSupport.ToolDirectory tools = null;
    try {
      executable(source, "prob2lts-sym", "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
      executable(source, "ltsmin-printtrace");
      tools = LtsminSupport.prepareTools(Animate.CheckBackend.LTSMIN_SYMBOLIC, source);

      Path preparedDirectory = tools.directory();
      Process process =
          new ProcessBuilder(
                  preparedDirectory.resolve("prob2lts-sym").toString(),
                  "endpoint",
                  "--stats",
                  "--when",
                  "--trace",
                  "trace.gcf",
                  "--invariant=inv1",
                  "argument with spaces")
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      assertEquals(0, process.waitFor());
      assertEquals(
          List.of("endpoint", "--stats", "--when", "--invariant=inv1", "argument with spaces"),
          output.lines().toList());

      tools.close();
      tools = null;
      assertFalse(Files.exists(preparedDirectory));
    } finally {
      if (tools != null) {
        tools.close();
      }
      deleteToolDirectory(source);
    }
  }

  private static void executable(Path directory, String name) throws IOException {
    executable(directory, name, "");
  }

  private static void executable(Path directory, String name, String contents) throws IOException {
    Path executable = Files.writeString(directory.resolve(name), contents);
    assertTrue(
        "Could not make test file executable: " + executable,
        executable.toFile().setExecutable(true));
  }

  private static void deleteToolDirectory(Path directory) throws IOException {
    MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
  }
}
