/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

final class TransmitterRestore {

    private TransmitterRestore() {
    }

    static int maxBatchTargets() {
        // 每组设备还需要一条接入主网的内部连接。
        return Math.min(TransmitterConfig.maxTargetsPerGridBatch(), TransmitterConfig.maxEditsPerTick() - 1);
    }

    static boolean restoreGrid(IGrid grid, List<WirelessTransmitterTile> tiles) {
        if (tiles.isEmpty() || !TransmitterGridCache.isReadyForRestore(grid)) {
            return false;
        }
        int maxTargets = maxBatchTargets();
        if (maxTargets < 2
                || grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() != ControllerState.CONTROLLER_ONLINE) {
            tiles.forEach(WirelessTransmitterTile::finishStartupRestore);
            return false;
        }

        Map<IGridConnection, Integer> reserved = new IdentityHashMap<>();
        int remainingTargets = maxTargets;
        boolean waitingForBudget = false;
        for (WirelessTransmitterTile tile : tiles) {
            if (remainingTargets < 2) {
                break;
            }
            IGridNode input = tile.startupInput();
            if (input == null || input.getGrid() != grid) {
                continue;
            }
            TransmitterChannelPath path = tile.findInputPath(grid);
            int limit = path == null ? 0 : Math.min(maxTargets,
                    Math.min(path.available(reserved), tile.getAppliedChannelLimit() - tile.getUsedChannels()));
            if (limit < 2) {
                tile.finishStartupRestore();
                continue;
            }

            WorldServer world = (WorldServer) tile.getWorld();
            List<BlockPos> targets = tile.links().readyStartupTargets(world, input, limit);
            if (targets.size() < 2) {
                tile.finishStartupRestore();
                continue;
            }
            if (targets.size() > remainingTargets) {
                continue;
            }
            if (TransmitterDiscovery.remainingAutomaticEdits() < targets.size() + 1) {
                waitingForBudget = true;
                continue;
            }

            remainingTargets -= targets.size();
            tile.finishStartupRestore();
            if (!tile.links().restoreThroughOutput(world, input, targets)) {
                TransmitterGridCache.deferEdits(grid);
                return waitingForBudget;
            }
            path.reserve(reserved, targets.size());
            TransmitterGridCache.awaitRouting(grid);
        }
        return waitingForBudget;
    }
}
