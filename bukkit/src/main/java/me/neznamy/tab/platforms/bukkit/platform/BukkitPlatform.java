package me.neznamy.tab.platforms.bukkit.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import me.clip.placeholderapi.PlaceholderAPI;
import me.neznamy.tab.platforms.bukkit.*;
import me.neznamy.tab.shared.GroupManager;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.chat.IChatBaseComponent;
import me.neznamy.tab.shared.chat.rgb.RGBUtils;
import me.neznamy.tab.shared.features.injection.PipelineInjector;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.platforms.bukkit.features.BukkitTabExpansion;
import me.neznamy.tab.platforms.bukkit.features.PerWorldPlayerList;
import me.neznamy.tab.platforms.bukkit.features.WitherBossBar;
import me.neznamy.tab.platforms.bukkit.features.BukkitNameTagX;
import me.neznamy.tab.platforms.bukkit.nms.NMSStorage;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.backend.BackendPlatform;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.bossbar.BossBarManagerImpl;
import me.neznamy.tab.shared.features.nametags.NameTag;
import me.neznamy.tab.shared.hook.LuckPermsHook;
import me.neznamy.tab.shared.placeholders.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.expansion.EmptyTabExpansion;
import me.neznamy.tab.shared.placeholders.expansion.TabExpansion;
import me.neznamy.tab.shared.util.ReflectionUtils;
import net.milkbowl.vault.permission.Permission;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Implementation of Platform interface for Bukkit platform
 */
@Getter
public class BukkitPlatform implements BackendPlatform {

    /** Plugin instance for registering tasks and events */
    @NotNull
    private final JavaPlugin plugin;

    /** Variables checking presence of other plugins to hook into */
    private final boolean placeholderAPI = ReflectionUtils.classExists("me.clip.placeholderapi.PlaceholderAPI");
    @Setter private boolean libsDisguisesEnabled = ReflectionUtils.classExists("me.libraryaddict.disguise.DisguiseAPI");

    /** NMS server to get TPS from on spigot */
    @Nullable private Object server;

    /** TPS field */
    @Nullable private Field spigotTps;

    /** Detection for presence of Paper's TPS getter */
    @Nullable private Method paperTps;

    /** Detection for presence of Paper's MSPT getter */
    private final boolean paperMspt = ReflectionUtils.methodExists(Bukkit.class, "getAverageTickTime");

    public BukkitPlatform(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        try {
            server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            spigotTps = server.getClass().getField("recentTps");
        } catch (ReflectiveOperationException e) {
            //not spigot
        }
        try { paperTps = Bukkit.class.getMethod("getTPS"); } catch (NoSuchMethodException ignored) {}
    }

    @Override
    @NotNull
    public BossBarManagerImpl getLegacyBossBar() {
        return new WitherBossBar(plugin);
    }

    @Override
    public void loadPlayers() {
        for (Player p : getOnlinePlayers()) {
            TAB.getInstance().addPlayer(new BukkitTabPlayer(p));
        }
    }

    @Override
    public void registerPlaceholders() {
        new BukkitPlaceholderRegistry(this).registerPlaceholders(TAB.getInstance().getPlaceholderManager());
    }

    @Override
    @Nullable
    public PipelineInjector createPipelineInjector() {
        return NMSStorage.getInstance().getMinorVersion() >= 8 ? new BukkitPipelineInjector() : null;
    }

    @Override
    @NotNull
    public NameTag getUnlimitedNameTags() {
        return new BukkitNameTagX(plugin);
    }

    @Override
    @NotNull
    public TabExpansion createTabExpansion() {
        if (placeholderAPI) {
            BukkitTabExpansion expansion = new BukkitTabExpansion();
            expansion.register();
            return expansion;
        }
        return new EmptyTabExpansion();
    }

    @Override
    @Nullable
    public TabFeature getPerWorldPlayerList() {
        return new PerWorldPlayerList(plugin);
    }

