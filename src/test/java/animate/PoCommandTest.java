package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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

  // 842 M1 obligations (328 discharged, 1 reviewed, 513 with a stale/broken proof) plus one
  // undischarged C1 theorem. The export left most proofs broken, which the gate must surface
  // rather than count as discharged.
  private static final String BASE_MODEL_M1 =
      Paths.get("src/test/resources/models/base-model/M1.bum").toString();
  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();
  private static final String TRAFFIC_LIGHT_DIR =
      Paths.get("src/test/resources/models/traffic-light").toString();
  // 19 discharged and 22 obligations whose stored proof is broken (stale FIS/INV proofs).
  private static final String BINARY_SEARCH_M3 =
      Paths.get("src/test/resources/models/binary-search/M3.bum").toString();
  // Two undischarged inc obligations: inv1 preservation is provable, inv2 is false.
  private static final String COUNTER_M0 =
      Paths.get("src/test/resources/models/counter/M0.bum").toString();

  @Test
  public void testOpenObligationsExitTwo() {
    TestCli.Result result = TestCli.execute("po", BASE_MODEL_M1);

    assertEquals(
        "Open obligations are unproven, exit 2:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The summary should count every status, broken included:\n" + result.output(),
        result
            .output()
            .contains(
                "Proof obligations: 843 total, 328 discharged, 1 reviewed, 513 broken,"
                    + " 1 undischarged"));
    assertTrue(
        "The undischarged obligation should be named:\n" + result.output(),
        result.output().contains("C1/InductionAxiom/THM"));
    assertTrue(
        "Broken proofs should be surfaced, not counted as discharged:\n" + result.output(),
        result.output().contains("Broken (stored proof is stale; replay it in Rodin):"));
    assertTrue(
        "The broken obligations should be named:\n" + result.output(),
        result.output().contains("M1/INITIALISATION/CommonRoleType/INV"));
    assertTrue(
        "The verdict should count the open obligations:\n" + result.output(),
        result.output().contains("Error: 515 of 843 proof obligations are not discharged"));
  }

  @Test
  public void testBrokenProofsAreNotDischarged() {
    // binary-search ships 22 obligations whose stored proof is broken (stale) at full
    // confidence; the kernel reports them discharged, so the gate reads the .bps flag back.
    TestCli.Result result = TestCli.execute("po", BINARY_SEARCH_M3);

    assertEquals(
        "Broken proofs are not discharged, so the gate fails:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "Broken obligations should be split out of the discharged count:\n" + result.output(),
        result.output().contains("Proof obligations: 41 total, 19 discharged, 22 broken"));
    assertTrue(
        "A broken obligation should be named:\n" + result.output(),
        result.output().contains("M1/search/act1/FIS"));
    assertTrue(
        "The verdict should count the broken obligations as open:\n" + result.output(),
        result.output().contains("Error: 22 of 41 proof obligations are not discharged"));
  }

  @Test
  public void testAllowReviewedDoesNotRescueBrokenProofs() {
    TestCli.Result result = TestCli.execute("po", "--allow-reviewed", BASE_MODEL_M1);

    assertEquals(
        "Broken proofs and the undischarged theorem still fail the gate:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "--allow-reviewed accepts the one reviewed obligation but not the 513 broken ones:\n"
            + result.output(),
        result.output().contains("Error: 514 of 843 proof obligations are not discharged"));
    assertTrue(
        "The broken obligations should still be surfaced:\n" + result.output(),
        result.output().contains("Broken (stored proof is stale; replay it in Rodin):"));
  }

  @Test
  public void testFilterComposesWithAllowReviewed() {
    // The one obligation that is reviewed rather than broken; --allow-reviewed accepts it.
    TestCli.Result result =
        TestCli.execute(
            "po", "--allow-reviewed", "--filter", "M1/INITIALISATION/SRootType/INV", BASE_MODEL_M1);

    assertEquals(
        "Scoping to the reviewed obligation and accepting it passes:\n" + result.output(),
        0,
        result.exitCode());
    assertTrue(
        "The summary should count the filtered-out obligations:\n" + result.output(),
        result.output().contains("(842 filtered out by --filter)"));
    assertTrue(
        "The verdict should mention the accepted reviewed obligation:\n" + result.output(),
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
    // The abstract machine's obligations are the one fully proven (unbroken) scope; the
    // later refinements carry the stale proofs.
    TestCli.Result result = TestCli.execute("po", "--filter", "M0/*", TRAFFIC_LIGHT_M2);

    assertEquals("A fully proven scope passes:\n" + result.output(), 0, result.exitCode());
    assertTrue(
        "The summary should count the scoped and filtered obligations:\n" + result.output(),
        result.output().contains("Proof obligations: 4 total, 4 discharged (19 filtered out"));
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
    TestCli.Result result = TestCli.execute("po", "-v", "-m", "M0", TRAFFIC_LIGHT_DIR);

    assertEquals(0, result.exitCode());
    assertTrue(
        "Discharged obligations should be listed under -v:\n" + result.output(),
        result.output().contains("\t - M0/INITIALISATION/inv3/INV: discharged"));
  }

  @Test
  public void testVerboseListsBrokenStatus() {
    TestCli.Result result = TestCli.execute("po", "-v", BINARY_SEARCH_M3);

    assertEquals(2, result.exitCode());
    assertTrue(
        "Broken obligations should be labelled as such under -v:\n" + result.output(),
        result.output().contains("\t - M1/search/act1/FIS: broken"));
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

  @Test(timeout = 120000)
  public void testDisproveFindsTheCounterexample() {
    // counter's inc/inv2/INV is false (x=4 steps to x=5 against x<5) while
    // inc/inv1/INV is provable; the solver must settle both.
    TestCli.Result result = TestCli.execute("po", "--disprove", COUNTER_M0);

    assertEquals(
        "A disproved obligation is a violation:\n" + result.output(), 1, result.exitCode());
    assertTrue(
        "The counterexample should be shown:\n" + result.output(),
        result.output().contains("M0/inc/inv2/INV: disproved (counterexample: x = 4)"));
    assertTrue(
        "The provable obligation should pass via the solver:\n" + result.output(),
        result.output().contains("M0/inc/inv1/INV: proven by the constraint solver"));
    assertTrue(
        "The verdict should count the disproof:\n" + result.output(),
        result.output().contains("Error: 1 of 2 proof obligations are disproved"));
  }

  @Test(timeout = 120000)
  public void testDisproveTimeoutKeepsTheObligationOpen() {
    // The induction theorem is true but needs induction, out of the solver's reach.
    TestCli.Result result =
        TestCli.execute(
            "po", "--disprove", "--disprove-timeout", "500", "--filter", "C1/*", BASE_MODEL_M1);

    assertEquals("No verdict stays exit 2:\n" + result.output(), 2, result.exitCode());
    assertTrue(
        "The timeout should be reported per obligation:\n" + result.output(),
        result
            .output()
            .contains(
                "C1/InductionAxiom/THM: no counterexample found (solver timeout after 500 ms)"));
  }

  @Test
  public void testDisproveTimeoutRequiresDisprove() {
    TestCli.Result result = TestCli.execute("po", "--disprove-timeout", "500", COUNTER_M0);

    assertEquals(
        "--disprove-timeout without --disprove is a usage error:\n" + result.output(),
        2,
        result.exitCode());
    assertTrue(
        "The error should point at --disprove:\n" + result.output(),
        result.output().contains("--disprove-timeout only tunes --disprove"));
  }

  @Test(timeout = 120000)
  public void testDisproveReportCarriesProbVersion() throws Exception {
    TestCli.SplitResult result =
        TestCli.executeSplit("po", "--disprove", "--json", "-", COUNTER_M0);

    assertEquals(1, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
    assertEquals("violation", root.get("status").asText());
    assertTrue("ProB ran, so its version is recorded", root.hasNonNull("probVersion"));
    assertEquals("failed", root.get("checks").get(1).get("outcome").asText());
    assertTrue(
        "The check message should carry the counterexample:\n" + result.stdout(),
        root.get("checks").get(1).get("message").asText().contains("x = 4"));
  }

  @Test
  public void testJsonReportCarriesOneCheckPerObligation() throws Exception {
    TestCli.SplitResult result = TestCli.executeSplit("po", "--json", "-", BASE_MODEL_M1);

    assertEquals(2, result.exitCode());
    JsonNode root = TestCli.parseJson(result.stdout());
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
      assertEquals("515", suite.getAttribute("failures"));
      assertEquals("0", suite.getAttribute("errors"));
      NodeList failures = doc.getElementsByTagName("failure");
      assertEquals(515, failures.getLength());
      Element firstFailure = (Element) failures.item(0);
      assertTrue(
          "The failure should carry the obligation status:\n"
              + firstFailure.getAttribute("message"),
          firstFailure.getAttribute("message").contains("broken: the recorded proof is stale"));
    } finally {
      Files.deleteIfExists(report);
    }
  }
}
