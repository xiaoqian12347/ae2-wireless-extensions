/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.core.AEConfig;
import appeng.tile.grid.AENetworkTile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

public final class WirelessTransmitterTile extends AENetworkTile implements ITickable {

    static final int MIN_RADIUS = 1;
    static final int MAX_CHANNELS = 999;
    static final int DEFAULT_RADIUS = 7;
    static final int DEFAULT_CHANNELS = 32;
    private static final int CHANNEL_EDIT_DELAY = 5;
    private static final int STARTUP_RESTORE_WINDOW = 60;

    private final TransmitterLinks links = new TransmitterLinks(this);
    private int radius = Math.min(DEFAULT_RADIUS, TransmitterConfig.maxRadius());
    private int requestedChannels = DEFAULT_CHANNELS;
    private int appliedRequestedChannels = DEFAULT_CHANNELS;
    private long applyChannelChangeAt = -1;
    private boolean automatic = true;
    private UUID owner;
    private UUID bandId;
    private UUID bandUser;
    private String bandName = "";
    private IGridConnection bandInput;
    private YmBands.Receiver bandReceiver;
    private int bandCapacity;
    private boolean inputOnline;
    private long nextScan;
    private long startupRestoreUntil = -1;
    private boolean discoveryPending;
    private long nextManualScan;

    public WirelessTransmitterTile() {
        getProxy().setFlags(GridFlags.DENSE_CAPACITY);
        getProxy().setIdlePowerUsage(1.0);
        applyInputSides();
    }

    int getRadius() {
        return radius;
    }

    int getRequestedChannels() {
        return Math.min(requestedChannels, getInputCapacity());
    }

    int getAppliedChannelLimit() {
        return Math.min(appliedRequestedChannels, getInputCapacity());
    }

    boolean isAutomatic() {
        return automatic;
    }

    TransmitterLinks links() {
        return links;
    }

    void setOwner(EntityPlayer player) {
        owner = player.getUniqueID();
        getProxy().setOwner(player);
        markDirty();
    }

    boolean canEdit(EntityPlayer player) {
        return world.isBlockModifiable(player, pos)
                && (owner == null || owner.equals(player.getUniqueID()) || player.canUseCommand(2, ""));
    }

    void changeRadius(int delta) {
        int value = MathHelper.clamp(radius + delta, MIN_RADIUS, TransmitterConfig.maxRadius());
        if (value != radius) {
            TransmitterDiscovery.unregister(this);
            radius = value;
            TransmitterDiscovery.register(this);
            links.rangeChanged();
            scheduleDiscovery();
            markDirty();
        }
    }

    boolean requestRescan() {
        long time = world.getTotalWorldTime();
        if (time < nextManualScan) {
            return false;
        }
        nextManualScan = time + 10;
        links.retrySavedTargets();
        scheduleDiscovery();
        return true;
    }

    void scheduleDiscovery() {
        discoveryPending = true;
        nextScan = 0;
    }

    void changeRequestedChannels(int delta) {
        int capacity = getInputCapacity();
        if (capacity == 0) {
            return;
        }
        int value = MathHelper.clamp(getRequestedChannels() + delta, 0, Math.min(MAX_CHANNELS, capacity));
        if (value == requestedChannels) {
            return;
        }
        requestedChannels = value;
        applyChannelChangeAt = world.getTotalWorldTime() + CHANNEL_EDIT_DELAY;
        markDirty();
    }

    private void applyChannelChange(long now) {
        int previous = getAppliedChannelLimit();
        int requested = getRequestedChannels();
        IGridNode node = getActionableNode();
        IGrid grid = node == null ? null : node.getGrid();

        if (requestedChannels == 0) {
            links.clearRuntime();
            disconnectBand();
        } else if (requested < previous && grid != null) {
            if (TransmitterGridCache.isRouting(grid)) {
                return;
            }
            if (links.trimOne(requested)) {
                applyChannelChangeAt = now + 2;
                return;
            }
        }

        appliedRequestedChannels = requestedChannels;
        applyChannelChangeAt = -1;
        if (bandInput != null && grid != null && requested > previous) {
            grid.<IPathingGrid>getCache(IPathingGrid.class).repath();
        }
        if (requested != previous) {
            nextScan = 0;
        }
    }

