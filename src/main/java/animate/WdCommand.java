package animate;

import de.prob.animator.command.CheckWellDefinednessCommand;
import de.prob.statespace.StateSpace;
import java.math.BigInteger;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "wd",
    description = "Check well-definedness proof obligations with ProB",
    mixinStandardHelpOptions = true,
    sortOptions = false,
    versionProvider = Animate.VersionProvider.class)
class WdCommand implements Callable<Integer> {

  @ParentCommand Animate parent;

  @Override
  public Integer call() {
    return parent.finishRun(parent.withStateSpace(this::checkWellDefinedness));
  }

  private RunReport checkWellDefinedness(StateSpace stateSpace) {
    CheckWellDefinednessCommand cmd = new CheckWellDefinednessCommand();
    try {
      stateSpace.execute(cmd);
    } catch (RuntimeException e) {
      // A ProB failure is a non-verdict (exit 2), not the exit-1 "obligations
      // not discharged" outcome the README documents.
      String message = "Well-definedness check did not complete: " + e.getMessage();
      System.err.println(message);
      return RunReport.singleCheck(RunReport.Status.INCOMPLETE, "well-definedness", message);
    }
    BigInteger discharged = cmd.getDischargedCount();
    BigInteger total = cmd.getTotalCount();

    String summary = "WD proof obligations: " + discharged + " discharged / " + total + " total";
    System.out.println(summary);
    if (discharged.equals(total)) {
      return RunReport.singleCheck(RunReport.Status.OK, "well-definedness", summary);
    }
    String message =
        total.subtract(discharged) + " well-definedness proof obligation(s) not discharged";
    System.err.println("Error: " + message);
    return RunReport.singleCheck(RunReport.Status.VIOLATION, "well-definedness", message);
  }
}
