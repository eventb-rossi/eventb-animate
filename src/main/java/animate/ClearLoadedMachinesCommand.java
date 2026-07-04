package animate;

import de.prob.animator.command.AbstractCommand;
import de.prob.parser.ISimplifiedROMap;
import de.prob.prolog.output.IPrologTermOutput;
import de.prob.prolog.term.PrologTerm;

/**
 * Unloads whatever the ProB session has loaded, so a synthetic per-obligation context can be loaded
 * next. After this runs, the Java-side StateSpace no longer matches the Prolog side; the session
 * must only be driven with raw commands and never asked for states again.
 */
class ClearLoadedMachinesCommand extends AbstractCommand {

  @Override
  public void writeCommand(IPrologTermOutput pto) {
    pto.openTerm("clear_loaded_machines").closeTerm();
  }

  @Override
  public void processResult(ISimplifiedROMap<String, PrologTerm> bindings) {}
}
