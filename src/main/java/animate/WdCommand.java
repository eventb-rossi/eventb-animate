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
    return parent.withStateSpace(this::checkWellDefinedness);
  }

  private int checkWellDefinedness(StateSpace stateSpace) {
    CheckWellDefinednessCommand cmd = new CheckWellDefinednessCommand();
    try {
      stateSpace.execute(cmd);
    } catch (RuntimeException e) {
      // A ProB failure is a non-verdict (exit 2), not the exit-1 "obligations
      // not discharged" outcome the README documents.
      System.err.println("Well-definedness check did not complete: " + e.getMessage());
      return 2;
    }
    BigInteger discharged = cmd.getDischargedCount();
    BigInteger total = cmd.getTotalCount();

    System.out.println("WD proof obligations: " + discharged + " discharged / " + total + " total");
    if (discharged.equals(total)) {
      return 0;
    }
    System.err.println(
        "Error: "
            + total.subtract(discharged)
            + " well-definedness proof obligation(s) not discharged");
    return 1;
  }
}
