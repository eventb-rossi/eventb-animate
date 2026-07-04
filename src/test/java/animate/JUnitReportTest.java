package animate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The --junit report: one testcase per checked property, in CI-ingestible JUnit XML (the violation
 * shape is covered together with the JSON report in {@link JsonReportTest}).
 */
public class JUnitReportTest {

  private static final String FILE_SYSTEM_M0 =
      Paths.get("src/test/resources/models/file-system/M0.bum").toString();

  @Test
  public void testLoadFailureProducesOneErrorTestcase() throws Exception {
    Path report = Files.createTempFile("animate-junit-", ".xml");
    try {
      TestCli.Result result = TestCli.execute("--junit", report.toString(), "missing.bum");

      assertEquals("The load failure keeps exit 1:\n" + result.output(), 1, result.exitCode());

      Document doc = TestCli.parseXml(report);
      Element suite = (Element) doc.getElementsByTagName("testsuite").item(0);
      assertEquals("eventb-animate check", suite.getAttribute("name"));
      assertEquals("1", suite.getAttribute("tests"));
      assertEquals("1", suite.getAttribute("errors"));
      Element testcase = (Element) doc.getElementsByTagName("testcase").item(0);
      assertEquals("run", testcase.getAttribute("name"));
      assertEquals("missing.bum", testcase.getAttribute("classname"));
      assertEquals(1, testcase.getElementsByTagName("error").getLength());
      Element error = (Element) testcase.getElementsByTagName("error").item(0);
      assertTrue(error.getAttribute("message").contains("Error loading model"));
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test(timeout = 120000)
  public void testUndischargedWdIsAFailureTestcase() throws Exception {
    Path report = Files.createTempFile("animate-junit-", ".xml");
    try {
      TestCli.Result result = TestCli.execute("wd", "--junit", report.toString(), FILE_SYSTEM_M0);

      assertEquals("M0 has open WD obligations:\n" + result.output(), 2, result.exitCode());

      Document doc = TestCli.parseXml(report);
      Element suite = (Element) doc.getElementsByTagName("testsuite").item(0);
      assertEquals("eventb-animate wd", suite.getAttribute("name"));
      assertEquals("1", suite.getAttribute("tests"));
      assertEquals("1", suite.getAttribute("failures"));
      Element testcase = (Element) doc.getElementsByTagName("testcase").item(0);
      assertEquals("well-definedness", testcase.getAttribute("name"));
      Element failure = (Element) testcase.getElementsByTagName("failure").item(0);
      assertTrue(failure.getAttribute("message").contains("not discharged"));
    } finally {
      Files.deleteIfExists(report);
    }
  }
}
