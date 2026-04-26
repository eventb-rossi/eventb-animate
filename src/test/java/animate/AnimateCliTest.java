package animate;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** Test the Animate CLI tool with various Event-B models. */
@RunWith(Parameterized.class)
public class AnimateCliTest {

  private final File modelFile;
  private final String modelName;

  public AnimateCliTest(String modelName, File modelFile) {
    this.modelName = modelName;
    this.modelFile = modelFile;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> getModels() {
    return TestModels.mainModels();
  }

  @Test(timeout = 30000) // 30 second timeout per test
  public void testAnimateWithSteps() throws Exception {
    System.out.println("Testing CLI animation for: " + modelName);

    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;

    try {
      System.setOut(new PrintStream(outContent));

      String[] args = {"--steps", "5", modelFile.getAbsolutePath()};

      int exitCode = Animate.execute(args);

      String output = outContent.toString();
      assertTrue("Output should contain animation information", output.length() > 0);
      assertEquals("Exit code should be 0", 0, exitCode);

      System.setOut(originalOut);
      System.out.println("  ✓ CLI animation completed");
    } catch (Exception e) {
      System.setOut(originalOut);
      System.err.println("  ✗ Animation failed: " + e.getMessage());
      throw e;
    }
  }

  @Test(timeout = 30000)
  public void testAnimateWithInvariants() throws Exception {
    System.out.println("Testing CLI with invariant checking for: " + modelName);

    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;

    try {
      System.setOut(new PrintStream(outContent));

      String[] args = {"--steps", "5", "--invariants", modelFile.getAbsolutePath()};

      int exitCode = Animate.execute(args);

      String output = outContent.toString();
      assertTrue("Output should contain animation information", output.length() > 0);
      assertTrue(
          "Exit code should be 0 (no violation) or 1 (invariant violated)",
          exitCode == 0 || exitCode == 1);

      System.setOut(originalOut);
      System.out.println("  ✓ Invariant checking completed (exit code: " + exitCode + ")");
    } catch (Exception e) {
      System.setOut(originalOut);
      System.err.println("  ✗ Invariant checking failed: " + e.getMessage());
      throw e;
    }
  }
}
