package drones.models.ATCommand;

/**
 * @TODO see what this command does
 *
 * Created by brecht on 3/8/15.
 */
public class ATCommandPMODE extends ATCommand {
    private static final String TYPE = "AT*PMODE";

    private int param1;

    public ATCommandPMODE(int param1) {
        this.param1 = param1;
    }

    @Override
    public String toString() {
        return TYPE + "=" + seq + "," + param1 + "\r";
    }
}
