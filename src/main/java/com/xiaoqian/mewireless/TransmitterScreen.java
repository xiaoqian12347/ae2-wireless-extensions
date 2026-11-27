/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import com.xiaoqian.mewireless.TransmitterContainer.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@SideOnly(Side.CLIENT)
final class TransmitterScreen extends GuiContainer {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation(MEWireless.MOD_ID, "textures/gui/wireless_signal_generator.png");
    private static final String KEY = "gui.ae2_wireless_extensions.transmitter.";
    private static final int TEXT = 0x36374A;
    private static final int PANEL_COLOR = 0xFFC9CAD3;
    private static final int SECONDARY = 0x51536B;
    private static final int ACCENT = 0x564583;
    private static final int UI_WIDTH = 176;
    private static final int UI_HEIGHT = 222;
    private static final int SETTING_COUNT = Setting.values().length;
    private static final int OPEN_BANDS = 100;
    private static final int CLEAR_BAND = OPEN_BANDS + 1;
    private static final int OPEN_CONNECTIONS = OPEN_BANDS + 2;
    private static final int BACK = OPEN_BANDS + 3;
    private static final int PREVIOUS = OPEN_BANDS + 4;
    private static final int NEXT = OPEN_BANDS + 5;
    private static final int FIRST_ROW = OPEN_BANDS + 6;
    private static final int OVERLAY = FIRST_ROW + TransmitterPackets.ROWS;
    private static final int RESCAN = OVERLAY + 1;

    private enum Page { MAIN, BANDS, CONNECTIONS }

    private final TransmitterContainer menu;
    private final BlockPos origin;
    private PanelButton overlay;
    private boolean overlayPressed;
    private BlockPos lastCoordinate;
    private long lastCoordinateClick;
    private int requestedPage = -1;
    private final Map<Setting, PanelButton> settings = new EnumMap<>(Setting.class);
    private Page page = Page.MAIN;
    private Page pendingPage;
    private PanelButton clearBand;
    private PanelButton rescan;
    private PanelButton connections;
    private PanelButton previous;
    private PanelButton next;
    private final PanelButton[] rows = new PanelButton[TransmitterPackets.ROWS];

    TransmitterScreen(TransmitterContainer menu, BlockPos origin) {
        super(menu);
        this.menu = menu;
        this.origin = origin;
        xSize = UI_WIDTH;
        ySize = UI_HEIGHT;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        settings.clear();
        overlay = addControl(OVERLAY, -19, 22, 18, 18, "");
        if (page != Page.MAIN) {
            previous = addControl(PREVIOUS, 9, 110, 16, 15, "<");
            next = addControl(NEXT, 65, 110, 16, 15, ">");
            addControl(BACK, 113, 110, 51, 15, I18n.format(KEY + "back"));
            for (int i = 0; i < rows.length; i++) {
                int y = 43 + i * 15;
                rows[i] = page == Page.BANDS
                        ? addControl(FIRST_ROW + i, 10, y, 156, 14, "")
                        : addControl(FIRST_ROW + i, 116, y, 50, 14, I18n.format(KEY + "disconnect"));
            }
            return;
        }

        addControl(OPEN_BANDS, 135, 23, 13, 13, "\u25BC");
        clearBand = addControl(CLEAR_BAND, 154, 22, 15, 16, "\u00D7");
        addSetting(Setting.CHANNELS_MINUS_TEN, 50, 75, 22, 15, "-10");
        addSetting(Setting.CHANNELS_MINUS_ONE, 74, 75, 18, 15, "-1");
        addSetting(Setting.CHANNELS_PLUS_ONE, 121, 75, 20, 15, "+1");
        addSetting(Setting.CHANNELS_PLUS_TEN, 143, 75, 26, 15, "+10");
        addSetting(Setting.RANGE_MINUS, 46, 97, 13, 14, "-");
        addSetting(Setting.RANGE_PLUS, 86, 97, 13, 14, "+");
        addSetting(Setting.AUTOMATIC, 107, 99, 28, 12, I18n.format(KEY + "automatic"));
        addSetting(Setting.MANUAL, 135, 99, 32, 12, I18n.format(KEY + "manual"));
        rescan = addControl(RESCAN, 106, 115, 30, 13, I18n.format(KEY + "rescan_short"));
        connections = addControl(OPEN_CONNECTIONS, 138, 115, 31, 13, I18n.format(KEY + "connections_short"));
    }

    private void addSetting(Setting setting, int x, int y, int width, int height, String label) {
        settings.put(setting, addControl(setting.ordinal(), x, y, width, height, label));
    }