    void setAutomatic(boolean automatic) {
        if (this.automatic != automatic) {
            this.automatic = automatic;
            if (!automatic) {
                links.pauseDiscovery();
            } else {
                scheduleDiscovery();
            }
            nextScan = 0;
            markDirty();
        }
    }

    @Nullable
    UUID getBandId() {
        return bandId;
    }

    boolean isWaitingForBand() {
        return bandId != null && bandInput == null;
    }

    String getBandName() {
        return bandName;
    }

    boolean selectBand(EntityPlayer player, @Nullable UUID id) {
        YmBands.Band band = id == null ? null : YmBands.get(world, id);
        if (!canEdit(player) || (id != null && (band == null || !band.permits(player)))) {
            return false;
        }

        if (owner == null) {
            setOwner(player);
        }
        applyBandSelection(band, id == null ? null : player.getUniqueID());
        return true;
    }

    private void applyBandSelection(@Nullable YmBands.Band band, @Nullable UUID user) {
        finishStartupRestore();
        disconnectBand();
        if (band != null) {
            TransmitterLinks.destroy(getWiredInput());
        }
        links.clearRuntime();
        appliedRequestedChannels = requestedChannels;
        applyChannelChangeAt = -1;
        bandId = band == null ? null : band.id;
        bandCapacity = 0;
        bandUser = user;
        bandName = band == null ? "" : band.name;
        inputOnline = false;
        applyInputSides();
        scheduleDiscovery();
        markDirty();
    }

    private void applyInputSides() {
        getProxy().setValidSides(bandId == null
                ? EnumSet.of(EnumFacing.DOWN) : EnumSet.noneOf(EnumFacing.class));
    }

    @Override
    public void onReady() {
        applyInputSides();
        getProxy().setVisualRepresentation(new ItemStack(getBlockType()));
        super.onReady();
        if (!world.isRemote && links.rangeChanged()) {
            markDirty();
        }
        TransmitterDiscovery.register(this);
        discoveryPending = true;
        nextScan = world.getTotalWorldTime() + Math.floorMod(pos.hashCode(), 20);
        if (!world.isRemote) {
            startSavedRestore();
        }
    }

    @Override
    @Nullable
    public IGridNode getGridNode(AEPartLocation side) {
        return bandId == null && side == AEPartLocation.DOWN ? super.getGridNode(side) : null;
    }

    @Override
    public AECableType getCableConnectionType(AEPartLocation side) {
        return bandId == null && side == AEPartLocation.DOWN ? AECableType.DENSE_SMART : AECableType.NONE;
    }

    @Override
    public void update() {
        if (!(world instanceof WorldServer) || !getProxy().isReady()) {
            return;
        }
        WorldServer serverWorld = (WorldServer) world;
        long time = world.getTotalWorldTime();
        if (time >= nextScan) {
            nextScan = time + 20;
            updateBandInput();
            boolean online = isInputOnline();
            if (online || !isInputRouting()) {
                if (inputOnline && !online) {
                    links.clearRuntime();
                }
                if (online && !inputOnline) {
                    discoveryPending = true;
                    if (bandId != null) {
                        startSavedRestore();
                    }
                }
                inputOnline = online;
            }
            boolean discover = online && automatic && discoveryPending;
            if (discover) {
                discoveryPending = false;
            }
            if (bandId == null || online) {
                links.scan(serverWorld, discover);
            }
        }
        if (applyChannelChangeAt >= 0 && time >= applyChannelChangeAt) {
            applyChannelChange(time);
        }
        if (applyChannelChangeAt < 0 && !isWaitingForBand() && !scheduleStartupRestore(time)) {
            finishStartupRestore();
            links.queueConnections(serverWorld);
        }
    }

    private boolean scheduleStartupRestore(long time) {
        if (startupRestoreUntil < 0 || appliedRequestedChannels < 2) {
            return false;
        }
        IGridNode input = startupInput();
        IGrid grid = input == null ? null : input.getGrid();
        if (grid == null) {
            return bandId != null || time <= startupRestoreUntil || isInputRouting();
        }
        if (TransmitterGridCache.isReadyForRestore(grid)) {
            TransmitterConnections.requestRestore(this);
        }
        return true;
    }

