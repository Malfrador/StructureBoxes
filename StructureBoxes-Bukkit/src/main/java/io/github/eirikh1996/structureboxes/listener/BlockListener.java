package io.github.eirikh1996.structureboxes.listener;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweAPI;
import com.boydti.fawe.object.RelightMode;
import com.boydti.fawe.util.EditSessionBuilder;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockType;
import io.github.eirikh1996.structureboxes.Direction;
import io.github.eirikh1996.structureboxes.Structure;
import io.github.eirikh1996.structureboxes.StructureBoxes;
import io.github.eirikh1996.structureboxes.StructureManager;
import io.github.eirikh1996.structureboxes.localisation.I18nSupport;
import io.github.eirikh1996.structureboxes.settings.Settings;
import io.github.eirikh1996.structureboxes.utils.IWorldEditLocation;
import io.github.eirikh1996.structureboxes.utils.ItemManager;
import io.github.eirikh1996.structureboxes.utils.MathUtils;
import jdk.nashorn.internal.ir.Block;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static io.github.eirikh1996.structureboxes.utils.ChatUtils.COMMAND_PREFIX;
import static java.lang.Math.PI;
import static org.bukkit.Bukkit.broadcastMessage;

public class BlockListener implements Listener {
    private final HashMap<UUID, Long> playerTimeMap = new HashMap<>();

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onBlockPlace(final BlockPlaceEvent event){
        if (event.isCancelled()) {
            return;
        }
        final UUID id = event.getPlayer().getUniqueId();
        if (!event.getBlockPlaced().getType().equals(Settings.StructureBoxItem) &&
        event.getItemInHand().getItemMeta() == null ||
        !event.getItemInHand().getItemMeta().hasLore()){
            return;
        }
        List<String> lore = event.getItemInHand().getItemMeta().getLore();
        assert lore != null;
        String schematicID = ChatColor.stripColor(lore.get(0));
        if (!schematicID.startsWith(ChatColor.stripColor(Settings.StructureBoxPrefix))){
            boolean hasAlternativePrefix = false;
            for (String prefix : Settings.AlternativePrefixes){
                if (!schematicID.startsWith(prefix)){
                    continue;
                }
                hasAlternativePrefix = true;
                schematicID = schematicID.replace(prefix, "");
                break;
            }
            if (!hasAlternativePrefix){
                return;
            }
        } else {
            schematicID = schematicID.replace(ChatColor.stripColor(Settings.StructureBoxPrefix), "");
        }
        int expiry = -1;
        for (String entry : lore) {
            if (!entry.startsWith("Expires after:"))
                continue;
            expiry = Integer.parseInt(entry.split(":")[1].replace(" ", ""));
            break;
        }
        if (Settings.RequirePermissionPerStructureBox && !event.getPlayer().hasPermission("structureboxes.place." + schematicID)){
            event.getPlayer().sendMessage(String.format(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Place - No permission for this ID"), schematicID));
            return;
        }
        if (playerTimeMap.containsKey(id) && playerTimeMap.get(id) != null && (System.currentTimeMillis() - playerTimeMap.get(id)) < Settings.PlaceCooldownTime){
            event.getPlayer().sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Place - Cooldown"));
            return;
        }
        Location locRaw = event.getBlockPlaced().getLocation();
        BlockVector3 location = BlockVector3.at(locRaw.getX(), locRaw.getY(), locRaw.getZ());
        File schemFile = new File(StructureBoxes.getInstance().getDataFolder() + "/schematics/" + schematicID + ".schem");
        Clipboard clipboard = null;
        try {
            ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
            ClipboardReader reader = format.getReader(new FileInputStream(schemFile));
            clipboard = reader.read();

        } catch (IOException e) {
            e.printStackTrace();
        }
        if (clipboard == null) {
            Bukkit.getLogger().info("No clipboard found");
            return;
        }
        Bukkit.getLogger().info("Loc: " + location.toString());
        final Location placed = event.getBlockPlaced().getLocation();
        EditSession session = new EditSessionBuilder(FaweAPI.getWorld(event.getBlockPlaced().getWorld().getName())).relightMode(RelightMode.ALL).build();
        Bukkit.getLogger().info("Session " + session.toString());
        ClipboardHolder holder = new ClipboardHolder(clipboard);
        Direction clipboardDir = StructureBoxes.getInstance().getWorldEditHandler().getClipboardFacingFromOrigin(clipboard, MathUtils.bukkit2SBLoc(placed));
        Direction playerDir = Direction.fromYaw(event.getPlayer().getLocation().getYaw());
        int angle = playerDir.getAngle(clipboardDir);
        holder.setTransform(new AffineTransform().rotateY(angle));
        Bukkit.getLogger().info("Angle: " + angle);

        final Collection<Location> structureLocs = new HashSet<>();

        int xLength = holder.getClipboard().getDimensions().getBlockX();
        int yLength = holder.getClipboard().getDimensions().getBlockY() + 1;
        int zLength = holder.getClipboard().getDimensions().getBlockZ();
        World world = event.getBlock().getWorld();
        for (int y = 0 ; y <= yLength ; y++){
            for (int x = 0 ; x <= xLength ; x++){
                for (int z = 0 ; z <= zLength ; z++){
                    Location loc = event.getBlock().getLocation().add(new Location(world, x,y,z)).clone();
                    Bukkit.getLogger().info(loc.toString());
                    structureLocs.add(loc);
                }
            }
        }
        for (Location location1 : structureLocs) {
            world.spawnParticle(Particle.VILLAGER_HAPPY, location1, 1);
        }
        Operation operation = holder.createPaste(session).to(location).ignoreAirBlocks(true).build();
        try {
            Bukkit.getLogger().info("Paste");
            Operations.complete(operation);
        } catch (WorldEditException e) {
            e.printStackTrace();
        }

        session.flushSession();
        ItemManager.getInstance().addItem(event.getPlayer().getUniqueId(), event.getItemInHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Set<Structure> sessions = StructureManager.getInstance().getStructures();
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        Iterator<Structure> iter = sessions.iterator();
        while (iter.hasNext()) {
            Structure next = iter.next();
            if (!next.getStructure().contains(MathUtils.bukkit2SBLoc(event.getBlock().getLocation()))){
                continue;
            }
            iter.remove();
            event.getPlayer().sendMessage(COMMAND_PREFIX + I18nSupport.getInternationalisedString("Session - Expired due to block broken"));
            break;
        }
    }

    private boolean isInteger(String str) {
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public @NotNull Direction getClipboardFacingFromOrigin(Clipboard clipboard, Location location) {
        BlockVector3 centerpoint = clipboard.getMinimumPoint().add(clipboard.getDimensions().divide(2));
        BlockVector3 distance = centerpoint.subtract(clipboard.getOrigin());
        if (Math.abs(distance.getBlockX()) > Math.abs(distance.getBlockZ())){
            if (distance.getBlockX() > 0){
                return Direction.EAST;
            } else {
                return Direction.WEST;
            }
        } else {
            if (distance.getBlockZ() > 0){
                return Direction.SOUTH;
            } else {
                return Direction.NORTH;
            }
        }
    }



}