    private PanelButton addControl(int id, int x, int y, int width, int height, String label) {
        PanelButton button = new PanelButton(id, guiLeft + x, guiTop + y, width, height, label);
        buttonList.add(button);
        return button;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (page == Page.MAIN) {
            int channels = menu.getRequestedChannels();
            NBTTagCompound data = menu.getSnapshot();
            boolean editable = data.getBoolean("Editable");
            boolean band = data.hasUniqueId("Band");
            clearBand.enabled = editable && band;
            rescan.enabled = editable;
            boolean canSubtract = editable && channels > 0;
            boolean canAdd = editable && channels < Math.min(data.getInteger("Capacity"), WirelessTransmitterTile.MAX_CHANNELS);
            settings.get(Setting.CHANNELS_MINUS_TEN).enabled = canSubtract;
            settings.get(Setting.CHANNELS_MINUS_ONE).enabled = canSubtract;
            settings.get(Setting.CHANNELS_PLUS_ONE).enabled = canAdd;
            settings.get(Setting.CHANNELS_PLUS_TEN).enabled = canAdd;
            settings.get(Setting.RANGE_MINUS).enabled = editable && menu.getRadius() > WirelessTransmitterTile.MIN_RADIUS;
            settings.get(Setting.RANGE_PLUS).enabled = editable && menu.getRadius() < menu.getMaxRadius();
            settings.get(Setting.AUTOMATIC).enabled = editable;
            settings.get(Setting.MANUAL).enabled = editable;
            settings.get(Setting.AUTOMATIC).selected = menu.isAutomatic();
            settings.get(Setting.MANUAL).selected = !menu.isAutomatic();
        } else {
            NBTTagCompound data = menu.getSnapshot();
            boolean matching = data.getInteger("Kind") == page.ordinal();
            NBTTagList entries = getRows();
            previous.enabled = matching && data.getInteger("Page") > 0;
            next.enabled = matching && data.getInteger("Page") + 1 < data.getInteger("Pages");
            for (int i = 0; i < rows.length; i++) {
                PanelButton button = rows[i];
                button.visible = i < entries.tagCount();
                button.enabled = data.getBoolean("Editable");
                if (button.visible && page == Page.BANDS) {
                    NBTTagCompound entry = entries.getCompoundTagAt(i);
                    button.selected = data.hasUniqueId("Band")
                            && data.getUniqueId("Band").equals(entry.getUniqueId("Band"));
                    button.displayString = fontRenderer.trimStringToWidth(entry.getString("Name"), 148);
                }
            }
        }

        overlay.selected = TransmitterOverlay.isEnabled(origin);
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
        drawOverlayIcon();
        if (overlay.mousePressed(mc, mouseX, mouseY)) {
            drawHoveringText(I18n.format(KEY + (overlay.selected ? "hide_overlay" : "show_overlay")),
                    mouseX, mouseY);
        }
        if (page == Page.MAIN && !overlay.mousePressed(mc, mouseX, mouseY)) {
            drawMainTooltips(mouseX, mouseY);
        }
    }

    private void drawMainTooltips(int mouseX, int mouseY) {
        if (rescan.isMouseOver()) {
            drawHoveringText(I18n.format(KEY + (menu.isAutomatic() ? "rescan" : "retry")), mouseX, mouseY);
        } else if (connections.isMouseOver()) {
            drawHoveringText(I18n.format(KEY + "connections"), mouseX, mouseY);
        }
    }

