package animate;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class ModelResolver {

  private static final Logger logger = LoggerFactory.getLogger(ModelResolver.class);

  private Path tempDir;

  Path resolve(Path model, String machineName) throws IOException {
    if (Files.isDirectory(model)) {
      return resolveDirectory(model, machineName);
    }
    if (!model.toString().endsWith(".zip")) {
      return model;
    }

    Path tempDirectory = Files.createTempDirectory("animate-");
    this.tempDir = tempDirectory;
    List<Path> bumFiles = new ArrayList<>();

    try (InputStream fis = Files.newInputStream(model);
        ZipInputStream zis = new ZipInputStream(fis)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path entryPath = tempDirectory.resolve(entry.getName()).normalize();
        if (!entryPath.startsWith(tempDirectory)) {
          throw new IOException("Zip entry outside target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(entryPath);
        } else {
          Files.createDirectories(entryPath.getParent());
          Files.copy(zis, entryPath);
          if (entry.getName().endsWith(RodinNames.BUM)) {
            bumFiles.add(entryPath);
          }
        }
      }
    }

    return selectBumFile(bumFiles, tempDirectory, machineName, "zip archive: " + model);
  }

  private Path resolveDirectory(Path dir, String machineName) throws IOException {
    List<Path> bumFiles;
    try (var stream = Files.walk(dir)) {
      bumFiles =
          stream
              .filter(p -> p.toString().endsWith(RodinNames.BUM))
              .filter(Files::isRegularFile)
              .collect(Collectors.toList());
    }

    return selectBumFile(bumFiles, dir, machineName, "directory: " + dir);
  }

  private Path selectBumFile(List<Path> bumFiles, Path root, String machineName, String source)
      throws IOException {
    if (bumFiles.isEmpty()) {
      throw new IOException("No .bum file found in " + source);
    }

    // A Rodin "Archive File" export can bundle several self-contained projects, each under its own
    // top-level directory and free to reuse component basenames (M0.bum, ...). Group machines by
    // project so selection and refinement analysis never cross project boundaries.
    Map<String, List<Path>> byProject = groupByProject(bumFiles, root);

    if (machineName != null) {
      return findByName(byProject, machineName, source);
    }

    if (byProject.size() > 1) {
      throw new IOException(
          source
              + " contains "
              + byProject.size()
              + " projects; select one with -m <project>/<machine>. Available: "
              + describeProjects(byProject));
    }

    return autoSelect(byProject.values().iterator().next(), source);
  }

  /**
   * Returns the project's only machine, or the leaf of its refinement chain when there are many.
   */
  private Path autoSelect(List<Path> project, String source) throws IOException {
    if (project.size() == 1) {
      return project.get(0);
    }
    validateUniqueMachineNames(project, source);
    Path selected = findMostRefinedBum(project, source);
    logger.info(
        "Multiple .bum files found, auto-selected most refined: {}", selected.getFileName());
    return selected;
  }

  /**
   * Groups machines by their project, identified by the top-level path segment relative to {@code
   * root}. Machines sitting directly at the root form the single "" (root) project, mirroring how a
   * flat archive holds one project.
   *
   * <p>The project key is the archive's top-level directory name, deliberately <em>not</em> the
   * {@code <name>} from a Rodin {@code .project} descriptor that the sibling tools (rossi,
   * eventb-checker) read. The reason is purpose: those tools validate every project and report on
   * it, so they want the authoritative project name; this tool only resolves one machine to
   * animate, and the key doubles as the {@code -m <project>/<machine>} selector the user types. The
   * user navigates by the layout they see in the archive, so the visible directory name is the
   * least surprising thing to type — keying on an internal {@code .project} name could force them
   * to type a string that never appears as a path. Loading needs no project name either: ProB
   * resolves a machine's sees/refines from its own directory. The trade-off is that for an export
   * whose directory name differs from its {@code .project} name, this tool and its siblings will
   * refer to the same project by different identifiers.
   */
  private Map<String, List<Path>> groupByProject(List<Path> bumFiles, Path root) {
    Map<String, List<Path>> byProject = new TreeMap<>();
    for (Path bumFile : bumFiles) {
      Path rel = root.relativize(bumFile);
      String prefix = rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
      byProject.computeIfAbsent(prefix, k -> new ArrayList<>()).add(bumFile);
    }
    return byProject;
  }

  private Path findByName(Map<String, List<Path>> byProject, String machineName, String source)
      throws IOException {
    int slash = machineName.indexOf('/');
    if (slash >= 0) {
      String projectKey = machineName.substring(0, slash);
      String bareName = machineName.substring(slash + 1);
      List<Path> project = resolveProject(byProject, projectKey, source);
      String label = "project " + projectLabel(projectKey);
      // "-m <project>/" with no machine means: auto-select the most refined machine in that
      // project.
      return bareName.isEmpty()
          ? autoSelect(project, label)
          : findByNameInList(project, bareName, label);
    }

    // Bare name: accept it only when it identifies a single machine across every project.
    List<Path> allMachines =
        byProject.values().stream().flatMap(List::stream).collect(Collectors.toList());
    List<Path> matches = machinesNamed(allMachines, machineName);
    if (matches.isEmpty()) {
      throw new IOException("Machine '" + machineName + "' not found in " + source);
    }
    if (matches.size() > 1) {
      throw new IOException(
          "Machine '"
              + machineName
              + "' is ambiguous across projects in "
              + source
              + "; qualify it as -m <project>/<machine>: "
              + qualifiedNames(byProject, machineName));
    }
    return matches.get(0);
  }

  /**
   * Resolves the machines of the requested project. A single-project source has no project
   * directory of its own (its files sit at the root, keyed by ""), so the documented {@code
   * <project>/<machine>} form is accepted whatever prefix the user supplied rather than rejected as
   * an unknown project.
   */
  private List<Path> resolveProject(
      Map<String, List<Path>> byProject, String projectKey, String source) throws IOException {
    List<Path> project = byProject.get(projectKey);
    if (project != null) {
      return project;
    }
    if (byProject.size() == 1) {
      return byProject.values().iterator().next();
    }
    throw new IOException(
        "Project '"
            + projectKey
            + "' not found in "
            + source
            + ". Available projects: "
            + byProject.keySet().stream()
                .map(ModelResolver::projectLabel)
                .collect(Collectors.joining(", ")));
  }

  /** Returns the .bum files named {@code machineName}, sorted by path. */
  private List<Path> machinesNamed(List<Path> bumFiles, String machineName) {
    String target = machineName + RodinNames.BUM;
    return bumFiles.stream()
        .filter(p -> machineFileName(p).equals(target))
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Renders the projects that hold {@code machineName} as the {@code project/machine} hint form.
   */
  private String qualifiedNames(Map<String, List<Path>> byProject, String machineName) {
    String target = machineName + RodinNames.BUM;
    return byProject.entrySet().stream()
        .filter(entry -> entry.getValue().stream().anyMatch(p -> machineFileName(p).equals(target)))
        // Render the form the user can actually retry with: the root project takes no prefix.
        .map(entry -> entry.getKey().isEmpty() ? machineName : entry.getKey() + "/" + machineName)
        .collect(Collectors.joining(", "));
  }

  private Path findByNameInList(List<Path> bumFiles, String machineName, String label)
      throws IOException {
    List<Path> matches = machinesNamed(bumFiles, machineName);
    if (matches.isEmpty()) {
      throw new IOException("Machine '" + machineName + "' not found in " + label);
    }
    if (matches.size() > 1) {
      throw new IOException(
          "Machine '"
              + machineName
              + "' is ambiguous in "
              + label
              + ": "
              + matches.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
    return matches.get(0);
  }

  private String describeProjects(Map<String, List<Path>> byProject) {
    return byProject.entrySet().stream()
        .map(
            entry ->
                projectLabel(entry.getKey())
                    + " -> ["
                    + entry.getValue().stream()
                        .map(this::machineName)
                        .sorted()
                        .collect(Collectors.joining(", "))
                    + "]")
        .collect(Collectors.joining("; "));
  }

  private static String projectLabel(String prefix) {
    return prefix.isEmpty() ? "(root)" : prefix;
  }

  private void validateUniqueMachineNames(List<Path> bumFiles, String source) throws IOException {
    Map<String, List<Path>> filesByName =
        bumFiles.stream()
            .collect(
                Collectors.groupingBy(this::machineFileName, TreeMap::new, Collectors.toList()));
    List<String> duplicates =
        filesByName.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(
                entry ->
                    entry.getKey()
                        + " -> "
                        + entry.getValue().stream()
                            .map(Path::toString)
                            .sorted()
                            .collect(Collectors.joining(", ")))
            .collect(Collectors.toList());
    if (!duplicates.isEmpty()) {
      throw new IOException(
          "Duplicate machine names found in " + source + ": " + String.join("; ", duplicates));
    }
  }

  private Path findMostRefinedBum(List<Path> bumFiles, String source) throws IOException {
    Map<String, String> refinesTarget = new HashMap<>();
    Map<String, Path> pathByName = new HashMap<>();

    DocumentBuilderFactory factory;
    try {
      factory = SecureXml.documentBuilderFactory();
    } catch (ParserConfigurationException e) {
      throw new IOException("Failed to configure XML parser", e);
    }

    for (Path bumFile : bumFiles) {
      String machineName = machineName(bumFile);
      pathByName.put(machineName, bumFile);

      try {
        Document doc = factory.newDocumentBuilder().parse(bumFile.toFile());
        NodeList refines = doc.getElementsByTagName(RodinNames.REFINES_MACHINE);
        if (refines.getLength() > 0) {
          Element refEl = (Element) refines.item(0);
          String target = refEl.getAttribute(RodinNames.ATTR_TARGET);
          if (!target.isEmpty()) {
            refinesTarget.put(machineName, target);
          }
        }
      } catch (ParserConfigurationException | SAXException e) {
        throw new IOException("Failed to parse .bum file: " + bumFile, e);
      }
    }

    Set<String> refinedByOthers = new HashSet<>(refinesTarget.values());
    List<String> leaves =
        pathByName.keySet().stream()
            .filter(name -> !refinedByOthers.contains(name))
            .collect(Collectors.toList());

    if (leaves.isEmpty()) {
      throw new IOException("Circular refinement detected among .bum files in " + source);
    }
    if (leaves.size() > 1) {
      throw new IOException(
          "Multiple independent refinement chains found in "
              + source
              + ", cannot auto-select. Leaf machines: "
              + leaves.stream().sorted().collect(Collectors.joining(", ")));
    }

    return pathByName.get(leaves.get(0));
  }

  private String machineFileName(Path bumFile) {
    Path fileName = bumFile.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("Machine path must include a file name: " + bumFile);
    }
    return fileName.toString();
  }

  /**
   * The component's machine name: its file name with a Rodin component suffix (.bum machine or .buc
   * context) stripped. Shared with {@link Animate#resolveComponent} so the reported machine
   * identity is derived one way. A name without a recognized suffix is returned unchanged.
   */
  String machineName(Path componentFile) {
    String fileName = machineFileName(componentFile);
    if (fileName.endsWith(RodinNames.BUM) || fileName.endsWith(RodinNames.BUC)) {
      return fileName.substring(0, fileName.length() - RodinNames.BUM.length());
    }
    return fileName;
  }

  void cleanupTempDir() {
    if (tempDir != null) {
      try {
        MoreFiles.deleteRecursively(tempDir, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException e) {
        logger.warn("Failed to clean up temp directory: " + tempDir, e);
      }
      tempDir = null;
    }
  }
}
