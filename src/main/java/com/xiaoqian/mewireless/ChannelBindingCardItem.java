/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

final class ChannelBindingCardItem extends Item {

    private static final String DIMENSION = "Dimension";
    private static final String TRANSMITTER = "Transmitter";

    ChannelBindingCardItem() {
        setMaxStackSize(1);
    }

    @Nullable
    static BlockPos heldTransmitter(EntityPlayer player) {
        int dimension = player.world.provider.getDimension();
        BlockPos mainHand = boundTransmitter(player.getHeldItemMainhand(), dimension);
        return mainHand != null ? mainHand : boundTransmitter(player.getHeldItemOffhand(), dimension);
    }

    @Nullable
    private static BlockPos boundTransmitter(ItemStack stack, int dimension) {
        if (!(stack.getItem() instanceof ChannelBindingCardItem)) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(TRANSMITTER, 4) && tag.getInteger(DIMENSION) == dimension
                ? BlockPos.fromLong(tag.getLong(TRANSMITTER)) : null;
    }

    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos target, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        TileEntity tile = world.getTileEntity(target);
        if (player.isSneaking() && tile instanceof WirelessTransmitterTile) {
            if (!world.isRemote && ((WirelessTransmitterTile) tile).canEdit(player)
                    && world.isBlockModifiable(player, target)) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setInteger(DIMENSION, world.provider.getDimension());
                tag.setLong(TRANSMITTER, target.toLong());
                stack.setTagCompound(tag);
                message(player, "bound", ((WirelessTransmitterTile) tile).links().getConnectedTargets().size());
            }
            return EnumActionResult.SUCCESS;
        }

        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TRANSMITTER, 4)) {
            return EnumActionResult.PASS;
        }

        if (!world.isRemote) {
            BlockPos emitterPos = BlockPos.fromLong(tag.getLong(TRANSMITTER));
            boolean linked = false;
            if (tag.getInteger(DIMENSION) == world.provider.getDimension() && world.isBlockLoaded(emitterPos)
                    && world.isBlockModifiable(player, emitterPos) && world.isBlockModifiable(player, target)
                    && player.canPlayerEdit(target, side, stack)) {
                TileEntity emitter = world.getTileEntity(emitterPos);
                if (emitter instanceof WirelessTransmitterTile
                        && ((WirelessTransmitterTile) emitter).canEdit(player)) {
                    linked = ((WirelessTransmitterTile) emitter).links().toggle(target);
                }
            }
            message(player, linked ? "updated" : "invalid");
        }
        return EnumActionResult.SUCCESS;
    }

    private static void message(EntityPlayer player, String key, Object... args) {
        player.sendStatusMessage(new TextComponentTranslation("item.me_wireless.channel_binding_card." + key, args), true);
    }
}
