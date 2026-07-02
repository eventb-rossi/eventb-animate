package animate;

import static org.junit.Assert.assertEquals;

import java.nio.file.Paths;
import java.util.Map;
import org.junit.Test;

/** Options that tune the model-checking run and the ProB preferences behind it. */
public class CheckOptionsTest {

  private static final String TRAFFIC_LIGHT_M2 =
      Paths.get("src/test/resources/models/traffic-light/M2.bum").toString();

  @Test(timeout = 120000)
  public void testPrefReachesProB() {
    TestCli.Result result =
        TestCli.execute("-p", "SYMMETRY_MODE=off", "--states", "300", TRAFFIC_LIGHT_M2);

    TestCli.assertModelChecked(result, "The model with -p SYMMETRY_MODE=off");
  }

  @Test
  public void testUserPrefsWinOverDefaultsAndSize() {
    Animate animate = new Animate(null, null);
    animate.size = 4;
    animate.userPrefs.put("DEFAULT_SETSIZE", "2");
    animate.userPrefs.put("COMPRESSION", "false");

    Map<String, String> prefs = animate.buildProBPreferences();

    assertEquals("2", prefs.get("DEFAULT_SETSIZE"));
    assertEquals("false", prefs.get("COMPRESSION"));
  }

  @Test
  public void testPrefWithoutValueIsUsageError() {
    TestCli.Result result = TestCli.execute("-p", "FOO", TRAFFIC_LIGHT_M2);

    assertEquals(
        "A -p value without '=' is a usage error:\n" + result.output(), 2, result.exitCode());
  }
}
