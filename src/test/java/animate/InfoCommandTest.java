package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
            "info", "--event-graph", "graph.png", "src/test/resources/models/base-model/M1.bum");

    assertEquals("Unsupported extension should exit 1:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "Error should use the standard prefix and name the path:\n" + result.output(),
        result
            .output()
            .contains("Error: unsupported extension for graph.png (expected .dot or .svg)"));
    assertFalse(
        "Validation should fail before the model is loaded:\n" + result.output(),
        result.output().contains("Machine:"));
  }

  @Test(timeout = 30000)
  public void testGraphWriteFailureReportsCleanError() throws Exception {
    // A directory named like the output passes the upfront validation with
    // --force but fails when the visualization is written.
    Path unwritable = Files.createTempDirectory("animate-info-").resolve("graph.dot");
    Files.createDirectories(unwritable);
    try {
      TestCli.Result result =
          TestCli.execute(
              "info",
              "--event-graph",
              unwritable.toString(),
              "--force",
              "src/test/resources/models/base-model/M1.bum");

      assertEquals(
          "Unwritable graph path should exit 1:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "Write failure should be reported with a clean message:\n" + result.output(),
          result.output().contains("Error saving event_hierarchy to " + unwritable + ":"));
    } finally {
      Files.deleteIfExists(unwritable);
      Files.deleteIfExists(unwritable.getParent());
    }
  }

  @Test(timeout = 30000)
  public void testExistingOutputRequiresForce() throws Exception {
    Path existing = Files.createTempFile("animate-info-", ".dot");
    try {
      TestCli.Result result =
          TestCli.execute(
              "info",
              "--event-graph",
              existing.toString(),
              "src/test/resources/models/base-model/M1.bum");

      assertEquals("Existing output should exit 1:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "Error should point at --force:\n" + result.output(),
          result.output().contains("already exists, use --force to overwrite: " + existing));
      assertFalse(
          "Validation should fail before the model is loaded:\n" + result.output(),
          result.output().contains("Machine:"));
    } finally {
      Files.deleteIfExists(existing);
    }
  }

  @Test(timeout = 30000)
  public void testForceOverwritesExistingOutput() throws Exception {
    Path existing = Files.createTempFile("animate-info-", ".dot");
    try {
      TestCli.Result result =
          TestCli.execute(
              "info",
              "--machine-graph",
              existing.toString(),
              "--force",
              "src/test/resources/models/base-model/M1.bum");

      assertEquals("--force should allow overwriting:\n" + result.output(), 0, result.exitCode());
      assertTrue("Graph should be written", Files.size(existing) > 0);
    } finally {
      Files.deleteIfExists(existing);
    }
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
    // Reserve the name only: info refuses to overwrite existing files without --force.
    Files.delete(machineGraph);
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
