package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The po command gates on the proof status recorded by Rodin, read from the .bpo/.bps files without
 * starting ProB.
 */
public class PoCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // 842 M1 obligations (836 discharged + 6 reviewed) plus one undischarged C1 theorem.
  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @Test
  public void testOpenObligationsExitTwo() {
    TestCli.Result result = TestCli.execute("po", BASE_MODEL_M1);

    assertEquals(
        "Open obligations are unproven, exit 2:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The summary should count every status:\n" + result.output(),
        result
            .output()
            .contains("Proof obligations: 843 total, 836 discharged, 6 reviewed, 1 undischarged"));
    assertTrue(
        "The undischarged obligation should be named:\n" + result.output(),
        result.output().contains("C1/InductionAxiom/THM"));
    assertTrue(
        "The reviewed obligations should be named:\n" + result.output(),
        result.output().contains("M1/INITIALISATION/RootType/INV"));
    assertTrue(
        "The verdict should count the open obligations:\n" + result.output(),
        result.output().contains("Error: 7 of 843 proof obligations are not discharged"));
  }

  @Test
  public void testAllowReviewedStillFailsOnUndischarged() {
    TestCli.Result result = TestCli.execute("po", "--allow-reviewed", BASE_MODEL_M1);

    assertEquals(
        "The undischarged theorem still fails the gate:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "Only the undischarged obligation should remain open:\n" + result.output(),
        result.output().contains("Error: 1 of 843 proof obligations are not discharged"));
  }

  @Test
  public void testFilterComposesWithAllowReviewed() {
    TestCli.Result result =
        TestCli.execute("po", "--allow-reviewed", "--filter", "M1/*", BASE_MODEL_M1);

    assertEquals(
        "Filtering out the C1 theorem and accepting reviewed passes:\n" + result.output(),
        0,
        result.exitCode());
    assertTrue(
        "The summary should count the filtered-out obligation:\n" + result.output(),
        result.output().contains("(1 filtered out by --filter)"));
    assertTrue(
        "The verdict should mention the accepted reviewed obligations:\n" + result.output(),
        result.output().contains("All proof obligations are discharged or reviewed."));
  }

  @Test
  public void testFilterMatchingNothingFailsTheGate() {
    TestCli.Result result = TestCli.execute("po", "--filter", "no-such*", BASE_MODEL_M1);

    assertEquals(
        "A filter matching nothing must not pass the gate:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should name the filter and the obligation count:\n" + result.output(),
        result
            .output()
            .contains(
                "no proof obligations match --filter 'no-such*'"
                    + " (843 obligations exist; check the pattern)"));
  }

  @Test
  public void testAllDischargedExitsZero() {
    TestCli.Result result = TestCli.execute("po", TRAFFIC_LIGHT_M2);

    assertEquals("A fully proven chain passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The summary should cover the whole refinement chain:\n" + result.output(),
        result.output().contains("Proof obligations: 23 total, 23 discharged"));
    assertTrue(
        "The verdict line should be printed:\n" + result.output(),
        result.output().contains("All proof obligations are discharged."));
  }

  @Test
  public void testZeroObligationsPass() {
    // A context Rodin generated no obligations for: its (empty) .bpo file exists,
    // so this is a genuine "nothing to prove", not a missing proof database.
    TestCli.Result result = TestCli.execute("po", "src/test/resources/models/traffic-light/C1.buc");

    assertEquals("Nothing to prove passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The empty proof database should be called out:\n" + result.output(),
        result.output().contains("No proof obligations found"));
  }

  @Test
  public void testMachineSelectionScopesTheChain() {
    TestCli.Result result =
        TestCli.execute("po", "-m", "M0", "src/test/resources/models/traffic-light");

    assertEquals("The abstract machine alone passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "Only M0's obligations should be counted:\n" + result.output(),
        result.output().contains("Proof obligations: 4 total, 4 discharged"));
  }

  @Test
  public void testVerboseListsEveryObligation() {
    TestCli.Result result = TestCli.execute("po", "-v", TRAFFIC_LIGHT_M2);

    assertEquals(0, result.exitCode());
    assertTrue(
        "Discharged obligations should be listed under -v:\n" + result.output(),
        result.output().contains("\t - M0/INITIALISATION/inv3/INV: discharged"));
  }

  @Test
  public void testMissingProofFilesAreALoadError() throws Exception {
    // A model exported without its proof database must not pass as "zero obligations".
    Path dir = Files.createTempDirectory("animate-no-bpo-");
    try {
      Path source = Paths.get("src/test/resources/models/traffic-light");
      Files.copy(source.resolve("M0.bum"), dir.resolve("M0.bum"));
      Files.copy(source.resolve("M0.bcm"), dir.resolve("M0.bcm"));

      TestCli.Result result = TestCli.execute("po", dir.resolve("M0.bum").toString());

      assertEquals(
          "A missing proof database is an input error:\n" + result.output(), 1, result.exitCode());
      assertTrue(
          "The error should name the component and the fix:\n" + result.output(),
          result.output().contains("no proof information for M0 (missing .bpo file"));
    } finally {
      Files.deleteIfExists(dir.resolve("M0.bum"));
      Files.deleteIfExists(dir.resolve("M0.bcm"));
      Files.deleteIfExists(dir);
    }
  }

  @Test
  public void testJsonReportCarriesOneCheckPerObligation() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("po", "--json", "-", BASE_MODEL_M1);

    assertEquals(2, result.exitCode());
    JsonNode root = MAPPER.readTree(result.stdout());
    assertEquals("po", root.get("command").asText());
    assertEquals("incomplete", root.get("status").asText());
    assertEquals(2, root.get("exitCode").asInt());
    assertEquals("M1", root.get("machine").asText());
    assertEquals(843, root.get("checks").size());
    assertNull("ProB never ran, so there is no version to report", root.get("probVersion"));
    JsonNode firstCheck = root.get("checks").get(0);
    assertTrue(
        "Check names are component-qualified: " + firstCheck,
        firstCheck.get("name").asText().contains("/"));
  }

  @Test
  public void testJUnitReportMarksOpenObligationsFailed() throws Exception {
    Path report = Files.createTempFile("animate-junit-", ".xml");
    try {
      TestCli.Result result = TestCli.execute("po", "--junit", report.toString(), BASE_MODEL_M1);

      assertEquals(2, result.exitCode());
      Document doc = TestCli.parseXml(report);
      Element suite = (Element) doc.getElementsByTagName("testsuite").item(0);
      assertEquals("eventb-animate po", suite.getAttribute("name"));
      assertEquals("843", suite.getAttribute("tests"));
      assertEquals("7", suite.getAttribute("failures"));
      assertEquals("0", suite.getAttribute("errors"));
      NodeList failures = doc.getElementsByTagName("failure");
      assertEquals(7, failures.getLength());
      Element firstFailure = (Element) failures.item(0);
      assertTrue(
          "The failure should carry the obligation status:\n"
              + firstFailure.getAttribute("message"),
          firstFailure.getAttribute("message").contains("reviewed, not proven"));
    } finally {
      Files.deleteIfExists(report);
    }
  }
}
