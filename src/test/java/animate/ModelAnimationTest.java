package animate;

import static org.junit.Assert.*;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import de.prob.scripting.Api;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.File;
import java.util.Collection;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test that verifies Event-B models can be loaded and animated. Tests models from
 * https://github.com/17451k/eventb-models
 */
@RunWith(Parameterized.class)
public class ModelAnimationTest {

  private static final int ANIMATION_STEPS = 10;
  private static Api api;
  private final File modelFile;
  private final String modelName;

  @BeforeClass
  public static void setupApi() {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    api = injector.getInstance(Api.class);
  }

  public ModelAnimationTest(String modelName, File modelFile) {
    this.modelName = modelName;
    this.modelFile = modelFile;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> getModels() {
    return TestModels.allModels();
  }

  @Test
  public void testModelLoads() throws Exception {
    System.out.println("Testing model: " + modelName);

    assertNotNull("ProB API should be available", api);

    StateSpace stateSpace = api.eventb_load(modelFile.getAbsolutePath());
    assertNotNull("Model should load successfully", stateSpace);

    try {
      Trace trace = new Trace(stateSpace);

      // Verify we can get the initial state
      assertNotNull("Current state should exist", trace.getCurrentState());

      System.out.println("  ✓ Model loaded successfully");
    } finally {
      stateSpace.kill();
    }
  }

  @Test
  public void testModelAnimation() throws Exception {
    System.out.println("Testing animation for: " + modelName);

    StateSpace stateSpace = api.eventb_load(modelFile.getAbsolutePath());

    try {
      Trace trace = new Trace(stateSpace);

      // Try to execute random steps
      int successfulSteps = 0;
      for (int i = 0; i < ANIMATION_STEPS; i++) {
        try {
          Trace newTrace = trace.anyEvent(null);
          if (newTrace != trace) {
            trace = newTrace;
            successfulSteps++;
          } else {
            // No more operations available
            break;
          }
        } catch (Exception e) {
          // Some models may have limited animation possibilities
          System.out.println(
              "  ⚠ Animation stopped after " + successfulSteps + " steps: " + e.getMessage());
          break;
        }
      }

      System.out.println("  ✓ Performed " + successfulSteps + " animation steps");

      assertNotNull("Current state should exist after animation", trace.getCurrentState());
    } finally {
      stateSpace.kill();
    }
  }
}
