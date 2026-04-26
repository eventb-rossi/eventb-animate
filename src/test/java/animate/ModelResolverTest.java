package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ModelResolverTest {

  @Test
  public void testDuplicateMachineNamesInDirectoryFail() throws Exception {
    Path root = Files.createTempDirectory("animate-duplicates-dir-");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root);
      assertTrue(error.getMessage().contains("Duplicate machine names"));
      assertTrue(error.getMessage().contains("Dup.bum"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testDuplicateMachineNamesInZipFail() throws Exception {
    Path root = Files.createTempDirectory("animate-duplicates-zip-");
    Path zipFile = Files.createTempFile("animate-duplicates-", ".zip");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");
      createZip(root, zipFile);

      IOException error = expectResolveFailure(zipFile);
      assertTrue(error.getMessage().contains("Duplicate machine names"));
      assertTrue(error.getMessage().contains("Dup.bum"));
    } finally {
      Files.deleteIfExists(zipFile);
      deleteRecursively(root);
    }
  }

  @Test
  public void testMaliciousXmlMachineFailsToParse() throws Exception {
    Path root = Files.createTempDirectory("animate-malicious-xml-");
    try {
      writeMachine(
          root.resolve("M0.bum"),
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<!DOCTYPE machine [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
              + "<org.eventb.core.machineFile>&xxe;</org.eventb.core.machineFile>\n");
      writeMachine(
          root.resolve("M1.bum"),
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<org.eventb.core.machineFile>\n"
              + "  <org.eventb.core.refinesMachine org.eventb.core.target=\"M0\"/>\n"
              + "</org.eventb.core.machineFile>\n");

      IOException error = expectResolveFailure(root);
      assertTrue(error.getMessage().contains("Failed to parse .bum file"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testSelectMachineByNameIgnoresUnrelatedDuplicates() throws Exception {
    Path root = Files.createTempDirectory("animate-machine-select-dir-");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");
      Path chosen = root.resolve("chosen/Leaf.bum");
      writeMachine(chosen, "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, "Leaf");

      assertEquals(
          "Requested machine should resolve even with unrelated duplicates", chosen, resolved);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testSelectMachineByNameFailsWhenRequestedMachineIsDuplicated() throws Exception {
    Path root = Files.createTempDirectory("animate-machine-ambiguous-dir-");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root, "Dup");
      assertTrue(error.getMessage().contains("Machine 'Dup' is ambiguous"));
      assertTrue(error.getMessage().contains("first/Dup.bum"));
      assertTrue(error.getMessage().contains("second/Dup.bum"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testSelectMachineByNameFromZipIgnoresUnrelatedDuplicates() throws Exception {
    Path root = Files.createTempDirectory("animate-machine-select-zip-");
    Path zipFile = Files.createTempFile("animate-machine-select-", ".zip");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("chosen/Leaf.bum"), "<org.eventb.core.machineFile/>");
      createZip(root, zipFile);

      Path resolved = new ModelResolver().resolve(zipFile, "Leaf");

      assertEquals("Leaf.bum", resolved.getFileName().toString());
    } finally {
      Files.deleteIfExists(zipFile);
      deleteRecursively(root);
    }
  }

  private static IOException expectResolveFailure(Path modelPath) throws IOException {
    return expectResolveFailure(modelPath, null);
  }

  private static IOException expectResolveFailure(Path modelPath, String machineName)
      throws IOException {
    try {
      new ModelResolver().resolve(modelPath, machineName);
      fail("Expected model resolution to fail");
      return null;
    } catch (IOException e) {
      return e;
    }
  }

  private static void writeMachine(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  private static void createZip(Path sourceDir, Path zipFile) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      Files.walk(sourceDir)
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                try {
                  output.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString()));
                  Files.copy(file, output);
                  output.closeEntry();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var stream = Files.walk(root)) {
      stream.sorted((left, right) -> right.compareTo(left)).forEach(ModelResolverTest::deletePath);
    }
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
