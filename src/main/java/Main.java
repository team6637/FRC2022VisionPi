// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.cscore.MjpegServer;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoSource;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionThread;

public final class Main {
    private static String configFile = "/boot/frc.json";

    @SuppressWarnings("MemberName")
    public static class CameraConfig {
        public String name;
        public String path;
        public JsonObject config;
        public JsonElement streamConfig;
    }

    public static int team;
    public static boolean server;
    public static List<CameraConfig> cameraConfigs = new ArrayList<>();
    public static List<VideoSource> cameras = new ArrayList<>();
    public static double centerX = 0.0;

    private Main() {
    }

    /**
     * Report parse error.
     */
    public static void parseError(String str) {
        System.err.println("config error in '" + configFile + "': " + str);
    }

    /**
     * Read single camera configuration.
     */
    public static boolean readCameraConfig(JsonObject config) {
        CameraConfig cam = new CameraConfig();

        // name
        JsonElement nameElement = config.get("name");
        if (nameElement == null) {
        parseError("could not read camera name");
        return false;
        }
        cam.name = nameElement.getAsString();

        // path
        JsonElement pathElement = config.get("path");
        if (pathElement == null) {
        parseError("camera '" + cam.name + "': could not read path");
        return false;
        }
        cam.path = pathElement.getAsString();

        // stream properties
        cam.streamConfig = config.get("stream");

        cam.config = config;

        cameraConfigs.add(cam);
        return true;
    }

    /**
     * Read configuration file.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public static boolean readConfig() {
        // parse file
        JsonElement top;
        try {
        top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
        } catch (IOException ex) {
        System.err.println("could not open '" + configFile + "': " + ex);
        return false;
        }

        // top level must be an object
        if (!top.isJsonObject()) {
        parseError("must be JSON object");
        return false;
        }
        JsonObject obj = top.getAsJsonObject();

        // team number
        JsonElement teamElement = obj.get("team");
        if (teamElement == null) {
        parseError("could not read team number");
        return false;
        }
        team = teamElement.getAsInt();

        // ntmode (optional)
        if (obj.has("ntmode")) {
            String str = obj.get("ntmode").getAsString();
            if ("client".equalsIgnoreCase(str)) {
                server = false;
            } else if ("server".equalsIgnoreCase(str)) {
                server = true;
            } else {
                parseError("could not understand ntmode value '" + str + "'");
            }
        }

        // cameras
        JsonElement camerasElement = obj.get("cameras");
        if (camerasElement == null) {
            parseError("could not read cameras");
            return false;
        }
        JsonArray cameras = camerasElement.getAsJsonArray();
        
        for (JsonElement camera : cameras) {
            if (!readCameraConfig(camera.getAsJsonObject())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Main.
     */
    public static void main(String... args) {
        final Object imgLock = new Object();

        if (args.length > 0) configFile = args[0];

        // read configuration
        if (!readConfig()) return;

        // start NetworkTables
        NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
        if (server) {
            System.out.println("Setting up NetworkTables server");
            ntinst.startServer();
        } else {
            System.out.println("Setting up NetworkTables client for team " + team);
            ntinst.startClientTeam(team);
            ntinst.startDSClient();
        }

        // start cameras
        for (CameraConfig config : cameraConfigs) {

            CameraServer inst = CameraServer.getInstance();
            UsbCamera camera = new UsbCamera(config.name, config.path);
            MjpegServer server = inst.startAutomaticCapture(camera);

            Gson gson = new GsonBuilder().create();

            camera.setConfigJson(gson.toJson(config.config));
            camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

            if (config.streamConfig != null) {
                server.setConfigJson(gson.toJson(config.streamConfig));
            }

            cameras.add(camera);
        }


        // start image processing on camera 0 if present
        if (cameras.size() >= 1) {

            //Detects color of blue ball using camera
            VisionThread blueThread = new VisionThread(cameras.get(0),
                new BlueBallPipeline(), 
                pipeline -> {
                    if(!pipeline.filterContoursOutput().isEmpty()){
                        Rect r = Imgproc.boundingRect(pipeline.filterContoursOutput().get(0));
                        synchronized (imgLock) {
                            centerX = 640 / 2 - (r.x + (r.width / 2));
                        }
                        ntinst.getTable("Vision").getEntry("BLUE").setNumber(centerX);

                    }else{
                        ntinst.getTable("Vision").getEntry("BLUE").setNumber(0);
                    }
                }
            );
            blueThread.start();

            //Detects color of red ball using camera
            VisionThread redThread = new VisionThread(cameras.get(0),
                new RedBallPipeline(), 
                pipeline -> {
                    if(!pipeline.filterContoursOutput().isEmpty()){
                        Rect r = Imgproc.boundingRect(pipeline.filterContoursOutput().get(0));
                        synchronized (imgLock) {
                            centerX = 640 / 2 - (r.x + (r.width / 2));
                        }
                        ntinst.getTable("Vision").getEntry("RED").setNumber(centerX);

                    }else{
                        ntinst.getTable("Vision").getEntry("RED").setNumber(0);
                    }
                }
            );
            redThread.start();

        }

        // loop forever
        for (;;) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

}
