package animate;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class VersionCheckScriptTest {

  @Test
  public void testVersionCheckScriptPassesForCurrentRelease() throws Exception {
    Process process =
        new ProcessBuilder("bash", "scripts/check-version.sh", "v4.2")
            .redirectErrorStream(true)
            .start();

    String output;
    try (var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertEquals("Version check script should succeed\n" + output, 0, process.waitFor());
    assertTrue(
        "Version check output should report success", output.contains("Version check passed"));
  }
}
