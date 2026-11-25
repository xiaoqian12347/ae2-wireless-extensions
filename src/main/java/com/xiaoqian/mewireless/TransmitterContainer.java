/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;

final class TransmitterContainer extends Container {

    enum Setting {
        CHANNELS_MINUS_TEN(tile -> tile.changeRequestedChannels(-10)),
        CHANNELS_MINUS_ONE(tile -> tile.changeRequestedChannels(-1)),
        CHANNELS_PLUS_ONE(tile -> tile.changeRequestedChannels(1)),
        CHANNELS_PLUS_TEN(tile -> tile.changeRequestedChannels(10)),
        RANGE_MINUS(tile -> tile.changeRadius(-1)),
        RANGE_PLUS(tile -> tile.changeRadius(1)),
        AUTOMATIC(tile -> tile.setAutomatic(true)),
        MANUAL(tile -> tile.setAutomatic(false));

        private static final Setting[] ACTIONS = values();
        private final java.util.function.Consumer<WirelessTransmitterTile> update;

        Setting(java.util.function.Consumer<WirelessTransmitterTile> update) {
            this.update = update;
        }
    }

    private static final int RADIUS = 0;
    private static final int REQUESTED_CHANNELS = 1;
    private static final int AUTOMATIC = 2;
    private static final int MAX_RADIUS = 3;
    private static final int MAIN_SLOTS = 27;
    private static final int INVENTORY_SLOTS = 36;

    @Nullable
    private final WirelessTransmitterTile tile;
    private final EntityPlayer viewer;
    private int view;
    private int page;
    private long nextSnapshot;
    private long refreshBandsAt;
    private List<YmBands.Band> visibleBands = Collections.emptyList();
    private NBTTagCompound snapshot = new NBTTagCompound();
    private int radius = WirelessTransmitterTile.DEFAULT_RADIUS;
    private int maxRadius = TransmitterConfig.DEFAULT_MAX_RADIUS;
    private int requestedChannels = WirelessTransmitterTile.DEFAULT_CHANNELS;
    private boolean automatic = true;