    @Nullable
    IGridNode startupInput() {
        return startupRestoreUntil >= 0 && !isInvalid() && applyChannelChangeAt < 0
                && isInputOnline() ? getActionableNode() : null;
    }

    private void startSavedRestore() {
        if (TransmitterRestore.maxBatchTargets() >= 2 && links.hasStartupTargets()) {
            startupRestoreUntil = world.getTotalWorldTime() + STARTUP_RESTORE_WINDOW;
        }
    }

    void finishStartupRestore() {
        startupRestoreUntil = -1;
    }

    @Nullable
    private IGridConnection routedBandInput(IGridNode input) {
        if (bandInput == null || !TransmitterLinks.isLive(input, bandInput)) {
            return null;
        }
        YmBands.Band band = permittedBand();
        return band != null && band.sender() == bandInput.getOtherSide(input) ? bandInput : null;
    }

    @Nullable
    private YmBands.Band permittedBand() {
        YmBands.Band band = bandId == null ? null : YmBands.get(world, bandId);
        return band != null && band.permits(bandUser, world) ? band : null;
    }

    private void updateBandInput() {
        if (bandId == null) {
            return;
        }

        YmBands.Band band = YmBands.get(world, bandId);
        if (band == null && YmBands.isDeleted(world, bandId)) {
            applyBandSelection(null, null);
            return;
        }
        if (band != null && !band.permits(bandUser, world)) {
            band = null;
        }
        if (band != null && !bandName.equals(band.name)) {
            bandName = band.name;
            markDirty();
        }
        IGridNode local = getActionableNode();
        IGridNode sender = band == null ? null : band.sender();
        IGrid senderGrid = sender == null ? null : sender.getGrid();
        if (local == null || senderGrid == null || sender == local
                || senderGrid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState()
                        != ControllerState.CONTROLLER_ONLINE) {
            bandCapacity = 0;
            disconnectBand();
            return;
        }
        bandCapacity = AEConfig.instance().getDenseChannelCapacity();
        if (getAppliedChannelLimit() == 0) {
            disconnectBand();
            return;
        }
        if (bandInput != null && TransmitterLinks.isLive(local, bandInput)
                && bandInput.getOtherSide(local) == sender) {
            registerBandReceiver(band);
            return;
        }

        disconnectBand();
        links.clearRuntime();
        if (local.getGrid() != senderGrid) {
            bandInput = TransmitterLinks.connect(local, sender);
            if (bandInput != null) {
                registerBandReceiver(band);
                startSavedRestore();
                TransmitterGridCache.awaitRouting(local.getGrid());
            }
        }
    }

    private void registerBandReceiver(YmBands.Band band) {
        if (bandReceiver != null && bandReceiver.isRegisteredWith(band)) {
            return;
        }
        unregisterBandReceiver();
        bandReceiver = band.registerReceiver(world, pos);
    }

    void unregisterBandReceiver() {
        YmBands.Receiver receiver = bandReceiver;
        bandReceiver = null;
        if (receiver != null) {
            receiver.unregister();
        }
    }

    private void disconnectBand() {
        IGridConnection connection = bandInput;
        bandInput = null;
        TransmitterLinks.destroy(connection);
        unregisterBandReceiver();
    }

    @Nullable
    private IGridConnection getWiredInput() {
        IGridNode node = getActionableNode();
        if (node != null) {
            for (IGridConnection connection : node.getConnections()) {
                if (connection.hasDirection() && connection.getDirection(node) == AEPartLocation.DOWN) {
                    return connection;
                }
            }
        }
        return null;
    }

    private boolean isInputRouting() {
        IGridConnection input = bandId == null ? getWiredInput() : bandInput;
        IGridNode node = getActionableNode();
        IGrid grid = node == null ? null : node.getGrid();
        return input != null && grid != null && TransmitterLinks.isLive(node, input)
                && TransmitterGridCache.isRouting(grid);
    }

    boolean isInputOnline() {
        if (!getProxy().isActive()) {
            return false;
        }
        if (bandId == null) {
            return getWiredInput() != null;
        }

        IGridNode node = getActionableNode();
        return getAppliedChannelLimit() > 0 && bandInput != null && node != null
                && TransmitterLinks.isLive(node, bandInput);
    }

