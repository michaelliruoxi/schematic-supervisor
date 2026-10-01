package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TopDownTargetOrderTest {
    @Test
    void sortsHighestLayerFirstThenXThenZWithoutMutatingInput() {
        ArrayList<BlockPosition> input = new ArrayList<>(List.of(
                new BlockPosition(4, 10, 5),
                new BlockPosition(1, 12, 9),
                new BlockPosition(1, 12, 3),
                new BlockPosition(2, 10, 0)
        ));

        List<BlockPosition> result = TopDownTargetOrder.copyOf(input);

        assertEquals(List.of(
                new BlockPosition(1, 12, 3),
                new BlockPosition(1, 12, 9),
                new BlockPosition(2, 10, 0),
                new BlockPosition(4, 10, 5)
        ), result);
        assertEquals(new BlockPosition(4, 10, 5), input.getFirst());
        assertThrows(UnsupportedOperationException.class,
                () -> result.add(new BlockPosition(0, 0, 0)));
    }
}
