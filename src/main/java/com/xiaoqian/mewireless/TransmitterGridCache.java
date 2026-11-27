/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.lang.ref.WeakReference;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridCache;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkControllerChange;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import com.xiaoqian.mewireless.mixin.PathGridCacheAccessor;
import net.minecraft.tileentity.TileEntity;

public final class TransmitterGridCache implements IGridCache {

    private static final ThreadLocal<Boolean> WIRELESS_CHANGE = new ThreadLocal<>();

    private final IGrid grid;
    private boolean routing;
    private int recheckIn;
    private long routingRevision;
    private long nextWirelessEdit;
    private long awaitedRoutingRevision;
    private WeakReference<WirelessTransmitterTile> nextConnection;

    public TransmitterGridCache(IGrid grid) {
        this.grid = grid;
    }

    static boolean beginWirelessChange() {
        boolean alreadyChanging = Boolean.TRUE.equals(WIRELESS_CHANGE.get());
        WIRELESS_CHANGE.set(true);
        return alreadyChanging;
    }

    static void endWirelessChange(boolean alreadyChanging) {
        if (!alreadyChanging) {
            WIRELESS_CHANGE.remove();
        }
    }

    static boolean isRouting(IGrid grid) {
        return grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).routing;
    }

    static long routingRevision(IGrid grid) {
        return grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).routingRevision;
    }

    static boolean isReadyForRestore(IGrid grid) {
        TransmitterGridCache cache = grid.getCache(TransmitterGridCache.class);
        return cache.routingRevision >= 2 && cache.recheckIn == 0 && cache.canEdit();
    }

    boolean canEdit() {
        return !routing && routingRevision >= awaitedRoutingRevision
                && TransmitterDiscovery.getServerTick() >= nextWirelessEdit
                && !((PathGridCacheAccessor) grid.getCache(IPathingGrid.class)).ae2WirelessExtensions$isRepathPending();
    }

    WirelessTransmitterTile nextConnection() {
        return nextConnection == null ? null : nextConnection.get();
    }

    void prioritizeConnection(WirelessTransmitterTile tile) {
        nextConnection = new WeakReference<>(tile);
    }

    static void deferEdits(IGrid grid) {
        if (grid != null) {
            grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).nextWirelessEdit =
                    TransmitterDiscovery.getServerTick() + 2;
        }
    }

    static void awaitRouting(IGrid grid) {
        if (grid != null) {
            TransmitterGridCache cache = grid.getCache(TransmitterGridCache.class);
            // 连接提交与 AE2 发出寻路开始事件之间仍有一个调度窗口。
            cache.awaitedRoutingRevision = cache.routingRevision + (cache.routing ? 1 : 2);
        }
    }

    static boolean canRecover(IGridNode node) {
        IGrid grid = node.getGrid();
        if (grid == null || node.isActive()) {
            return false;
        }
        TransmitterGridCache cache = grid.getCache(TransmitterGridCache.class);
        return !cache.routing && cache.recheckIn == 0;
    }

    private void scheduleRecheck() {
        if (!Boolean.TRUE.equals(WIRELESS_CHANGE.get())) {
            recheckIn = 2;
        }
    }

    @Override
    public void addNode(IGridNode node, IGridHost machine) {
        scheduleRecheck();
    }

    @Override
    public void removeNode(IGridNode node, IGridHost machine) {
        scheduleRecheck();
    }

    @Override
    public void onSplit(IGridStorage storage) {
        scheduleRecheck();
    }

    @Override
    public void onJoin(IGridStorage storage) {
        scheduleRecheck();
    }

    @Override
    public void populateGridStorage(IGridStorage storage) {
    }

    @MENetworkEventSubscribe
    public void onControllerChange(MENetworkControllerChange event) {
        scheduleRecheck();
    }

    @MENetworkEventSubscribe
    public void onRoutingChange(MENetworkBootingStatusChange event) {
        // AE2 UEL sends this event when routing starts and again when it finishes.
        routing = !routing;
        routingRevision++;
        if (recheckIn > 0) {
            recheckIn = 2;
        }
    }

    @Override
    public void onUpdateTick() {
        if (routing || recheckIn == 0 || --recheckIn != 0 || grid.isEmpty()
                || grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() != ControllerState.NO_CONTROLLER) {
            return;
        }

        for (IGridNode node : grid.getNodes()) {
            IGridHost machine = node.getMachine();
            if (machine instanceof TileEntity) {
                TileEntity device = (TileEntity) machine;
                if (TransmitterLinks.isCandidate(device) && !node.isActive()) {
                    TransmitterDiscovery.deviceChanged(device.getWorld(), device.getPos(), true);
                }
            }
        }
    }
}
