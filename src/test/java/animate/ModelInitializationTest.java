package animate;

import static org.junit.Assert.*;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.nio.file.Paths;
import org.junit.Test;

public class ModelInitializationTest {

  @Test
  public void testBaseModelInitializationCompletes() {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    Animate animate = injector.getInstance(Animate.class);
    animate.model = Paths.get("src/test/resources/models/base-model/M1.bum");
    animate.steps = 1;
    animate.size = 4;

    StateSpace stateSpace = animate.initAndLoadModel();
    assertNotNull("Model should load successfully", stateSpace);

    try {
      stateSpace.startTransaction();
      Trace trace = animate.initializeTrace(stateSpace);
      assertTrue("Constants should be set up", trace.getCurrentState().isConstantsSetUp());
      assertTrue("Machine should be initialised", trace.getCurrentState().isInitialised());
    } finally {
      stateSpace.endTransaction();
      stateSpace.kill();
    }
  }

  @Test
  public void testInitializedBaseModelInvariantViolationIsDetected() {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    Animate animate = injector.getInstance(Animate.class);
    animate.model = Paths.get("src/test/resources/models/base-model/M1.bum");
    animate.steps = 1;
    animate.size = 4;
    animate.checkInv = true;

    StateSpace stateSpace = animate.initAndLoadModel();
    assertNotNull("Model should load successfully", stateSpace);

    try {
      stateSpace.startTransaction();
      Trace trace = animate.initializeTrace(stateSpace);

      assertTrue("Machine should be initialised", trace.getCurrentState().isInitialised());
      assertFalse(
          "Initialized state should violate invariants", trace.getCurrentState().isInvariantOk());
      assertTrue("Animate should remember the invariant violation", animate.invariantViolated);
    } finally {
      stateSpace.endTransaction();
      stateSpace.kill();
    }
  }
}
