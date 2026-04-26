package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.scripting.Api;
import de.prob.statespace.Trace;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class InfoCommandTest {

  @Test
  public void testVisualizationStillSavesWhenInitializationRaises() throws Exception {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    FailingInitializationAnimate animate =
        new FailingInitializationAnimate(
            injector.getInstance(Api.class), injector.getInstance(TraceManager.class));
    animate.model = Paths.get("src/test/resources/models/base-model/M1.bum");
    animate.steps = 1;
    animate.size = 4;

    InfoCommand command = new InfoCommand();
    command.parent = animate;

    Path machineGraph = Files.createTempFile("animate-info-", ".dot");
    command.machine = machineGraph;

    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;

    try {
      System.setErr(new PrintStream(errContent));

      int exitCode = command.call();

      assertEquals("Visualization export should continue after init warning", 0, exitCode);
      assertTrue("Visualization file should be created", Files.exists(machineGraph));
      assertTrue("Visualization file should not be empty", Files.size(machineGraph) > 0);
      assertTrue(
          "Initialization warning should be reported",
          errContent.toString().contains("Warning: Could not fully initialize model"));
    } finally {
      System.setErr(originalErr);
      Files.deleteIfExists(machineGraph);
    }
  }

  private static final class FailingInitializationAnimate extends Animate {

    FailingInitializationAnimate(Api api, TraceManager traceManager) {
      super(api, traceManager);
    }

    @Override
    Trace runInitializationEvent(Trace trace, String eventName) {
      if (INITIALISE_MACHINE_EVENT.equals(eventName)) {
        throw new IllegalStateException("simulated initialization failure");
      }
      return super.runInitializationEvent(trace, eventName);
    }
  }
}
