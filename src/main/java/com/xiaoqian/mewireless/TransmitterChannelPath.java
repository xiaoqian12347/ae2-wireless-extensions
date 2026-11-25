/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.core.AEConfig;
import appeng.me.pathfinding.IPathItem;
import appeng.tile.networking.TileController;

final class TransmitterChannelPath {

    private final List<Segment> segments;

    private TransmitterChannelPath(List<Segment> segments) {
        this.segments = segments;
    }

    static TransmitterChannelPath find(IGridNode start, IGrid grid, IGridConnection bandInput) {
        List<Segment> segments = new ArrayList<>();
        Set<IGridNode> visited = new HashSet<>();
        int normal = AEConfig.instance().getNormalChannelCapacity();
        int dense = AEConfig.instance().getDenseChannelCapacity();
        IGridNode node = start;
        while (!(node.getMachine() instanceof TileController)) {
            if (!(node instanceof IPathItem) || !visited.add(node) || node.hasFlag(GridFlags.CANNOT_CARRY)) {
                return null;
            }
            IPathItem route = ((IPathItem) node).getControllerRoute();
            if (!(route instanceof IGridConnection)) {
                return null;
            }
            IGridConnection connection = (IGridConnection) route;
            boolean expectedConnection = node == start && bandInput != null
                    ? connection == bandInput : connection.hasDirection();
            if (!expectedConnection || !TransmitterLinks.isLive(node, connection)) {
                return null;
            }
            IGridNode upstream = connection.getOtherSide(node);
            if (upstream == null || upstream.getGrid() != grid || connection.a() != upstream) {
                return null;
            }

            int capacity = node.hasFlag(GridFlags.DENSE_CAPACITY) ? dense : Math.min(normal, dense);
            segments.add(new Segment(connection, capacity - connection.getUsedChannels()));
            node = upstream;
        }
        return segments.isEmpty() ? null : new TransmitterChannelPath(segments);
    }

    int available(Map<IGridConnection, Integer> reserved) {
        int available = Integer.MAX_VALUE;
        for (Segment segment : segments) {
            available = Math.min(available, segment.freeChannels - reserved.getOrDefault(segment.connection, 0));
        }
        return Math.max(0, available);
    }

    void reserve(Map<IGridConnection, Integer> reserved, int channels) {
        for (Segment segment : segments) {
            reserved.merge(segment.connection, channels, Integer::sum);
        }
    }

    boolean isLive(IGrid grid) {
        for (Segment segment : segments) {
            IGridConnection connection = segment.connection;
            if (connection.a().getGrid() != grid || connection.b().getGrid() != grid
                    || !TransmitterLinks.isLive(connection.a(), connection)) {
                return false;
            }
        }
        return true;
    }

    private static final class Segment {
        final IGridConnection connection;
        final int freeChannels;

        Segment(IGridConnection connection, int freeChannels) {
            this.connection = connection;
            this.freeChannels = freeChannels;
        }
    }
}
