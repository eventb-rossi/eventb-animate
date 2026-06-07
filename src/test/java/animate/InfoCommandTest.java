package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provider;
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

  @Test(timeout = 30000)
  public void testUnsupportedGraphExtensionFailsWithCleanError() {
    TestCli.Result result =
        TestCli.execute(
            "info", "--events", "graph.png", "src/test/resources/models/base-model/M1.bum");

    assertEquals("Unsupported extension should exit 1:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "Error should use the standard prefix and name the path:\n" + result.output(),
        result
            .output()
            .contains("Error: unsupported extension for graph.png (expected .dot or .svg)"));
  }

  @Test(timeout = 30000)
  public void testGraphWriteFailureReportsCleanError() {
    TestCli.Result result =
        TestCli.execute(
            "info",
            "--events",
            "/nonexistent-dir/graph.dot",
            "src/test/resources/models/base-model/M1.bum");

    assertEquals("Unwritable graph path should exit 1:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "Write failure should be reported with a clean message:\n" + result.output(),
        result.output().contains("Error saving event_hierarchy to /nonexistent-dir/graph.dot:"));
  }

  @Test
  public void testVisualizationStillSavesWhenInitializationRaises() throws Exception {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    FailingInitializationAnimate animate =
        new FailingInitializationAnimate(
            injector.getProvider(Api.class), injector.getProvider(TraceManager.class));
    animate.model = Paths.get("src/test/resources/models/base-model/M1.bum");
    animate.steps = 1;
    animate.size = 4;

    InfoCommand command = new InfoCommand();
    command.parent = animate;

    Path machineGraph = Files.createTempFile("animate-info-", ".dot");
    command.machineGraph = machineGraph;

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

    FailingInitializationAnimate(Provider<Api> api, Provider<TraceManager> traceManager) {
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
