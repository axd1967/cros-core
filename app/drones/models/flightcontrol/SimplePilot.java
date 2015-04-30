package drones.models.flightcontrol;

import akka.actor.ActorRef;
import akka.dispatch.OnSuccess;
import api.DroneCommander;
import drones.models.flightcontrol.messages.*;
import messages.FlyingStateChangedMessage;
import messages.LocationChangedMessage;
import messages.NavigationStateChangedMessage;
import model.properties.FlyingState;
import model.properties.Location;
import model.properties.NavigationState;
import drones.models.scheduler.messages.to.FlightCanceledMessage;
import drones.models.scheduler.messages.to.FlightCompletedMessage;
import models.Checkpoint;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;


/**
 * Created by Sander on 18/03/2015.
 *
 * Pilot class to fly with the drone to its destination via the waypoints.
 * He lands on the last item in the list.
 */
public class SimplePilot extends Pilot {

    private Location actualLocation;

    private List<Checkpoint> waypoints;
    private int actualWaypoint = -1;

    //List of points where the drone cannot fly
    private List<Location> noFlyPoints = new ArrayList<>();
    //List of points(wrapped in messages) where the drone currently is but that need to be evacuated for a land.
    private List<LocationMessage> evacuationPoints = new ArrayList<>();

    //Range around a no fly point where the drone cannot fly.
    private static final int NO_FY_RANGE = 4;
    //Range around a evacuation point where the drone should be evacuated.
    private static final int EVACUATION_RANGE = 6;

    private boolean waitForTakeOffDone = false;
    private boolean start = false;

    
    /**
     * @param reporterRef            Actor to report the messages. In theory this should be the same actor that sends the start message.
     * @param droneId                  Drone to control.
     * @param linkedWithControlTower True if connected to ControlTower
     * @param waypoints              Route to fly, the drone will land on the last item
     */
    public SimplePilot(ActorRef reporterRef, Long droneId, boolean linkedWithControlTower, List<Checkpoint> waypoints) {
        super(reporterRef, droneId, linkedWithControlTower);

        if (waypoints.size() < 1) {
            throw new IllegalArgumentException("Waypoints must contain at least 1 element");
        }
        this.waypoints = waypoints;
    }

    /**
     * Use only for testing!
     */
    public SimplePilot(ActorRef reporterRef, DroneCommander dc, boolean linkedWithControlTower, List<Checkpoint> waypoints) {
        super(reporterRef, dc, linkedWithControlTower);

        if (waypoints.size() < 1) {
            throw new IllegalArgumentException("Waypoints must contain at least 1 element");
        }
        this.waypoints = waypoints;
    }

