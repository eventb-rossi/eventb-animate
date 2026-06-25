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
  public void testMultipleProjectsInDirectoryRequireSelection() throws Exception {
    Path root = Files.createTempDirectory("animate-multi-project-dir-");
    try {
      // first/ and second/ are two distinct projects that happen to share a basename.
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root);
      assertTrue(error.getMessage().contains("contains 2 projects"));
      assertTrue(error.getMessage().contains("first"));
      assertTrue(error.getMessage().contains("second"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testMultipleProjectsInZipRequireSelection() throws Exception {
    Path root = Files.createTempDirectory("animate-multi-project-zip-");
    Path zipFile = Files.createTempFile("animate-multi-project-", ".zip");
    try {
      writeMachine(root.resolve("first/Dup.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("second/Dup.bum"), "<org.eventb.core.machineFile/>");
      createZip(root, zipFile);

      IOException error = expectResolveFailure(zipFile);
      assertTrue(error.getMessage().contains("contains 2 projects"));
      assertTrue(error.getMessage().contains("first"));
      assertTrue(error.getMessage().contains("second"));
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
  public void testDirectoryNamedLikeMachineIsIgnored() throws Exception {
    Path root = Files.createTempDirectory("animate-bum-dir-");
    try {
      Files.createDirectories(root.resolve("Decoy.bum"));
      Path machine = root.resolve("Real.bum");
      writeMachine(machine, "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, null);

      assertEquals("Only the regular .bum file should be selected", machine, resolved);
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
      // The hint lists the project-qualified forms the user should retry with.
      assertTrue(error.getMessage().contains("first/Dup"));
      assertTrue(error.getMessage().contains("second/Dup"));
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

  @Test
  public void testSelectMachineByProjectQualifiedName() throws Exception {
    Path root = Files.createTempDirectory("animate-qualified-dir-");
    try {
      // Two projects with colliding component basenames: the project prefix disambiguates.
      writeMachine(root.resolve("ProjA/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjA/M1.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjB/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjB/M1.bum"), "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, "ProjA/M1");

      assertEquals(
          "Project-qualified name should select that project's machine",
          root.resolve("ProjA/M1.bum"),
          resolved);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testBareMachineNameUniqueAcrossProjectsResolves() throws Exception {
    Path root = Files.createTempDirectory("animate-bare-unique-dir-");
    try {
      writeMachine(root.resolve("ProjA/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjB/Leaf.bum"), "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, "Leaf");

      assertEquals(
          "A globally-unique bare name resolves without a project prefix",
          root.resolve("ProjB/Leaf.bum"),
          resolved);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testSelectMostRefinedWithinProjectViaTrailingSlash() throws Exception {
    Path root = Files.createTempDirectory("animate-project-autoselect-");
    try {
      writeMachine(root.resolve("ProjA/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(
          root.resolve("ProjA/M1.bum"),
          "<org.eventb.core.machineFile>\n"
              + "  <org.eventb.core.refinesMachine org.eventb.core.target=\"M0\"/>\n"
              + "</org.eventb.core.machineFile>\n");
      writeMachine(root.resolve("ProjB/N0.bum"), "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, "ProjA/");

      assertEquals(
          "'-m ProjA/' should auto-select the most refined machine in ProjA",
          root.resolve("ProjA/M1.bum"),
          resolved);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testQualifiedNameOnSingleProjectDirectoryResolves() throws Exception {
    Path root = Files.createTempDirectory("animate-single-project-qualified-");
    try {
      // The directory is itself the project, so its machines sit at the root (project key "").
      writeMachine(root.resolve("M0.bum"), "<org.eventb.core.machineFile/>");

      Path resolved = new ModelResolver().resolve(root, "AnyName/M0");

      assertEquals(
          "A single-project source accepts the documented project-qualified form",
          root.resolve("M0.bum"),
          resolved);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testDuplicateMachineNamesWithinProjectFail() throws Exception {
    Path root = Files.createTempDirectory("animate-dup-within-project-");
    try {
      // One project (single top-level dir) but the same machine basename twice in subfolders.
      writeMachine(root.resolve("Proj/a/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("Proj/b/M0.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root);
      assertTrue(error.getMessage().contains("Duplicate machine names"));
      assertTrue(error.getMessage().contains("M0.bum"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testUnknownProjectFails() throws Exception {
    Path root = Files.createTempDirectory("animate-unknown-project-dir-");
    try {
      writeMachine(root.resolve("ProjA/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjB/M0.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root, "Nope/M0");
      assertTrue(error.getMessage().contains("Project 'Nope' not found"));
      assertTrue(error.getMessage().contains("ProjA"));
      assertTrue(error.getMessage().contains("ProjB"));
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  public void testMachineNotFoundWithinProjectFails() throws Exception {
    Path root = Files.createTempDirectory("animate-missing-machine-dir-");
    try {
      writeMachine(root.resolve("ProjA/M0.bum"), "<org.eventb.core.machineFile/>");
      writeMachine(root.resolve("ProjB/M0.bum"), "<org.eventb.core.machineFile/>");

      IOException error = expectResolveFailure(root, "ProjA/Nope");
      assertTrue(error.getMessage().contains("Machine 'Nope' not found"));
      assertTrue(error.getMessage().contains("project ProjA"));
    } finally {
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
