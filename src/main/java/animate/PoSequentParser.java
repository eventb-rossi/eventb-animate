package animate;

import de.prob.animator.domainobjects.EventB;
import de.prob.animator.domainobjects.IEvalElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the full sequents (goal, hypotheses, selected hypotheses, typed identifiers) of every proof
 * obligation in a Rodin .bpo file. The bundled ProB kernel parses the same file but only keeps name
 * and status, so the sequent extraction is ported here from the kernel's newer ProofXmlHandler
 * (prob-java, EPL-1.0).
 *
 * <p>Hypotheses form a chain of predicate sets linked by parentSet references; a sequent points at
 * the head of its chain, and selection hints mark which sets and single predicates Rodin considers
 * selected (cf. org.eventb.internal.core.pom.POLoader).
 */
final class PoSequentParser extends DefaultHandler {

  /** One obligation's sequent; the formulas are lazily parsed Event-B eval elements. */
  record Sequent(
      IEvalElement goal,
      List<IEvalElement> hypotheses,
      List<IEvalElement> selectedHypotheses,
      Map<String, IEvalElement> identifiers) {}

  private static final String PREDICATE_SET_PREFIX = RodinNames.PREDICATE_SET_HANDLE_PREFIX;
  private static final String PREDICATE_PREFIX = RodinNames.PREDICATE_HANDLE_PREFIX;

  private final Set<String> sequentNames = new LinkedHashSet<>();
  private final Map<String, IEvalElement> goals = new LinkedHashMap<>();
  private final Map<String, String> poPredicateSets = new LinkedHashMap<>();
  private final Map<String, String> predicateSetParents = new LinkedHashMap<>();
  private final Map<String, Map<String, IEvalElement>> predicateSetPredicates =
      new LinkedHashMap<>();
  private final Map<String, Map<String, IEvalElement>> predicateSetIdentifiers =
      new LinkedHashMap<>();
  private final Map<String, List<String[]>> singleSelectionHints = new LinkedHashMap<>();
  private final Map<String, List<String[]>> rangeSelectionHints = new LinkedHashMap<>();

  // Flattened hypothesis chains, memoized per set: ancestor sets are shared by all of a
  // component's obligations, and only the open ones ever ask for their sequents.
  private final Map<String, List<IEvalElement>> flattenedPredicates = new LinkedHashMap<>();
  private final Map<String, Map<String, IEvalElement>> flattenedIdentifiers = new LinkedHashMap<>();

  private String currSequent;
  private String currHypSet;

  private PoSequentParser() {}

  static PoSequentParser parse(Path bpoFile) throws IOException {
    PoSequentParser handler = new PoSequentParser();
    try {
      SecureXml.saxParserFactory().newSAXParser().parse(bpoFile.toFile(), handler);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("cannot parse " + bpoFile + ": " + e.getMessage(), e);
    }
    return handler;
  }

