package animate;

import de.be4.classicalb.core.parser.analysis.prolog.ASTProlog;
import de.be4.classicalb.core.parser.node.AAxiomsContextClause;
import de.be4.classicalb.core.parser.node.AConstantsContextClause;
import de.be4.classicalb.core.parser.node.ADeferredSetSet;
import de.be4.classicalb.core.parser.node.AEventBContextParseUnit;
import de.be4.classicalb.core.parser.node.AIdentifierExpression;
import de.be4.classicalb.core.parser.node.AMemberPredicate;
import de.be4.classicalb.core.parser.node.APowSubsetExpression;
import de.be4.classicalb.core.parser.node.ASetsContextClause;
import de.be4.classicalb.core.parser.node.PExpression;
import de.be4.classicalb.core.parser.node.PPredicate;
import de.be4.classicalb.core.parser.node.PSet;
import de.be4.classicalb.core.parser.node.TIdentifierLiteral;
import de.prob.animator.command.AbstractCommand;
import de.prob.animator.domainobjects.IBEvalElement;
import de.prob.animator.domainobjects.IEvalElement;
import de.prob.parser.ISimplifiedROMap;
import de.prob.prolog.output.IPrologTermOutput;
import de.prob.prolog.term.ListPrologTerm;
import de.prob.prolog.term.PrologTerm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads a synthetic Event-B context holding exactly one proof obligation's environment: the
 * sequent's identifiers become constants typed by membership axioms (given sets become deferred
 * sets, or they would be declared twice), and its hypotheses become axioms. The construction
 * follows the disprover machinery of ProB's Rodin plugin (DisproverContextCreator /
 * DisproverLoadCommand, EPL-1.0).
 */
class DisproverContextLoadCommand extends AbstractCommand {

  private final PoSequentParser.Sequent sequent;

  DisproverContextLoadCommand(PoSequentParser.Sequent sequent) {
    this.sequent = sequent;
  }

  @Override
  public void writeCommand(IPrologTermOutput pto) {
    pto.openTerm("load_event_b_project");
    pto.openList();
    pto.closeList();
    pto.openList();
    disproverContext().apply(new ASTProlog(pto, null));
    pto.closeList();
    pto.openList();
    pto.openTerm("exporter_version");
    pto.printNumber(3);
    pto.closeTerm();
    pto.closeList();
    pto.printVariable("Errors");
    pto.closeTerm();
  }

  @Override
  public void processResult(ISimplifiedROMap<String, PrologTerm> bindings) {
    PrologTerm errors = bindings.get("Errors");
    if (errors instanceof ListPrologTerm list && !list.isEmpty()) {
      throw new IllegalStateException("loading the obligation's context failed: " + errors);
    }
  }

  private AEventBContextParseUnit disproverContext() {
    List<PExpression> constants = new ArrayList<>();
    List<PSet> sets = new ArrayList<>();
    List<PPredicate> axioms = new ArrayList<>();
    for (Map.Entry<String, IEvalElement> id : sequent.identifiers().entrySet()) {
      if (!(id.getValue() instanceof IBEvalElement typed)) {
        continue;
      }
      if (isGivenSet(id.getKey(), typed)) {
        sets.add(new ADeferredSetSet(List.of(new TIdentifierLiteral(id.getKey()))));
        continue;
      }
      PExpression identifier =
          new AIdentifierExpression(List.of(new TIdentifierLiteral(id.getKey())));
      axioms.add(new AMemberPredicate(identifier, (PExpression) typed.getAst()));
      constants.add(identifier.clone());
    }
    for (IEvalElement hypothesis : sequent.hypotheses()) {
      if (hypothesis instanceof IBEvalElement typed) {
        axioms.add((PPredicate) typed.getAst());
      }
    }
    return contextParseUnit(constants, sets, axioms);
  }

  /**
   * A given (carrier) set is typed by itself: S has type P(S). Checked on the parsed AST because
   * EventB translates its code to ASCII, so a textual comparison against the .bpo's unicode type
   * attribute would never match.
   */
  private static boolean isGivenSet(String name, IBEvalElement type) {
    return type.getAst() instanceof APowSubsetExpression pow
        && pow.getExpression() instanceof AIdentifierExpression identifier
        && identifier.getIdentifier().size() == 1
        && identifier.getIdentifier().get(0).getText().equals(name);
  }

  private static AEventBContextParseUnit contextParseUnit(
      List<PExpression> constants, List<PSet> sets, List<PPredicate> axioms) {
    return new AEventBContextParseUnit(
        new TIdentifierLiteral("DisproverContext"),
        List.of(
            new AAxiomsContextClause(axioms),
            new AConstantsContextClause(constants),
            new ASetsContextClause(sets)));
  }
}
