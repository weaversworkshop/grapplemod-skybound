package com.yyon.grapplinghook.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class GrapplePropertyConfigLoader {

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("grapplemod-properties.json");

    // =========================
    // MOD-ENFORCED CONFIG BOUNDS
    // Adjust these to change the allowed range. Values outside the range
    // are silently clamped at load time. Not exposed to the user config.
    // =========================
    private static final double MAX_VERTICAL_AIRSPEED_MIN = 0.0;
    private static final double MAX_VERTICAL_AIRSPEED_MAX = 10.0;
    private static final double MAX_HORIZONTAL_AIRSPEED_MIN = 0.0;
    private static final double MAX_HORIZONTAL_AIRSPEED_MAX = 10.0;
    private static final double FLING_BASE_POWER_MIN = 0.0;
    private static final double FLING_BASE_POWER_MAX = 3.0;
    private static final double FLING_LAUNCH_ANGLE_BIAS_MIN = -90.0;
    private static final double FLING_LAUNCH_ANGLE_BIAS_MAX = 90.0;

    public static Config CONFIG;

    public static void load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject existingJson;
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                    existingJson = gson.fromJson(reader, JsonObject.class);
                }

                if (existingJson == null) existingJson = new JsonObject();

                CONFIG = gson.fromJson(existingJson, Config.class);

                // Populate missing fields; existing values are preserved
                JsonObject expected = gson.toJsonTree(new Config()).getAsJsonObject();
                boolean hasMissingFields = false;
                for (String key : expected.keySet()) {
                    if (!existingJson.has(key)) {
                        hasMissingFields = true;
                        break;
                    }
                }

                if (hasMissingFields) {
                    try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                        gson.toJson(CONFIG, writer);
                    }
                }
            } else {
                CONFIG = new Config();
                try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                    gson.toJson(CONFIG, writer);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            CONFIG = new Config();
        }

        if (CONFIG == null)
            CONFIG = new Config();

        clampBounds(CONFIG);
    }

    private static void clampBounds(Config cfg) {
        cfg.maxVerticalAirspeed = Math.clamp(cfg.maxVerticalAirspeed, MAX_VERTICAL_AIRSPEED_MIN, MAX_VERTICAL_AIRSPEED_MAX);
        cfg.maxHorizontalAirspeed = Math.clamp(cfg.maxHorizontalAirspeed, MAX_HORIZONTAL_AIRSPEED_MIN, MAX_HORIZONTAL_AIRSPEED_MAX);
        cfg.flingBasePower = Math.clamp(cfg.flingBasePower, FLING_BASE_POWER_MIN, FLING_BASE_POWER_MAX);
        cfg.flingVerticalAngle = Math.clamp(cfg.flingVerticalAngle, FLING_LAUNCH_ANGLE_BIAS_MIN, FLING_LAUNCH_ANGLE_BIAS_MAX);
    }

    public static class Config {
        public double maxRopeLength = 30.0;
        public double hookGravity = 1.0;
        public double hookSpeed = 2.0;

        public double playerMovementMultiplier = 1.0;
        public double grappleGravity = 0.08;

        public double maxVerticalAirspeed = 4.0;
        public double maxHorizontalAirspeed = 4.0;

        public double flingBasePower = 0.4;
        public double flingVerticalAngle = 12.5;
    }

}
