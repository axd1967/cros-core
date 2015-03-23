package drones.models;

import models.Drone;
import models.DroneType;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by yasser on 17/03/15.
 */
public class BepopDriver implements DroneDriver {

    public static final DroneType BEPOP_TYPE = new DroneType("ARDrone3", "Bepop");

    @Override
    public Set<DroneType> supportedTypes() {

        Set<DroneType> supportedTypes = new HashSet<>();
        supportedTypes.add(BEPOP_TYPE);
        return supportedTypes;
    }

    @Override
    public Class<Bepop> getActorClass() {
        return Bepop.class;
    }

    @Override
    public <T extends DroneActor> T createActor(Drone droneEntity) {
        // TODO: set indoor property to true
        return (T) new Bepop(droneEntity.getAddress(), true);
    }
}