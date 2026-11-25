/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientTransmitterGuiHandler extends TransmitterGuiHandler {

    @Override
    @Nullable
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return id == TRANSMITTER
                ? new TransmitterScreen(new TransmitterContainer(player.inventory, null), new BlockPos(x, y, z))
                : null;
    }

    @Override
    void applySnapshot(int window, NBTTagCompound data) {
        Minecraft client = Minecraft.getMinecraft();
        client.addScheduledTask(() -> {
            if (window == TransmitterPackets.OVERLAY_WINDOW) {
                TransmitterOverlay.accept(data);
                return;
            }
            if (client.player != null && client.player.openContainer instanceof TransmitterContainer
                    && client.player.openContainer.windowId == window) {
                ((TransmitterContainer) client.player.openContainer).applySnapshot(data);
            }
        });
    }
}