    /**
     * Returns online players from Bukkit API
     *
     * @return  online players from Bukkit API
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    @NotNull
    private Player[] getOnlinePlayers() {
        Object players = Bukkit.class.getMethod("getOnlinePlayers").invoke(null);
        if (players instanceof Player[]) {
            //1.7-
            return (Player[]) players;
        } else {
            //1.8+
            return ((Collection<Player>)players).toArray(new Player[0]);
        }
    }

    @Override
    public void registerUnknownPlaceholder(@NotNull String identifier) {
        PlaceholderManagerImpl pl = TAB.getInstance().getPlaceholderManager();
        int refresh = pl.getRefreshInterval(identifier);
        if (identifier.startsWith("%rel_")) {
            //relational placeholder
            TAB.getInstance().getPlaceholderManager().registerRelationalPlaceholder(identifier, pl.getRefreshInterval(identifier), (viewer, target) ->
                    placeholderAPI ? PlaceholderAPI.setRelationalPlaceholders((Player) viewer.getPlayer(), (Player) target.getPlayer(), identifier) : identifier);
        } else if (identifier.startsWith("%sync:")) {
            registerSyncPlaceholder(identifier, refresh);
        } else if (identifier.startsWith("%server_")) {
            TAB.getInstance().getPlaceholderManager().registerServerPlaceholder(identifier, refresh, () ->
                    placeholderAPI ? PlaceholderAPI.setPlaceholders(null, identifier) : identifier);
        } else {
            TAB.getInstance().getPlaceholderManager().registerPlayerPlaceholder(identifier, refresh, p ->
                    placeholderAPI ? PlaceholderAPI.setPlaceholders((Player) p.getPlayer(), identifier) : identifier);
        }
    }

    public void registerSyncPlaceholder(@NotNull String identifier, int refresh) {
        String syncedPlaceholder = "%" + identifier.substring(6);
        PlayerPlaceholderImpl[] ppl = new PlayerPlaceholderImpl[1];
        ppl[0] = TAB.getInstance().getPlaceholderManager().registerPlayerPlaceholder(identifier, refresh, p -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                long time = System.nanoTime();
                ppl[0].updateValue(p, placeholderAPI ? PlaceholderAPI.setPlaceholders((Player) p.getPlayer(), syncedPlaceholder) : identifier);
                TAB.getInstance().getCPUManager().addPlaceholderTime(identifier, System.nanoTime()-time);
            });
            return null;
        });
    }

    @Override
    public void logInfo(@NotNull IChatBaseComponent message) {
        Bukkit.getConsoleSender().sendMessage("[TAB] " + RGBUtils.getInstance().convertToBukkitFormat(message.toFlatText(),
                TAB.getInstance().getServerVersion().getMinorVersion() >= 16));
    }

    @Override
    public void logWarn(@NotNull IChatBaseComponent message) {
        Bukkit.getConsoleSender().sendMessage(EnumChatFormat.RED.getFormat() + "[TAB] [WARN] " + RGBUtils.getInstance().convertToBukkitFormat(message.toFlatText(),
                TAB.getInstance().getServerVersion().getMinorVersion() >= 16));
    }

    @Override
    @NotNull
    public String getServerVersionInfo() {
        return "[Bukkit] " + Bukkit.getName() + " - " + Bukkit.getBukkitVersion().split("-")[0] + " (" + NMSStorage.getInstance().getServerPackage() + ")";
    }

    @Override
    public void registerListener() {
        Bukkit.getPluginManager().registerEvents(new BukkitEventListener(), plugin);
    }

    @Override
    public void registerCommand() {
        PluginCommand command = Bukkit.getPluginCommand(TabConstants.COMMAND_BACKEND);
        if (command != null) {
            BukkitTabCommand cmd = new BukkitTabCommand();
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        } else {
            logWarn(new IChatBaseComponent("Failed to register command, is it defined in plugin.yml?"));
        }
    }

    @Override
    public void startMetrics() {
        Metrics metrics = new Metrics(plugin, 5304);
        metrics.addCustomChart(new SimplePie(TabConstants.MetricsChart.UNLIMITED_NAME_TAG_MODE_ENABLED,
                () -> TAB.getInstance().getFeatureManager().isFeatureEnabled(TabConstants.Feature.UNLIMITED_NAME_TAGS) ? "Yes" : "No"));
        metrics.addCustomChart(new SimplePie(TabConstants.MetricsChart.PERMISSION_SYSTEM,
                () -> TAB.getInstance().getGroupManager().getPermissionPlugin()));
        metrics.addCustomChart(new SimplePie(TabConstants.MetricsChart.SERVER_VERSION,
                () -> "1." + TAB.getInstance().getServerVersion().getMinorVersion() + ".x"));
    }

    @Override
    @NotNull
    public ProtocolVersion getServerVersion() {
        return ProtocolVersion.fromFriendlyName(Bukkit.getBukkitVersion().split("-")[0]);
    }

    @Override
    @NotNull
    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    @NotNull
    public GroupManager detectPermissionPlugin() {
        if (LuckPermsHook.getInstance().isInstalled()) {
            return new GroupManager("LuckPerms", LuckPermsHook.getInstance().getGroupFunction());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            RegisteredServiceProvider<Permission> provider = Bukkit.getServicesManager().getRegistration(Permission.class);
            if (provider != null && !provider.getProvider().getName().equals("SuperPerms")) {
                return new GroupManager(provider.getProvider().getName(), p -> provider.getProvider().getPrimaryGroup((Player) p.getPlayer()));
            }
        }
        return new GroupManager("None", p -> TabConstants.NO_GROUP);
    }

    @Override
    @SneakyThrows
    public double getTPS() {
        if (paperTps != null) {
            return Bukkit.getTPS()[0];
        } else if (spigotTps != null) {
            return ((double[]) spigotTps.get(server))[0];
        } else {
            return -1;
        }
    }

    @Override
    public double getMSPT() {
       if (paperMspt) return Bukkit.getAverageTickTime();
       return -1;
    }

    public void runEntityTask(@NotNull Entity entity, @NotNull Runnable task) {
        task.run();
    }
}