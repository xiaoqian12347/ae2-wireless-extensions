/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public class TransmitterGuiHandler implements IGuiHandler {

    static final int TRANSMITTER = 0;

    @Override
    @Nullable
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != TRANSMITTER) {
            return null;
        }

        TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
        if (tile instanceof WirelessTransmitterTile) {
            return new TransmitterContainer(player.inventory, (WirelessTransmitterTile) tile);
        }
        return null;
    }

    @Override
    @Nullable
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
    void applySnapshot(int window, NBTTagCompound data) {
    }
}
