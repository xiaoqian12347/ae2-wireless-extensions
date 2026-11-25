/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = MEWireless.MOD_ID, value = Side.CLIENT)
public final class TransmitterOverlay {

    private static final int RANGE_EDGE = 0x83B19AF2;
    private static final int RANGE_CORNER = 0xE4D7C5FF;
    private static final int RANGE_OCCLUDED = 0x3AB19AF2;
    private static final int LINK_HALO = 0x522AD9D4;
    private static final int LINK_CORE = 0xEEB8FFFA;
    private static final int LINK_OCCLUDED = 0x4B70EFDF;
    private static final double MARKER_SIZE = 0.15;
    private static final int HIGHLIGHT_COLOR = 0xFFFFE064;
    private static final int HIGHLIGHT_SHADOW = 0xFA392510;

    private static final BlockPos[] NO_LINKS = new BlockPos[0];
    private static final Overlay RANGE = new Overlay(false);
    private static final Overlay CARD = new Overlay(true);

    private static WorldClient world;
    private static BlockPos rangeOrigin;
    private static WorldClient highlightWorld;
    private static BlockPos highlightTarget;
    private static long highlightEnds;

    private TransmitterOverlay() {
    }

    static boolean isEnabled(BlockPos pos) {
        return world == Minecraft.getMinecraft().world && pos.equals(rangeOrigin);
    }

    static void toggle(BlockPos pos) {
        Minecraft client = Minecraft.getMinecraft();
        if (client.world == null || client.player == null) {
            return;
        }
        syncWorld(client);
        rangeOrigin = pos.equals(rangeOrigin) ? null : pos.toImmutable();
        updateWatches(client);
    }

    static void highlight(BlockPos target) {
        Minecraft client = Minecraft.getMinecraft();
        if (client.world != null && client.world.isBlockLoaded(target)) {
            highlightWorld = client.world;
            highlightTarget = target.toImmutable();
            highlightEnds = System.nanoTime() + 8_000_000_000L;
        }
    }

    static void accept(NBTTagCompound data) {
        if (world == null || world != Minecraft.getMinecraft().world
                || world.provider.getDimension() != data.getInteger("Dimension")) {
            return;
        }
        Overlay overlay = data.getBoolean("Card") ? CARD : RANGE;
        overlay.accept(data);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getMinecraft();
        if (highlightTarget != null && (client.world != highlightWorld
                || System.nanoTime() >= highlightEnds || !highlightWorld.isBlockLoaded(highlightTarget))) {
            highlightTarget = null;
            highlightWorld = null;
        }
        syncWorld(client);
        updateWatches(client);
        if (client.player != null && client.player.ticksExisted % 5 == 0) {
            RANGE.refreshVisibleLinks();
            CARD.refreshVisibleLinks();
        }
    }

    private static void syncWorld(Minecraft client) {
        if (world != client.world) {
            world = client.world;
            rangeOrigin = null;
            RANGE.watch(client, null);
            CARD.watch(client, null);
        }
    }

    private static void updateWatches(Minecraft client) {
        if (client.player == null || world == null) {
            rangeOrigin = null;
            RANGE.watch(client, null);
            CARD.watch(client, null);
            return;
        }

        WirelessTransmitterTile rangeTile = overlayTile(client, rangeOrigin);
        if (rangeTile == null) {
            rangeOrigin = null;
        }
        RANGE.watch(client, rangeTile);

        BlockPos cardOrigin = ChannelBindingCardItem.heldTransmitter(client.player);
        CARD.watch(client, cardOrigin == null || cardOrigin.equals(rangeOrigin) ? null : overlayTile(client, cardOrigin));
    }

