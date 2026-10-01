package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.MossToolCustody;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the declaring send method so drop and offhand packets are covered as well as manager calls. */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientToolCustodyMixin {
    @Inject(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void observeToolCustody(Packet<?> packet, CallbackInfo callback) {
        MossToolCustody.beforeOutgoing((ClientCommonNetworkHandler) (Object) this, packet);
    }
}
