/*
 * Copyright (c) 2026 xiaoqian
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.xiaoqian.mewireless;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Loader;

final class YmBands {

    private static final String PACKAGE = "com.xinyihl.ymadditions.";
    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];
    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<Map<String, Method>>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> methods = new HashMap<>();
            for (Method method : type.getMethods()) {
                methods.put(method.getName() + Arrays.toString(method.getParameterTypes()), method);
            }
            return methods;
        }
    };

    private YmBands() {
    }

    static final class Band {
        final UUID id;
        final String name;
        final Object network;
        final Object storage;

        Band(UUID id, String name, Object network, Object storage) {
            this.id = id;
            this.name = name;
            this.network = network;
            this.storage = storage;
        }

        @Nullable
        IGridNode sender() {
            TileEntity tile = senderTile();
            return tile == null ? null : ((IActionHost) tile).getActionableNode();
        }

        @Nullable
        private TileEntity senderTile() {
            Object endpoint = call(network, "getSendPos");
            if (endpoint == null) {
                return null;
            }
            World world = DimensionManager.getWorld((Integer) call(endpoint, "getDimension"));
            BlockPos pos = (BlockPos) call(endpoint, "toBlockPos");
            if (world == null || !world.isBlockLoaded(pos)) {
                return null;
            }

            TileEntity tile = world.getTileEntity(pos);
            if (!(tile instanceof IActionHost) || tile.isInvalid()
                    || !tile.getClass().getName().equals(PACKAGE + "common.title.TileNetworkHub")
                    || !id.equals(call(tile, "getNetworkUuid"))
                    || !Boolean.TRUE.equals(call(tile, "isHead"))) {
                return null;
            }
            return tile;
        }

        Receiver registerReceiver(World world, BlockPos pos) {
            Constructor<?> constructor = Binding.INSTANCE.endpoint;
            Object endpoint;
            try {
                endpoint = constructor.newInstance(pos.toImmutable(), world.provider.getDimension());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("YM Additions receiver address creation failed", e);
            }
            call(network, "addReceivePos", new Class<?>[] {constructor.getDeclaringClass()}, endpoint);
            updateSenderStatus();
            return new Receiver(this, endpoint);
        }

        private void updateSenderStatus() {
            TileEntity sender = senderTile();
            if (sender == null) {
                return;
            }
            boolean connected = !((Set<?>) call(network, "getReceivePos")).isEmpty();
            if (connected != Boolean.TRUE.equals(call(sender, "isConnected"))) {
                call(sender, "setConnected", new Class<?>[] {boolean.class}, connected);
                call(sender, "sync");
            }
        }

        boolean permits(EntityPlayer player) {
            Object member = Binding.INSTANCE.memberPermission;
            return Boolean.TRUE.equals(call(network, "hasPermission",
                    new Class<?>[] {EntityPlayer.class, member.getClass()}, player, member));
        }

        boolean permits(UUID player, World world) {
            if (player == null) {
                return false;
            }
            EntityPlayer online = world.getMinecraftServer().getPlayerList().getPlayerByUUID(player);
            if (online != null) {
                return permits(online);
            }

            UUID groupId = (UUID) call(network, "getOwner");
            Object group = call(storage, "getGroup", new Class<?>[] {UUID.class}, groupId);
            if (group == null) {
                return false;
            }
            if (Boolean.TRUE.equals(call(network, "isOvert"))) {
                return true;
            }
            if (hasMembership(call(group, "getOwner"), player)) {
                return true;
            }
            for (Object user : (List<?>) call(group, "getUsers")) {
                if (hasMembership(user, player)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasMembership(@Nullable Object user, UUID player) {
            if (user == null || !player.equals(call(user, "getUuid"))) {
                return false;
            }
            Enum<?> rank = (Enum<?>) call(user, "getPerm");
            return rank != null && rank.ordinal() >= Binding.INSTANCE.memberPermission.ordinal();
        }
    }

    static final class Receiver {
        private final Band band;
        private final Object endpoint;

        Receiver(Band band, Object endpoint) {
            this.band = band;
            this.endpoint = endpoint;
        }

        boolean isRegisteredWith(Band current) {
            return band.network == current.network;
        }

        void unregister() {
            call(band.network, "removeReceivePos", new Class<?>[] {endpoint.getClass()}, endpoint);
            band.updateSenderStatus();
        }
    }

    static boolean isAvailable() {
        return Binding.INSTANCE != null;
    }

    static List<Band> visibleTo(EntityPlayer player) {
        Object storage = storage(player.world);
        if (storage == null) {
            return Collections.emptyList();
        }

        List<Band> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) call(storage, "getNetworks")).entrySet()) {
            Band band = band((UUID) entry.getKey(), entry.getValue(), storage);
            if (band.permits(player)) {
                result.add(band);
            }
        }
        result.sort(Comparator.comparing((Band band) -> band.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(band -> band.id));
        return result;
    }

    @Nullable
    static Band get(World world, UUID id) {
        Object storage = storage(world);
        if (storage == null) {
            return null;
        }
        Object network = call(storage, "getNetwork", new Class<?>[] {UUID.class}, id);
        return network == null ? null : band(id, network, storage);
    }

    static boolean isDeleted(World world, UUID id) {
        Object storage = storage(world);
        return storage != null && !((Map<?, ?>) call(storage, "getNetworks")).containsKey(id);
    }

    private static Band band(UUID id, Object network, Object storage) {
        return new Band(id, (String) call(network, "getName"), network, storage);
    }

    @Nullable
    private static Object storage(World world) {
        Binding binding = Binding.INSTANCE;
        if (binding == null || world.getMapStorage() == null) {
            return null;
        }
        return invoke(binding.storage, null, "get", new Class<?>[] {World.class}, world);
    }

    private static Object call(Object instance, String method) {
        return call(instance, method, NO_PARAMETERS);
    }

    private static Object call(Object instance, String method, Class<?>[] types, Object... arguments) {
        return invoke(instance.getClass(), instance, method, types, arguments);
    }

    private static Object invoke(Class<?> type, @Nullable Object instance, String method,
                                 Class<?>[] types, Object... arguments) {
        try {
            Method resolved = METHODS.get(type).get(method + Arrays.toString(types));
            if (resolved == null) {
                throw new NoSuchMethodException(method);
            }
            return resolved.invoke(instance, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("YM Additions API call failed: " + method, e);
        }
    }

    private static final class Binding {
        static final Binding INSTANCE = load();

        final Class<?> storage;
        final Enum<?> memberPermission;
        final Constructor<?> endpoint;

        Binding(Class<?> storage, Enum<?> memberPermission, Constructor<?> endpoint) {
            this.storage = storage;
            this.memberPermission = memberPermission;
            this.endpoint = endpoint;
        }

        @Nullable
        private static Binding load() {
            if (!Loader.isModLoaded("ymadditions")) {
                return null;
            }
            try {
                Class<?> storage = Class.forName(PACKAGE + "common.data.DataStorage");
                Class<?> sender = Class.forName(PACKAGE + "common.title.TileNetworkHub");
                Class<?> permissions = Class.forName(PACKAGE + "api.entity.User$Perm");
                Class<?> endpoint = Class.forName(PACKAGE + "common.utils.BlockPosDim");
                Class<?> network = Class.forName(PACKAGE + "api.entity.Network");
                Constructor<?> address = endpoint.getConstructor(BlockPos.class, int.class);
                network.getMethod("getReceivePos");
                network.getMethod("addReceivePos", endpoint);
                network.getMethod("removeReceivePos", endpoint);
                sender.getMethod("isConnected");
                sender.getMethod("setConnected", boolean.class);
                sender.getMethod("sync");
                if (!IActionHost.class.isAssignableFrom(sender)) {
                    return null;
                }
                for (Object rank : permissions.getEnumConstants()) {
                    if (((Enum<?>) rank).name().equals("USER")) {
                        return new Binding(storage, (Enum<?>) rank, address);
                    }
                }
                return null;
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }
    }
}
