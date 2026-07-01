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

  @Test(timeout = 60000)
  public void testModelCheck() {
    System.out.println("Model checking: " + modelName);

    TestCli.Result result = TestCli.execute("--states", "500", modelFile.getAbsolutePath());

    // base-model/M1 reaches an invariant violation (exit 1); the others check clean (exit 0).
    TestCli.assertModelChecked(result, "Model " + modelName);
    assertFalse(
        "Native Event-B model-checking must not hit the Classical-B init error:\n"
            + result.output(),
        result.output().contains("may not initialise"));
    System.out.println("  ✓ Model checking completed (exit code: " + result.exitCode() + ")");
  }
}
