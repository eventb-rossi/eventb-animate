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
 * Verifies that every bundled Event-B model loads into a ProB state space. Models from
 * https://github.com/17451k/eventb-models
 */
@RunWith(Parameterized.class)
public class ModelLoadTest {

  private static Api api;
  private final File modelFile;
  private final String modelName;

  @BeforeClass
  public static void setupApi() {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    api = injector.getInstance(Api.class);
  }

  public ModelLoadTest(String modelName, File modelFile) {
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
}
