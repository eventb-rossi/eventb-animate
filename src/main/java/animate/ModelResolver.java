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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class ModelResolver {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ModelResolver.class);

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
          if (entry.getName().endsWith(".bum")) {
            bumFiles.add(entryPath);
          }
        }
      }
    }

    return selectBumFile(bumFiles, machineName, "zip archive: " + model);
  }

  private Path resolveDirectory(Path dir, String machineName) throws IOException {
    List<Path> bumFiles;
    try (var stream = Files.walk(dir)) {
      bumFiles =
          stream
              .filter(p -> p.toString().endsWith(".bum"))
              .filter(Files::isRegularFile)
              .collect(Collectors.toList());
    }

    return selectBumFile(bumFiles, machineName, "directory: " + dir);
  }

  private Path selectBumFile(List<Path> bumFiles, String machineName, String source)
      throws IOException {
    if (bumFiles.isEmpty()) {
      throw new IOException("No .bum file found in " + source);
    }
    if (machineName != null) {
      return findByName(bumFiles, machineName, source);
    }
    if (bumFiles.size() == 1) {
      return bumFiles.get(0);
    }
    validateUniqueMachineNames(bumFiles, source);
    Path selected = findMostRefinedBum(bumFiles, source);
    logger.info(
        "Multiple .bum files found, auto-selected most refined: {}", selected.getFileName());
    return selected;
  }

  private Path findByName(List<Path> bumFiles, String machineName, String source)
      throws IOException {
    String target = machineName + ".bum";
    List<Path> matches =
        bumFiles.stream()
            .filter(p -> machineFileName(p).equals(target))
            .sorted()
            .collect(Collectors.toList());
    if (matches.isEmpty()) {
      throw new IOException("Machine '" + machineName + "' not found in " + source);
    }
    if (matches.size() > 1) {
      throw new IOException(
          "Machine '"
              + machineName
              + "' is ambiguous in "
              + source
              + ": "
              + matches.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
    return matches.get(0);
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
      factory = newDocumentBuilderFactory();
    } catch (ParserConfigurationException e) {
      throw new IOException("Failed to configure XML parser", e);
    }

    for (Path bumFile : bumFiles) {
      String machineName = machineName(bumFile);
      pathByName.put(machineName, bumFile);

      try {
        Document doc = factory.newDocumentBuilder().parse(bumFile.toFile());
        NodeList refines = doc.getElementsByTagName("org.eventb.core.refinesMachine");
        if (refines.getLength() > 0) {
          Element refEl = (Element) refines.item(0);
          String target = refEl.getAttribute("org.eventb.core.target");
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

  private DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  private String machineFileName(Path bumFile) {
    Path fileName = bumFile.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("Machine path must include a file name: " + bumFile);
    }
    return fileName.toString();
  }

  private String machineName(Path bumFile) {
    String fileName = machineFileName(bumFile);
    return fileName.substring(0, fileName.length() - ".bum".length());
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
