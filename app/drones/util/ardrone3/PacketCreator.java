package drones.util.ardrone3;

import akka.util.ByteStringBuilder;
import drones.handlers.ardrone3.ArDrone3TypeProcessor;
import drones.handlers.ardrone3.CommonTypeProcessor;
import drones.models.ardrone3.Packet;
import drones.models.ardrone3.PacketType;

/**
 * Created by Cedric on 3/8/2015.
 */
public class PacketCreator {

    public static Packet createFlatTrimPacket(){
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTING.getVal(), (short)0, null);
    }

    public static Packet createTakeOffPacket(){
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTING.getVal(), (short)1, null);
    }

    public static Packet createLandingPacket(){
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTING.getVal(), (short)3, null);
    }

    public static Packet createRequestStatusPacket(){
        return new Packet(PacketType.COMMON.getVal(), CommonTypeProcessor.CommonClass.COMMON.getVal(), (short)0, null);
    }

    public static Packet createRequestAllSettingsCommand(){
        return new Packet(PacketType.COMMON.getVal(), CommonTypeProcessor.CommonClass.SETTINGS.getVal(), (short)0, null);
    }

    public static Packet createOutdoorStatusPacket(boolean outdoor){
        ByteStringBuilder b = new ByteStringBuilder();
        b.putByte(outdoor ? (byte)1 : (byte)0);
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.SPEEDSETTINGS.getVal(), (short)3, b.result());
    }

    public static Packet createSetMaxAltitudePacket(float meters){
        ByteStringBuilder b = new ByteStringBuilder();
        b.putFloat(meters, FrameHelper.BYTE_ORDER);
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTINGSETTINGS.getVal(), (short)0, b.result());
    }

    public static Packet createSetMaxTiltPacket(float degrees){
        ByteStringBuilder b = new ByteStringBuilder();
        b.putFloat(degrees, FrameHelper.BYTE_ORDER);
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTINGSETTINGS.getVal(), (short)1, b.result());
    }

    public static Packet createMove3dPacket(boolean useRoll, byte roll, byte pitch, byte yaw, byte gaz){
        ByteStringBuilder b = new ByteStringBuilder();
        b.putByte(useRoll ? (byte)1 : (byte)0);
        b.putByte(roll); //Following bytes are signed! [-100;100]
        b.putByte(pitch);
        b.putByte(yaw);
        b.putByte(gaz);
        b.putFloat(0f, FrameHelper.BYTE_ORDER); //unused PSI heading for compass
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.PILOTING.getVal(), (short)2, b.result());
    }

    public static Packet createSetVideoStreamingStatePacket(boolean enabled){
        ByteStringBuilder b = new ByteStringBuilder();
        b.putByte(enabled ? (byte)1 : (byte)0);
        return new Packet(PacketType.ARDRONE3.getVal(), ArDrone3TypeProcessor.ArDrone3Class.MEDIASTREAMING.getVal(), (short)0, b.result());
    }
}
