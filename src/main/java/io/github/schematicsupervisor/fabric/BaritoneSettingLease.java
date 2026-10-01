package io.github.schematicsupervisor.fabric;

import baritone.api.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Vec3i;

/**
 * Restores every overridden global Baritone setting in reverse acquisition order.
 */
final class BaritoneSettingLease implements AutoCloseable {
    private final Settings settings;
    private final List<Runnable> restorations = new ArrayList<>();
    private boolean closed;
    private RuntimeException closeFailure;

    private BaritoneSettingLease(Settings settings) {
        this.settings = settings;
    }

    static BaritoneSettingLease forDeterministicBuild(Settings settings, boolean building) {
        BaritoneSettingLease lease = forNonDestructiveRoute(settings);
        try {
            lease.override(settings.buildIgnoreBlocks, List.of());
            lease.override(settings.buildSkipBlocks, List.of());
            lease.override(settings.buildValidSubstitutes, Map.of());
            lease.override(settings.buildSubstitutes, Map.of());
            lease.override(settings.okIfAir, List.of());
            lease.override(settings.buildIgnoreExisting, false);
            lease.override(settings.buildIgnoreDirection, false);
            lease.override(settings.buildIgnoreProperties, List.of());
            lease.override(settings.buildInLayers, true);
            lease.override(settings.layerOrder, false);
            lease.override(settings.layerHeight, 1);
            lease.override(settings.startAtLayer, 0);
            lease.override(settings.skipFailedLayers, false);
            lease.override(settings.buildOnlySelection, false);
            lease.override(settings.buildRepeat, new Vec3i(0, 0, 0));
            lease.override(settings.breakFromAbove, false);
            lease.override(settings.goalBreakFromAbove, false);
            lease.override(settings.mapArtMode, false);
            lease.override(settings.okIfWater, false);
            lease.override(settings.schematicOrientationX, false);
            lease.override(settings.schematicOrientationY, false);
            lease.override(settings.schematicOrientationZ, false);
            lease.override(settings.buildSchematicRotation, BlockRotation.NONE);
            lease.override(settings.buildSchematicMirror, BlockMirror.NONE);
            lease.override(settings.distanceTrim, false);
            lease.setBuilding(building);
            return lease;
        } catch (RuntimeException exception) {
            closeAfterAcquisitionFailure(lease, exception);
            throw exception;
        }
    }

    static BaritoneSettingLease forNonDestructiveRoute(Settings settings) {
        BaritoneSettingLease lease = new BaritoneSettingLease(settings);
        try {
            lease.override(settings.acceptableThrowawayItems, List.of());
            lease.override(settings.allowBreak, false);
            lease.override(settings.allowPlace, false);
            lease.override(settings.allowInventory, false);
            lease.override(settings.allowParkourPlace, false);
            lease.override(settings.allowWaterBucketFall, false);
            return lease;
        } catch (RuntimeException exception) {
            closeAfterAcquisitionFailure(lease, exception);
            throw exception;
        }
    }

    void setBuilding(boolean building) {
        requireOpen();
        settings.allowPlace.value = building;
        settings.allowInventory.value = building;
    }

    void disableMutation() {
        requireOpen();
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
        settings.allowInventory.value = false;
    }

    @Override
    public void close() {
        if (closed) {
            if (closeFailure != null) {
                throw closeFailure;
            }
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (int index = restorations.size() - 1; index >= 0; index--) {
            try {
                restorations.get(index).run();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IllegalStateException(
                            "one or more Baritone settings could not be restored"
                    );
                }
                failure.addSuppressed(exception);
            }
        }
        restorations.clear();
        closeFailure = failure;
        if (failure != null) {
            throw failure;
        }
    }

    private <T> void override(Settings.Setting<T> setting, T replacement) {
        T original = setting.value;
        restorations.add(() -> setting.value = original);
        setting.value = replacement;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Baritone setting lease is closed");
        }
    }

    private static void closeAfterAcquisitionFailure(
            BaritoneSettingLease lease,
            RuntimeException acquisitionFailure
    ) {
        try {
            lease.close();
        } catch (RuntimeException closeException) {
            acquisitionFailure.addSuppressed(closeException);
        }
    }
}
