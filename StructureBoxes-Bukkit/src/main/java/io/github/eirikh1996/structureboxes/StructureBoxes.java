package io.github.eirikh1996.structureboxes;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import io.github.eirikh1996.structureboxes.commands.StructureBoxCommand;
import io.github.eirikh1996.structureboxes.listener.BlockListener;
import io.github.eirikh1996.structureboxes.listener.InventoryListener;
import io.github.eirikh1996.structureboxes.localisation.I18nSupport;
import io.github.eirikh1996.structureboxes.settings.Settings;
import io.github.eirikh1996.structureboxes.utils.Location;
import io.github.eirikh1996.structureboxes.utils.MathUtils;
import io.github.eirikh1996.structureboxes.utils.UpdateChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

import static io.github.eirikh1996.structureboxes.utils.ChatUtils.COMMAND_PREFIX;

public class StructureBoxes extends JavaPlugin implements SBMain {
    private static StructureBoxes instance;
    private WorldGuardPlugin worldGuardPlugin;
    private WorldEditPlugin worldEditPlugin;
    private WorldEditHandler worldEditHandler;

    private boolean plotSquaredInstalled = false;
    private boolean factionsUUIDInstalled = false;
    private Plugin landsPlugin;
    private Metrics metrics;
    private boolean startup = true;

    private static Method GET_MATERIAL;

    static {
        try {
            GET_MATERIAL = Material.class.getDeclaredMethod("getMaterial", int.class);
        } catch (NoSuchMethodException e) {
            GET_MATERIAL = null;
        }
    }

    @Override
    public void onLoad() {
        instance = this;
        String packageName = getServer().getClass().getPackage().getName();
        String version = packageName.substring(packageName.lastIndexOf(".") + 1);
        Settings.IsLegacy = Integer.parseInt(version.split("_")[1]) <= 12;
        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        //Check for WorldGuard
        if (wg instanceof WorldGuardPlugin){
            worldGuardPlugin = (WorldGuardPlugin) wg;
        }
    }

