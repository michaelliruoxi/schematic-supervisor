package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.MossToolCustody;
import io.github.schematicsupervisor.fabric.ServerInventorySnapshotObserver;
import io.github.schematicsupervisor.fabric.ServerShopCursorObserver;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCursorItemS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientInventorySnapshotMixin {
    @Inject(method = "onInventory", at = @At("TAIL"))
    private void captureServerInventory(InventoryS2CPacket packet, CallbackInfo callback) {
        ServerInventorySnapshotObserver.afterInventory((ClientPlayNetworkHandler) (Object) this, packet);
        ServerShopCursorObserver.afterInventory((ClientPlayNetworkHandler) (Object) this, packet);
        MossToolCustody.afterIncoming((ClientPlayNetworkHandler) (Object) this);
    }

    @Inject(method = "onSetCursorItem", at = @At("TAIL"))
    private void captureServerCursor(SetCursorItemS2CPacket packet, CallbackInfo callback) {
        ServerShopCursorObserver.afterCursor((ClientPlayNetworkHandler) (Object) this, packet);
        MossToolCustody.afterIncoming((ClientPlayNetworkHandler) (Object) this);
    }
}
