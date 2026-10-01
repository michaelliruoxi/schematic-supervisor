package io.github.schematicsupervisor.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import fi.dy.masa.litematica.config.Configs;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;

/**
 * Owns and restores settings that could otherwise create competing placements.
 */
final class AutomationSafetyGuard {
    private boolean active;
    private boolean easyPlaceMode;
    private List<Item> acceptableThrowawayItems = List.of();
    private boolean buildInLayers;
    private boolean layerOrder;
    private boolean skipFailedLayers;
    private boolean allowParkourPlace;
    private boolean allowWaterBucketFall;

    void activate() {
        if (active) {
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("litematica_printer")) {
            throw new IllegalStateException(
                    "Disable Litematica Printer before starting the supervisor."
            );
        }
        Settings settings = BaritoneAPI.getSettings();
        easyPlaceMode = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        acceptableThrowawayItems = List.copyOf(settings.acceptableThrowawayItems.value);
        buildInLayers = settings.buildInLayers.value;
        layerOrder = settings.layerOrder.value;
        skipFailedLayers = settings.skipFailedLayers.value;
        allowParkourPlace = settings.allowParkourPlace.value;
        allowWaterBucketFall = settings.allowWaterBucketFall.value;

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        settings.acceptableThrowawayItems.value = new ArrayList<>();
        settings.buildInLayers.value = true;
        settings.layerOrder.value = false;
        settings.skipFailedLayers.value = false;
        settings.allowParkourPlace.value = false;
        settings.allowWaterBucketFall.value = false;
        active = true;
    }

    void deactivate() {
        if (!active) {
            return;
        }
        Settings settings = BaritoneAPI.getSettings();
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(easyPlaceMode);
        settings.acceptableThrowawayItems.value = new ArrayList<>(acceptableThrowawayItems);
        settings.buildInLayers.value = buildInLayers;
        settings.layerOrder.value = layerOrder;
        settings.skipFailedLayers.value = skipFailedLayers;
        settings.allowParkourPlace.value = allowParkourPlace;
        settings.allowWaterBucketFall.value = allowWaterBucketFall;
        active = false;
    }

    boolean active() {
        return active;
    }
}