    @Override
    public void onEnable() {
        final String[] LOCALES = {"en", "no", "it", "zhcn"};
        for (String locale : LOCALES){
            final File langFile = new File(getDataFolder().getAbsolutePath() + "/localisation/lang_" + locale + ".properties");
            if (langFile.exists()){
                continue;
            }
            saveResource("localisation/lang_" + locale + ".properties", false);

        }


        if (Settings.IsLegacy){
            saveLegacyConfig();
        } else {
            saveDefaultConfig();
        }
        readConfig();

        if (!I18nSupport.initialize(getDataFolder(), this)){
            return;
        }
        worldEditPlugin = (WorldEditPlugin) getServer().getPluginManager().getPlugin("WorldEdit");
        //This plugin requires WorldEdit in order to load. Therefore, assert that WorldEdit is not null when this enables
        assert worldEditPlugin != null;
        //Disable this plugin if WorldEdit is disabled
        if (!worldEditPlugin.isEnabled()){
            getLogger().severe(I18nSupport.getInternationalisedString("Startup - WorldEdit is disabled"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        //if on 1.13 and up, Check if FAWE is installed
        if (!Settings.IsLegacy) {
            try {
                Class.forName("com.boydti.fawe.bukkit.FaweBukkit");
                Settings.FAWE = true;
            } catch (ClassNotFoundException e) {
                Settings.FAWE = false;
            }
        }

        String weVersion = worldEditPlugin.getDescription().getVersion();

        int versionNumber = Settings.IsLegacy ? 6 : 7;
        final Map data;
        try {
            File weConfig = new File(getWorldEditPlugin().getDataFolder(), "config" + (Settings.FAWE ? "-legacy" : "") + ".yml");
            Yaml yaml = new Yaml();
            data = yaml.load(new FileInputStream(weConfig));
        } catch (IOException e){
            getLogger().severe(I18nSupport.getInternationalisedString("Startup - Error reading WE config"));
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        File schemDir = new File(worldEditPlugin.getDataFolder(), (String) ((Map) data.get("saving")).get("dir"));

        //Check if there is a compatible version of WorldEdit
        try {
            final Class weHandler = Class.forName("io.github.eirikh1996.structureboxes.compat.we" + versionNumber + ".IWorldEditHandler");
            if (WorldEditHandler.class.isAssignableFrom(weHandler)){
                worldEditHandler = (WorldEditHandler) weHandler.getConstructor(File.class, SBMain.class).newInstance(schemDir , this);
            }
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            getLogger().severe(I18nSupport.getInternationalisedString("Startup - Unsupported WorldEdit"));
            getLogger().severe(String.format(I18nSupport.getInternationalisedString("Startup - Requires WorldEdit 6.0.0 or 7.0.0"), weVersion));
            getLogger().severe(I18nSupport.getInternationalisedString("Startup - Will be disabled"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.getCommand("structurebox").setExecutor(new StructureBoxCommand());

        getServer().getPluginManager().registerEvents(new BlockListener(), this);
        getServer().getPluginManager().registerEvents(UpdateChecker.getInstance(), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(), this);
        StructureManager.getInstance().setSbMain(this);
        if (startup){
            getServer().getScheduler().runTaskTimerAsynchronously(this, StructureManager.getInstance(), 0, 20);
            UpdateChecker.getInstance().runTaskTimerAsynchronously(this, 120, 36000);
            startup = false;
        }

    }

    @Override
    public void onDisable(){
    }

    public static StructureBoxes getInstance(){
        return instance;
    }

    public WorldGuardPlugin getWorldGuardPlugin(){
        return worldGuardPlugin;
    }

    public WorldEditPlugin getWorldEditPlugin() {
        return worldEditPlugin;
    }


    public Plugin getLandsPlugin() {
        return landsPlugin;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    @Override
    public WorldEditHandler getWorldEditHandler() {
        return worldEditHandler;
    }

    @Override
    public boolean structureWithinRegion(UUID playerID, String schematicID, Collection<Location> locations) {
        boolean withinRegion = true;
        boolean exempt = false;
        for (String exception : Settings.RestrictToRegionsExceptions){
            if (!schematicID.contains(exception)){
                continue;
            }
            exempt = true;
        }
        final Player player = getServer().getPlayer(playerID);
        if (Settings.Debug){
            Bukkit.broadcastMessage("Within region: " + withinRegion);
            Bukkit.broadcastMessage("Exempt: " + exempt);
            Bukkit.broadcastMessage("Can bypass: " + player.hasPermission("structureboxes.bypassregionrestriction"));
            Bukkit.broadcastMessage("Entire structure enabled: " + Settings.RestrictToRegionsEntireStructure);
        }
        if (!withinRegion && !exempt && Settings.RestrictToRegionsEntireStructure && !player.hasPermission("structureboxes.bypassregionrestriction")){
            player.sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Place - Structure must be in region"));
            return false;
        }
        return true;
    }

    @Override
    public Platform getPlatform() {
        return Platform.BUKKIT;
    }

    @Override
    public void clearStructure(Structure structure) {
        final Player sender = Bukkit.getPlayer(structure.getOwner());
        Map<Location, Object> locationMaterialHashMap = structure.getOriginalBlocks();
        if (locationMaterialHashMap == null) {
            sender.sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Command - latest session expired"));
            return;
        }
        final Deque<Location> locations = structure.getLocationsToRemove();
        for (Location loc : locations) {
            org.bukkit.Location bukkitLoc = MathUtils.sb2BukkitLoc(loc);
            if (!bukkitLoc.getBlock().getType().name().endsWith("_DOOR"))
                continue;
        }
        new BukkitRunnable() {
            final int queueSize = locations.size();
            final int blocksToProcess = Math.min(queueSize, Settings.IncrementalPlacement ? Settings.IncrementalPlacementBlocksPerTick : 30000);
            int blocksProcessed = 0;
            @Override
            public void run() {

                for (int i = 1 ; i <= blocksToProcess ; i++) {
                    Location poll = locations.pollLast();
                    if (poll == null)
                        break;
                    final Material origType = (Material) locationMaterialHashMap.get(poll);
                    org.bukkit.Location bukkitLoc = MathUtils.sb2BukkitLoc(poll);
                    Block b = bukkitLoc.getBlock();
                    if (b.getState() instanceof InventoryHolder){
                        InventoryHolder holder = (InventoryHolder) b.getState();
                        holder.getInventory().clear();
                    }
                    b.setType(origType, false);
                    blocksProcessed++;
                }
                if (Settings.IncrementalPlacement && blocksToProcess % blocksProcessed == 10) {
                    float percent = (((float) blocksProcessed / (float) blocksToProcess) * 100f);
                    sender.sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Removal - Progress") + ": " + percent );
                }
                if (locations.isEmpty()){
                    StructureManager.getInstance().removeStructure(structure);
                    if (structure.isProcessing()) {
                        structure.setProcessing(false);
                    }
                    cancel();
                }
            }

        }.runTaskTimer(StructureBoxes.getInstance(), 0, Settings.IncrementalPlacement ? Settings.IncrementalPlacementDelay : 3);
    }

    @Override
    public boolean isFreeSpace(UUID playerID, String schematicName, Collection<Location> locations) {
        final HashMap<Location, Object> originalBlocks = new HashMap<>();
        @NotNull final Player p = getServer().getPlayer(playerID);
        assert p != null;
        for (Location location : locations){
            World world = getServer().getWorld(location.getWorld());
            org.bukkit.Location bukkitLoc = new org.bukkit.Location(world, location.getX(), location.getY(), location.getZ());
            if (Settings.Debug) {
                world.spawnParticle(Particle.VILLAGER_ANGRY, bukkitLoc, 1);
            }
            Material test = bukkitLoc.getBlock().getType();
            originalBlocks.put(location, test);
            if (test.name().endsWith("AIR") || Settings.blocksToIgnore.contains(test)){
                continue;
            }
            if (!Settings.CheckFreeSpace){
                continue;
            }
            p.sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Place - No free space") );
            return false;
        }
        StructureManager.getInstance().addStructureByPlayer(playerID, schematicName, originalBlocks);
        return true;
    }

    private void saveLegacyConfig(){

        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists())
            return;
        saveResource("config_legacy.yml", false);
        File legacyConfigFile = new File(getDataFolder(), "config_legacy.yml");
        legacyConfigFile.renameTo(configFile);
    }

    @Override
    public void sendMessageToPlayer(UUID recipient, String message) {
        Bukkit.getPlayer(recipient).sendMessage(I18nSupport.getInternationalisedString(message));
    }

    @Override
    public void logMessage(Level level, String message) {
        getLogger().log(level, message);
    }

    @Override
    public void clearInterior(Collection<Location> interior) {
        for (Location location : interior){
            org.bukkit.Location bukkitLoc = MathUtils.sb2BukkitLoc(location);
            //ignore air blocks
            if (bukkitLoc.getBlock().getType().name().endsWith("AIR")){
                continue;
            }
            bukkitLoc.getBlock().setType(Material.AIR, false);
        }
    }

    @Override
    public void scheduleSyncTask(final Runnable runnable) {
        getServer().getScheduler().runTask(this, runnable);
    }

    @Override
    public void scheduleSyncTaskLater(Runnable runnable, long delay) {
        long ticks = (delay / 1000) * 20;
        ticks = Math.max(ticks, 1);
        getServer().getScheduler().runTaskLater(this, runnable, ticks);
    }

    @Override
    public void scheduleAsyncTask(final Runnable runnable) {
        getServer().getScheduler().runTaskAsynchronously(this, runnable);
    }

    @Override
    public void broadcast(String s) {
        getServer().broadcastMessage(s);
    }

    public void readConfig() {
        reloadConfig();
        Settings.locale = getConfig().getString("Locale", "en");
        Settings.Metrics = getConfig().getBoolean("Metrics", true);
        Settings.PlaceCooldownTime = getConfig().getLong("Place Cooldown Time", 10);
        Settings.PluginPrefix = getConfig().getString("Plugin prefix", "§5[§6StructureBoxes§5]§r");
        Settings.StructureBoxItem = Material.getMaterial(getConfig().getString("Structure Box Item").toUpperCase());
        Settings.StructureBoxLore = getConfig().getString("Structure Box Display Name");
        Object object = getConfig().get("Structure Box Instruction Message");
        Settings.StructureBoxInstruction.clear();
        if (object instanceof String){
            Settings.StructureBoxInstruction.add((String) object);
        } else if (object instanceof List) {
            List list = (List) object;
            for (Object i : list) {
                if (i == null)
                    continue;
                Settings.StructureBoxInstruction.add((String) i);
            }
        }
        Settings.StructureBoxPrefix = getConfig().getString("Structure Box Prefix");
        Settings.AlternativePrefixes = getConfig().getStringList("Alternative Prefixes");
        Settings.RequirePermissionPerStructureBox = getConfig().getBoolean("Require permission per structure box", false);
        ConfigurationSection restrictToRegions = getConfig().getConfigurationSection("Restrict to regions");
        Settings.RestrictToRegionsEnabled = restrictToRegions.getBoolean("Enabled", false);
        Settings.RestrictToRegionsEntireStructure = restrictToRegions.getBoolean("Entire structure", false);
        Settings.RestrictToRegionsExceptions.clear();
        List<String> exceptions = restrictToRegions.getStringList("Exceptions");
        if (!exceptions.isEmpty()){
            Settings.RestrictToRegionsExceptions.addAll(exceptions);
        }
        Settings.MaxSessionTime = getConfig().getLong("Max Session Time", 60);
        Settings.MaxStructureSize = getConfig().getInt("Max Structure Size", 10000);
        Settings.Debug = getConfig().getBoolean("Debug", false);
        ConfigurationSection freeSpace = getConfig().getConfigurationSection("Free space");
        List materials = freeSpace.getList("Blocks to ignore");
        for (Object obj : materials) {
            Material type = null;
            if (obj == null){
                continue;
            }
            else if (obj instanceof Integer) {
                int id = (int) obj;
                if (GET_MATERIAL == null){
                    throw new IllegalArgumentException("Numerical block IDs are not supported by this server version: " + getServer().getVersion());
                }
                try {
                    type = (Material) GET_MATERIAL.invoke(Material.class, id);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            } else if (obj instanceof String){
                String str = (String) obj;
                type = Material.getMaterial(str.toUpperCase());
            }
            if (type == null){
                continue;
            }
            Settings.blocksToIgnore.add(type);
        }
        Settings.CheckFreeSpace = freeSpace.getBoolean("Require free space", true);
        final ConfigurationSection incrementalPlacement = getConfig().getConfigurationSection("Incremental placement");
        if (incrementalPlacement != null) {
            Settings.IncrementalPlacement = incrementalPlacement.getBoolean("Enabled", false);
            Settings.IncrementalPlacementBlocksPerTick = incrementalPlacement.getInt("Blocks per tick", 1);
            Settings.IncrementalPlacementDelay = incrementalPlacement.getInt("Delay", 1);
        }

    }

}
