package com.yyon.grapplinghook.client.network;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.client.GrappleModClient;
import com.yyon.grapplinghook.config.GrappleModCommonConfig;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.IExtendedSpawnPacketEntity;
import com.yyon.grapplinghook.content.entity.grapplinghook.RopeSegmentHandler;
import com.yyon.grapplinghook.content.physics.PhysicsControllers;
import com.yyon.grapplinghook.network.clientbound.AddExtraEntityDataS2CPayload;
import com.yyon.grapplinghook.network.clientbound.DetachSingleHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachHookS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleAttachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToEntityS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleReanchorToBlockS2CPayload;
import com.yyon.grapplinghook.network.clientbound.GrappleDetachS2CPayload;
import com.yyon.grapplinghook.network.clientbound.RestoreGrappleStateS2CPayload;
import com.yyon.grapplinghook.network.clientbound.RopeSegmentUpdateS2CPayload;
import com.yyon.grapplinghook.network.clientbound.SyncServerConfigS2CPayload;
import com.yyon.grapplinghook.util.Vec;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.LinkedList;

/**
 * Client-side S2C receiver registration and handler bodies.
 *
 * <p>All code that touches {@link Minecraft}, {@link ClientPlayNetworking},
 * or {@link Level} in a client-only sense lives here. The payload classes
 * themselves stay free of client-only references so Fabric's dedicated-server
 * classloader can verify them without needing {@code ClientLevel} or
 * {@code ClientPlayNetworking$PlayPayloadHandler}.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientNetworkReceivers {

    private ClientNetworkReceivers() {}

    public static void registerAll() {
        ClientPlayNetworking.registerGlobalReceiver(AddExtraEntityDataS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleAddExtraEntityData);
        ClientPlayNetworking.registerGlobalReceiver(DetachSingleHookS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleDetachSingleHook);
        ClientPlayNetworking.registerGlobalReceiver(GrappleAttachS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleGrappleAttach);
        ClientPlayNetworking.registerGlobalReceiver(GrappleReanchorToEntityS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleGrappleReanchor);
        ClientPlayNetworking.registerGlobalReceiver(GrappleReanchorToBlockS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleGrappleReanchorToBlock);
        ClientPlayNetworking.registerGlobalReceiver(GrappleDetachS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleGrappleDetach);
        ClientPlayNetworking.registerGlobalReceiver(GrappleAttachHookS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleGrappleAttachHook);
        ClientPlayNetworking.registerGlobalReceiver(RestoreGrappleStateS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleRestoreGrappleState);
        ClientPlayNetworking.registerGlobalReceiver(RopeSegmentUpdateS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleRopeSegmentUpdate);
        ClientPlayNetworking.registerGlobalReceiver(SyncServerConfigS2CPayload.PAYLOAD_TYPE, ClientNetworkReceivers::handleSyncServerConfig);
    }

    private static void handleAddExtraEntityData(AddExtraEntityDataS2CPayload payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            if (Minecraft.getInstance().level == null)
                throw new IllegalStateException("World must not be null");

            Entity entity = Minecraft.getInstance().level.getEntity(payload.entityId());

            if (entity instanceof IExtendedSpawnPacketEntity entityAdditionalSpawnData) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.extraData()));
                entityAdditionalSpawnData.readSpawnData(buf);
                buf.release();
            }
        });
    }

    private static void handleDetachSingleHook(DetachSingleHookS2CPayload payload, ClientPlayNetworking.Context ctx) {
        GrappleModClient.get().getClientControllerManager().receiveGrappleDetachHook(payload.id(), payload.hookId());
    }

    private static void handleGrappleDetach(GrappleDetachS2CPayload payload, ClientPlayNetworking.Context ctx) {
        GrappleModClient.get().getClientControllerManager().receiveGrappleDetach(payload.holderId());
    }

    private static void handleGrappleAttachHook(GrappleAttachHookS2CPayload payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            Level world = Minecraft.getInstance().level;

            if (world == null) {
                GrappleMod.LOGGER.warn("Network Message received in invalid context (World not present | GrappleAttachPos)");
                return;
            }

            Entity e = world.getEntity(payload.hookId());

            if (e == null) {
                GrappleMod.LOGGER.warn("GrappleAttachPos received for a hook that doesn't exist on the client side! (yet?)");
                return;
            }

            if (e instanceof GrapplinghookEntity grapple) {
                if (grapple.getAttachedEntityId() != -1) {
                    return;
                }

                grapple.setAttachPos(payload.attachPos());
            }
        });
    }

    private static void handleRestoreGrappleState(RestoreGrappleStateS2CPayload payload, ClientPlayNetworking.Context ctx) {
        //todo: reimplement.
    }

    private static void handleRopeSegmentUpdate(RopeSegmentUpdateS2CPayload payload, ClientPlayNetworking.Context ctx) {
        Level world = Minecraft.getInstance().level;
        Entity grapple = world.getEntity(payload.hookId());
        if (grapple == null)
            return;

        if (grapple instanceof GrapplinghookEntity hookEntity) {
            RopeSegmentHandler segmentHandler = hookEntity.getSegmentHandler();
            if (payload.shouldAdd()) {
                segmentHandler.actuallyAddSegment(payload.index(), payload.pos(), payload.bottomFacing(), payload.topFacing());
            } else {
                segmentHandler.removeSegment(payload.index());
            }
        }
    }

    private static void handleSyncServerConfig(SyncServerConfigS2CPayload payload, ClientPlayNetworking.Context ctx) {
        GrappleModCommonConfig.syncIncomingFromServer(payload.config());
    }

    private static void handleGrappleReanchor(GrappleReanchorToEntityS2CPayload payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            Level world = Minecraft.getInstance().level;
            if (world == null) return;

            Entity e = world.getEntity(payload.hookId());
            if (!(e instanceof GrapplinghookEntity grapple)) {
                GrappleMod.LOGGER.warn("GrappleReanchor received for missing hook {}", payload.hookId());
                return;
            }

            Entity newAnchor = world.getEntity(payload.newEntityId());
            grapple.setAttachedEntityIdClient(payload.newEntityId());
            if (newAnchor != null) {
                grapple.setAttachedEntityClient(newAnchor);
            }
            grapple.setAttachedContraptionLocalOffset(payload.localOffset());
        });
    }

    private static void handleGrappleReanchorToBlock(GrappleReanchorToBlockS2CPayload payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            Level world = Minecraft.getInstance().level;
            if (world == null) return;

            Entity e = world.getEntity(payload.hookId());
            if (!(e instanceof GrapplinghookEntity grapple)) {
                GrappleMod.LOGGER.warn("GrappleReanchorToBlock received for missing hook {}", payload.hookId());
                return;
            }

            grapple.clientReanchorToBlock(payload.blockPos(), payload.hookWorldPos());
        });
    }

    private static void handleGrappleAttach(GrappleAttachS2CPayload payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            Level world = Minecraft.getInstance().level;

            if (world == null) {
                GrappleMod.LOGGER.warn("Network Message received in invalid context (World not present | GrappleAttach)");
                return;
            }

            Entity e = world.getEntity(payload.hookId());

            if (e == null) {
                GrappleMod.LOGGER.warn("GrappleAttachMessage received for a hook that doesn't exist on the client side! (yet?)");
                return;
            }

            if (e instanceof GrapplinghookEntity grapple) {

                grapple.clientAttach(payload.hookPos());

                BlockPos hookedBlock = null;
                switch (payload.attachTarget()) {
                    case GrappleAttachS2CPayload.GrappleAttachTarget.Block b -> hookedBlock = b.pos();
                    case GrappleAttachS2CPayload.GrappleAttachTarget.Entity ent -> {
                        grapple.setAttachedEntityIdClient(ent.id());
                        Entity attached = world.getEntity(ent.id());
                        if (attached != null) {
                            grapple.setAttachedEntityClient(attached);
                        }
                    }
                    case GrappleAttachS2CPayload.GrappleAttachTarget.EntityOffset eo -> {
                        grapple.setAttachedEntityIdClient(eo.id());
                        Entity attached = world.getEntity(eo.id());
                        if (attached != null) {
                            grapple.setAttachedEntityClient(attached);
                        }
                        grapple.setAttachedContraptionLocalOffset(eo.localOffset());
                    }
                }

                RopeSegmentHandler segmentHandler = grapple.getSegmentHandler();
                segmentHandler.segments = new LinkedList<>(payload.ropeState().getSegments());
                segmentHandler.segmentTopSides = new LinkedList<>(payload.ropeState().getTopSides());
                segmentHandler.segmentBottomSides = new LinkedList<>(payload.ropeState().getBottomSides());

                Entity holder = world.getEntity(payload.holderId());

                if (holder == null) {
                    GrappleMod.LOGGER.warn("Network Message received in invalid context (Holder does not exist | GrappleAttach)");
                    return;
                }

                segmentHandler.forceSetPos(new Vec(payload.hookPos()), Vec.positionVec(holder));
                GrappleModClient.get()
                        .getClientControllerManager()
                        .createControl(PhysicsControllers.GRAPPLING_HOOK, payload.hookId(), payload.holderId(), world, hookedBlock, payload.customization());
            }
        });
    }
}
