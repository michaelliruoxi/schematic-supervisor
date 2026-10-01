package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.SupervisorPorts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;

final class MinecraftNotifications implements SupervisorPorts.Notifications {
    private final MinecraftClient client;
    private final Logger logger;

    MinecraftNotifications(MinecraftClient client, Logger logger) {
        this.client = client;
        this.logger = logger;
    }

    /** A chat line for the operator that is not a pause or a failure. */
    void info(String message) {
        logger.info(message);
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[Schematic Supervisor] " + message));
        }
    }

    @Override
    public void alert(String message, MaterialQuantities missingMaterials) {
        String detail = missingMaterials.isEmpty()
                ? message
                : message + " Missing: " + missingMaterials;
        logger.warn("Supervisor paused: {}", detail);
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(
                    Text.literal("[Schematic Supervisor] " + detail)
            );
        }
    }
}
