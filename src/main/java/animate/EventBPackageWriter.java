package animate;

import de.prob.model.eventb.EventBModel;
import de.prob.model.eventb.translate.EventBModelTranslator;
import de.prob.prolog.output.PrologTermOutput;
import de.prob.statespace.StateSpace;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a ProB Event-B package (.eventb) — same as Api.eventb_save, but pretty-printed. */
final class EventBPackageWriter {

  private EventBPackageWriter() {}

  static void write(StateSpace stateSpace, Path path) throws IOException {
    EventBModelTranslator translator =
        new EventBModelTranslator((EventBModel) stateSpace.getModel());

    try (OutputStream out = Files.newOutputStream(path)) {
      PrologTermOutput pto = new PrologTermOutput(out, true);
      pto.openTerm("package");
      translator.printProlog(pto);
      pto.closeTerm();
      pto.fullstop();
      pto.flush();
    }
  }
}