    private void drawOverlayIcon() {
        int x = overlay.x + 4;
        int y = overlay.y + 4;
        int frame = overlay.selected ? 0xFF66548F : 0xFF555769;
        int point = overlay.selected ? 0xFF387D73 : frame;
        drawRect(x, y, x + 4, y + 1, frame);
        drawRect(x, y, x + 1, y + 4, frame);
        drawRect(x + 6, y, x + 10, y + 1, frame);
        drawRect(x + 9, y, x + 10, y + 4, frame);
        drawRect(x, y + 6, x + 1, y + 10, frame);
        drawRect(x, y + 9, x + 4, y + 10, frame);
        drawRect(x + 9, y + 6, x + 10, y + 10, frame);
        drawRect(x + 6, y + 9, x + 10, y + 10, frame);
        drawRect(x + 4, y + 4, x + 6, y + 6, point);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case OPEN_BANDS:
                pendingPage = Page.BANDS;
                break;
            case OPEN_CONNECTIONS:
                pendingPage = Page.CONNECTIONS;
                break;
            case RESCAN:
                TransmitterPackets.rescan(menu.windowId);
                break;
            case CLEAR_BAND:
                TransmitterPackets.selectBand(menu.windowId, null);
                break;
            case PREVIOUS:
            case NEXT:
                turnPage(button.id == NEXT ? 1 : -1);
                break;
            case BACK:
                pendingPage = Page.MAIN;
                break;
            default:
                if (button.id >= 0 && button.id < SETTING_COUNT) {
                    mc.playerController.sendEnchantPacket(menu.windowId, button.id);
                } else if (button.id >= FIRST_ROW && button.id < FIRST_ROW + TransmitterPackets.ROWS) {
                    int index = button.id - FIRST_ROW;
                    NBTTagList entries = getRows();
                    if (index >= entries.tagCount()) {
                        break;
                    }
                    NBTTagCompound row = entries.getCompoundTagAt(index);
                    if (page == Page.BANDS) {
                        TransmitterPackets.selectBand(menu.windowId, row.getUniqueId("Band"));
                        pendingPage = Page.MAIN;
                    } else if (page == Page.CONNECTIONS) {
                        TransmitterPackets.disconnect(menu.windowId, BlockPos.fromLong(row.getLong("Target")));
                    }
                }
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE && page != Page.MAIN) {
            showPage(Page.MAIN);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (overlay.mousePressed(mc, mouseX, mouseY)) {
            if (mouseButton == 0) {
                overlay.playPressSound(mc.getSoundHandler());
                TransmitterOverlay.toggle(origin);
            }
            overlayPressed = true;
            return;
        }
        if (page == Page.MAIN && mouseButton == 0
                && mouseX >= guiLeft + 37 && mouseX < guiLeft + 135
                && mouseY >= guiTop + 22 && mouseY < guiTop + 38) {
            pendingPage = Page.BANDS;
        }
        if (mouseButton == 0 && page == Page.CONNECTIONS
                && highlightCoordinate(mouseX - guiLeft, mouseY - guiTop)) {
            mc.player.closeScreen();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (pendingPage != null) {
            Page target = pendingPage;
            pendingPage = null;
            showPage(target);
        }
    }

    private boolean highlightCoordinate(int x, int y) {
        NBTTagList entries = getRows();
        if (x < 10 || x >= 115 || y < 43 || (y - 43) / 15 >= entries.tagCount()) {
            lastCoordinate = null;
            return false;
        }

        BlockPos target = BlockPos.fromLong(entries.getCompoundTagAt((y - 43) / 15).getLong("Target"));
        long now = System.nanoTime();
        if (target.equals(lastCoordinate) && now - lastCoordinateClick <= 400_000_000L) {
            TransmitterOverlay.highlight(target);
            lastCoordinate = null;
            return true;
        }

        lastCoordinate = target;
        lastCoordinateClick = now;
        return false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (page != Page.CONNECTIONS || wheel == 0) {
            return;
        }
        int x = Mouse.getEventX() * width / mc.displayWidth - guiLeft;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1 - guiTop;
        if (x >= 7 && x < 169 && y >= 40 && y < 105) {
            turnPage(wheel > 0 ? -1 : 1);
        }
    }

    private void turnPage(int direction) {
        NBTTagCompound data = menu.getSnapshot();
        if (data.getInteger("Kind") != page.ordinal()) {
            return;
        }
        int last = Math.max(0, data.getInteger("Pages") - 1);
        int current = requestedPage < 0 ? data.getInteger("Page") : Math.min(requestedPage, last);
        int nextPage = Math.max(0, Math.min(last, current + direction));
        if (nextPage != current) {
            requestedPage = nextPage;
            TransmitterPackets.view(menu.windowId, page.ordinal(), nextPage);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (overlayPressed) {
            overlayPressed = false;
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void showPage(Page page) {
        this.page = page;
        requestedPage = -1;
        lastCoordinate = null;
        TransmitterPackets.view(menu.windowId, page.ordinal(), 0);
        initGui();
    }

    private NBTTagList getRows() {
        NBTTagCompound data = menu.getSnapshot();
        return data.getInteger("Kind") == page.ordinal() ? data.getTagList("Rows", 10) : new NBTTagList();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(BACKGROUND);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

        if (page != Page.MAIN) {
            drawRect(guiLeft + 6, guiTop + 20, guiLeft + 171, guiTop + 132, PANEL_COLOR);
            drawRect(guiLeft + 7, guiTop + 40, guiLeft + 169, guiTop + 105, 0xFF84869B);
            drawRect(guiLeft + 8, guiTop + 41, guiLeft + 168, guiTop + 104, 0xFFDBDCE6);
            return;
        }

        drawRect(guiLeft + 7, guiTop + 97, guiLeft + 86, guiTop + 131, PANEL_COLOR);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("tile.ae2_wireless_extensions.wireless_signal_generator.name"), 8, 7, TEXT);
        fontRenderer.drawString(I18n.format("container.inventory"), 8, 133, TEXT);
        NBTTagCompound data = menu.getSnapshot();
        boolean online = data.getBoolean("Online");
        String status = I18n.format(KEY + (online ? "online" : "offline"));
        int statusX = 168 - fontRenderer.getStringWidth(status);
        drawRect(statusX - 9, 8, statusX - 4, 13, online ? 0xFF63A996 : 0xFF9396AA);
        fontRenderer.drawString(status, statusX, 7, SECONDARY);

        if (page != Page.MAIN) {
            label(page == Page.BANDS ? "bands" : "connections", 8, 26);
            NBTTagList entries = getRows();
            if (entries.tagCount() == 0 && data.getInteger("Kind") == page.ordinal()) {
                String empty = page == Page.CONNECTIONS ? "no_connections"
                        : (data.getBoolean("YmSupported") ? "no_bands" : "ym_unavailable");
                centered(I18n.format(KEY + empty),
                        8, 69, 160, SECONDARY);
            }
            if (page == Page.CONNECTIONS) {
                for (int i = 0; i < entries.tagCount(); i++) {
                    NBTTagCompound entry = entries.getCompoundTagAt(i);
                    BlockPos target = BlockPos.fromLong(entry.getLong("Target"));
                    int y = 46 + i * 15;
                    String coords = target.getX() + ", " + target.getY() + ", " + target.getZ();
                    fontRenderer.drawString(fontRenderer.trimStringToWidth(coords, 102), 12, y, TEXT);
                }
            }
            if (data.getInteger("Kind") == page.ordinal()) {
                String pages = (data.getInteger("Page") + 1) + "/" + data.getInteger("Pages");
                centered(pages, 26, 114, 38, SECONDARY);
            }
            return;
        }

        label("band", 8, 26);
        boolean hasBand = data.hasUniqueId("Band");
        String input = hasBand ? data.getString("BandName") : I18n.format(KEY + "wired_input");
        fontRenderer.drawString(fontRenderer.trimStringToWidth(input, 87), 45, 26, ACCENT);
        int total = data.getInteger("Capacity");
        int used = data.getInteger("Used");
        int remaining = data.getInteger("Remaining");
        centered(I18n.format(KEY + "channel_total"), 8, 47, 53, SECONDARY);
        centered(I18n.format(KEY + "used"), 62, 47, 53, ACCENT);
        centered(I18n.format(KEY + "remaining"), 116, 47, 52, SECONDARY);
        centered(capacityLabel(total), 8, 58, 53, TEXT);
        centered(capacityLabel(used), 62, 58, 53, ACCENT);
        centered(capacityLabel(remaining), 116, 58, 52, TEXT);

        label("requested", 8, 79);
        centered(Integer.toString(menu.getRequestedChannels()), 94, 79, 25, ACCENT);
        label("range", 8, 100);
        int radius = menu.getRadius();
        centered(Integer.toString(radius), 60, 100, 25, TEXT);
    }

    private static String capacityLabel(int count) {
        return count == Integer.MAX_VALUE ? "\u221E" : Integer.toString(count);
    }

    private void label(String key, int x, int y) {
        fontRenderer.drawString(I18n.format(KEY + key), x, y, SECONDARY);
    }

    private void centered(String text, int x, int y, int width, int color) {
        fontRenderer.drawString(text, x + (width - fontRenderer.getStringWidth(text)) / 2, y, color);
    }

    private static final class PanelButton extends GuiButton {

        private boolean selected;

        PanelButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) {
                return;
            }

            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            int face = 0xFFD5D6E1;
            if (selected) {
                face = 0xFFE7E0F1;
            } else if (hovered && enabled) {
                face = 0xFFE5E1F1;
            }
            drawRect(x, y, x + width, y + height, 0xFF77798E);
            drawRect(x + 1, y + 1, x + width - 1, y + height - 1, face);
            drawRect(x + 1, y + 1, x + width - 1, y + 2, 0xFFF4F5FA);
            drawRect(x + 1, y + 1, x + 2, y + height - 1, 0xFFE7E8F0);
            drawRect(x + 1, y + height - 2, x + width - 1, y + height - 1,
                    selected ? 0xFF9279B6 : 0xFFA4A6BA);
            int textColor = enabled ? (selected ? ACCENT : TEXT) : 0x898BA1;
            int textX = x + (width - mc.fontRenderer.getStringWidth(displayString)) / 2;
            mc.fontRenderer.drawString(displayString, textX, y + (height - 8) / 2 + 1, textColor);
        }
    }
}
