/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import appeng.api.AEApi;
import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@Mod(
        modid = MEWireless.MOD_ID,
        name = "@{MOD_NAME}",
        version = "@{MOD_VERSION}",
        acceptedMinecraftVersions = "[1.12.2]",
        dependencies = "required-after:appliedenergistics2;after:ymadditions"
)
@Mod.EventBusSubscriber(modid = MEWireless.MOD_ID)
public final class MEWireless {

    public static final String MOD_ID = "@{MOD_ID}";
    public static final ResourceLocation WIRELESS_SIGNAL_GENERATOR_ID = new ResourceLocation(MOD_ID, "wireless_signal_generator");

    public static final ResourceLocation CHANNEL_BINDING_CARD_ID = new ResourceLocation(MOD_ID, "channel_binding_card");

    private static final CreativeTabs CREATIVE_TAB = new CreativeTabs(MOD_ID) {
        @Override
        @SideOnly(Side.CLIENT)
        public ItemStack createIcon() {
            return new ItemStack(Block.REGISTRY.getObject(WIRELESS_SIGNAL_GENERATOR_ID));
        }
    };

    @Mod.Instance(MOD_ID)
    public static MEWireless instance;

    @SidedProxy(
            clientSide = "com.xiaoqian.mewireless.ClientTransmitterGuiHandler",
            serverSide = "com.xiaoqian.mewireless.TransmitterGuiHandler"
    )
    public static TransmitterGuiHandler guiHandler;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        TransmitterConfig.load(event.getSuggestedConfigurationFile());
        AEApi.instance().registries().gridCache().registerGridCache(TransmitterGridCache.class, TransmitterGridCache.class);
        GameRegistry.registerTileEntity(WirelessTransmitterTile.class, WIRELESS_SIGNAL_GENERATOR_ID);
        NetworkRegistry.INSTANCE.registerGuiHandler(this, guiHandler);
        TransmitterPackets.register();
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(new WirelessTransmitterBlock()
                .setRegistryName(WIRELESS_SIGNAL_GENERATOR_ID)
                .setCreativeTab(CREATIVE_TAB)
                .setTranslationKey(MOD_ID + ".wireless_signal_generator"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        Block transmitter = Block.REGISTRY.getObject(WIRELESS_SIGNAL_GENERATOR_ID);
        event.getRegistry().register(new ItemBlock(transmitter).setRegistryName(WIRELESS_SIGNAL_GENERATOR_ID));
        event.getRegistry().register(new ChannelBindingCardItem()
                .setRegistryName(CHANNEL_BINDING_CARD_ID)
                .setCreativeTab(CREATIVE_TAB)
                .setTranslationKey(MOD_ID + ".channel_binding_card"));
    }
}
