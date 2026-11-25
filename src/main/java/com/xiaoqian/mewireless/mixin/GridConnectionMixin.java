/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless.mixin;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.me.GridConnection;
import com.xiaoqian.mewireless.WirelessTransmitterTile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GridConnection.class, remap = false)
abstract class GridConnectionMixin {

    @Shadow
    private int lastUsedChannels;

    @Inject(method = "canSupportMoreChannels", at = @At("RETURN"), cancellable = true)
    private void meWireless$limitWirelessInput(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ()) {
            return;
        }
        IGridConnection connection = (IGridConnection) (Object) this;
        if (atLimit(connection.a(), connection) || atLimit(connection.b(), connection)) {
            result.setReturnValue(false);
        }
    }

    @Unique
    private boolean atLimit(IGridNode node, IGridConnection connection) {
        return node.getMachine() instanceof WirelessTransmitterTile
                && ((WirelessTransmitterTile) node.getMachine()).isAtWirelessChannelLimit(connection, lastUsedChannels);
    }
}
