package animate;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class TestModels {

  private TestModels() {}

  static Collection<Object[]> allModels() {
    List<Object[]> models = new ArrayList<>();
    File resourcesDir = new File("src/test/resources/models");
    if (resourcesDir.exists()) {
      findBumFiles(resourcesDir, models, "");
    }
    return models;
  }

  static Collection<Object[]> mainModels() {
    List<Object[]> filteredModels = new ArrayList<>();
    for (Object[] model : allModels()) {
      String name = (String) model[0];
      if (name.contains("base-model") && name.contains("M1.bum")
          || name.contains("binary-search") && name.contains("M3.bum")
          || name.contains("cars-on-bridge") && name.contains("M3.bum")
          || name.contains("file-system") && name.contains("M0.bum")
          || name.contains("traffic-light") && name.contains("M2.bum")) {
        filteredModels.add(model);
      }
    }
    return filteredModels.isEmpty() ? allModels() : filteredModels;
  }

  private static void findBumFiles(File dir, List<Object[]> models, String prefix) {
    File[] files = dir.listFiles();
    if (files == null) return;

    for (File file : files) {
      if (file.isDirectory()) {
        String newPrefix = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
        findBumFiles(file, models, newPrefix);
      } else if (file.getName().endsWith(".bum")) {
        String modelName = prefix + "/" + file.getName();
        models.add(new Object[] {modelName, file});
      }
    }
  }
}
