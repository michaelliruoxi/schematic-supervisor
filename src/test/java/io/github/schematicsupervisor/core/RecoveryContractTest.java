package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryContractTest {
    @Test
    void advisorActionAllowlistContainsExactlyTheSixApprovedActions() {
        assertEquals(
                List.of(
                        "WAIT",
                        "REPATH",
                        "RESTOCK",
                        "RETRY_CHUNK",
                        "RETURN_TO_SAFE_POSITION",
                        "PAUSE_AND_ALERT"
                ),
                Arrays.stream(RecoveryAdvice.values()).map(Enum::name).toList()
        );
    }

    @Test
    void advisorIncidentContractContainsNoCoordinateTypes() {
        List<Class<?>> componentTypes = Arrays.stream(RecoveryIncident.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();

        assertFalse(componentTypes.contains(BlockPosition.class));
        assertFalse(componentTypes.contains(ChunkCoordinate.class));
        assertFalse(componentTypes.contains(BuildVolume.class));
        assertFalse(componentTypes.contains(WorkOrder.class));
    }
}
