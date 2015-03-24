package drones.messages;

import java.io.Serializable;

/**
 * Created by Cedric on 3/22/2015.
 */
public class PingMessage implements Serializable {
    private String ip;

    public PingMessage(String ip) {
        this.ip = ip;
    }

    public String getIp() {
        return ip;
    }
}
