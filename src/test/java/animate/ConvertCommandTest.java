package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ConvertCommandTest {

  private static final Path MODEL = Paths.get("src/test/resources/models/file-system/M0.bum");

  @Test(timeout = 60000)
  public void convertsEventBMachineToClassicalB() throws Exception {
    Path outputDir = Files.createTempDirectory("animate-convert-test-");
    Path output = outputDir.resolve("M0.mch");

    TestCli.Result result = TestCli.execute("convert", MODEL.toString(), output.toString());

    assertEquals("Conversion should succeed:\n" + result.output(), 0, result.exitCode());
    assertTrue("Output machine should exist", Files.isRegularFile(output));
    assertTrue("Output machine should not be empty", Files.size(output) > 0);

    String machine = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(machine.contains("MACHINE M0"));
    assertTrue(machine.contains("INITIALISATION"));
    assertTrue(machine.contains("OPERATIONS"));
  }

  @Test
  public void refusesToOverwriteOutputWithoutForce() throws Exception {
    Path output = Files.createTempFile("animate-convert-existing-", ".mch");
    Files.writeString(output, "existing", StandardCharsets.UTF_8);

    TestCli.Result result = TestCli.execute(MODEL.toString(), "convert", output.toString());

    assertEquals(
        "Existing output should fail without --force:\n" + result.output(), 66, result.exitCode());
    assertEquals("existing", Files.readString(output, StandardCharsets.UTF_8));
  }

  @Test
  public void derivesTheDefaultOutputNameFromTheModel() {
    // model, -m selection, expected output name (null: nothing to derive a name from)
    String[][] cases = {
      {"M0.bum", null, "M0.mch"},
      {"file-system/C0.buc", null, "C0.mch"},
      {"archive/model.zip", null, "model.mch"},
      {"package.eventb", null, "package.mch"},
      {"M0", null, "M0.mch"},
      // A Rodin project is a directory: its name carries no extension to strip.
      {"src/test/resources/models/file-system", null, "file-system.mch"},
      // -m names the machine to convert, so it wins over the archive or project name.
      {"model.zip", "M2", "M2.mch"},
      {"model.zip", "file-system/M2", "M2.mch"},
      // -m <project>/ auto-selects, so it names no machine to fall back on.
      {"model.zip", "file-system/", "model.mch"},
      {"/", null, null},
    };
    for (String[] testCase : cases) {
      assertEquals(
          testCase[0] + " -m " + testCase[1],
          testCase[2],
          ConvertCommand.defaultOutputName(Paths.get(testCase[0]), testCase[1]));
    }
  }

  @Test
  public void derivesTheOutputPathWhenOnlyTheModelIsGiven() throws Exception {
    // The derived name is relative, so it resolves against the working directory rather than the
    // model's own; occupying it shows which path the run picked, before any model is loaded.
    Path derived = Paths.get("M0.mch");
    Files.writeString(derived, "existing", StandardCharsets.UTF_8);
    try {
      String[][] forms = {{"convert", MODEL.toString()}, {MODEL.toString(), "convert"}};
      for (String[] argv : forms) {
        TestCli.Result result = TestCli.execute(argv);

        assertEquals(
            "The derived output should hit the overwrite guard:\n" + result.output(),
            66,
            result.exitCode());
        assertTrue(
            "The error should name the derived output:\n" + result.output(),
            result.output().contains(derived.toString()));
      }
    } finally {
      Files.deleteIfExists(derived);
    }
  }

  @Test
  public void missingModelIsAUsageError() {
    String[][] forms = {{"convert"}, {"convert", "out.mch"}};
    for (String[] argv : forms) {
      TestCli.Result result = TestCli.execute(argv);

      assertEquals(
          "convert without a model is a usage error:\n" + result.output(), 2, result.exitCode());
      assertTrue(
          "The usage error should name the missing parameter:\n" + result.output(),
          result.output().contains("<model>"));
    }
  }

  /** An unloadable input is the user's to fix (66), never probcli's own exit code. */
  @Test(timeout = 60000)
  public void refusesAnUnloadableInputAsAnInputError() throws Exception {
    Path directory = Files.createTempDirectory("animate-convert-failure-");
    Path input = directory.resolve("broken.eventb");
    Path output = directory.resolve("out.mch");
    Files.writeString(input, "not a prolog package", StandardCharsets.UTF_8);
    try {
      TestCli.Result result = TestCli.execute("convert", input.toString(), output.toString());

      assertEquals(
          "An unloadable conversion input is not a violation:\n" + result.output(),
          66,
          result.exitCode());
    } finally {
      MoreFiles.deleteRecursively(directory, RecursiveDeleteOption.ALLOW_INSECURE);
    }
  }
}
