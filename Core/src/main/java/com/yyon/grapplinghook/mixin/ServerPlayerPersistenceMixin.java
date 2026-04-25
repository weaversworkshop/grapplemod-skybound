package com.yyon.grapplinghook.mixin;

import com.yyon.grapplinghook.physics.persistence.HookPersistenceManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerPersistenceMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void grapplemod$saveHooks(CompoundTag tag, CallbackInfo ci) {
        HookPersistenceManager.saveForPlayer((ServerPlayer) (Object) this, tag);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void grapplemod$loadHooks(CompoundTag tag, CallbackInfo ci) {
        HookPersistenceManager.loadForPlayer((ServerPlayer) (Object) this, tag);
    }
}
