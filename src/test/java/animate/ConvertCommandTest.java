package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ConvertCommandTest {

  private static final Path MODEL = Paths.get("src/test/resources/models/file-system/M0.bum");

  private String capturedOutput = "";

  @Test(timeout = 60000)
  public void convertsEventBMachineToClassicalB() throws Exception {
    Path outputDir = Files.createTempDirectory("animate-convert-test-");
    Path output = outputDir.resolve("M0.mch");

    int exitCode =
        executeQuietly("convert", MODEL.toString(), output.toString(), "--check", "init");

    assertEquals("Conversion should succeed:\n" + capturedOutput, 0, exitCode);
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

    int exitCode = executeQuietly(MODEL.toString(), "convert", output.toString());

    assertEquals("Existing output should fail without --force:\n" + capturedOutput, 1, exitCode);
    assertEquals("existing", Files.readString(output, StandardCharsets.UTF_8));
  }

  @Test
  public void rejectsInvalidCheckModeBeforeWritingOutput() throws Exception {
    Path outputDir = Files.createTempDirectory("animate-convert-invalid-check-");
    Path output = outputDir.resolve("M0.mch");

    int exitCode =
        executeQuietly("convert", MODEL.toString(), output.toString(), "--check", "mc:0");

    assertEquals("Invalid check mode should fail:\n" + capturedOutput, 1, exitCode);
    assertFalse("Output should not be written", Files.exists(output));
  }

  private int executeQuietly(String... args) {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try (PrintStream capturedStream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(capturedStream);
      System.setErr(capturedStream);
      return Animate.execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      capturedOutput = captured.toString(StandardCharsets.UTF_8);
    }
  }
}
