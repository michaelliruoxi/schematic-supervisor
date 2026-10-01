package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.MossToolCustody;
import io.github.schematicsupervisor.fabric.ServerPlayerInventoryObserver;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayerInventoryRepairMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void captureRepairSlot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo callback) {
        ServerPlayerInventoryObserver.afterSlot((ClientPlayNetworkHandler) (Object) this, packet);
        MossToolCustody.afterIncoming((ClientPlayNetworkHandler) (Object) this);
    }

    @Inject(method = "onSetPlayerInventory", at = @At("TAIL"))
    private void captureRepairPlayerSlot(SetPlayerInventoryS2CPacket packet, CallbackInfo callback) {
        ServerPlayerInventoryObserver.afterPlayerSlot((ClientPlayNetworkHandler) (Object) this, packet);
        MossToolCustody.afterIncoming((ClientPlayNetworkHandler) (Object) this);
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void captureRepairInventory(InventoryS2CPacket packet, CallbackInfo callback) {
        ServerPlayerInventoryObserver.afterInventory((ClientPlayNetworkHandler) (Object) this, packet);
    }

    @Inject(method = "onEntityEquipmentUpdate", at = @At("TAIL"))
    private void captureToolEquipment(EntityEquipmentUpdateS2CPacket packet, CallbackInfo callback) {
        MossToolCustody.afterIncoming((ClientPlayNetworkHandler) (Object) this);
    }
}
