/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.exceptions.FailedConnectionException;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.parts.IPartHost;
import appeng.api.util.AEPartLocation;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.pathfinding.IPathItem;
import appeng.tile.networking.TileController;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;

final class TransmitterLinks {

    private static final int FAILED_RETRY_DELAY = 100;
    private static final int QUEUE_RETRY_DELAY = 10;

    private final WirelessTransmitterTile tile;
    private final Map<BlockPos, Boolean> targets = new LinkedHashMap<>();
    private final Map<BlockPos, IGridConnection> live = new LinkedHashMap<>();
    private final Map<BlockPos, Boolean> pending = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> nodeWaits = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> shedDemand = new LinkedHashMap<>();
    private final Map<BlockPos, Long> retryAfter = new LinkedHashMap<>();
    private AENetworkProxy output;
    private IGridConnection outputBridge;
    private long nextConnectAt;
    private long linkRevision;
    private IGrid measuredGrid;
    private long measuredRoutingRevision;
    private long measuredLinkRevision = -1;
    private Map<BlockPos, Integer> measuredChannels;
    private int committedChannels;

    TransmitterLinks(WirelessTransmitterTile tile) {
        this.tile = tile;
    }

    Set<BlockPos> getConnectedTargets() {
        return live.keySet();
    }

    long getLinkRevision() {
        return linkRevision;
    }

    boolean hasTarget(BlockPos pos) {
        return targets.containsKey(pos);
    }

    void retrySavedTargets() {
        retryAfter.clear();
    }

    boolean isManual(BlockPos pos) {
        return Boolean.TRUE.equals(targets.get(pos));
    }

    boolean toggle(BlockPos pos) {
        if (targets.containsKey(pos)) {
            disconnect(pos);
            return true;
        }
        if (!inRange(pos) || !tile.getWorld().isBlockLoaded(pos)) {
            return false;
        }
        TileEntity target = tile.getWorld().getTileEntity(pos);
        IGridNode node = findDeviceNode(target);
        IGridNode source = tile.getActionableNode();
        if (node == null || source == null || !canConnect(source, node)) {
            return false;
        }
        targets.put(pos.toImmutable(), true);
        pending.put(pos.toImmutable(), false);
        tile.markDirty();
        return true;
    }

    void disconnect(BlockPos pos) {
        pending.remove(pos);
        nodeWaits.remove(pos);
        shedDemand.remove(pos);
        retryAfter.remove(pos);
        if (targets.remove(pos) != null) {
            removeLive(pos);
            tile.markDirty();
        }
    }

    boolean rangeChanged() {
        boolean changed = targets.keySet().removeIf(pos -> {
            if (inRange(pos)) {
                return false;
            }
            removeLive(pos);
            shedDemand.remove(pos);
            retryAfter.remove(pos);
            return true;
        });
        pending.keySet().removeIf(pos -> !inRange(pos));
        nodeWaits.keySet().retainAll(pending.keySet());
        return changed;
    }

    void pauseDiscovery() {
        pending.keySet().retainAll(targets.keySet());
        nodeWaits.keySet().retainAll(pending.keySet());
    }

    void queueNewTarget(BlockPos pos, boolean recovering) {
        if (!live.containsKey(pos)) {
            retryAfter.remove(pos);
            BlockPos key = pos.toImmutable();
            if (recovering) {
                pending.putIfAbsent(key, true);
            } else {
                pending.put(key, false);
            }
        }
    }

