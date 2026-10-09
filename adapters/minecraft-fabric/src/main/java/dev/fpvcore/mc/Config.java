package dev.fpvcore.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/fpvcore.json */
public final class Config {
    public String preset = "5race";
    public double fovHorizontal = 120;      // degrees, converted to Minecraft's vertical FOV by window aspect
    public double uptiltDeg = -1;           // < 0: preset default (camera_tilt_deg)
    public double droneRadius = 0.10;       // collision sphere (m)
    public boolean simulateBattery = false;
    public boolean useKeyboard = true;      // keyboard fallback when no controller is connected
    public boolean chaseCam = false;
    public double chaseDistance = 1.6;
    public int joystick = -1;               // GLFW joystick slot of the selected controller (re-resolved by GUID), -1 = none
    public String controllerGuid = "";      // selected controller ("" = none chosen yet: the first real RC/gamepad is auto-selected)
    public String controllerName = "";
    public boolean forceKeyboard = false;   // user chose "keyboard only"
    public java.util.Map<String, Mapping> mappings = new java.util.HashMap<>(); // calibration per controller GUID
    public double hintBlocks = 0;
    public double droneVolume = 1.0;        // 0..1, drone sounds (on top of the Players/master sliders)
    public double remoteDroneScale = 3.0;   // visual scale of other pilots' drones (a real 5" quad is only ~0.25 m across)
    public Mapping mapping = new Mapping();
    // rates (null = library default Betaflight 1.0/0.7/0.0)
    public Integer ratesType;
    public double[] rc, srate, expo;
    public Double thrMid, thrExpo;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("fpvcore.json"); }

    public static Config load() {
        try {
            if (Files.isRegularFile(path())) {
                Config c = GSON.fromJson(Files.readString(path()), Config.class);
                if (c != null) { c.fix(); return c; }
            }
        } catch (Exception e) { FpvClient.LOG.warn("config load failed: {}", e.toString()); }
        return new Config();
    }

    private void fix() {
        preset = Drone.canonicalId(preset);   // old ids ("KM", "7Msurfer") from earlier configs
        if (mapping == null) mapping = new Mapping();
        if (mappings == null) mappings = new java.util.HashMap<>();
        if (controllerGuid == null) controllerGuid = "";
        if (controllerName == null) controllerName = "";
        if (!mapping.deviceGuid.isEmpty()) { // config from before per-device mappings
            mapping.calibrated = true;
            mappings.putIfAbsent(mapping.deviceGuid, mapping);
            if (controllerGuid.isEmpty() && !forceKeyboard) { controllerGuid = mapping.deviceGuid; controllerName = mapping.deviceName; }
        }
    }

    public void save() {
        try { Files.writeString(path(), GSON.toJson(this)); } catch (Exception e) { FpvClient.LOG.warn("config save failed: {}", e.toString()); }
    }
}