  /** The named obligation's sequent, materialized on demand; null for an unknown name. */
  Sequent sequent(String poName) {
    if (!sequentNames.contains(poName)) {
      return null;
    }
    String hypSetHead = poPredicateSets.get(poName);
    return new Sequent(
        goals.get(poName),
        predicatesOf(hypSetHead),
        selectedHypotheses(poName, hypSetHead),
        identifiersOf(hypSetHead));
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    switch (qName) {
      case RodinNames.PO_SEQUENT -> {
        currSequent = attributes.getValue(RodinNames.ATTR_NAME);
        sequentNames.add(currSequent);
        singleSelectionHints.put(currSequent, new ArrayList<>());
        rangeSelectionHints.put(currSequent, new ArrayList<>());
      }
      case RodinNames.PO_PREDICATE_SET -> {
        String parentSet = attributes.getValue(RodinNames.ATTR_PARENT_SET);
        if (currSequent == null) {
          currHypSet = attributes.getValue(RodinNames.ATTR_NAME);
        } else {
          // The sequent's local predicate set: its predicates are hypotheses of this
          // obligation alone, so it heads the sequent's hypothesis chain. Local sets
          // all share the name SEQHYP; qualify with the sequent for a unique key.
          currHypSet = currSequent + "|" + attributes.getValue(RodinNames.ATTR_NAME);
          poPredicateSets.put(currSequent, currHypSet);
        }
        predicateSetPredicates.put(currHypSet, new LinkedHashMap<>());
        predicateSetIdentifiers.put(currHypSet, new LinkedHashMap<>());
        if (parentSet != null) {
          predicateSetParents.put(currHypSet, extractName(parentSet, PREDICATE_SET_PREFIX));
        }
      }
      case RodinNames.PO_PREDICATE -> {
        String predicate = attributes.getValue(RodinNames.ATTR_PREDICATE);
        if (currHypSet != null) {
          predicateSetPredicates
              .get(currHypSet)
              .put(attributes.getValue(RodinNames.ATTR_NAME), new EventB(predicate));
        } else if (currSequent != null) {
          goals.put(currSequent, new EventB(predicate));
        }
      }
      case RodinNames.PO_SEL_HINT -> {
        if (currSequent != null) {
          String first = attributes.getValue(RodinNames.ATTR_SEL_HINT_FST);
          String second = attributes.getValue(RodinNames.ATTR_SEL_HINT_SND);
          if (second == null) {
            int predicateIdx = first.indexOf(PREDICATE_PREFIX);
            String predName = extractName(first, PREDICATE_PREFIX);
            String setName = extractName(first.substring(0, predicateIdx), PREDICATE_SET_PREFIX);
            singleSelectionHints.get(currSequent).add(new String[] {setName, predName});
          } else {
            rangeSelectionHints
                .get(currSequent)
                .add(
                    new String[] {
                      extractName(first, PREDICATE_SET_PREFIX),
                      extractName(second, PREDICATE_SET_PREFIX)
                    });
          }
        }
      }
      case RodinNames.PO_IDENTIFIER -> {
        if (currHypSet != null) {
          predicateSetIdentifiers
              .get(currHypSet)
              .put(
                  attributes.getValue(RodinNames.ATTR_NAME),
                  new EventB(attributes.getValue(RodinNames.ATTR_TYPE)));
        }
      }
      default -> {}
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    if (RodinNames.PO_PREDICATE_SET.equals(qName)) {
      // Also inside a sequent: the goal poPredicate follows the local set as a
      // direct child of the sequent and must not be routed into the set.
      currHypSet = null;
    } else if (RodinNames.PO_SEQUENT.equals(qName)) {
      currSequent = null;
    }
  }

  /**
   * Names are the part after the typed '#' anchor; Rodin escapes '/' and '\' in them with a
   * backslash, so each backslash simply quotes the character after it.
   */
  private static String extractName(String reference, String prefix) {
    int idx = reference.indexOf(prefix);
    if (idx == -1) {
      throw new IllegalStateException("cannot extract a name from reference: " + reference);
    }
    String value = reference.substring(idx + prefix.length());
    StringBuilder unescaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        i++;
        unescaped.append(value.charAt(i));
      } else {
        unescaped.append(c);
      }
    }
    return unescaped.toString();
  }

  private List<IEvalElement> selectedHypotheses(String poLabel, String hypSetHead) {
    // A range hint selects every set from the second reference up to (excluding) the
    // first; the local set is referenced under its fixed unqualified name SEQHYP, which
    // must be resolved before the exclusive-endpoint comparison, or the endpoint and
    // its whole ancestor chain get selected when the local set sits directly below it.
    Set<String> selectedSets = new LinkedHashSet<>();
    for (String[] range : rangeSelectionHints.get(poLabel)) {
      String start = range[0];
      String end = RodinNames.LOCAL_HYP_SET.equals(range[1]) ? hypSetHead : range[1];
      while (end != null && !end.equals(start)) {
        selectedSets.add(end);
        end = predicateSetParents.get(end);
      }
    }
    List<IEvalElement> selected = new ArrayList<>();
    for (String set : selectedSets) {
      Map<String, IEvalElement> predicates = predicateSetPredicates.get(set);
      if (predicates != null) {
        selected.addAll(predicates.values());
      }
    }
    for (String[] hint : singleSelectionHints.get(poLabel)) {
      Map<String, IEvalElement> predicates = predicateSetPredicates.get(hint[0]);
      if (predicates != null && predicates.get(hint[1]) != null) {
        selected.add(predicates.get(hint[1]));
      }
    }
    return selected;
  }

  private List<IEvalElement> predicatesOf(String setName) {
    if (setName == null || predicateSetPredicates.get(setName) == null) {
      return List.of();
    }
    List<IEvalElement> flattened = flattenedPredicates.get(setName);
    if (flattened == null) {
      flattened = new ArrayList<>(predicatesOf(predicateSetParents.get(setName)));
      flattened.addAll(predicateSetPredicates.get(setName).values());
      flattenedPredicates.put(setName, flattened);
    }
    return flattened;
  }

  private Map<String, IEvalElement> identifiersOf(String setName) {
    if (setName == null || predicateSetIdentifiers.get(setName) == null) {
      return Map.of();
    }
    Map<String, IEvalElement> flattened = flattenedIdentifiers.get(setName);
    if (flattened == null) {
      flattened = new LinkedHashMap<>(identifiersOf(predicateSetParents.get(setName)));
      flattened.putAll(predicateSetIdentifiers.get(setName));
      flattenedIdentifiers.put(setName, flattened);
    }
    return flattened;
  }
}
