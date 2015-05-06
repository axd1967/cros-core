package controllers;

import akka.actor.ActorRef;
import com.avaje.ebean.ExpressionList;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import droneapi.api.DroneCommander;
import drones.models.Fleet;
import drones.scheduler.Scheduler;
import drones.scheduler.SchedulerException;
import drones.scheduler.messages.to.EmergencyMessage;
import models.Drone;
import models.DroneType;
import models.Location;
import models.User;
import play.Logger;
import play.data.Form;
import play.libs.F;
import play.libs.Json;
import play.mvc.BodyParser;
import play.mvc.Result;
import play.mvc.WebSocket;
import utilities.ControllerHelper;
import utilities.JsonHelper;
import utilities.QueryHelper;
import utilities.VideoWebSocket;
import utilities.annotations.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static play.mvc.Controller.request;
import static play.mvc.Results.*;

/**
 * Created by matthias on 19/02/2015.
 */

public class DroneController {

    private static ObjectNode EMPTY_RESULT;
    static {
        EMPTY_RESULT = Json.newObject();
        EMPTY_RESULT.put("status", "ok");
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static Result getAll() {
        ExpressionList<Drone> exp = QueryHelper.buildQuery(Drone.class, Drone.FIND.where(),false);

        List<JsonHelper.Tuple> tuples = exp.findList().stream().map(drone -> new JsonHelper.Tuple(drone, new ControllerHelper.Link("self",
                controllers.routes.DroneController.get(drone.getId()).absoluteURL(request())))).collect(Collectors.toList());

        // TODO: add links when available
        List<ControllerHelper.Link> links = new ArrayList<>();
        links.add(new ControllerHelper.Link("self", controllers.routes.DroneController.getAll().absoluteURL(request())));
        links.add(new ControllerHelper.Link("total", controllers.routes.DroneController.getTotal().absoluteURL(request())));
        links.add(new ControllerHelper.Link("types", controllers.routes.DroneController.getSuportedTypes().absoluteURL(request())));

        try {
            JsonNode result = JsonHelper.createJsonNode(tuples, links, Drone.class);
            String[] totalQuery = request().queryString().get("total");
            if (totalQuery != null && totalQuery.length == 1 && totalQuery[0].equals("true")) {
                ExpressionList<Drone> countExpression = QueryHelper.buildQuery(Drone.class, Drone.FIND.where(), true);
                String root = Drone.class.getAnnotation(JsonRootName.class).value();
                ((ObjectNode) result.get(root)).put("total",countExpression.findRowCount());
            }
            return ok(result);
        } catch(JsonProcessingException ex) {
            play.Logger.error(ex.getMessage(), ex);
            return internalServerError();
        }
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static Result getTotal() {
        return ok(JsonHelper.addRootElement(Json.newObject().put("total", Drone.FIND.findRowCount()), Drone.class));
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN, User.Role.USER})
    public static Result getSuportedTypes() {
        JsonNode node = JsonHelper.addRootElement(Json.toJson(Fleet.registeredDrivers().keySet()), DroneType.class);
        return ok(JsonHelper.addRootElement(node, Drone.class));
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static Result get(long id) {
        Drone drone = Drone.FIND.byId(id);

        if (drone == null)
            return notFound();

        return ok(JsonHelper.createJsonNode(drone, getAllLinks(id), Drone.class));
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> initVideo(long id){
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander d = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(d.initVideo()).map(v -> {
            ObjectNode result = Json.newObject();
            result.put("status", "ok");
            return ok(result);
        });
    }

    public static WebSocket<String> videoSocket(long id) {
        String[] tokens = request().queryString().get("authToken");

        if (tokens == null || tokens.length != 1 || tokens[0] == null)
            return WebSocket.reject(unauthorized());

        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return WebSocket.reject(notFound(Json.toJson("no drone found with this id")));

        User u = models.User.findByAuthToken(tokens[0]);
        if (u != null) {
            return WebSocket.withActor(out -> VideoWebSocket.props(out, id));
        } else {
            return WebSocket.reject(unauthorized());
        }
    }

    @Authentication({User.Role.ADMIN})
    @BodyParser.Of(BodyParser.Json.class)
    public static F.Promise<Result> create() {
        JsonNode body = request().body().asJson();
        JsonNode strippedBody;
        try {
            strippedBody = JsonHelper.removeRootElement(body, Drone.class, false);
        } catch(JsonHelper.InvalidJSONException ex) {
            play.Logger.debug(ex.getMessage(), ex);
            return F.Promise.pure(badRequest(ex.getMessage()));
        }
        Form<Drone> form = Form.form(Drone.class).bind(strippedBody);

        if (form.hasErrors())
            return F.Promise.pure(badRequest(form.errorsAsJson()));

        Drone drone = form.get();
        drone.save();

        try {
            Scheduler.addDrone(drone.getId());
        } catch (SchedulerException ex) {
            Logger.error("Failed to add drone to scheduler.",ex);
        }

        return F.Promise.pure(created(JsonHelper.createJsonNode(drone, getAllLinks(drone.getId()), Drone.class)));
    }

    @Authentication({User.Role.ADMIN})
    public static Result update(Long id) {
        return update(id, request().body().asJson().toString());
    }

    public static Result update(Long id, String update) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return notFound();

        if (drone.getStatus() == Drone.Status.FLYING)
            return forbidden("You cannot update a drone which is in flight.");

        JsonNode body = Json.parse(update);
        JsonNode strippedBody;
        try {
            strippedBody = JsonHelper.removeRootElement(body, Drone.class, false);
        } catch(JsonHelper.InvalidJSONException ex) {
            play.Logger.debug(ex.getMessage(), ex);
            return badRequest(ex.getMessage());
        }
        Form<Drone> droneForm = Form.form(Drone.class).bind(strippedBody);

        if (droneForm.hasErrors())
            return badRequest(droneForm.errors().toString());

        Drone updatedDrone = droneForm.get();

        if (!transitionAllowed(drone.getStatus(), updatedDrone.getStatus()))
            return forbidden(Json.toJson("cannot transition dronestatus from " + drone.getStatus() + " to " + updatedDrone.getStatus() + "."));

        updatedDrone.setVersion(drone.getVersion());
        updatedDrone.setId(drone.getId());
        updatedDrone.update();
        return ok(JsonHelper.createJsonNode(updatedDrone, getAllLinks(updatedDrone.getId()), Drone.class));
    }

    public static boolean transitionAllowed(Drone.Status s1, Drone.Status s2) {
        if (s1 == s2)
            return true;

        return ((s1 == Drone.Status.AVAILABLE && s2 == Drone.Status.CHARGING) ||
                (s1 == Drone.Status.AVAILABLE && s2 == Drone.Status.INACTIVE) ||
                (s1 == Drone.Status.AVAILABLE && s2 == Drone.Status.MANUAL_CONTROL) ||
                (s1 == Drone.Status.AVAILABLE && s2 == Drone.Status.RETIRED) ||

                (s1 == Drone.Status.CHARGING && s2 == Drone.Status.AVAILABLE) ||
                (s1 == Drone.Status.CHARGING && s2 == Drone.Status.INACTIVE) ||
                (s1 == Drone.Status.CHARGING && s2 == Drone.Status.MANUAL_CONTROL) ||
                (s1 == Drone.Status.CHARGING && s2 == Drone.Status.RETIRED) ||

                (s1 == Drone.Status.EMERGENCY && s2 == Drone.Status.AVAILABLE) ||
                (s1 == Drone.Status.EMERGENCY && s2 == Drone.Status.CHARGING) ||
                (s1 == Drone.Status.EMERGENCY && s2 == Drone.Status.INACTIVE) ||
                (s1 == Drone.Status.EMERGENCY && s2 == Drone.Status.MANUAL_CONTROL) ||
                (s1 == Drone.Status.EMERGENCY && s2 == Drone.Status.RETIRED) ||

                (s1 == Drone.Status.INACTIVE && s2 == Drone.Status.AVAILABLE) ||
                (s1 == Drone.Status.INACTIVE && s2 == Drone.Status.CHARGING) ||
                (s1 == Drone.Status.INACTIVE && s2 == Drone.Status.MANUAL_CONTROL) ||
                (s1 == Drone.Status.INACTIVE && s2 == Drone.Status.RETIRED) ||

                (s1 == Drone.Status.MANUAL_CONTROL && s2 == Drone.Status.AVAILABLE) ||
                (s1 == Drone.Status.MANUAL_CONTROL && s2 == Drone.Status.CHARGING) ||
                (s1 == Drone.Status.MANUAL_CONTROL && s2 == Drone.Status.INACTIVE) ||
                (s1 == Drone.Status.MANUAL_CONTROL && s2 == Drone.Status.RETIRED) ||

                (s1 == Drone.Status.RETIRED && s2 == Drone.Status.AVAILABLE) ||
                (s1 == Drone.Status.RETIRED && s2 == Drone.Status.CHARGING) ||
                (s1 == Drone.Status.RETIRED && s2 == Drone.Status.INACTIVE) ||
                (s1 == Drone.Status.RETIRED && s2 == Drone.Status.MANUAL_CONTROL)
        );
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> location(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null) {
            return F.Promise.pure(notFound());
        }
        if(!Fleet.getFleet().hasCommander(drone)){
            // TODO: Handle when a drone has no commander (yet).
            return F.Promise.pure(notFound());
        }

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getLocation()).flatMap(v -> F.Promise.wrap(commander.getAltitude()).map(altitude ->  {
            Location l = new Location(v.getLatitude(),v.getLongitude(), altitude);
            JsonNode node = JsonHelper.addRootElement(Json.toJson(l), Location.class);
            return ok(JsonHelper.addRootElement(node, Drone.class));
        }));

    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> testConnection(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        ObjectNode node = Json.newObject();
        node.put("connection", true); // TODO: call connection Test
        return F.Promise.pure(ok(JsonHelper.addRootElement(node, Drone.class)));
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> battery(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getBatteryPercentage()).map(percentage -> {
            ObjectNode node = Json.newObject().put("battery", percentage);
            return ok(JsonHelper.addRootElement(node, Drone.class));
        });
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> rotation(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getRotation()).map(rotation -> {
            ObjectNode node = Json.newObject();
            node.put("rotation", Json.toJson(rotation));
            return ok(JsonHelper.addRootElement(node, Drone.class));
        });
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> speed(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getSpeed()).map(speed -> {
            ObjectNode node = Json.newObject();
            node.put("speed", Json.toJson(speed));
            return ok(JsonHelper.addRootElement(node, Drone.class));
        });
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> altitude(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getAltitude()).map(altitude -> {
            ObjectNode node = Json.newObject();
            node.put("altitude", Json.toJson(altitude));
            return ok(JsonHelper.addRootElement(node, Drone.class));
        });
    }

    @Authentication({User.Role.ADMIN, User.Role.READONLY_ADMIN})
    public static F.Promise<Result> cameraCapture(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        DroneCommander commander = Fleet.getFleet().getCommanderForDrone(drone);
        return F.Promise.wrap(commander.getImage()).map(image -> {
            ObjectNode node = Json.newObject();
            node.put("image", Json.toJson(image));
            return ok(JsonHelper.addRootElement(node, Drone.class));
        });
    }

    @Authentication({User.Role.ADMIN})
    public static F.Promise<Result> emergency(Long id) {
        Drone drone = Drone.FIND.byId(id);
        if (drone == null)
            return F.Promise.pure(notFound());

        // [QUICK FIX] Send emergency via Scheduler
        try {
            // TODO: Use advanced scheduler in the future for more reliable emergency.
            Scheduler.getScheduler().tell(new EmergencyMessage(drone.getId()), ActorRef.noSender());
            return F.Promise.pure(ok(EMPTY_RESULT));
        }catch(SchedulerException ex){
            Logger.error("Scheduler error", ex);
            return F.Promise.pure(internalServerError("Scheduler could not process emergency."));
        }
    }

    @Authentication({User.Role.ADMIN})
    public static Result deleteAll() {
        Drone.FIND.all().forEach(d -> d.delete());
        return ok(EMPTY_RESULT);
    }

    @Authentication({User.Role.ADMIN})
    public static Result delete(long i) {
        Drone d = Drone.FIND.byId(i);
        if (d == null)
            return notFound();

        d.delete();
        return ok(EMPTY_RESULT);
    }

    private static final List<ControllerHelper.Link> getAllLinks(long id) {
        List<ControllerHelper.Link> links = new ArrayList<>();
        links.add(new ControllerHelper.Link("self", controllers.routes.DroneController.get(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("connection", controllers.routes.DroneController.testConnection(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("battery", controllers.routes.DroneController.battery(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("cameraCapture", controllers.routes.DroneController.cameraCapture(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("emergency", controllers.routes.DroneController.emergency(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("location", controllers.routes.DroneController.location(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("speed", controllers.routes.DroneController.speed(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("rotation", controllers.routes.DroneController.rotation(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("altitude", controllers.routes.DroneController.altitude(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("commands", controllers.routes.ManualDroneController.links(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("videoSocket", controllers.routes.DroneController.videoSocket(id).absoluteURL(request())));
        links.add(new ControllerHelper.Link("initVideo", controllers.routes.DroneController.initVideo(id).absoluteURL(request())));
        return links;
    }
}
