package io.github.schematicsupervisor.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchematicSupervisorClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-supervisor");

    private SupervisorRuntimeController runtime;
    private ControlHttpServer controlServer;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path stateDirectory = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("schematic-supervisor");
        try {
            SupervisorSettings settings = SupervisorSettings.loadOrCreate(
                    stateDirectory.resolve("settings.json")
            );
            ShopSettings shop = ShopSettings.load(stateDirectory.resolve(ShopSettings.FILE_NAME));
            ShopSettings.install(shop);
            LOGGER.info(shop.enabled()
                    ? "Shop purchases are on; the shop opens with /{}"
                    : "Shop purchases are off; materials come from registered chests (see {})",
                    shop.enabled() ? shop.layout().command() : ShopSettings.FILE_NAME);
            runtime = new SupervisorRuntimeController(
                    client,
                    settings,
                    stateDirectory,
                    LOGGER
            );
            MinecraftBackgroundTickAccess.bind(runtime::stateName, runtime::ownsBackgroundWork);
            controlServer = new ControlHttpServer(
                    settings,
                    (request, completion) -> client.execute(() -> {
                        if (!completion.tryStart()) {
                            return;
                        }
                        try {
                            SupervisorRuntimeController.OperatorResult result =
                                    runtime.applyControl(request);
                            completion.complete(new ControlHttpServer.ControlResult(
                                    result.accepted(),
                                    result.message(),
                                    result.state()
                            ));
                        } catch (RuntimeException exception) {
                            LOGGER.error("Loopback control failed safely", exception);
                            completion.complete(new ControlHttpServer.ControlResult(
                                    false,
                                    "Control failed safely.",
                                    runtime.stateName()
                            ));
                        }
                    }),
                    runtime::observationSnapshot,
                    runtime::progressSnapshot
            );
            controlServer.start();
            registerEvents();
            LOGGER.info(
                    "Schematic Supervisor initialized; loopback control port {}",
                    controlServer.port()
            );
        } catch (IOException | RuntimeException exception) {
            closeAfterInitializationFailure();
            throw new IllegalStateException(
                    "Unable to initialize Schematic Supervisor safely",
                    exception
            );
        }
    }

    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> runtime.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MinecraftBackgroundTickAccess.clear();
            MinecraftBackgroundBuildAccess.forgetLeftPage();
            if (controlServer != null) {
                controlServer.close();
            }
            if (runtime != null) {
                runtime.close();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(commandTree())
        );
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> commandTree() {
        return literal("schematic-supervisor")
                .executes(context -> runCommand(
                        context.getSource(),
                        runtime::statusResult
                ))
                .then(literal("takeoff").executes(context -> runCommand(
                        context.getSource(),
                        runtime::takeoff
                )))
                .then(literal("approach")
                        .then(argument("x", integer(-30000000, 30000000))
                                .then(argument("y", integer(-2048, 2048))
                                        .then(argument("z", integer(-30000000, 30000000))
                                                .executes(context -> runCommand(context.getSource(),
                                                        () -> runtime.approach(getInteger(context, "x"),
                                                                getInteger(context, "y"), getInteger(context, "z"))))))))
                .then(literal("start").executes(context -> runCommand(
                        context.getSource(),
                        runtime::start
                )))
                .then(literal("pause").executes(context -> runCommand(
                        context.getSource(),
                        runtime::pause
                )))
                .then(literal("resume").executes(context -> runCommand(
                        context.getSource(),
                        runtime::resume
                )))
                .then(literal("stop").executes(context -> runCommand(
                        context.getSource(),
                        runtime::stop
                )))
                .then(literal("status").executes(context -> runCommand(
                        context.getSource(),
                        runtime::statusResult
                )))
                .then(literal("reset").executes(context -> runCommand(
                        context.getSource(),
                        runtime::reset
                )))
                .then(literal("unload").executes(context -> runCommand(
                        context.getSource(),
                        runtime::unload
                )))
                .then(literal("buy-dirt").executes(context -> runCommand(
                        context.getSource(),
                        runtime::buyDirt
                )))
                .then(literal("depot")
                        .then(literal("add-nearby")
                                .executes(context -> runCommand(context.getSource(),
                                        () -> runtime.registerNearbyDepots(8)))
                                .then(argument("radius", integer(1, 16))
                                        .executes(context -> runCommand(context.getSource(),
                                                () -> runtime.registerNearbyDepots(getInteger(context, "radius"))))))
                        .then(literal("add").executes(context -> runCommand(
                                context.getSource(),
                                runtime::registerTargetedDepot
                        )))
                        .then(literal("scan").executes(context -> runCommand(
                                context.getSource(),
                                runtime::rescanDepots
                        )))
                        .then(literal("list").executes(context -> {
                            List<String> depots = runtime.listDepots();
                            if (depots.isEmpty()) {
                                context.getSource().sendFeedback(
                                        Text.literal("[Schematic Supervisor] No depots registered.")
                                );
                            } else {
                                context.getSource().sendFeedback(
                                        Text.literal("[Schematic Supervisor] Registered depots:")
                                );
                                depots.forEach(depot -> context.getSource().sendFeedback(
                                        Text.literal("  " + depot)
                                ));
                            }
                            return 1;
                        }))
                        .then(literal("clear").executes(context -> runCommand(
                                context.getSource(),
                                runtime::clearDepots
                        ))));
    }

    private static int runCommand(
            FabricClientCommandSource source,
            Supplier<SupervisorRuntimeController.OperatorResult> operation
    ) {
        SupervisorRuntimeController.OperatorResult result = operation.get();
        Text message = Text.literal("[Schematic Supervisor] " + result.message());
        if (result.accepted()) {
            source.sendFeedback(message);
            return 1;
        }
        source.sendError(message);
        return 0;
    }

    private void closeAfterInitializationFailure() {
        MinecraftBackgroundTickAccess.clear();
        if (controlServer != null) {
            controlServer.close();
            controlServer = null;
        }
        if (runtime != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.isOnThread()) {
                runtime.close();
            }
            runtime = null;
        }
    }
}
