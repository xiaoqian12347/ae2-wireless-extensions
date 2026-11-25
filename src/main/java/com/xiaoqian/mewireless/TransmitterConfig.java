/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.io.File;

import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

final class TransmitterConfig {

    static final int DEFAULT_MAX_RADIUS = 15;
    static final int RADIUS_LIMIT = 32;
    private static final int DEFAULT_EDITS_PER_TICK = 64;
    private static final int DEFAULT_TARGETS_PER_GRID_BATCH = 32;

    private static int maxRadius = DEFAULT_MAX_RADIUS;
    private static int maxEditsPerTick = DEFAULT_EDITS_PER_TICK;
    private static int maxTargetsPerGridBatch = DEFAULT_TARGETS_PER_GRID_BATCH;

    private TransmitterConfig() {
    }

    static void load(File file) {
        Configuration config = new Configuration(file);
        maxRadius = readBoundedInt(config, "range", "maxRadius", DEFAULT_MAX_RADIUS, WirelessTransmitterTile.MIN_RADIUS,
                RADIUS_LIMIT, "无线信号发生器允许设置的最大半径，按方块计算。");
        maxEditsPerTick = readBoundedInt(config, "performance", "maxEditsPerTick", DEFAULT_EDITS_PER_TICK, 1, 128,
                "全服每个服务器 tick 的无线建连和主动缩容预算。快恢复的内部桥接也占用预算。");
        maxTargetsPerGridBatch = readBoundedInt(config, "performance", "maxTargetsPerGridBatch", DEFAULT_TARGETS_PER_GRID_BATCH, 1, 64,
                "同一张 AE 网格每批最多接入的设备数。设为 1 时逐台接入。");
        if (config.hasChanged()) {
            config.save();
        }
    }

    static int maxRadius() {
        return maxRadius;
    }

    static int maxEditsPerTick() {
        return maxEditsPerTick;
    }

    static int maxTargetsPerGridBatch() {
        return maxTargetsPerGridBatch;
    }

    private static int readBoundedInt(Configuration config, String category, String key, int fallback, int min, int max, String comment) {
        Property property = config.get(category, key, fallback, comment + " 范围 " + min + " 到 " + max + "。", min, max);
        int configured = property.getInt(fallback);
        int value = MathHelper.clamp(configured, min, max);
        if (!property.isIntValue() || value != configured) {
            property.set(value);
        }
        return value;
    }
}
