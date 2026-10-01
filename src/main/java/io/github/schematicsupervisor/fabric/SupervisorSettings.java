package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.SupervisorConfig;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

record SupervisorSettings(
        URI companionUri,
        int controlPort,
        int placementBlocksPerTick,
        int verificationBlocksPerTick,
        int interactionCooldownTicks,
        int pathGoalRadius,
        long minimumFood,
        boolean deferPlanting,
        boolean autoRepairHoes,
        boolean discardSurplusWhenStorageFull,
        boolean discardSurplusDirectly,
        boolean buyMaterialsInPlace,
        boolean glowstoneAfterStructure,
        int tillInteractionCooldownTicks
) {
    static final String TOKEN_ENVIRONMENT_VARIABLE = "SCHEMATIC_PROTOCOL_TOKEN";
    static final int DEFAULT_TILL_INTERACTION_COOLDOWN_TICKS = 2;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).setPrettyPrinting().create();

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting, boolean autoRepairHoes,
                       boolean discardSurplusWhenStorageFull, boolean discardSurplusDirectly,
                       boolean buyMaterialsInPlace, boolean glowstoneAfterStructure) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, autoRepairHoes,
                discardSurplusWhenStorageFull, discardSurplusDirectly, buyMaterialsInPlace,
                glowstoneAfterStructure, DEFAULT_TILL_INTERACTION_COOLDOWN_TICKS);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting, boolean autoRepairHoes,
                       boolean discardSurplusWhenStorageFull, boolean discardSurplusDirectly,
                       boolean buyMaterialsInPlace) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, autoRepairHoes,
                discardSurplusWhenStorageFull, discardSurplusDirectly, buyMaterialsInPlace, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting, boolean autoRepairHoes,
                       boolean discardSurplusWhenStorageFull, boolean discardSurplusDirectly) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, autoRepairHoes,
                discardSurplusWhenStorageFull, discardSurplusDirectly, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting, boolean autoRepairHoes, boolean discardSurplusWhenStorageFull) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, autoRepairHoes, discardSurplusWhenStorageFull, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting, boolean autoRepairHoes) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, autoRepairHoes, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood, boolean deferPlanting) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, deferPlanting, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius,
                       long minimumFood) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, minimumFood, false);
    }

    SupervisorSettings(URI companionUri, int controlPort, int placementBlocksPerTick,
                       int verificationBlocksPerTick, int interactionCooldownTicks, int pathGoalRadius) {
        this(companionUri, controlPort, placementBlocksPerTick, verificationBlocksPerTick,
                interactionCooldownTicks, pathGoalRadius, SupervisorConfig.defaults().minimumFood());
    }

    SupervisorSettings {
        Objects.requireNonNull(companionUri, "companionUri");
        requireLoopback(companionUri);
        if (controlPort < 1_024 || controlPort > 65_535) {
            throw new IllegalArgumentException("controlPort must be between 1024 and 65535");
        }
        if (placementBlocksPerTick < 1 || placementBlocksPerTick > 100_000) {
            throw new IllegalArgumentException(
                    "placementBlocksPerTick must be between 1 and 100000"
            );
        }
        if (verificationBlocksPerTick < 1 || verificationBlocksPerTick > 100_000) {
            throw new IllegalArgumentException(
                    "verificationBlocksPerTick must be between 1 and 100000"
            );
        }
        if (interactionCooldownTicks < 1 || interactionCooldownTicks > 100) {
            throw new IllegalArgumentException(
                    "interactionCooldownTicks must be between 1 and 100"
            );
        }
        if (tillInteractionCooldownTicks < 1 || tillInteractionCooldownTicks > 100) {
            throw new IllegalArgumentException(
                    "tillInteractionCooldownTicks must be between 1 and 100"
            );
        }
        if (minimumFood < 0) {
            throw new IllegalArgumentException("minimumFood must be non-negative");
        }
        if (pathGoalRadius < 1 || pathGoalRadius > 8) {
            throw new IllegalArgumentException("pathGoalRadius must be between 1 and 8");
        }
    }

    static SupervisorSettings defaults() {
        return new SupervisorSettings(
                URI.create("http://127.0.0.1:8766"),
                8_765,
                20_000,
                20_000,
                4,
                3
        );
    }

    SupervisorConfig supervisorConfig() {
        SupervisorConfig base = SupervisorConfig.defaults();
        return new SupervisorConfig(base.stallTimeout(), base.lagWait(), base.advisorTimeout(),
                base.minimumHoes(), minimumFood);
    }

    static SupervisorSettings loadOrCreate(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            SupervisorSettings defaults = defaults();
            save(path, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject()) { throw new IllegalArgumentException("settings file must be an object"); }
            for (String name : java.util.List.of("discardSurplusWhenStorageFull", "discardSurplusDirectly",
                    "buyMaterialsInPlace", "glowstoneAfterStructure")) {
                var discard = json.getAsJsonObject().get(name);
                if (discard != null && (!discard.isJsonPrimitive() || !discard.getAsJsonPrimitive().isBoolean())) {
                    throw new IllegalArgumentException(name + " must be a JSON boolean");
                }
            }
            var tillInterval = json.getAsJsonObject().get("tillInteractionCooldownTicks");
            if (tillInterval != null) {
                if (!tillInterval.isJsonPrimitive() || !tillInterval.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("tillInteractionCooldownTicks must be a JSON integer");
                }
                try {
                    tillInterval.getAsBigDecimal().intValueExact();
                } catch (ArithmeticException | NumberFormatException exception) {
                    throw new IllegalArgumentException("tillInteractionCooldownTicks must be a JSON integer", exception);
                }
            }
            SettingsFile file = GSON.fromJson(json, SettingsFile.class);
            if (file == null) {
                throw new IllegalArgumentException("settings file is empty");
            }
            return file.toSettings();
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("settings file is not valid JSON", exception);
        }
    }

    static void save(Path path, SupervisorSettings settings) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(SettingsFile.from(settings), writer);
        }
    }

    String token() {
        try {
            return LocalProtocolToken.resolve(
                    FabricLoader.getInstance().getConfigDir()
                            .resolve("schematic-supervisor").resolve("protocol-token.txt"),
                    System.getenv(TOKEN_ENVIRONMENT_VARIABLE)
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load local pairing credential", exception);
        }
    }

    private static void requireLoopback(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("companionUri must use http on loopback");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException(
                    "companionUri must not contain credentials, a query, or a fragment"
            );
        }
        if (uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException("companionUri must include a host and port");
        }
        try {
            if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("companionUri must resolve to loopback");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("companionUri host cannot be resolved", exception);
        }
    }

    private static final class SettingsFile {
        private String companionUri;
        private int controlPort;
        private int placementBlocksPerTick;
        private int verificationBlocksPerTick;
        private int interactionCooldownTicks;
        private int pathGoalRadius;
        private Long minimumFood;
        private boolean deferPlanting;
        private boolean autoRepairHoes;
        private boolean discardSurplusWhenStorageFull;
        private boolean discardSurplusDirectly;
        private boolean buyMaterialsInPlace;
        private boolean glowstoneAfterStructure;
        private Integer tillInteractionCooldownTicks;

        private SupervisorSettings toSettings() {
            return new SupervisorSettings(
                    URI.create(Objects.requireNonNull(companionUri, "companionUri")),
                    controlPort,
                    placementBlocksPerTick,
                    verificationBlocksPerTick,
                    interactionCooldownTicks,
                    pathGoalRadius,
                    minimumFood == null ? SupervisorConfig.defaults().minimumFood() : minimumFood,
                    deferPlanting,
                    autoRepairHoes,
                    discardSurplusWhenStorageFull,
                    discardSurplusDirectly,
                    buyMaterialsInPlace,
                    glowstoneAfterStructure,
                    tillInteractionCooldownTicks == null
                            ? DEFAULT_TILL_INTERACTION_COOLDOWN_TICKS : tillInteractionCooldownTicks
            );
        }

        private static SettingsFile from(SupervisorSettings settings) {
            SettingsFile file = new SettingsFile();
            file.companionUri = settings.companionUri().toString();
            file.controlPort = settings.controlPort();
            file.placementBlocksPerTick = settings.placementBlocksPerTick();
            file.verificationBlocksPerTick = settings.verificationBlocksPerTick();
            file.interactionCooldownTicks = settings.interactionCooldownTicks();
            file.pathGoalRadius = settings.pathGoalRadius();
            file.minimumFood = settings.minimumFood();
            file.deferPlanting = settings.deferPlanting();
            file.autoRepairHoes = settings.autoRepairHoes();
            file.discardSurplusWhenStorageFull = settings.discardSurplusWhenStorageFull();
            file.discardSurplusDirectly = settings.discardSurplusDirectly();
            file.buyMaterialsInPlace = settings.buyMaterialsInPlace();
            file.glowstoneAfterStructure = settings.glowstoneAfterStructure();
            file.tillInteractionCooldownTicks = settings.tillInteractionCooldownTicks();
            return file;
        }
    }
}
