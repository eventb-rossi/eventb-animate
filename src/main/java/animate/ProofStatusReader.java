package animate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the broken-proof flags of a Rodin .bps proof-status file. Rodin marks a status {@code
 * org.eventb.core.psBroken="true"} when the stored proof no longer applies to the current
 * obligation (the model changed since it was proved); the proof must be replayed before it counts.
 * Rodin renders such an obligation as broken rather than discharged, whatever its recorded
 * confidence.
 *
 * <p>The bundled ProB kernel parses the same file but keeps only the confidence, so a
 * discharged-but-broken proof reaches {@link de.prob.model.eventb.ProofObligation#isDischarged()}
 * as discharged. The {@code po} gate would then pass a model whose proofs are stale, so the broken
 * flags are read here and folded back in.
 */
final class ProofStatusReader extends DefaultHandler {

  private static final String PS_STATUS = "org.eventb.core.psStatus";
  private static final String PS_BROKEN = "org.eventb.core.psBroken";

  private final Set<String> broken = new LinkedHashSet<>();

  private ProofStatusReader() {}

  /** Names of the obligations whose stored proof is broken; empty when the file records none. */
  static Set<String> brokenObligations(Path bpsFile) throws IOException {
    ProofStatusReader handler = new ProofStatusReader();
    try {
      SecureXml.saxParserFactory().newSAXParser().parse(bpsFile.toFile(), handler);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("cannot parse " + bpsFile + ": " + e.getMessage(), e);
    }
    return handler.broken;
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    if (PS_STATUS.equals(qName) && "true".equals(attributes.getValue(PS_BROKEN))) {
      broken.add(attributes.getValue("name"));
    }
  }
}