    @Override
    public void start() {
        if (cruisingAltitude == 0) {
            cruisingAltitude = DEFAULT_ALTITUDE;
        }
        try {
            Await.ready(dc.setMaxHeight(4), Duration.create(10, "seconds"));
            start = true;
            takeOff();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void goToNextWaypoint() {
        if (actualWaypoint >= 0) {
            if (actualWaypoint == waypoints.size()) {
                //arrived at destination => land
                land();
            } else {
                models.Location waypoint = waypoints.get(actualWaypoint).getLocation();
                dc.moveToLocation(waypoint.getLatitude(), waypoint.getLongitude(), cruisingAltitude);
            }
        }
    }

    private void land() {
        if (linkedWithControlTower) {
            reporterRef.tell(new RequestForLandingMessage(self(), actualLocation), self());
        } else {
            dc.land().onSuccess(new OnSuccess<Void>() {

                @Override
                public void onSuccess(Void result) throws Throwable {
                    start = false;
                    reporterRef.tell(new FlightCompletedMessage(droneId,actualLocation),self());
                }
            }, getContext().system().dispatcher());
        }
    }

    @Override
    protected void locationChanged(LocationChangedMessage m) {
        if(start){
            actualLocation = new Location(m.getLatitude(), m.getLongitude(), m.getGpsHeight());
            for (LocationMessage l : evacuationPoints) {
                if (actualLocation.distance(l.getLocation()) > EVACUATION_RANGE) {

                    evacuationPoints.remove(l);
                    noFlyPoints.add(l.getLocation());
                    switch (l.getType()) {
                        case LANDING:
                            reporterRef.tell(new RequestForLandingGrantedMessage(l.getRequestor(), l.getLocation()), self());
                            break;
                        case TAKEOFF:
                            reporterRef.tell(new RequestForTakeOffGrantedMessage(l.getRequestor(), l.getLocation()), self());
                            break;
                        default:
                            log.debug("Unsupported type");
                    }

                }
            }
            for (Location l : noFlyPoints) {
                if (actualLocation.distance(l) < NO_FY_RANGE) {
                    //stop with flying
                    dc.cancelMoveToLocation();
                }
            }
        }

    }

    @Override
    protected void requestForLandingMessage(RequestForLandingMessage m) {
        if (actualLocation.distance(m.getLocation()) <= EVACUATION_RANGE) {
            evacuationPoints.add(m);
        } else {
            noFlyPoints.add(m.getLocation());
            reporterRef.tell(new RequestForLandingGrantedMessage(m.getRequestor(), m.getLocation()), self());
        }
    }

    @Override
    protected void requestForLandingGrantedMessage(RequestForLandingGrantedMessage m) {
        dc.land().onSuccess(new OnSuccess<Void>() {

            @Override
            public void onSuccess(Void result) throws Throwable {
                start = false;
                reporterRef.tell(new LandingCompletedMessage(m.getRequestor(), m.getLocation()), self());
                reporterRef.tell(new FlightCompletedMessage(droneId, actualLocation), self());
            }
        }, getContext().system().dispatcher());
    }

    @Override
    protected void landingCompletedMessage(LandingCompletedMessage m) {
        completedMessage(m);
    }

    private void completedMessage(LocationMessage m) {
        noFlyPoints.remove(m.getLocation());

        //try to fly further
        for (Location l : noFlyPoints) {
            if (actualLocation.distance(l) < NO_FY_RANGE) {
                return;
            }
        }

        //allowed to continue flying
        models.Location waypoint = waypoints.get(actualWaypoint).getLocation();
        dc.moveToLocation(waypoint.getLatitude(), waypoint.getLongitude(), cruisingAltitude);
    }

    @Override
    protected void requestForTakeOffMessage(RequestForTakeOffMessage m) {
        if (actualLocation.distance(m.getLocation()) <= EVACUATION_RANGE) {
            evacuationPoints.add(m);
        } else {
            noFlyPoints.add(m.getLocation());
            reporterRef.tell(new RequestForTakeOffGrantedMessage(m.getRequestor(), m.getLocation()), self());
        }
    }

    @Override
    protected void requestForTakeOffGrantedMessage(RequestForTakeOffGrantedMessage m) {
        //TO DO on failure
        dc.takeOff().onSuccess(new OnSuccess<Void>() {
            @Override
            public void onSuccess(Void result) throws Throwable {
                reporterRef.tell(new TakeOffCompletedMessage(m.getRequestor(), m.getLocation()), self());
            }
        }, getContext().system().dispatcher());
    }

    @Override
    protected void takeOffCompletedMessage(TakeOffCompletedMessage m) {
        completedMessage(m);
    }

    private void takeOff() {
        if (linkedWithControlTower) {
            reporterRef.tell(new RequestForTakeOffMessage(self(), actualLocation), self());
        } else {
            dc.takeOff().onSuccess(new OnSuccess<Void>() {
                @Override
                public void onSuccess(Void result) throws Throwable {
                }
            }, getContext().system().dispatcher());
        }

        waitForTakeOffDone = true;
    }

    @Override
    protected void flyingStateChanged(FlyingStateChangedMessage m) {
        if(start && waitForTakeOffDone && m.getState() == FlyingState.HOVERING){
            waitForTakeOffDone = false;
            actualWaypoint++;
            goToNextWaypoint();
        }
    }

    @Override
    protected void navigationStateChanged(NavigationStateChangedMessage m) {
        if(start && m.getState() == NavigationState.AVAILABLE){
            switch (m.getReason()){
                case FINISHED:
                    //TO DO wait at checkpoint
                    actualWaypoint++;
                    goToNextWaypoint();
                    break;
                case STOPPED:
                    start = false;
            }

        }
    }

    @Override
    protected void stopFlightControlMessage(StopFlightControlMessage m) {
        if (actualWaypoint != waypoints.size()){
            try {
                Await.ready(dc.land(), Duration.create(2, "seconds"));
            } catch (Exception e) {
                e.printStackTrace();
                //TO DO add exception
            }
            reporterRef.tell(new FlightCanceledMessage(droneId), self());
        }
        //stop
        getContext().stop(self());
    }
}