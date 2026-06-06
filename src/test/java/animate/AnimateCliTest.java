package animate;

import static org.junit.Assert.*;

import java.io.File;
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
  public void testAnimateWithSteps() {
    System.out.println("Testing CLI animation for: " + modelName);

    TestCli.Result result = TestCli.execute("--steps", "5", modelFile.getAbsolutePath());

    assertTrue("Output should contain animation information", result.output().length() > 0);
    // A random walk may legitimately run out of enabled events, which the CLI
    // reports as a deadlock and exits 1; only that non-zero outcome is allowed.
    boolean deadlocked = result.exitCode() == 1 && result.output().contains("deadlock");
    assertTrue(
        "Exit code should be 0, or 1 for a reported deadlock:\n" + result.output(),
        result.exitCode() == 0 || deadlocked);
    System.out.println("  ✓ CLI animation completed (exit code: " + result.exitCode() + ")");
  }

  @Test(timeout = 30000)
  public void testAnimateWithInvariants() {
    System.out.println("Testing CLI with invariant checking for: " + modelName);

    TestCli.Result result =
        TestCli.execute("--steps", "5", "--invariants", modelFile.getAbsolutePath());

    assertTrue("Output should contain animation information", result.output().length() > 0);
    assertTrue(
        "Exit code should be 0 (no violation) or 1 (invariant violated)",
        result.exitCode() == 0 || result.exitCode() == 1);
    System.out.println("  ✓ Invariant checking completed (exit code: " + result.exitCode() + ")");
  }
}
