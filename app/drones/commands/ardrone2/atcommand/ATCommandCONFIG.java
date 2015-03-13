package drones.commands.ardrone2.atcommand;

/**
 * Created by brecht on 3/8/15.
 */
public class ATCommandCONFIG extends ATCommand {
    private static final String TYPE = "AT*CONFIG";

    private String key;
    private String value;

    public ATCommandCONFIG(int seq, String key, String value) {
        super(seq);
        this.key = key;
        this.value = value;
    }

    @Override
    public String toString() {
        return String.format("%s=%d,\"%s\",\"%s\"\r",TYPE, seq, key, value);
    }
}