    void scan(WorldServer world, boolean discover) {
        IGridNode source = linkSource();
        if (output != null && source == null) {
            clearRuntime();
        }
        if (!live.isEmpty()) {
            Set<IGridConnection> sourceConnections = Collections.newSetFromMap(new IdentityHashMap<>());
            if (source != null) {
                source.getConnections().forEach(sourceConnections::add);
            }
            for (Iterator<Map.Entry<BlockPos, IGridConnection>> it = live.entrySet().iterator(); it.hasNext();) {
                Map.Entry<BlockPos, IGridConnection> entry = it.next();
                if (!world.isBlockLoaded(entry.getKey()) || !sourceConnections.contains(entry.getValue())) {
                    destroy(entry.getValue());
                    it.remove();
                    linkRevision++;
                }
            }
        }

        dropUnservedWiredLinks(source);

        long now = world.getTotalWorldTime();
        boolean removed = false;
        for (Iterator<BlockPos> it = targets.keySet().iterator(); it.hasNext();) {
            BlockPos pos = it.next();
            if (live.containsKey(pos) || pending.containsKey(pos)
                    || now < retryAfter.getOrDefault(pos, 0L) || !world.isBlockLoaded(pos)) {
                continue;
            }
            if (!isCandidate(world.getTileEntity(pos))) {
                it.remove();
                pending.remove(pos);
                nodeWaits.remove(pos);
                shedDemand.remove(pos);
                retryAfter.remove(pos);
                removed = true;
            } else {
                pending.put(pos, false);
            }
        }
        if (removed) {
            tile.markDirty();
        }
        if (!discover) {
            return;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = tile.getPos();
        int radius = tile.getRadius();
        int minX = (origin.getX() - radius) >> 4;
        int maxX = (origin.getX() + radius) >> 4;
        int minZ = (origin.getZ() - radius) >> 4;
        int maxZ = (origin.getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                if (chunk == null) {
                    continue;
                }
                for (TileEntity target : chunk.getTileEntityMap().values()) {
                    BlockPos pos = target.getPos();
                    if (inRange(pos) && isCandidate(target) && !targets.containsKey(pos)) {
                        candidates.add(pos.toImmutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(tile.getPos()::distanceSq));
        candidates.forEach(pos -> pending.put(pos, false));
    }

    private void dropUnservedWiredLinks(IGridNode source) {
        if (tile.getBandId() != null || live.isEmpty() || source == null || live.size() <= tile.getUsedChannels()) {
            return;
        }
        IGrid grid = source.getGrid();
        if (grid == null || !TransmitterGridCache.isReadyForRestore(grid)
                || !TransmitterDiscovery.hasAutomaticEditBudget()) {
            return;
        }
        TransmitterChannelPath path = tile.findInputPath(grid);
        if (path == null || path.remaining() > 0) {
            return;
        }

        boolean removed = false;
        Iterator<IGridConnection> it = live.values().iterator();
        while (it.hasNext() && TransmitterDiscovery.hasAutomaticEditBudget()) {
            IGridConnection connection = it.next();
            IGridNode target = connection.getOtherSide(source);
            if (target != null && target.getGrid() == grid && hasOnlyConnection(target, connection)
                    && !target.meetsChannelRequirements() && TransmitterDiscovery.tryReserveAutomaticEdit()) {
                it.remove();
                destroy(connection);
                removed = true;
            }
        }
        if (removed) {
            linkRevision++;
            TransmitterGridCache.deferEdits(grid);
            TransmitterGridCache.awaitRouting(grid);
        }
    }

    boolean hasStartupTargets() {
        return targets.size() >= 2;
    }

    List<BlockPos> readyStartupTargets(WorldServer world, IGridNode input, int limit) {
        List<BlockPos> ready = new ArrayList<>();
        addStartupTargets(world, input, true, limit, ready);
        if (ready.size() < limit) {
            addStartupTargets(world, input, false, limit, ready);
        }
        return ready;
    }

    private void addStartupTargets(WorldServer world, IGridNode input, boolean manual, int limit, List<BlockPos> ready) {
        for (Map.Entry<BlockPos, Boolean> entry : targets.entrySet()) {
            BlockPos pos = entry.getKey();
            if (entry.getValue() == manual && startupTarget(world, input, pos) != null) {
                ready.add(pos);
                if (ready.size() == limit) {
                    return;
                }
            }
        }
    }

    private IGridNode startupTarget(WorldServer world, IGridNode input, BlockPos pos) {
        if (live.containsKey(pos) || !inRange(pos) || !world.isBlockLoaded(pos)) {
            return null;
        }
        IGridNode node = findDeviceNode(world.getTileEntity(pos));
        return node != null && canConnect(input, node) && isIsolatedTarget(node)
                && mayBuild(input.getGrid().getCache(ISecurityGrid.class), node) ? node : null;
    }

    private static boolean isIsolatedTarget(IGridNode node) {
        if (!node.getConnections().isEmpty() || !node.hasFlag(GridFlags.REQUIRE_CHANNEL)
                || node.hasFlag(GridFlags.MULTIBLOCK) || node.hasFlag(GridFlags.COMPRESSED_CHANNEL)
                || node.hasFlag(GridFlags.CANNOT_CARRY)) {
            return false;
        }
        IGrid grid = node.getGrid();
        return grid != null && grid.getNodes().size() == 1
                && !grid.<ISecurityGrid>getCache(ISecurityGrid.class).isAvailable()
                && !grid.<IEnergyGrid>getCache(IEnergyGrid.class).isNetworkPowered();
    }

    boolean restoreThroughOutput(WorldServer world, IGridNode input, List<BlockPos> positions) {
        if (output != null || !live.isEmpty()) {
            return false;
        }
        IGrid mainGrid = input.getGrid();
        if (mainGrid == null) {
            return false;
        }
        ISecurityGrid security = mainGrid.getCache(ISecurityGrid.class);
        boolean secured = security.isAvailable();
        if (!mayBuild(security, input)) {
            return false;
        }
        AENetworkProxy staged = new AENetworkProxy(tile, "WirelessOutput", ItemStack.EMPTY, false);
        staged.setIdlePowerUsage(0);
        staged.setFlags(GridFlags.DENSE_CAPACITY);
        Map<BlockPos, IGridConnection> stagedLinks = new LinkedHashMap<>();
        IGridConnection bridge = null;
        try {
            staged.onReady();
            IGridNode outputNode = staged.getNode();
            if (outputNode == null) {
                return false;
            }
            outputNode.setPlayerID(input.getPlayerID());
            for (BlockPos pos : positions) {
                IGridNode target = startupTarget(world, input, pos);
                IGridConnection connection = target == null ? null : connect(outputNode, target);
                if (connection == null) {
                    return false;
                }
                stagedLinks.put(pos, connection);
            }

            // 内部输出连接只校验自身两端，并网前还要重新核对每台设备的权限。
            IGrid stagedGrid = outputNode.getGrid();
            if (tile.isInvalid() || tile.getActionableNode() != input || input.getGrid() != mainGrid
                    || security.isAvailable() != secured || !mayBuild(security, input)
                    || stagedGrid == null || stagedGrid == mainGrid
                    || stagedGrid.<IEnergyGrid>getCache(IEnergyGrid.class).isNetworkPowered()
                    || stagedGrid.getNodes().size() != stagedLinks.size() + 1) {
                return false;
            }
            for (Map.Entry<BlockPos, IGridConnection> entry : stagedLinks.entrySet()) {
                BlockPos pos = entry.getKey();
                IGridConnection connection = entry.getValue();
                if (!world.isBlockLoaded(pos) || !isLive(outputNode, connection)) {
                    return false;
                }
                IGridNode target = connection.getOtherSide(outputNode);
                if (findDeviceNode(world.getTileEntity(pos)) != target || !hasOnlyConnection(target, connection)
                        || !mayBuild(security, target)) {
                    return false;
                }
            }

            bridge = connect(input, outputNode);
            if (bridge == null || input.getGrid() != mainGrid || outputNode.getGrid() != mainGrid) {
                return false;
            }
            outputBridge = bridge;
            output = staged;
            live.putAll(stagedLinks);
            linkRevision++;
            for (BlockPos pos : positions) {
                pending.remove(pos);
                nodeWaits.remove(pos);
                retryAfter.remove(pos);
            }
            nextConnectAt = world.getTotalWorldTime() + 2;
            return true;
        } finally {
            if (output != staged) {
                discardOutput(staged, bridge, stagedLinks.values());
            }
        }
    }

    private static boolean mayBuild(ISecurityGrid security, IGridNode node) {
        return !security.isAvailable() || security.hasPermission(node.getPlayerID(), SecurityPermissions.BUILD);
    }

    private static boolean hasOnlyConnection(IGridNode node, IGridConnection connection) {
        return node.getConnections().size() == 1 && isLive(node, connection);
    }

    boolean isOutputBridge(IGridConnection connection) {
        return connection == outputBridge;
    }

    private IGridNode linkSource() {
        IGridNode input = tile.getActionableNode();
        if (output == null) {
            return input;
        }
        return input != null && isLive(input, outputBridge) ? output.getNode() : null;
    }

    private static void discardOutput(AENetworkProxy proxy, IGridConnection bridge, Collection<IGridConnection> links) {
        destroy(bridge);
        links.forEach(TransmitterLinks::destroy);
        boolean alreadyChanging = TransmitterGridCache.beginWirelessChange();
        try {
            proxy.invalidate();
        } finally {
            TransmitterGridCache.endWirelessChange(alreadyChanging);
        }
    }

    WirelessTransmitterTile transmitter() {
        return tile;
    }

    void queueConnections(WorldServer world) {
        if (pending.isEmpty() || world.getTotalWorldTime() < nextConnectAt) {
            return;
        }
        IGridNode source = linkSource();
        IGrid grid = source == null ? null : source.getGrid();
        if (grid != null && grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).canEdit()) {
            TransmitterConnections.request(this);
        }
    }

    ConnectionPlan connectionPlan() {
        if (tile.isInvalid() || !(tile.getWorld() instanceof WorldServer) || pending.isEmpty() || !tile.isInputOnline()) {
            return null;
        }
        WorldServer world = (WorldServer) tile.getWorld();
        long now = world.getTotalWorldTime();
        IGridNode source = linkSource();
        IGrid grid = source == null ? null : source.getGrid();
        if (now < nextConnectAt || grid == null || !grid.<TransmitterGridCache>getCache(TransmitterGridCache.class).canEdit()) {
            return null;
        }
        linkChannels(source);
        int used = tile.getUsedChannels();
        int available = tile.getAppliedChannelLimit() - Math.max(committedChannels, used);
        TransmitterChannelPath path = available > 0 ? connectionPath(source, grid) : null;
        if (tile.getBandId() == null) {
            available = Math.min(available, tile.getWiredRemaining(grid, path, used));
        } else if (path != null) {
            available = Math.min(available, path.remaining());
        }
        if (available <= 0) {
            nextConnectAt = now + QUEUE_RETRY_DELAY;
            return null;
        }
        return new ConnectionPlan(this, world, source, grid, path, available);
    }

    private TransmitterChannelPath connectionPath(IGridNode source, IGrid grid) {
        if (!TransmitterGridCache.isReadyForRestore(grid)
                || grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() != ControllerState.CONTROLLER_ONLINE) {
            return null;
        }
        IGridNode input = tile.getActionableNode();
        if (input == null || input.getGrid() != grid || (source != input && (!(source instanceof IPathItem)
                || ((IPathItem) source).getControllerRoute() != outputBridge || outputBridge.a() != input))) {
            return null;
        }
        return tile.findInputPath(grid);
    }

    int targetPriority(BlockPos pos) {
        return isManual(pos) ? 2 : hasTarget(pos) ? 1 : 0;
    }

    boolean canClaim(ConnectionPlan plan, TransmitterConnections.Round round, BlockPos pos) {
        return plan.source.getGrid() == plan.grid && inRange(pos) && pending.containsKey(pos)
                && plan.available(round) >= Math.max(1, shedDemand.getOrDefault(pos, 0));
    }

    ConnectionResult connectOne(ConnectionPlan plan, TransmitterConnections.Round round) {
        IGridNode source = plan.source;
        IGrid grid = plan.grid;
        WorldServer world = plan.world;
        long now = world.getTotalWorldTime();
        int available = plan.available(round);
        if (available <= 0 || pending.isEmpty() || tile.isInvalid() || source != linkSource()
                || source.getGrid() != grid || !tile.isInputOnline() || (plan.path != null && !plan.path.isLive(grid))) {
            return ConnectionResult.SKIPPED;
        }
        Iterator<Map.Entry<BlockPos, Boolean>> it = pending.entrySet().iterator();
        for (int attempts = 0; attempts < 12 && it.hasNext();) {
            Map.Entry<BlockPos, Boolean> entry = it.next();
            BlockPos pos = entry.getKey();
            if (shedDemand.getOrDefault(pos, 0) > available) {
                continue;
            }
            attempts++;
            boolean recovering = entry.getValue();
            if (!inRange(pos) || !world.isBlockLoaded(pos)) {
                it.remove();
                nodeWaits.remove(pos);
                continue;
            }
            TileEntity target = world.getTileEntity(pos);
            if (!isCandidate(target)) {
                it.remove();
                nodeWaits.remove(pos);
                retryAfter.remove(pos);
                shedDemand.remove(pos);
                if (targets.remove(pos) != null) {
                    tile.markDirty();
                }
                continue;
            }
            IGridNode node = findDeviceNode(target);
            if (node == null || node.getGrid() == null) {
                if (nodeWaits.merge(pos, 1, Integer::sum) >= 8) {
                    it.remove();
                    nodeWaits.remove(pos);
                    retryLater(pos, now);
                }
                continue;
            }
            if ((recovering && !TransmitterGridCache.canRecover(node)) || !canConnect(source, node)) {
                it.remove();
                nodeWaits.remove(pos);
                retryLater(pos, now);
                continue;
            }
            boolean batchable = plan.path != null && isIsolatedTarget(node)
                    && mayBuild(grid.getCache(ISecurityGrid.class), node);
            if (round.hasPreferredTransmitter(plan, pos)) {
                continue;
            }
            if (round.batchStarted && !batchable) {
                round.deferExclusive(tile);
                continue;
            }
            if (!TransmitterDiscovery.hasAutomaticEditBudget()) {
                return ConnectionResult.SKIPPED;
            }
            it.remove();
            nodeWaits.remove(pos);
            IGridConnection connection = connect(source, node);
            TransmitterGridCache.deferEdits(grid);
            if (connection == null) {
                retryLater(pos, now);
                nextConnectAt = now + QUEUE_RETRY_DELAY;
                return ConnectionResult.EXCLUSIVE;
            }
            live.put(pos, connection);
            linkRevision++;
            TransmitterGridCache.awaitRouting(source.getGrid());
            shedDemand.remove(pos);
            retryAfter.remove(pos);
            nextConnectAt = now + 2;
            if (targets.putIfAbsent(pos, false) == null) {
                tile.markDirty();
            }
            if (!batchable) {
                return ConnectionResult.EXCLUSIVE;
            }
            plan.path.reserve(round.reserved, 1);
            plan.freeChannels--;
            return ConnectionResult.BATCHED;
        }
        nextConnectAt = now + QUEUE_RETRY_DELAY;
        return ConnectionResult.SKIPPED;
    }

    enum ConnectionResult {
        SKIPPED, BATCHED, EXCLUSIVE
    }

    static final class ConnectionPlan {
        final TransmitterLinks links;
        final WorldServer world;
        final IGridNode source;
        final IGrid grid;
        final TransmitterChannelPath path;
        int freeChannels;

        ConnectionPlan(TransmitterLinks links, WorldServer world, IGridNode source, IGrid grid,
                       TransmitterChannelPath path, int freeChannels) {
            this.links = links;
            this.world = world;
            this.source = source;
            this.grid = grid;
            this.path = path;
            this.freeChannels = freeChannels;
        }

        int available(TransmitterConnections.Round round) {
            return path == null ? freeChannels : Math.min(freeChannels, path.available(round.reserved));
        }
    }

    private void retryLater(BlockPos pos, long now) {
        if (targets.containsKey(pos)) {
            retryAfter.put(pos, now + FAILED_RETRY_DELAY);
        }
    }

    boolean trimOne(int limit) {
        IGridNode source = linkSource();
        if (source == null || source.getGrid() == null) {
            return false;
        }
        Map<BlockPos, Integer> usage = linkChannels(source);
        if (committedChannels <= limit) {
            return false;
        }

        BlockPos latestAutomatic = null;
        BlockPos latestManual = null;
        for (Map.Entry<BlockPos, Integer> entry : usage.entrySet()) {
            if (entry.getValue() > 0) {
                BlockPos pos = entry.getKey();
                if (isManual(pos)) {
                    latestManual = pos;
                } else {
                    latestAutomatic = pos;
                }
            }
        }

        if (!TransmitterGridCache.tryReserveEdit(source.getGrid())) {
            return true;
        }

        BlockPos pos = latestAutomatic != null ? latestAutomatic : latestManual;
        shedDemand.put(pos, usage.get(pos));
        removeLive(pos);
        TransmitterGridCache.deferEdits(source.getGrid());
        return true;
    }

    private Map<BlockPos, Integer> linkChannels(IGridNode source) {
        IGrid grid = source.getGrid();
        long routingRevision = TransmitterGridCache.routingRevision(grid);
        if (measuredGrid != grid || measuredRoutingRevision != routingRevision || measuredLinkRevision != linkRevision) {
            measuredChannels = measureLinkChannels(source);
            committedChannels = measuredChannels.values().stream().mapToInt(Integer::intValue).sum();
            measuredGrid = grid;
            measuredRoutingRevision = routingRevision;
            measuredLinkRevision = linkRevision;
        }
        return measuredChannels;
    }

    private Map<BlockPos, Integer> measureLinkChannels(IGridNode source) {
        if (live.isEmpty()) {
            return Collections.emptyMap();
        }

        IGrid grid = source.getGrid();
        Map<BlockPos, Integer> usage = new LinkedHashMap<>();
        if (grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() == ControllerState.NO_CONTROLLER) {
            // AE2 reports the aggregate channel count on every edge of a controllerless grid.
            Set<IGridNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<IGridNode> counted = Collections.newSetFromMap(new IdentityHashMap<>());
            IGridNode input = tile.getActionableNode();
            if (output != null) {
                visited.add(source);
            }
            countBranchChannels(input, input, grid, visited, counted);
            live.forEach((pos, connection) ->
                    usage.put(pos, countBranchChannels(connection.getOtherSide(source), source, grid, visited, counted)));
        } else {
            live.forEach((pos, connection) -> {
                IGridNode target = connection.getOtherSide(source);
                int minimum = target != null && target.hasFlag(GridFlags.REQUIRE_CHANNEL) ? 1 : 0;
                if (target != null && target.getGrid() == grid) {
                    usage.put(pos, Math.max(minimum, connection.getUsedChannels()));
                }
            });
        }
        return usage;
    }

    private static int countBranchChannels(IGridNode start, IGridNode source, IGrid grid,
                                           Set<IGridNode> visited, Set<IGridNode> counted) {
        if (start == null || start.getGrid() != grid || !visited.add(start)) {
            return 0;
        }

        int channels = 0;
        Deque<IGridNode> nodes = new ArrayDeque<>();
        nodes.add(start);
        while (!nodes.isEmpty()) {
            IGridNode node = nodes.removeFirst();
            if (node.hasFlag(GridFlags.REQUIRE_CHANNEL) && counted.add(node)) {
                channels++;
                if (node.hasFlag(GridFlags.MULTIBLOCK) && node.getGridBlock() instanceof IGridMultiblock) {
                    ((IGridMultiblock) node.getGridBlock()).getMultiblockNodes().forEachRemaining(counted::add);
                }
            }
            for (IGridConnection connection : node.getConnections()) {
                if (node != source || connection.hasDirection()) {
                    IGridNode other = connection.getOtherSide(node);
                    if (other != null && other.getGrid() == grid && visited.add(other)) {
                        nodes.addLast(other);
                    }
                }
            }
        }
        return channels;
    }

    boolean inRange(BlockPos pos) {
        BlockPos origin = tile.getPos();
        int radius = tile.getRadius();
        return !pos.equals(origin)
                && Math.abs(pos.getX() - origin.getX()) <= radius
                && Math.abs(pos.getY() - origin.getY()) <= radius
                && Math.abs(pos.getZ() - origin.getZ()) <= radius;
    }

    static boolean isCandidate(TileEntity target) {
        return target != null && !target.isInvalid() && !(target instanceof WirelessTransmitterTile)
                && !(target instanceof TileController)
                && !target.getClass().getName().equals("com.xinyihl.ymadditions.common.title.TileNetworkHub")
                && target instanceof IGridHost && !(target instanceof IPartHost);
    }

    private static IGridNode findDeviceNode(TileEntity target) {
        if (!isCandidate(target)) {
            return null;
        }
        IGridHost host = (IGridHost) target;
        for (AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
            IGridNode node = host.getGridNode(side);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private static boolean canConnect(IGridNode source, IGridNode target) {
        IGrid sourceGrid = source.getGrid();
        IGrid targetGrid = target.getGrid();
        if (sourceGrid == null || targetGrid == null || sourceGrid == targetGrid
                || targetGrid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() != ControllerState.NO_CONTROLLER) {
            return false;
        }
        ISecurityGrid security = targetGrid.getCache(ISecurityGrid.class);
        return !security.isAvailable()
                || security.hasPermission(source.getPlayerID(), SecurityPermissions.BUILD);
    }

    static boolean isLive(IGridNode node, IGridConnection connection) {
        for (IGridConnection current : node.getConnections()) {
            if (current == connection) {
                return true;
            }
        }
        return false;
    }

    static IGridConnection connect(IGridNode source, IGridNode target) {
        if (!TransmitterDiscovery.tryReserveAutomaticEdit()) {
            return null;
        }
        boolean alreadyChanging = TransmitterGridCache.beginWirelessChange();
        try {
            return AEApi.instance().grid().createGridConnection(source, target);
        } catch (FailedConnectionException e) {
            return null;
        } finally {
            TransmitterGridCache.endWirelessChange(alreadyChanging);
        }
    }

    static void destroy(IGridConnection connection) {
        if (connection != null && isLive(connection.a(), connection)) {
            boolean alreadyChanging = TransmitterGridCache.beginWirelessChange();
            try {
                connection.destroy();
            } finally {
                TransmitterGridCache.endWirelessChange(alreadyChanging);
            }
        }
    }

    private void removeLive(BlockPos pos) {
        IGridConnection connection = live.remove(pos);
        if (connection != null) {
            linkRevision++;
            destroy(connection);
        }
    }

    void clearRuntime() {
        if (output != null) {
            discardOutput(output, outputBridge, live.values());
            output = null;
            outputBridge = null;
        } else {
            live.values().forEach(TransmitterLinks::destroy);
        }
        if (!live.isEmpty()) {
            live.clear();
            linkRevision++;
        }
        measuredGrid = null;
        retryAfter.clear();
        pending.clear();
        nodeWaits.clear();
        shedDemand.clear();
    }

    void read(NBTTagCompound nbt) {
        targets.clear();
        NBTTagList saved = nbt.getTagList("Targets", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < saved.tagCount(); i++) {
            NBTTagCompound entry = saved.getCompoundTagAt(i);
            targets.put(BlockPos.fromLong(entry.getLong("Pos")), entry.getBoolean("Manual"));
        }
    }

    void write(NBTTagCompound nbt) {
        NBTTagList saved = new NBTTagList();
        targets.forEach((pos, manual) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("Pos", pos.toLong());
            entry.setBoolean("Manual", manual);
            saved.appendTag(entry);
        });
        nbt.setTag("Targets", saved);

        nbt.removeTag("IgnoredTargets");
    }
}
