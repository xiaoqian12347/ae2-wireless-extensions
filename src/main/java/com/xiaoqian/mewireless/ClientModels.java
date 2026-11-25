/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = MEWireless.MOD_ID, value = Side.CLIENT)
public final class ClientModels {

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(
                Item.REGISTRY.getObject(MEWireless.WIRELESS_SIGNAL_GENERATOR_ID),
                0,
                new ModelResourceLocation(MEWireless.WIRELESS_SIGNAL_GENERATOR_ID, "inventory")
        );
        ModelLoader.setCustomModelResourceLocation(
                Item.REGISTRY.getObject(MEWireless.CHANNEL_BINDING_CARD_ID), 0,
                new ModelResourceLocation(MEWireless.CHANNEL_BINDING_CARD_ID, "inventory")
        );
    }
}
