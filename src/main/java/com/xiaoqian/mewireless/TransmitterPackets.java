/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = MEWireless.MOD_ID)
public final class TransmitterPackets {

    static final int MAIN = 0;
    static final int BANDS = 1;
    static final int CONNECTIONS = 2;
    static final int ROWS = 4;
    static final int OVERLAY_WINDOW = 255;
    static final double OVERLAY_DISTANCE_SQ = 96.0 * 96.0;
    private static final int MAX_OVERLAY_LINKS = 512;
    private static final Map<EntityPlayerMP, OverlayWatch> RANGE_OVERLAYS = new WeakHashMap<>();
    private static final Map<EntityPlayerMP, OverlayWatch> CARD_OVERLAYS = new WeakHashMap<>();

    private static final int VIEW = 0;
    private static final int BAND = 1;
    private static final int DISCONNECT = 2;
    private static final int CLEAR = 3;
    private static final int OVERLAY = 4;
    private static final int RESCAN = 5;

    private static SimpleNetworkWrapper channel;

    private TransmitterPackets() {
    }

    static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(MEWireless.MOD_ID);
        channel.registerMessage(RequestHandler.class, Request.class, 0, Side.SERVER);
        channel.registerMessage(SnapshotHandler.class, Snapshot.class, 1, Side.CLIENT);
    }

    static void view(int window, int kind, int page) {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("Kind", kind);
        data.setInteger("Page", page);
        channel.sendToServer(new Request(window, VIEW, data));
    }

    static void selectBand(int window, @Nullable UUID id) {
        NBTTagCompound data = new NBTTagCompound();
        if (id != null) {
            data.setUniqueId("Band", id);
        }
        channel.sendToServer(new Request(window, id == null ? CLEAR : BAND, data));
    }

    static void disconnect(int window, BlockPos pos) {
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("Target", pos.toLong());
        channel.sendToServer(new Request(window, DISCONNECT, data));
    }

    static void rescan(int window) {
        channel.sendToServer(new Request(window, RESCAN, new NBTTagCompound()));
    }

    static void requestOverlay(int dimension, BlockPos origin, boolean card, boolean stop) {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("Dimension", dimension);
        data.setLong("Origin", origin.toLong());
        data.setBoolean("Card", card);
        data.setBoolean("Stop", stop);
        channel.sendToServer(new Request(OVERLAY_WINDOW, OVERLAY, data));
    }

    private static void watchOverlay(EntityPlayerMP player, NBTTagCompound request) {
        if (!request.hasKey("Origin", 4) || player.dimension != request.getInteger("Dimension")) {
            return;
        }

        BlockPos origin = BlockPos.fromLong(request.getLong("Origin"));
        boolean card = request.getBoolean("Card");
        Map<EntityPlayerMP, OverlayWatch> overlays = card ? CARD_OVERLAYS : RANGE_OVERLAYS;
        if (request.getBoolean("Stop")) {
            OverlayWatch existing = overlays.get(player);
            if (existing != null && existing.tile.getWorld() == player.world && existing.tile.getPos().equals(origin)) {
                overlays.remove(player);
            }
            return;
        }

        TileEntity tile = player.getDistanceSqToCenter(origin) <= OVERLAY_DISTANCE_SQ
                && player.world.isBlockLoaded(origin) ? player.world.getTileEntity(origin) : null;
        if (tile instanceof WirelessTransmitterTile && !tile.isInvalid()) {
            overlays.put(player, new OverlayWatch((WirelessTransmitterTile) tile));
        } else {
            overlays.remove(player);
            sync(player, OVERLAY_WINDOW, overlayHeader(player.dimension, origin, false, card));
        }
    }

    @SubscribeEvent
    public static void onOverlayTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof EntityPlayerMP)
                || event.player.ticksExisted % 5 != 0) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        syncOverlay(player, RANGE_OVERLAYS, false);
        syncOverlay(player, CARD_OVERLAYS, true);
    }

    private static void syncOverlay(EntityPlayerMP player, Map<EntityPlayerMP, OverlayWatch> overlays, boolean card) {
        OverlayWatch watch = overlays.get(player);
        if (watch == null) {
            return;
        }

        WirelessTransmitterTile tile = watch.tile;
        BlockPos origin = tile.getPos();
        if (player.world != tile.getWorld() || tile.isInvalid() || !player.world.isBlockLoaded(origin)
                || player.getDistanceSqToCenter(origin) > OVERLAY_DISTANCE_SQ) {
            overlays.remove(player);
            if (player.world == tile.getWorld()) {
                sync(player, OVERLAY_WINDOW, overlayHeader(player.dimension, origin, false, card));
            }
            return;
        }

        long revision = tile.links().getLinkRevision();
        int radius = tile.getRadius();
        if (watch.sentRevision == revision && watch.sentRadius == radius) {
            return;
        }
        watch.sentRevision = revision;
        watch.sentRadius = radius;

        NBTTagCompound response = overlayHeader(player.dimension, origin, true, card);
        response.setInteger("Radius", radius);
        NBTTagList targets = new NBTTagList();
        for (BlockPos target : tile.links().getConnectedTargets()) {
            targets.appendTag(new NBTTagLong(target.toLong()));
            if (targets.tagCount() == MAX_OVERLAY_LINKS) {
                break;
            }
        }
        response.setTag("Links", targets);
        sync(player, OVERLAY_WINDOW, response);
    }

    @SubscribeEvent
    public static void onOverlayLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RANGE_OVERLAYS.remove(event.player);
        CARD_OVERLAYS.remove(event.player);
    }

    private static NBTTagCompound overlayHeader(int dimension, BlockPos origin, boolean valid, boolean card) {
        NBTTagCompound response = new NBTTagCompound();
        response.setInteger("Dimension", dimension);
        response.setLong("Origin", origin.toLong());
        response.setBoolean("Valid", valid);
        response.setBoolean("Card", card);
        return response;
    }

    private static final class OverlayWatch {
        final WirelessTransmitterTile tile;
        long sentRevision = -1;
        int sentRadius = -1;

        OverlayWatch(WirelessTransmitterTile tile) {
            this.tile = tile;
        }
    }

    static void sync(EntityPlayerMP player, int window, NBTTagCompound data) {
        channel.sendTo(new Snapshot(window, data), player);
    }

    public static final class Request implements IMessage {
        private int window;
        private int action;
        private NBTTagCompound data;

        public Request() {
        }

        Request(int window, int action, NBTTagCompound data) {
            this.window = window;
            this.action = action;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            window = buf.readUnsignedByte();
            action = buf.readUnsignedByte();
            data = ByteBufUtils.readTag(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(window);
            buf.writeByte(action);
            ByteBufUtils.writeTag(buf, data);
        }
    }

    public static final class Snapshot implements IMessage {
        private int window;
        private NBTTagCompound data;

        public Snapshot() {
        }

        Snapshot(int window, NBTTagCompound data) {
            this.window = window;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            window = buf.readUnsignedByte();
            data = ByteBufUtils.readTag(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(window);
            ByteBufUtils.writeTag(buf, data);
        }
    }

    public static final class RequestHandler implements IMessageHandler<Request, IMessage> {
        @Override
        public IMessage onMessage(Request message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (message.data == null) {
                    return;
                }
                if (message.window == OVERLAY_WINDOW && message.action == OVERLAY) {
                    watchOverlay(player, message.data);
                    return;
                }
                if (!(player.openContainer instanceof TransmitterContainer)) {
                    return;
                }
                TransmitterContainer menu = (TransmitterContainer) player.openContainer;
                if (menu.windowId != message.window || !menu.canInteractWith(player)) {
                    return;
                }
                switch (message.action) {
                    case VIEW:
                        menu.setView(message.data.getInteger("Kind"), message.data.getInteger("Page"));
                        break;
                    case BAND:
                        if (message.data.hasUniqueId("Band")) {
                            menu.selectBand(message.data.getUniqueId("Band"));
                        }
                        break;
                    case RESCAN:
                        menu.rescan();
                        break;
                    case CLEAR:
                        menu.selectBand(null);
                        break;
                    case DISCONNECT:
                        if (message.data.hasKey("Target", 4)) {
                            menu.disconnect(BlockPos.fromLong(message.data.getLong("Target")));
                        }
                        break;
                    default:
                        break;
                }
            });
            return null;
        }
    }

    public static final class SnapshotHandler implements IMessageHandler<Snapshot, IMessage> {
        @Override
        public IMessage onMessage(Snapshot message, MessageContext context) {
            if (message.data != null) {
                MEWireless.guiHandler.applySnapshot(message.window, message.data);
            }
            return null;
        }
    }
}
