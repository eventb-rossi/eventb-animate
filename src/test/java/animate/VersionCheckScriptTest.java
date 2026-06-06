package animate;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class VersionCheckScriptTest {

  @Test
  public void testVersionCheckScriptPassesForCurrentRelease() throws Exception {
    Process process =
        new ProcessBuilder("bash", "scripts/check-version.sh", "v" + gradleVersion())
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

  /** Mirrors the sed extraction in check-version.sh so the test never trails a release bump. */
  private static String gradleVersion() throws IOException {
    String buildFile = Files.readString(Paths.get("build.gradle"), StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("(?m)^version = '([^']+)'$").matcher(buildFile);
    assertTrue("build.gradle should declare a version", matcher.find());
    return matcher.group(1);
  }
}
