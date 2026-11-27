/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless.mixin;

import appeng.me.cache.PathGridCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PathGridCache.class, remap = false)
public interface PathGridCacheAccessor {

    @Accessor("updateNetwork")
    boolean ae2WirelessExtensions$isRepathPending();
}
