package animate;

/**
 * The Rodin database schema names this tool reads directly out of the .bum machine files and the
 * .bpo/.bps proof files -- element names, attribute names, handle-reference prefixes, and the fixed
 * local hypothesis-set name -- together with the Rodin component and proof file suffixes. The
 * bundled kernel discards the proof sequents and the broken-proof flags, so these files are parsed
 * here (see {@link PoSequentParser}, {@link ProofStatusReader}, {@link ModelResolver}); keeping the
 * schema in one place stops the same names being re-spelled as inline literals across those readers
 * and {@link PoCommand}. The names are stable Rodin core constants, verified against the Rodin
 * sources.
 *
 * <p>Every field is a compile-time constant (String concatenation of constants), so the element
 * names can be used as {@code switch} case labels.
 */
final class RodinNames {

  private RodinNames() {}

  private static final String NS = "org.eventb.core.";

  /** The local name of an element within its component, carried by most Rodin elements. */
  static final String ATTR_NAME = "name";

  // .bum machine elements (refinement-chain analysis in ModelResolver).
  static final String REFINES_MACHINE = NS + "refinesMachine";
  static final String ATTR_TARGET = NS + "target";

  // .bpo proof-obligation elements (PoSequentParser).
  static final String PO_SEQUENT = NS + "poSequent";
  static final String PO_PREDICATE_SET = NS + "poPredicateSet";
  static final String PO_PREDICATE = NS + "poPredicate";
  static final String PO_SEL_HINT = NS + "poSelHint";
  static final String PO_IDENTIFIER = NS + "poIdentifier";
  static final String ATTR_PARENT_SET = NS + "parentSet";
  static final String ATTR_PREDICATE = NS + "predicate";
  static final String ATTR_TYPE = NS + "type";
  static final String ATTR_SEL_HINT_FST = NS + "poSelHintFst";
  static final String ATTR_SEL_HINT_SND = NS + "poSelHintSnd";

  /** Handle references have the form {@code |<elementType>#<name>}, e.g. a set's parentSet. */
  static final String PREDICATE_SET_HANDLE_PREFIX = "|" + PO_PREDICATE_SET + "#";

  static final String PREDICATE_HANDLE_PREFIX = "|" + PO_PREDICATE + "#";

  /** Every sequent's local hypothesis set carries this fixed name. */
  static final String LOCAL_HYP_SET = "SEQHYP";

  // .bps proof-status elements (ProofStatusReader).
  static final String PS_STATUS = NS + "psStatus";
  static final String ATTR_PS_BROKEN = NS + "psBroken";

  // Rodin component and proof file suffixes.
  static final String BUM = ".bum";
  static final String BUC = ".buc";
  static final String BPO = ".bpo";
  static final String BPS = ".bps";
}
