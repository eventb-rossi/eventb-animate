package animate;

import static org.junit.Assert.assertFalse;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Model-checking must work on data-refinement levels. ProB loads the native Event-B state space
 * with witnesses/gluing, so the data-refined-away variables are initialised and the check never
 * hits the Classical-B "may not initialise" error that the old {@code convert --check mc:N} path
 * produced on the same machines.
 */
public class ModelCheckTest {

  @Test(timeout = 120000)
  public void checksDataRefinedLeafMachine() {
    Path model = Paths.get("src/test/resources/models/cars-on-bridge/M3.bum");

    TestCli.Result result = TestCli.execute("--size", "3", "--states", "300", model.toString());

    TestCli.assertModelChecked(result, "The data-refined leaf machine");
    assertFalse(
        "Native Event-B MC must not hit the Classical-B init error:\n" + result.output(),
        result.output().contains("may not initialise"));
  }
}
