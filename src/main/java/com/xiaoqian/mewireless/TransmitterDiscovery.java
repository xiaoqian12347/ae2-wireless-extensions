/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = MEWireless.MOD_ID)
public final class TransmitterDiscovery {

    private static final Map<WorldServer, Map<Long, Set<WirelessTransmitterTile>>> CHUNKS = new IdentityHashMap<>();
    private static long serverTick;
    private static int automaticEdits;

    private TransmitterDiscovery() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            serverTick++;
            automaticEdits = 0;
        }
    }

    static long getServerTick() {
        return serverTick;
    }

    static boolean hasAutomaticEditBudget() {
        return remainingAutomaticEdits() > 0;
    }

    static int remainingAutomaticEdits() {
        return TransmitterConfig.maxEditsPerTick() - automaticEdits;
    }

    static boolean tryReserveAutomaticEdit() {
        return tryReserveAutomaticEdits(1);
    }

    static boolean tryReserveAutomaticEdits(int count) {
        if (count > remainingAutomaticEdits()) {
            return false;
        }
        automaticEdits += count;
        return true;
    }

    static void register(WirelessTransmitterTile tile) {
        updateIndex(tile, true);
    }

    static void unregister(WirelessTransmitterTile tile) {
        updateIndex(tile, false);
    }

    private static void updateIndex(WirelessTransmitterTile tile, boolean add) {
        if (!(tile.getWorld() instanceof WorldServer)) {
            return;
        }
        WorldServer world = (WorldServer) tile.getWorld();
        Map<Long, Set<WirelessTransmitterTile>> chunks = add
                ? CHUNKS.computeIfAbsent(world, key -> new HashMap<>()) : CHUNKS.get(world);
        if (chunks == null) {
            return;
        }
        BlockPos pos = tile.getPos();
        int radius = tile.getRadius();
        for (int x = (pos.getX() - radius) >> 4; x <= (pos.getX() + radius) >> 4; x++) {
            for (int z = (pos.getZ() - radius) >> 4; z <= (pos.getZ() + radius) >> 4; z++) {
                long key = ChunkPos.asLong(x, z);
                if (add) {
                    chunks.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(tile);
                } else {
                    Set<WirelessTransmitterTile> emitters = chunks.get(key);
                    if (emitters != null && emitters.remove(tile) && emitters.isEmpty()) {
                        chunks.remove(key);
                    }
                }
            }
        }
        if (chunks.isEmpty()) {
            CHUNKS.remove(world);
        }
    }

    static void deviceChanged(World world, BlockPos pos, boolean recovering) {
        Set<WirelessTransmitterTile> nearby = emitters(world, pos.getX() >> 4, pos.getZ() >> 4);
        if (nearby == null) {
            return;
        }

        if (!world.isBlockLoaded(pos) || !TransmitterLinks.isCandidate(world.getTileEntity(pos))) {
            return;
        }

        WirelessTransmitterTile best = null;
        boolean bestOnline = false;
        double bestDistance = Double.MAX_VALUE;
        for (WirelessTransmitterTile tile : nearby) {
            if (tile.isInvalid() || tile.isWaitingForBand() || !tile.links().inRange(pos)) {
                continue;
            }

            boolean saved = tile.links().hasTarget(pos);
            if (!saved && !tile.isAutomatic()) {
                continue;
            }
            boolean online = tile.isInputOnline();
            double distance = tile.getPos().distanceSq(pos);
            if (best == null || (online == bestOnline ? distance < bestDistance : online)) {
                best = tile;
                bestOnline = online;
                bestDistance = distance;
            }
        }
        if (best != null) {
            best.links().queueNewTarget(pos, recovering && !best.links().hasTarget(pos));
        }
    }

    static Set<WirelessTransmitterTile> emitters(World world, int x, int z) {
        if (!(world instanceof WorldServer)) {
            return null;
        }
        Map<Long, Set<WirelessTransmitterTile>> chunks = CHUNKS.get(world);
        return chunks == null ? null : chunks.get(ChunkPos.asLong(x, z));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        deviceChanged(event.getWorld(), event.getPos(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void broken(BlockEvent.BreakEvent event) {
        BlockPos pos = event.getPos();
        Set<WirelessTransmitterTile> nearby = emitters(event.getWorld(), pos.getX() >> 4, pos.getZ() >> 4);
        if (nearby != null) {
            nearby.forEach(tile -> tile.links().disconnect(pos));
        }
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        ChunkPos pos = event.getChunk().getPos();
        Set<WirelessTransmitterTile> nearby = emitters(event.getWorld(), pos.x, pos.z);
        if (nearby != null) {
            for (WirelessTransmitterTile tile : nearby) {
                if (!tile.isWaitingForBand()) {
                    tile.scheduleDiscovery();
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void connectQueued(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TransmitterConnections.connectQueued();
        }
    }

    @SubscribeEvent
    public static void worldUnloaded(WorldEvent.Unload event) {
        if (event.getWorld() instanceof WorldServer) {
            Map<Long, Set<WirelessTransmitterTile>> unloaded = CHUNKS.remove(event.getWorld());
            if (unloaded != null) {
                unloaded.values().forEach(tiles -> tiles.forEach(WirelessTransmitterTile::unregisterBandReceiver));
            }
            TransmitterConnections.clear((WorldServer) event.getWorld());
        }
    }
}