    private static WirelessTransmitterTile overlayTile(Minecraft client, BlockPos pos) {
        if (pos == null || client.player.getDistanceSqToCenter(pos) > TransmitterPackets.OVERLAY_DISTANCE_SQ
                || !world.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity tile = world.getTileEntity(pos);
        return tile instanceof WirelessTransmitterTile && !tile.isInvalid() ? (WirelessTransmitterTile) tile : null;
    }

    @SubscribeEvent
    public static void render(RenderWorldLastEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        Entity camera = client.getRenderViewEntity();
        boolean renderRange = world == client.world && RANGE.radius >= 0;
        boolean renderOverlay = renderRange || (world == client.world && CARD.visibleLinkCount > 0);
        boolean renderTarget = highlightTarget != null && highlightWorld == client.world;
        if (camera == null || (!renderOverlay && !renderTarget)) {
            return;
        }

        float partial = event.getPartialTicks();
        double cameraX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partial;
        double cameraY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partial;
        double cameraZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partial;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean writeDepth = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.depthMask(false);
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (renderOverlay) {
                double yaw = Math.toRadians(camera.prevRotationYaw
                        + MathHelper.wrapDegrees(camera.rotationYaw - camera.prevRotationYaw) * partial);
                double pitch = Math.toRadians(camera.prevRotationPitch
                        + (camera.rotationPitch - camera.prevRotationPitch) * partial);
                drawWirelessOverlay(tessellator, buffer, cameraX, cameraY, cameraZ, yaw, pitch, renderRange);
            }
            if (renderTarget) {
                GlStateManager.disableDepth();
                double x = highlightTarget.getX() - cameraX - 0.025;
                double y = highlightTarget.getY() - cameraY - 0.025;
                double z = highlightTarget.getZ() - cameraZ - 0.025;
                double maxX = x + 1.05;
                double maxY = y + 1.05;
                double maxZ = z + 1.05;
                RenderGlobal.renderFilledBox(x, y, z, maxX, maxY, maxZ, 1.0F, 0.71F, 0.12F, 0.19F);

                GlStateManager.glLineWidth(6.0F);
                buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                box(buffer, x, y, z, maxX, maxY, maxZ, HIGHLIGHT_SHADOW);
                tessellator.draw();

                GlStateManager.glLineWidth(3.0F);
                buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                box(buffer, x, y, z, maxX, maxY, maxZ, HIGHLIGHT_COLOR);
                tessellator.draw();
            }
        } finally {
            GlStateManager.glLineWidth(lineWidth);
            GlStateManager.depthMask(writeDepth);
            GlStateManager.depthFunc(depthFunc);
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (depth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (!blend) {
                GlStateManager.disableBlend();
            }
            if (lighting) {
                GlStateManager.enableLighting();
            }
            if (texture) {
                GlStateManager.enableTexture2D();
            }
        }
    }

    private static void drawWirelessOverlay(Tessellator tessellator, BufferBuilder buffer,
                                            double cameraX, double cameraY, double cameraZ,
                                            double yaw, double pitch, boolean renderRange) {
        BlockPos origin = renderRange ? RANGE.tile.getPos() : BlockPos.ORIGIN;
        int radius = RANGE.radius;
        double minX = origin.getX() - radius - cameraX;
        double minY = origin.getY() - radius - cameraY;
        double minZ = origin.getZ() - radius - cameraZ;
        double size = radius * 2 + 1;
        double maxX = minX + size;
        double maxY = minY + size;
        double maxZ = minZ + size;

        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_GREATER);
        GlStateManager.glLineWidth(2.5F);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        if (renderRange) {
            box(buffer, minX, minY, minZ, maxX, maxY, maxZ, RANGE_OCCLUDED);
        }
        RANGE.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_OCCLUDED);
        CARD.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_OCCLUDED);
        tessellator.draw();

        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.glLineWidth(6.0F);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        if (renderRange) {
            drawCorners(buffer, minX, minY, minZ, maxX, maxY, maxZ, Math.min(2.5, size * 0.16));
        }
        RANGE.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_HALO);
        CARD.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_HALO);
        tessellator.draw();

        GlStateManager.glLineWidth(3.5F);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        if (renderRange) {
            box(buffer, minX, minY, minZ, maxX, maxY, maxZ, RANGE_EDGE);
        }
        RANGE.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_CORE);
        CARD.drawLinks(buffer, cameraX, cameraY, cameraZ, LINK_CORE);
        RANGE.drawMarkers(buffer, cameraX, cameraY, cameraZ, yaw, pitch);
        CARD.drawMarkers(buffer, cameraX, cameraY, cameraZ, yaw, pitch);
        tessellator.draw();
    }

    private static void drawCorners(BufferBuilder buffer, double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ, double length) {
        for (int corner = 0; corner < 8; corner++) {
            boolean upperX = (corner & 1) != 0;
            boolean upperY = (corner & 2) != 0;
            boolean upperZ = (corner & 4) != 0;
            double x = upperX ? maxX : minX;
            double y = upperY ? maxY : minY;
            double z = upperZ ? maxZ : minZ;
            edge(buffer, x, y, z, x + (upperX ? -length : length), y, z, RANGE_CORNER);
            edge(buffer, x, y, z, x, y + (upperY ? -length : length), z, RANGE_CORNER);
            edge(buffer, x, y, z, x, y, z + (upperZ ? -length : length), RANGE_CORNER);
        }
    }

    private static void box(BufferBuilder buffer, double x1, double y1, double z1,
                            double x2, double y2, double z2, int color) {
        rectangle(buffer, x1, z1, x2, z2, y1, color);
        rectangle(buffer, x1, z1, x2, z2, y2, color);
        edge(buffer, x1, y1, z1, x1, y2, z1, color);
        edge(buffer, x1, y1, z2, x1, y2, z2, color);
        edge(buffer, x2, y1, z1, x2, y2, z1, color);
        edge(buffer, x2, y1, z2, x2, y2, z2, color);
    }

    private static void rectangle(BufferBuilder buffer, double x1, double z1, double x2, double z2,
                                  double y, int color) {
        edge(buffer, x1, y, z1, x2, y, z1, color);
        edge(buffer, x2, y, z1, x2, y, z2, color);
        edge(buffer, x2, y, z2, x1, y, z2, color);
        edge(buffer, x1, y, z2, x1, y, z1, color);
    }

    private static void edge(BufferBuilder buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        int alpha = color >>> 24;
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;
        buffer.pos(x1, y1, z1).color(red, green, blue, alpha).endVertex();
        buffer.pos(x2, y2, z2).color(red, green, blue, alpha).endVertex();
    }

    private static final class Overlay {
        private final boolean card;
        private WirelessTransmitterTile tile;
        private BlockPos[] links = NO_LINKS;
        private BlockPos[] visibleLinks = NO_LINKS;
        private int visibleLinkCount;
        private int radius = -1;

        Overlay(boolean card) {
            this.card = card;
        }

        void watch(Minecraft client, WirelessTransmitterTile nextTile) {
            if (nextTile == tile) {
                return;
            }
            if (nextTile != null) {
                TransmitterPackets.requestOverlay(world.provider.getDimension(), nextTile.getPos(), card, false);
            } else if (world != null && client.player != null && tile.getWorld() == world) {
                TransmitterPackets.requestOverlay(world.provider.getDimension(), tile.getPos(), card, true);
            }
            tile = nextTile;
            clearSnapshot();
        }

        void accept(NBTTagCompound data) {
            if (tile == null || tile.getPos().toLong() != data.getLong("Origin")) {
                return;
            }
            if (!data.getBoolean("Valid")) {
                clearSnapshot();
                return;
            }

            radius = MathHelper.clamp(data.getInteger("Radius"),
                    WirelessTransmitterTile.MIN_RADIUS, TransmitterConfig.RADIUS_LIMIT);
            NBTTagList targets = data.getTagList("Links", 4);
            links = new BlockPos[targets.tagCount()];
            for (int i = 0; i < links.length; i++) {
                links[i] = BlockPos.fromLong(((NBTTagLong) targets.get(i)).getLong());
            }
            visibleLinks = new BlockPos[links.length];
            refreshVisibleLinks();
        }

        void refreshVisibleLinks() {
            visibleLinkCount = 0;
            for (BlockPos target : links) {
                if (world.isBlockLoaded(target)) {
                    visibleLinks[visibleLinkCount++] = target;
                }
            }
        }

        private void clearSnapshot() {
            radius = -1;
            links = NO_LINKS;
            visibleLinks = NO_LINKS;
            visibleLinkCount = 0;
        }

        void drawLinks(BufferBuilder buffer, double cameraX, double cameraY, double cameraZ, int color) {
            if (visibleLinkCount == 0) {
                return;
            }
            BlockPos origin = tile.getPos();
            double startX = origin.getX() + 0.5 - cameraX;
            double startY = origin.getY() + 1.1 - cameraY;
            double startZ = origin.getZ() + 0.5 - cameraZ;
            for (int i = 0; i < visibleLinkCount; i++) {
                BlockPos target = visibleLinks[i];
                edge(buffer, startX, startY, startZ, target.getX() + 0.5 - cameraX,
                        target.getY() + 1.15 - cameraY, target.getZ() + 0.5 - cameraZ, color);
            }
        }

        void drawMarkers(BufferBuilder buffer, double cameraX, double cameraY, double cameraZ,
                                        double yaw, double pitch) {
            if (visibleLinkCount == 0) {
                return;
            }
            double rightX = Math.cos(yaw) * MARKER_SIZE;
            double rightZ = Math.sin(yaw) * MARKER_SIZE;
            double upX = -Math.sin(yaw) * Math.sin(pitch) * MARKER_SIZE;
            double upY = Math.cos(pitch) * MARKER_SIZE;
            double upZ = Math.cos(yaw) * Math.sin(pitch) * MARKER_SIZE;

            for (int i = 0; i < visibleLinkCount; i++) {
                BlockPos target = visibleLinks[i];
                double x = target.getX() + 0.5 - cameraX;
                double y = target.getY() + 1.15 - cameraY;
                double z = target.getZ() + 0.5 - cameraZ;
                edge(buffer, x + upX, y + upY, z + upZ, x + rightX, y, z + rightZ, LINK_CORE);
                edge(buffer, x + rightX, y, z + rightZ, x - upX, y - upY, z - upZ, LINK_CORE);
                edge(buffer, x - upX, y - upY, z - upZ, x - rightX, y, z - rightZ, LINK_CORE);
                edge(buffer, x - rightX, y, z - rightZ, x + upX, y + upY, z + upZ, LINK_CORE);
            }
        }
    }
}
