package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.CompletedPieces;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.ScheduleProgress;
import io.github.schematicsupervisor.core.SchematicPlan;
import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** On the checked-in farm, progress material counts agree with the stage counts behind the % built. */
final class FarmMaterialProgressTest {
    @Test
    void eachStageKindCountsItsOwnMaterials() throws IOException {
        SchematicPlan farm = checkedInFarm().withGlowstoneAfterStructure(true);
        LayerBuildSchedule schedule = new LayerBuildSchedule(farm);
        ScheduleProgress.Index index = ScheduleProgress.index(farm, schedule);
        assertEquals(farm.plannedMaterials(), ScheduleProgress.of(index, 0, -1).plannedMaterials());
        Random random = new Random(7);
        for (int trial = 0; trial < 20; trial++) {
            int cursor = random.nextInt(schedule.size() + 1);
            BitSet finished = new BitSet();
            for (int piece = cursor + 1; piece < schedule.size(); piece++) {
                if (random.nextInt(3) == 0) {
                    finished.set(piece);
                }
            }
            ScheduleProgress progress = ScheduleProgress.of(index, cursor, -1, CompletedPieces.of(finished));
            Map<String, Long> doneByKind = new HashMap<>();
            for (int stage = 0; stage < progress.stageCount(); stage++) {
                doneByKind.merge(progress.stageKind(stage), progress.stageDone(stage), Long::sum);
            }
            MaterialQuantities done = progress.doneMaterials();
            assertEquals(doneByKind.getOrDefault("STRUCTURE", 0L),
                    done.get(Material.DIRT) + done.get(Material.BIRCH_PLANKS));
            assertEquals(doneByKind.getOrDefault("LIGHTING", 0L), done.get(Material.GLOWSTONE));
            assertEquals(doneByKind.getOrDefault("PLANT", 0L), done.get(Material.WHEAT_SEEDS));
        }
    }

    private static SchematicPlan checkedInFarm() throws IOException {
        LitematicSourceFixture.Source source = LitematicSourceFixture.read();
        SchematicSourceScan scan = new SchematicSourceScan(source.width(), source.height(), source.depth(),
                new SchematicSourceScan.Transform(new BlockPosition(6_144, -63, -47_456),
                        new BlockPosition(1, 0, 0), new BlockPosition(0, 1, 0), new BlockPosition(0, 0, 1)),
                new BuildVolume(6_144, -63, -47_456, 6_255, 12, -47_345), source.expectedNonAir(), source::get);
        while (!scan.complete()) {
            scan.tick(1 << 20);
        }
        return scan.result();
    }
}
