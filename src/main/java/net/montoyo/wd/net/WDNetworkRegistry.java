package net.montoyo.wd.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.montoyo.wd.net.client_bound.*;
import net.montoyo.wd.net.server_bound.*;

/**
 * Network registry for WebDisplays using NeoForge 1.21.1 payload system.
 */
@EventBusSubscriber(modid = "webdisplays", bus = EventBusSubscriber.Bus.MOD)
public class WDNetworkRegistry {
    public static final String NETWORK_VERSION = "2";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);

        // Server -> Client packets
        registrar.playToClient(
                S2CMessageServerInfo.TYPE,
                S2CMessageServerInfo.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageMiniservKey.TYPE,
                S2CMessageMiniservKey.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageCloseGui.TYPE,
                S2CMessageCloseGui.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageOpenGui.TYPE,
                S2CMessageOpenGui.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageAddScreen.TYPE,
                S2CMessageAddScreen.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageScreenUpdate.TYPE,
                S2CMessageScreenUpdate.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageACResult.TYPE,
                S2CMessageACResult.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToClient(
                S2CMessageJSResponse.TYPE,
                S2CMessageJSResponse.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );

        // Client -> Server packets
        registrar.playToServer(
                C2SMessageMiniservConnect.TYPE,
                C2SMessageMiniservConnect.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToServer(
                C2SMessageScreenCtrl.TYPE,
                C2SMessageScreenCtrl.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToServer(
                C2SMessageRedstoneCtrl.TYPE,
                C2SMessageRedstoneCtrl.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToServer(
                C2SMessageACQuery.TYPE,
                C2SMessageACQuery.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
        registrar.playToServer(
                C2SMessageMinepadUrl.TYPE,
                C2SMessageMinepadUrl.STREAM_CODEC,
                (payload, ctx) -> payload.handle(ctx)
        );
    }

    /**
     * Send a packet to a specific player
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Send a packet to all players
     */
    public static void sendToAll(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
    }

    /**
     * Send a packet to the server (client-side only)
     */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /**
     * Send a packet to all players near a position
     */
    public static void sendToNear(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, double radius, CustomPacketPayload payload) {
        sendToNearExcept(level, pos, radius, payload, null);
    }

    /**
     * Send a packet to all players near a position, except for a specific player
     */
    public static void sendToNearExcept(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, double radius, CustomPacketPayload payload, @javax.annotation.Nullable ServerPlayer except) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            double radius2 = radius * radius;
            for (ServerPlayer player : serverLevel.players()) {
                if (player == except) continue;

                // Sable compat: pos can be a screen's raw BlockPos, which for a screen inside a
                // Sable sub-level (e.g. a Create Aeronautics vehicle) is the LOCAL position
                // within the sub-level's own "plot" - deliberately parked far from normal play
                // areas, NOT the apparent/visual world position. A plain distSqr against a
                // player's real position would then always measure as "impossibly far away", so
                // this server would never broadcast screen updates (new URL, clicks, power
                // state...) to ANY player watching such a screen - the exact bug behind "I change
                // the URL/turn it on and nothing happens on a screen on my airship". See
                // SableCompat for the full story; this is a no-op (identical to distSqr) when
                // Sable isn't installed or pos isn't inside a sub-level.
                double dist2 = net.montoyo.wd.compat.sable.SableCompat.distanceSquared(serverLevel, player.position(), pos);
                if (dist2 <= radius2) {
                    sendToPlayer(player, payload);
                }
            }
        }
    }

    /**
     * Send a packet to all players tracking a specific chunk
     */
    public static void sendToTrackingChunk(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new net.minecraft.world.level.ChunkPos(pos), payload);
    }

    public static void init() {
        // Registration happens via the event subscriber
    }
}
