package com.vanillage.raytraceantixray;

import com.comphenix.protocol.ProtocolLibrary;
import com.destroystokyo.paper.antixray.ChunkPacketBlockController;
import com.google.common.collect.MapMaker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vanillage.raytraceantixray.antixray.ChunkPacketBlockControllerAntiXray;
import com.vanillage.raytraceantixray.commands.RayTraceAntiXrayTabExecutor;
import com.vanillage.raytraceantixray.data.ChunkBlocks;
import com.vanillage.raytraceantixray.data.PlayerData;
import com.vanillage.raytraceantixray.data.VectorialLocation;
import com.vanillage.raytraceantixray.listeners.PacketListener;
import com.vanillage.raytraceantixray.listeners.PlayerListener;
import com.vanillage.raytraceantixray.listeners.WorldListener;
import com.vanillage.raytraceantixray.tasks.RayTraceTimerTask;
import com.vanillage.raytraceantixray.tasks.UpdateBukkitRunnable;
import io.papermc.paper.chunk.system.RegionizedPlayerChunkLoader;
import io.papermc.paper.configuration.type.EngineMode;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_20_R2.CraftWorld;
import org.bukkit.craftbukkit.v1_20_R2.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_20_R2.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.Map;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class RayTraceAntiXray extends JavaPlugin {
    private volatile boolean running = false;
    private volatile boolean timingsEnabled = false;
    private final Map<ClientboundLevelChunkWithLightPacket, ChunkBlocks> packetChunkBlocksCache = new MapMaker().weakKeys().makeMap();
    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private ExecutorService executorService;
    private Timer timer;

    @Override
    public void onEnable() {
        if (!new File(getDataFolder(), "README.txt").exists()) {
            saveResource("README.txt", false);
        }

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        reloadConfig();

        for (World w : Bukkit.getWorlds()) {
            WorldListener.handleLoad(this, w);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerListener.handleJoin(this, p);
        }

        // saveConfig();
        // Initialize stuff.
        running = true;
        int threadCount = Math.max(getConfig().getInt("settings.anti-xray.ray-trace-threads"), 1);
        long interval = Math.max(getConfig().getLong("settings.anti-xray.ms-per-ray-trace-tick"), 1L);
        executorService = Executors.newFixedThreadPool(
                threadCount,
                new ThreadFactoryBuilder().setNameFormat("raytrace-anti-xray-worker-%d").build()
        );
        timer = new Timer("raytrace-anti-xray-timer", true);
        timer.scheduleAtFixedRate(new RayTraceTimerTask(this), 0L, interval);
        new UpdateBukkitRunnable(this).runTaskTimer(this, 0L, Math.max(getConfig().getLong("settings.anti-xray.update-ticks"), 1L));
        // Register events.
        getServer().getPluginManager().registerEvents(new WorldListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketListener(this));
        // registerCommands();
        getCommand("raytraceantixray").setExecutor(new RayTraceAntiXrayTabExecutor(this));
        getLogger().info(getDescription().getFullName() + " enabled");
    }

    @Override
    public void onDisable() {
        // unregisterCommands();
        // Cleanup stuff.
        ProtocolLibrary.getProtocolManager().removePacketListeners(this);
        HandlerList.unregisterAll(this);

        running = false;
        executorService.shutdownNow();

        try {
            executorService.awaitTermination(1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.WARNING, "Interrupted while shutting down", e);
        }

        for (World w : Bukkit.getWorlds()) {
            WorldListener.handleUnload(this, w);
        }

        packetChunkBlocksCache.clear();
        playerData.clear();
        getLogger().info(getDescription().getFullName() + " disabled");
    }

    public void reload() {
        onDisable();
        onEnable();
        getLogger().info(getDescription().getFullName() + " reloaded");
    }

    public void reloadChunks(Iterable<Player> players) {
        for (Player bp : players) {
            ServerPlayer p = ((CraftPlayer) bp).getHandle();
            RegionizedPlayerChunkLoader playerChunkManager = p.serverLevel().getChunkSource().chunkMap.level.playerChunkLoader;
            playerChunkManager.removePlayer(p);
            playerChunkManager.addPlayer(p);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isTimingsEnabled() {
        return timingsEnabled;
    }

    public void setTimingsEnabled(boolean timingsEnabled) {
        this.timingsEnabled = timingsEnabled;
    }

    public Map<ClientboundLevelChunkWithLightPacket, ChunkBlocks> getPacketChunkBlocksCache() {
        return packetChunkBlocksCache;
    }

    public Map<UUID, PlayerData> getPlayerData() {
        return playerData;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public boolean isEnabled(World world) {
        return ((CraftWorld) world).getHandle().paperConfig().anticheat.antiXray.enabled && ((CraftWorld) world).getHandle().paperConfig().anticheat.antiXray.engineMode == EngineMode.HIDE && getConfig().getBoolean("world-settings." + world.getName() + ".anti-xray.ray-trace", getConfig().getBoolean("world-settings.default.anti-xray.ray-trace"));
    }

    public VectorialLocation[] getLocations(Entity entity, VectorialLocation location) {
        ChunkPacketBlockController chunkPacketBlockController = ((CraftWorld) location.getWorld()).getHandle().chunkPacketBlockController;

        if (chunkPacketBlockController instanceof ChunkPacketBlockControllerAntiXray && ((ChunkPacketBlockControllerAntiXray) chunkPacketBlockController).rayTraceThirdPerson) {
            VectorialLocation thirdPersonBackLocation = new VectorialLocation(location.getWorld(), location.getVector().clone(), location.getDirection());
            VectorialLocation thirdPersonFrontLocation = new VectorialLocation(location);
            thirdPersonFrontLocation.getDirection().multiply(-1.);
            return new VectorialLocation[] { location, move(entity, thirdPersonBackLocation), move(entity, thirdPersonFrontLocation) };
        }

        return new VectorialLocation[] { location };
    }

    private VectorialLocation move(Entity entity, VectorialLocation location) {
        location.getVector().subtract(location.getDirection().clone().multiply(getMaxZoom(entity, location, 4.)));
        return location;
    }

    private double getMaxZoom(Entity entity, VectorialLocation location, double maxZoom) {
        Vector vector = location.getVector();
        Vector direction = location.getDirection();
        Vec3 position = new Vec3(vector.getX(), vector.getY(), vector.getZ());

        for (int i = 0; i < 8; i++) {
            float edgeX = (float) ((i & 1) * 2 - 1);
            float edgeY = (float) ((i >> 1 & 1) * 2 - 1);
            float edgeZ = (float) ((i >> 2 & 1) * 2 - 1);
            edgeX *= 0.1f;
            edgeY *= 0.1f;
            edgeZ *= 0.1f;
            Vec3 edge = position.add(edgeX, edgeY, edgeZ);
            Vec3 edgeMoved = new Vec3(position.x - direction.getX() * maxZoom + (double) edgeX, position.y - direction.getY() * maxZoom + (double) edgeY, position.z - direction.getZ() * maxZoom + (double) edgeZ);
            BlockHitResult result = ((CraftWorld) location.getWorld()).getHandle().clip(new ClipContext(edge, edgeMoved, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ((CraftEntity) entity).getHandle()));

            if (result.getType() != HitResult.Type.MISS) {
                double zoom = result.getLocation().distanceTo(position);

                if (zoom < maxZoom) {
                    maxZoom = zoom;
                }
            }
        }

        return maxZoom;
    }

    public static boolean hasController(World world) {
        return ((CraftWorld) world).getHandle().chunkPacketBlockController instanceof ChunkPacketBlockControllerAntiXray;
    }

}
