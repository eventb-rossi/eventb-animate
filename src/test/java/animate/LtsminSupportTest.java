package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.IOException;
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

  private static void executable(Path directory, String name) throws IOException {
    Path executable = Files.createFile(directory.resolve(name));
    assertTrue(
        "Could not make test file executable: " + executable,
        executable.toFile().setExecutable(true));
  }

  private static void deleteToolDirectory(Path directory) throws IOException {
    MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
  }
}
