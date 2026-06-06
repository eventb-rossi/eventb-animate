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

  @Test(timeout = 30000)
  public void testAnimateFromZip() throws Exception {
    Path sourceDir = Paths.get("src/test/resources/models/base-model");
    Path zipFile = createTestZip(sourceDir);

    try {
      TestCli.Result result = TestCli.execute("--steps", "3", zipFile.toString());

      assertEquals("Exit code should be 0", 0, result.exitCode());
      assertTrue("Output should contain animation information", result.output().length() > 0);
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }

  @Test(timeout = 60000)
  public void testDirectoryWithMultipleBumFiles() {
    Path dir = Paths.get("src/test/resources/models/cars-on-bridge");

    TestCli.Result result = TestCli.execute("--steps", "3", dir.toString());

    assertEquals(
        "Exit code should be 0 (auto-selected most refined machine)", 0, result.exitCode());
    assertTrue("Output should contain animation information", result.output().length() > 0);
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
      assertEquals("Exit code should be 1 for zip with no .bum file", 1, result.exitCode());
    } finally {
      Files.deleteIfExists(zipFile);
    }
  }
}
