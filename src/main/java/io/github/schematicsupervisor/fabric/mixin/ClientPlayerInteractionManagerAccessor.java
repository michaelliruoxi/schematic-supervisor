package io.github.schematicsupervisor.fabric.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Invoker("syncSelectedSlot")
    void supervisor$syncSelectedSlot();

    @Accessor("currentBreakingPos")
    BlockPos supervisor$getCurrentBreakingPos();

    @Accessor("currentBreakingProgress")
    float supervisor$getCurrentBreakingProgress();

    @Accessor("blockBreakingCooldown")
    int supervisor$getBlockBreakingCooldown();
}
