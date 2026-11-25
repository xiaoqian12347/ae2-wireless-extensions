/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import com.xiaoqian.mewireless.TransmitterLinks.ConnectionPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

final class TransmitterConnections {

    private static final Map<WirelessTransmitterTile, Boolean> REQUESTS = new LinkedHashMap<>();
    private static final Set<IGrid> READY_ORDER = new LinkedHashSet<>();

    private TransmitterConnections() {
    }

    static void request(TransmitterLinks links) {
        REQUESTS.put(links.transmitter(), false);
    }

    static void requestRestore(WirelessTransmitterTile tile) {
        REQUESTS.put(tile, true);
    }

    static void clear(WorldServer world) {
        REQUESTS.keySet().removeIf(tile -> tile.getWorld() == world);
        if (world.provider.getDimension() == 0) {
            READY_ORDER.clear();
        }
    }

    static void connectQueued() {
        if (REQUESTS.isEmpty()) {
            READY_ORDER.clear();
            return;
        }

        Map<IGrid, GridWork> byGrid = new LinkedHashMap<>();
        for (Map.Entry<WirelessTransmitterTile, Boolean> request : REQUESTS.entrySet()) {
            WirelessTransmitterTile tile = request.getKey();
            if (tile.isInvalid() || !(tile.getWorld() instanceof WorldServer)) {
                continue;
            }
            boolean restore = request.getValue();
            IGridNode node = restore ? tile.startupInput() : tile.getActionableNode();
            IGrid grid = node == null ? null : node.getGrid();
            if (grid != null && grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).canEdit()) {
                GridWork work = byGrid.computeIfAbsent(grid, unused -> new GridWork());
                (restore ? work.restore : work.connect).add(tile);
            }
        }
        REQUESTS.clear();

        READY_ORDER.retainAll(byGrid.keySet());
        READY_ORDER.addAll(byGrid.keySet());
        Iterator<IGrid> ready = READY_ORDER.iterator();
        while (ready.hasNext() && TransmitterDiscovery.hasAutomaticEditBudget()) {
            IGrid grid = ready.next();
            GridWork work = byGrid.get(grid);
            if (TransmitterRestore.restoreGrid(grid, work.restore)) {
                continue;
            }
            ready.remove();
            if (!work.connect.isEmpty() && grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).canEdit()) {
                connectGrid(grid, work.connect);
            }
        }
    }

    private static final class GridWork {
        final List<WirelessTransmitterTile> restore = new ArrayList<>();
        final List<WirelessTransmitterTile> connect = new ArrayList<>();
    }

    private static void connectGrid(IGrid grid, List<WirelessTransmitterTile> tiles) {
        List<ConnectionPlan> plans = new ArrayList<>();
        for (WirelessTransmitterTile tile : tiles) {
            ConnectionPlan plan = tile.links().connectionPlan();
            if (plan != null && plan.grid == grid) {
                plans.add(plan);
            }
        }
        if (plans.isEmpty()) {
            return;
        }
        TransmitterGridCache cache = grid.getCache(TransmitterGridCache.class);
        Round round = new Round(plans);
        Deque<ConnectionPlan> queue = new ArrayDeque<>(plans.size());
        int start = 0;
        WirelessTransmitterTile next = cache.nextConnection();
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).links.transmitter() == next) {
                start = i;
                break;
            }
        }
        for (int i = 0; i < plans.size(); i++) {
            queue.addLast(plans.get((start + i) % plans.size()));
        }

        ConnectionPlan served = null;
        int submitted = 0;
        while (!queue.isEmpty() && submitted < TransmitterConfig.maxTargetsPerGridBatch() && TransmitterDiscovery.hasAutomaticEditBudget()) {
            ConnectionPlan plan = queue.removeFirst();
            TransmitterLinks.ConnectionResult result = plan.links.connectOne(plan, round);
            if (result == TransmitterLinks.ConnectionResult.SKIPPED) {
                round.plans.remove(plan.links.transmitter());
                continue;
            }
            submitted++;
            served = plan;
            if (result == TransmitterLinks.ConnectionResult.EXCLUSIVE || plan.source.getGrid() != grid) {
                break;
            }
            round.batchStarted = true;
            queue.addLast(plan);
        }
        if (round.deferredExclusive != null) {
            cache.prioritizeConnection(round.deferredExclusive);
        } else if (served != null) {
            int nextIndex = (plans.indexOf(served) + 1) % plans.size();
            cache.prioritizeConnection(plans.get(nextIndex).links.transmitter());
        }
    }

    static final class Round {
        private final Map<WirelessTransmitterTile, ConnectionPlan> plans = new IdentityHashMap<>();
        final Map<IGridConnection, Integer> reserved = new IdentityHashMap<>();
        boolean batchStarted;
        private WirelessTransmitterTile deferredExclusive;

        Round(List<ConnectionPlan> group) {
            for (ConnectionPlan plan : group) {
                plans.put(plan.links.transmitter(), plan);
            }
        }

        void deferExclusive(WirelessTransmitterTile transmitter) {
            if (deferredExclusive == null) {
                deferredExclusive = transmitter;
            }
        }

        boolean hasPreferredTransmitter(ConnectionPlan current, BlockPos pos) {
            Set<WirelessTransmitterTile> nearby = TransmitterDiscovery.emitters(current.world, pos.getX() >> 4, pos.getZ() >> 4);
            if (nearby == null) {
                return false;
            }
            WirelessTransmitterTile transmitter = current.links.transmitter();
            int priority = current.links.targetPriority(pos);
            double distance = transmitter.getPos().distanceSq(pos);
            for (WirelessTransmitterTile neighbor : nearby) {
                if (neighbor == transmitter) {
                    continue;
                }
                ConnectionPlan other = plans.get(neighbor);
                if (other == null || !other.links.canClaim(other, this, pos)) {
                    continue;
                }
                int otherPriority = other.links.targetPriority(pos);
                double otherDistance = neighbor.getPos().distanceSq(pos);
                if (otherPriority > priority || (otherPriority == priority && (otherDistance < distance
                        || (otherDistance == distance && neighbor.getPos().compareTo(transmitter.getPos()) < 0)))) {
                    return true;
                }
            }
            return false;
        }
    }
}
