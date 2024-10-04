package com.vanillage.raytraceantixray.util;

import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

public class BukkitUtil {

    public static final boolean IS_PAPER = isPaper();
    public static final boolean IS_FOLIA = isFolia();

    public static boolean isRunning() {
        return MinecraftServer.getServer().getTickCount() > 0;
    }

    public static boolean isReloading() {
        for (StackTraceElement e : MinecraftServer.getServer().serverThread.getStackTrace()) {
            if (e.getClassName().equals(CraftServer.class.getName()) && e.getMethodName().equals("reload")) {
                return true;
            }
        }
        return false;
    }

    public static String getMinecraftVersion() {
        return Bukkit.getBukkitVersion().split("-")[0];
    }

    private static boolean isPaper() {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        return cl.getResource("io/papermc/paper") != null || cl.getResource("io/papermc/paperclip") != null
                || cl.getResource("com/destroystokyo/paper") != null || cl.getResource("com/destroystokyo/paperclip") != null;
    }

    private static boolean isFolia() {
        return classForName(Bukkit.getServer().getClass().getClassLoader(), "io.papermc.paper.threadedregions.RegionizedServer") != null;
    }

    private static Class<?> classForName(ClassLoader ldr, String name) {
        try {
            return ldr.loadClass(name);
        } catch (Exception e) {
            return null;
        }
    }

}
