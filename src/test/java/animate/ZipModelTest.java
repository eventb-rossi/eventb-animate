package animate;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/** Test that the CLI can load Event-B models from .zip archives. */
public class ZipModelTest {

  private static Path createTestZip(Path sourceDir) throws IOException {
    Path zipFile = Files.createTempFile("animate-test-", ".zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      Files.walkFileTree(
          sourceDir,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              String entryName = sourceDir.getParent().relativize(file).toString();
              zos.putNextEntry(new ZipEntry(entryName));
              Files.copy(file, zos);
              zos.closeEntry();
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              String entryName = sourceDir.getParent().relativize(dir).toString();
              if (!entryName.isEmpty()) {
                zos.putNextEntry(new ZipEntry(entryName + "/"));
                zos.closeEntry();
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }
    return zipFile;
  }

  /**
   * Bundles several Rodin project directories into one archive, each under its own top-level dir.
   */
  private static Path createMultiProjectZip(Path... sourceDirs) throws IOException {
    Path zipFile = Files.createTempFile("animate-multiproject-", ".zip");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      for (Path sourceDir : sourceDirs) {
        Files.walkFileTree(
            sourceDir,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                String entryName = sourceDir.getParent().relativize(file).toString();
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
              }
            });
      }
    }
    return zipFile;
  }

  @Test(timeout = 30000)
  public void testMultiProjectArchiveRequiresMachineSelection() throws Exception {
    Path zipFile =
        createMultiProjectZip(
            Paths.get("src/test/resources/models/base-model"),
            Paths.get("src/test/resources/models/file-system"));
    try {
      TestCli.Result result = TestCli.execute(zipFile.toString());

      assertEquals(
          "Exit code should be 66 when the archive bundles multiple projects",
          66,
          result.exitCode());
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }

  @Test(timeout = 30000)
  public void testMultiProjectArchiveSelectsByProjectQualifiedMachine() throws Exception {
    Path zipFile =
        createMultiProjectZip(
            Paths.get("src/test/resources/models/base-model"),
            Paths.get("src/test/resources/models/file-system"));
    try {
      TestCli.Result result =
          TestCli.execute("--states", "100", "-m", "base-model/M1", zipFile.toString());

      TestCli.assertModelChecked(result, "The project-qualified machine");
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }

  @Test(timeout = 30000)
  public void testAnimateFromZip() throws Exception {
    Path sourceDir = Paths.get("src/test/resources/models/base-model");
    Path zipFile = createTestZip(sourceDir);

    try {
      TestCli.Result result = TestCli.execute("--states", "100", zipFile.toString());

      TestCli.assertModelChecked(result, "The model from the zip");
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }

  @Test(timeout = 60000)
  public void testDirectoryWithMultipleBumFiles() {
    Path dir = Paths.get("src/test/resources/models/cars-on-bridge");

    TestCli.Result result = TestCli.execute("--states", "100", dir.toString());

    TestCli.assertModelChecked(result, "The most refined machine");
  }

  @Test(timeout = 30000)
  public void testZipWithNoBumFile() throws Exception {
    Path zipFile = Files.createTempFile("animate-test-nobum-", ".zip");

    try {
      // Create a zip with no .bum files
      try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
        zos.putNextEntry(new ZipEntry("dummy.txt"));
        zos.write("no bum here".getBytes());
        zos.closeEntry();
      }

      TestCli.Result result = TestCli.execute(zipFile.toString());
      assertEquals("Exit code should be 66 for zip with no .bum file", 66, result.exitCode());
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }
}