    TransmitterContainer(InventoryPlayer inventory, @Nullable WirelessTransmitterTile tile) {
        this.tile = tile;
        viewer = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new Slot(inventory, column + (row + 1) * 9,
                        8 + column * 18, 145 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new Slot(inventory, column, 8 + column * 18, 203));
        }
    }

    int getRadius() {
        return radius;
    }

    int getMaxRadius() {
        return maxRadius;
    }

    int getRequestedChannels() {
        return requestedChannels;
    }

    boolean isAutomatic() {
        return automatic;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (tile == null) {
            return player.world.isRemote;
        }

        BlockPos pos = tile.getPos();
        return player.world == tile.getWorld()
                && !tile.isInvalid()
                && player.world.isBlockLoaded(pos)
                && player.world.getTileEntity(pos) == tile
                && player.getDistanceSqToCenter(pos) <= 64.0;
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        if (tile == null || id < 0 || id >= Setting.ACTIONS.length || !canInteractWith(player)
                || !tile.canEdit(player)) {
            return false;
        }

        Setting.ACTIONS[id].update.accept(tile);
        detectAndSendChanges();
        return true;
    }

    @Override
    public void addListener(IContainerListener listener) {
        super.addListener(listener);
        if (tile != null) {
            listener.sendWindowProperty(this, MAX_RADIUS, TransmitterConfig.maxRadius());
            listener.sendWindowProperty(this, RADIUS, tile.getRadius());
            listener.sendWindowProperty(this, REQUESTED_CHANNELS, tile.getRequestedChannels());
            listener.sendWindowProperty(this, AUTOMATIC, tile.isAutomatic() ? 1 : 0);
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (tile == null) {
            return;
        }

        if (radius != tile.getRadius()) {
            radius = tile.getRadius();
            sendProperty(RADIUS, radius);
        }
        if (requestedChannels != tile.getRequestedChannels()) {
            requestedChannels = tile.getRequestedChannels();
            sendProperty(REQUESTED_CHANNELS, requestedChannels);
        }
        if (automatic != tile.isAutomatic()) {
            automatic = tile.isAutomatic();
            sendProperty(AUTOMATIC, automatic ? 1 : 0);
        }
        long now = tile.getWorld().getTotalWorldTime();
        if (now >= nextSnapshot) {
            nextSnapshot = now + 10;
            sendSnapshot();
        }
    }

    void setView(int kind, int page) {
        if (kind >= TransmitterPackets.MAIN && kind <= TransmitterPackets.CONNECTIONS) {
            if (this.view != kind && kind == TransmitterPackets.BANDS) {
                refreshBandsAt = 0;
            }
            this.view = kind;
            this.page = Math.max(0, page);
            sendSnapshot();
        }
    }

    void selectBand(@Nullable UUID id) {
        if (tile != null && tile.selectBand(viewer, id)) {
            sendSnapshot();
        }
    }

    void rescan() {
        if (tile != null && tile.canEdit(viewer) && tile.requestRescan()) {
            String result = tile.isAutomatic() ? "rescan_queued" : "retry_queued";
            viewer.sendStatusMessage(new TextComponentTranslation("gui.ae2_wireless_extensions.transmitter." + result), true);
        }
    }

    void disconnect(BlockPos pos) {
        if (tile != null && tile.canEdit(viewer)) {
            tile.links().disconnect(pos);
            sendSnapshot();
        }
    }

    NBTTagCompound getSnapshot() {
        return snapshot;
    }

    void applySnapshot(NBTTagCompound snapshot) {
        this.snapshot = snapshot;
    }

    private void sendSnapshot() {
        if (tile == null || !(viewer instanceof EntityPlayerMP)) {
            return;
        }
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("Kind", view);
        data.setBoolean("YmSupported", YmBands.isAvailable());
        data.setBoolean("Editable", tile.canEdit(viewer));
        data.setBoolean("Online", tile.isInputOnline());
        data.setInteger("Capacity", tile.getInputCapacity());
        data.setInteger("Used", tile.getUsedChannels());
        UUID selected = tile.getBandId();
        if (selected != null) {
            data.setUniqueId("Band", selected);
            data.setString("BandName", limitName(tile.getBandName()));
        }

        NBTTagList rows = new NBTTagList();
        if (view == TransmitterPackets.BANDS) {
            long now = tile.getWorld().getTotalWorldTime();
            if (now >= refreshBandsAt) {
                visibleBands = YmBands.visibleTo(viewer);
                refreshBandsAt = now + 40;
            }
            List<YmBands.Band> bands = visibleBands;
            int start = pageStart(data, bands.size());
            for (int i = start; i < Math.min(bands.size(), start + TransmitterPackets.ROWS); i++) {
                YmBands.Band band = bands.get(i);
                NBTTagCompound row = new NBTTagCompound();
                row.setUniqueId("Band", band.id);
                row.setString("Name", limitName(band.name));
                rows.appendTag(row);
            }
        } else if (view == TransmitterPackets.CONNECTIONS) {
            int start = pageStart(data, tile.links().getConnectedTargets().size());
            Iterator<BlockPos> targets = tile.links().getConnectedTargets().iterator();
            for (int i = 0; i < start && targets.hasNext(); i++) {
                targets.next();
            }
            for (int i = 0; i < TransmitterPackets.ROWS && targets.hasNext(); i++) {
                BlockPos target = targets.next();
                NBTTagCompound row = new NBTTagCompound();
                row.setLong("Target", target.toLong());
                rows.appendTag(row);
            }
        }
        data.setTag("Rows", rows);

        if (!snapshot.equals(data)) {
            snapshot = data;
            TransmitterPackets.sync((EntityPlayerMP) viewer, windowId, data);
        }
    }

    private int pageStart(NBTTagCompound data, int total) {
        int pages = Math.max(1, (total + TransmitterPackets.ROWS - 1) / TransmitterPackets.ROWS);
        page = Math.min(page, pages - 1);
        data.setInteger("Page", page);
        data.setInteger("Pages", pages);
        return page * TransmitterPackets.ROWS;
    }

    private static String limitName(String name) {
        return name == null ? "" : name.substring(0, Math.min(name.length(), 64));
    }

    private void sendProperty(int property, int value) {
        for (IContainerListener listener : listeners) {
            listener.sendWindowProperty(this, property, value);
        }
    }

    @Override
    public void updateProgressBar(int id, int value) {
        switch (id) {
            case MAX_RADIUS:
                maxRadius = value;
                break;
            case RADIUS:
                radius = value;
                break;
            case REQUESTED_CHANNELS:
                requestedChannels = value;
                break;
            case AUTOMATIC:
                automatic = value != 0;
                break;
            default:
                break;
        }
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (!slot.getHasStack()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getStack();
        ItemStack original = stack.copy();
        boolean moved = index < MAIN_SLOTS
                ? mergeItemStack(stack, MAIN_SLOTS, INVENTORY_SLOTS, false)
                : mergeItemStack(stack, 0, MAIN_SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }
        slot.onTake(player, stack);
        return original;
    }
}