    int getInputCapacity() {
        if (bandId != null) {
            return bandCapacity;
        }
        if (!isInputOnline()) {
            return 0;
        }
        IGridConnection input = getWiredInput();
        IGridNode node = getActionableNode();
        return input.getOtherSide(node).hasFlag(GridFlags.DENSE_CAPACITY)
                ? AEConfig.instance().getDenseChannelCapacity() : AEConfig.instance().getNormalChannelCapacity();
    }

    @Nullable
    TransmitterChannelPath findInputPath(IGrid grid) {
        IGridNode node = getActionableNode();
        if (node == null || node.getGrid() != grid
                || grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState() != ControllerState.CONTROLLER_ONLINE) {
            return null;
        }
        IGridConnection input = bandId == null ? getWiredInput() : routedBandInput(node);
        return input == null ? null : TransmitterChannelPath.find(node, grid, input);
    }

    int getWiredRemaining(IGrid grid, @Nullable TransmitterChannelPath path, int used) {
        ControllerState state = grid.<IPathingGrid>getCache(IPathingGrid.class).getControllerState();
        if (state == ControllerState.NO_CONTROLLER) {
            return Math.max(0, AEConfig.instance().getNormalChannelCapacity() - used);
        }
        return path == null ? 0 : path.remaining();
    }

    int getRemainingChannels(int capacity, int used) {
        int localRemaining = capacity == Integer.MAX_VALUE ? capacity : Math.max(0, capacity - used);
        if (bandId != null || localRemaining == 0) {
            return localRemaining;
        }
        IGridNode node = getActionableNode();
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null || !TransmitterGridCache.isReadyForRestore(grid)) {
            return 0;
        }
        return Math.min(localRemaining, getWiredRemaining(grid, findInputPath(grid), used));
    }

    int getUsedChannels() {
        IGridConnection input = bandId == null ? getWiredInput() : bandInput;
        return input != null && isInputOnline() ? input.getUsedChannels() : 0;
    }

    public boolean isAtWirelessChannelLimit(IGridConnection connection, int pendingChannels) {
        return connection == bandInput && pendingChannels >= getAppliedChannelLimit()
                || links.isOutputBridge(connection) && pendingChannels >= appliedRequestedChannels;
    }

    private void clearRuntime() {
        finishStartupRestore();
        bandCapacity = 0;
        disconnectBand();
        links.clearRuntime();
        inputOnline = false;
    }

    @Override
    public void onChunkUnload() {
        TransmitterDiscovery.unregister(this);
        clearRuntime();
        super.onChunkUnload();
    }

    @Override
    public void invalidate() {
        TransmitterDiscovery.unregister(this);
        clearRuntime();
        super.invalidate();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        int savedRadius = nbt.hasKey("BlockRadius") ? nbt.getInteger("BlockRadius") : DEFAULT_RADIUS;
        radius = MathHelper.clamp(savedRadius, MIN_RADIUS, TransmitterConfig.maxRadius());
        requestedChannels = nbt.hasKey("RequestedChannels")
                ? MathHelper.clamp(nbt.getInteger("RequestedChannels"), 0, MAX_CHANNELS) : DEFAULT_CHANNELS;
        appliedRequestedChannels = requestedChannels;
        applyChannelChangeAt = -1;
        automatic = !nbt.hasKey("Automatic") || nbt.getBoolean("Automatic");
        owner = nbt.hasUniqueId("Owner") ? nbt.getUniqueId("Owner") : null;
        bandId = nbt.hasUniqueId("Band") ? nbt.getUniqueId("Band") : null;
        bandUser = nbt.hasUniqueId("BandUser") ? nbt.getUniqueId("BandUser") : owner;
        bandName = nbt.getString("BandName");
        links.read(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.removeTag("RangeRadius");
        nbt.setInteger("BlockRadius", radius);
        nbt.setInteger("RequestedChannels", requestedChannels);
        nbt.setBoolean("Automatic", automatic);
        if (owner != null) {
            nbt.setUniqueId("Owner", owner);
        }
        if (bandId != null) {
            nbt.setUniqueId("Band", bandId);
            nbt.setString("BandName", bandName);
            if (bandUser != null) {
                nbt.setUniqueId("BandUser", bandUser);
            }
        }
        links.write(nbt);
        return nbt;
    }
}
