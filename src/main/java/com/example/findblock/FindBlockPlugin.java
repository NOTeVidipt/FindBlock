package com.example.findblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FindBlockPlugin extends JavaPlugin {
    private final Map<UUID, SearchJob> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, List<SavedResult>> history = new ConcurrentHashMap<>();
    private final Map<UUID, SavedResult> lastResults = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadHistory();
        getLogger().info("FindBlock 1.4.0 enabled.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            sendHelp(sender); return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) return adminCommand(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be used by a player (except /findblock admin reload)."); return true;
        }
        if (!player.hasPermission("findblock.use")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use FindBlock."); return true;
        }
        switch (sub) {
            case "resume" -> { return resumeWorldSearch(player); }
            case "cancel" -> { cancelPlayerSearch(player, true); return true; }
            case "history" -> { showHistory(player); return true; }
            case "tp", "teleport" -> { teleportToLast(player); return true; }
            case "world" -> {
                if (!player.hasPermission("findblock.world")) {
                    player.sendMessage(ChatColor.RED + "You need findblock.world permission for full-world searches."); return true;
                }
                if (args.length != 2) { player.sendMessage(ChatColor.YELLOW + "Usage: /findblock world <block[,block...]>"); return true; }
                return startWorldSearch(player, parseBlocks(player, args[1]));
            }
            default -> { }
        }
        if (args.length > 2) { sendHelp(player); return true; }
        List<Material> targets = parseBlocks(player, args[0]);
        if (targets == null) return true;
        int radius = getConfig().getInt("settings.default-radius", 128);
        if (args.length == 2) {
            try { radius = Integer.parseInt(args[1]); }
            catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Radius must be a whole number."); return true; }
        }
        int maxRadius = getConfig().getInt("settings.max-radius", 512);
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(ChatColor.RED + "Radius must be between 1 and " + maxRadius + " blocks."); return true;
        }
        if (!canStart(player)) return true;
        cancelPlayerSearch(player, false);
        Location origin = player.getLocation().getBlock().getLocation();
        SearchJob job = new SearchJob(player, player.getWorld(), targets, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), false);
        job.radius = radius; jobs.put(player.getUniqueId(), job);
        player.sendMessage(ChatColor.AQUA + "Searching for " + displayTargets(targets) + " within " + radius + " blocks...");
        World world = job.world;
        final int searchRadius = radius;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<ChunkPos> candidates = new ArrayList<>();
            int minCx = (job.originX - searchRadius) >> 4, maxCx = (job.originX + searchRadius) >> 4;
            int minCz = (job.originZ - searchRadius) >> 4, maxCz = (job.originZ + searchRadius) >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) for (int cz = minCz; cz <= maxCz; cz++)
                if (world.isChunkLoaded(cx, cz)) candidates.add(new ChunkPos(cx, cz));
            candidates.sort(Comparator.comparingLong(pos -> chunkDistanceSquared(pos, job.originX, job.originZ)));
            Bukkit.getScheduler().runTask(this, () -> {
                if (!isCurrent(job) || !player.isOnline()) return;
                job.chunks = candidates;
                player.sendMessage(ChatColor.GRAY + "Checking " + candidates.size() + " already-loaded chunks. Unloaded chunks are skipped.");
                processNextChunk(job);
            });
        });
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
            if (!(sender instanceof Player) || sender.hasPermission("findblock.admin")) {
                reloadConfig(); sender.sendMessage(ChatColor.GREEN + "FindBlock config reloaded.");
            } else sender.sendMessage(ChatColor.RED + "You need findblock.admin permission.");
            return true;
        }
        if (!(sender instanceof Player) || !sender.hasPermission("findblock.admin")) {
            sender.sendMessage(ChatColor.RED + "You need findblock.admin permission."); return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !jobs.containsKey(target.getUniqueId())) sender.sendMessage(ChatColor.YELLOW + "That player has no active search.");
            else { cancelPlayerSearch(target, true); sender.sendMessage(ChatColor.GREEN + "Cancelled search for " + target.getName() + "."); }
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Admin: /findblock admin reload | /findblock admin cancel <player>"); return true;
    }

    private boolean canStart(Player player) {
        int max = Math.max(1, getConfig().getInt("settings.max-active-searches", 2));
        if (!jobs.containsKey(player.getUniqueId()) && jobs.size() >= max) {
            player.sendMessage(ChatColor.RED + "FindBlock is busy with " + jobs.size() + " search(es). Try again later."); return false;
        }
        return true;
    }

    private List<Material> parseBlocks(Player player, String input) {
        Set<Material> targets = new LinkedHashSet<>();
        for (String part : input.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                player.sendMessage(ChatColor.RED + "Unknown block: " + part.trim());
                player.sendMessage(ChatColor.GRAY + "Use names like diamond_ore, chest, stripped_cherry_log. Multiple blocks: chest,barrel."); return null;
            }
            targets.add(material);
        }
        if (targets.isEmpty()) { player.sendMessage(ChatColor.RED + "Specify at least one block."); return null; }
        return List.copyOf(targets);
    }

    private boolean startWorldSearch(Player player, List<Material> targets) {
        if (targets == null) return true;
        if (!canStart(player)) return true;
        cancelPlayerSearch(player, false);
        Location origin = player.getLocation().getBlock().getLocation();
        SearchJob job = new SearchJob(player, player.getWorld(), targets, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), true);
        jobs.put(player.getUniqueId(), job);
        player.sendMessage(ChatColor.AQUA + "Starting resumable full-world search for " + displayTargets(targets) + " in " + job.world.getName() + ".");
        player.sendMessage(ChatColor.GRAY + "Existing saved chunks only; no chunks are generated. Use /findblock cancel to stop.");
        buildWorldChunkList(job, false);
        return true;
    }

    private void buildWorldChunkList(SearchJob job, boolean resuming) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<ChunkPos> chunks = listExistingChunks(job.world.getWorldFolder().toPath().resolve("region"));
                chunks.sort(Comparator.comparingLong((ChunkPos pos) -> chunkDistanceSquared(pos, job.originX, job.originZ))
                        .thenComparingInt(pos -> pos.x).thenComparingInt(pos -> pos.z));
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isCurrent(job) || !job.player.isOnline()) return;
                    job.chunks = chunks;
                    if (job.index > chunks.size()) job.index = chunks.size();
                    saveCheckpoint(job);
                    job.player.sendMessage(ChatColor.GREEN + (resuming ? "Resuming" : "Found") + " " + chunks.size() + " saved chunks; starting at " + job.index + ".");
                    processNextChunk(job);
                });
            } catch (Exception ex) {
                getLogger().warning("Could not enumerate region files for " + job.world.getName() + ": " + ex);
                Bukkit.getScheduler().runTask(this, () -> finishError(job, "Couldn't read world region files; check console."));
            }
        });
    }

    private boolean resumeWorldSearch(Player player) {
        Path checkpoint = checkpointPath(player.getUniqueId());
        if (!Files.isRegularFile(checkpoint)) { player.sendMessage(ChatColor.YELLOW + "No saved full-world search found. Start one with /findblock world stripped_cherry_log"); return true; }
        if (!player.hasPermission("findblock.world")) { player.sendMessage(ChatColor.RED + "You need findblock.world permission to resume a full-world search."); return true; }
        try (InputStream in = Files.newInputStream(checkpoint)) {
            Properties props = new Properties(); props.load(in);
            World world = Bukkit.getWorld(props.getProperty("world", ""));
            if (world == null) { player.sendMessage(ChatColor.RED + "Saved search world isn't loaded: " + props.getProperty("world")); return true; }
            List<Material> targets = parseBlocks(player, props.getProperty("targets", props.getProperty("target", "")));
            if (targets == null) return true;
            cancelPlayerSearch(player, false);
            SearchJob job = new SearchJob(player, world, targets, Integer.parseInt(props.getProperty("originX")), Integer.parseInt(props.getProperty("originY")), Integer.parseInt(props.getProperty("originZ")), true);
            job.index = Integer.parseInt(props.getProperty("index", "0"));
            job.bestDistanceSquared = Double.parseDouble(props.getProperty("bestDistanceSquared", "Infinity"));
            String nearestName = props.getProperty("nearestType", "");
            if (!nearestName.isBlank()) job.nearestType = Material.matchMaterial(nearestName);
            int nx = Integer.parseInt(props.getProperty("nearestX", "2147483647"));
            if (nx != Integer.MAX_VALUE) job.nearest = new Location(world, nx, Integer.parseInt(props.getProperty("nearestY", "0")), Integer.parseInt(props.getProperty("nearestZ", "0")));
            jobs.put(player.getUniqueId(), job);
            player.sendMessage(ChatColor.AQUA + "Rebuilding chunk list and resuming saved search...");
            buildWorldChunkList(job, true);
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Couldn't load saved progress. Check server console.");
            getLogger().warning("Couldn't load checkpoint for " + player.getUniqueId() + ": " + ex);
        }
        return true;
    }

    private void saveCheckpoint(SearchJob job) {
        if (!job.fullWorld) return;
        try {
            Files.createDirectories(getDataFolder().toPath());
            Properties p = new Properties(); p.setProperty("world", job.world.getName());
            p.setProperty("targets", String.join(",", job.targets.stream().map(Material::name).toList()));
            p.setProperty("originX", Integer.toString(job.originX)); p.setProperty("originY", Integer.toString(job.originY)); p.setProperty("originZ", Integer.toString(job.originZ));
            p.setProperty("index", Integer.toString(job.index)); p.setProperty("bestDistanceSquared", Double.toString(job.bestDistanceSquared));
            if (job.nearest != null) { p.setProperty("nearestX", Integer.toString(job.nearest.getBlockX())); p.setProperty("nearestY", Integer.toString(job.nearest.getBlockY())); p.setProperty("nearestZ", Integer.toString(job.nearest.getBlockZ())); p.setProperty("nearestType", job.nearestType == null ? "" : job.nearestType.name()); }
            try (OutputStream out = Files.newOutputStream(checkpointPath(job.player.getUniqueId()))) { p.store(out, "FindBlock resumable full-world search checkpoint"); }
        } catch (IOException ex) { getLogger().warning("Couldn't save checkpoint: " + ex.getMessage()); }
    }
    private Path checkpointPath(UUID uuid) { return getDataFolder().toPath().resolve("search-" + uuid + ".properties"); }
    private void deleteCheckpoint(UUID uuid) { try { Files.deleteIfExists(checkpointPath(uuid)); } catch (IOException ex) { getLogger().warning("Couldn't delete checkpoint: " + ex.getMessage()); } }

    private void processNextChunk(SearchJob job) {
        if (!isCurrent(job)) { jobs.remove(job.player.getUniqueId(), job); return; }
        if (!job.player.isOnline()) { saveCheckpoint(job); jobs.remove(job.player.getUniqueId(), job); return; }
        if (job.index >= job.chunks.size()) { finishSearch(job); return; }
        ChunkPos pos = job.chunks.get(job.index++);
        boolean wasLoaded = job.world.isChunkLoaded(pos.x, pos.z);
        job.world.getChunkAtAsync(pos.x, pos.z, false).whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (!isCurrent(job)) return;
            if (error == null && chunk != null) {
                scanChunk(job, chunk);
                if (job.fullWorld && !wasLoaded && job.world.isChunkLoaded(pos.x, pos.z)) job.world.unloadChunkRequest(pos.x, pos.z);
            }
            if (job.fullWorld) saveCheckpoint(job);
            int every = Math.max(1, getConfig().getInt("settings.progress-every-chunks", 500));
            if (job.fullWorld && job.index % every == 0) job.player.sendMessage(ChatColor.GRAY + "Progress: " + job.index + "/" + job.chunks.size() + " chunks.");
            long delay = Math.max(1, getConfig().getLong("settings.chunk-delay-ticks", 1));
            job.task = Bukkit.getScheduler().runTaskLater(this, () -> processNextChunk(job), delay);
        }));
    }

    private void scanChunk(SearchJob job, Chunk chunk) {
        int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int worldX = baseX + x, worldZ = baseZ + z;
            long dx = (long) worldX - job.originX, dz = (long) worldZ - job.originZ;
            if (!job.fullWorld && dx * dx + dz * dz > (long) job.radius * job.radius) continue;
            for (int y = job.world.getMinHeight(); y < job.world.getMaxHeight(); y++) {
                long dy = (long) y - job.originY;
                double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                if (!job.fullWorld && dist > (long) job.radius * job.radius) continue;
                Block block = chunk.getBlock(x, y, z); Material type = block.getType();
                if (job.targets.contains(type) && dist < job.bestDistanceSquared) {
                    job.bestDistanceSquared = dist; job.nearest = new Location(job.world, worldX, y, worldZ); job.nearestType = type;
                }
            }
        }
    }

    private void finishSearch(SearchJob job) {
        jobs.remove(job.player.getUniqueId(), job); deleteCheckpoint(job.player.getUniqueId());
        if (job.nearest == null) { job.player.sendMessage(ChatColor.RED + "No matching block found " + (job.fullWorld ? "in existing saved chunks." : "in loaded chunks within that radius.")); }
        else {
            SavedResult result = new SavedResult(job.nearest.getWorld().getName(), job.nearest.getBlockX(), job.nearest.getBlockY(), job.nearest.getBlockZ(), job.nearestType.name());
            rememberResult(job.player.getUniqueId(), result);
            job.player.sendMessage(ChatColor.GREEN + "Nearest " + display(job.nearestType) + " found at ");
            job.player.sendMessage(resultComponent(result));
            job.player.sendMessage(ChatColor.GRAY + "Distance: " + String.format(Locale.ROOT, "%.1f", Math.sqrt(job.bestDistanceSquared)) + " blocks. Use /findblock tp to teleport (permission required).");
        }
        job.player.sendMessage(ChatColor.GRAY + "Chunks checked: " + job.index + ".");
    }

    private Component resultComponent(SavedResult r) {
        String coords = "X: " + r.x + ", Y: " + r.y + ", Z: " + r.z + " (" + r.world + ")";
        return Component.text("  » " + coords).clickEvent(ClickEvent.copyToClipboard(r.x + " " + r.y + " " + r.z))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy coordinates")));
    }
    private void rememberResult(UUID uuid, SavedResult result) {
        lastResults.put(uuid, result); List<SavedResult> list = history.computeIfAbsent(uuid, k -> new ArrayList<>());
        list.add(0, result); int limit = Math.max(1, getConfig().getInt("settings.history-size", 10));
        while (list.size() > limit) list.remove(list.size() - 1); saveHistory();
    }
    private void showHistory(Player player) {
        List<SavedResult> list = history.getOrDefault(player.getUniqueId(), List.of());
        if (list.isEmpty()) { player.sendMessage(ChatColor.YELLOW + "No saved results yet."); return; }
        player.sendMessage(ChatColor.AQUA + "Recent FindBlock results (click a line to copy coordinates):");
        for (int i = 0; i < list.size(); i++) player.sendMessage(Component.text(ChatColor.GRAY + "" + (i + 1) + ". ").append(resultComponent(list.get(i))));
    }
    private void teleportToLast(Player player) {
        if (!player.hasPermission("findblock.tp")) { player.sendMessage(ChatColor.RED + "You need findblock.tp permission to teleport."); return; }
        SavedResult r = lastResults.get(player.getUniqueId());
        if (r == null) { List<SavedResult> list = history.getOrDefault(player.getUniqueId(), List.of()); if (!list.isEmpty()) r = list.get(0); }
        if (r == null) { player.sendMessage(ChatColor.YELLOW + "No result to teleport to. Run a search first."); return; }
        World world = Bukkit.getWorld(r.world);
        if (world == null) { player.sendMessage(ChatColor.RED + "The result world isn't loaded: " + r.world); return; }
        player.teleport(new Location(world, r.x + 0.5, r.y + 1.0, r.z + 0.5));
        player.sendMessage(ChatColor.GREEN + "Teleported to " + r.x + ", " + r.y + ", " + r.z + ".");
    }
    private void loadHistory() {
        Path path = getDataFolder().toPath().resolve("history.yml"); if (!Files.isRegularFile(path)) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(path.toFile());
        for (String key : yml.getKeys(false)) try {
            UUID uuid = UUID.fromString(key); List<SavedResult> results = new ArrayList<>();
            for (Map<?, ?> row : yml.getMapList(key)) results.add(new SavedResult(String.valueOf(row.get("world")), ((Number) row.get("x")).intValue(), ((Number) row.get("y")).intValue(), ((Number) row.get("z")).intValue(), String.valueOf(row.get("type"))));
            if (!results.isEmpty()) { history.put(uuid, results); lastResults.put(uuid, results.get(0)); }
        } catch (Exception ex) { getLogger().warning("Skipped malformed history for " + key); }
    }
    private void saveHistory() {
        YamlConfiguration yml = new YamlConfiguration();
        for (var entry : history.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SavedResult r : entry.getValue()) { Map<String, Object> row = new HashMap<>(); row.put("world", r.world); row.put("x", r.x); row.put("y", r.y); row.put("z", r.z); row.put("type", r.type); rows.add(row); }
            yml.set(entry.getKey().toString(), rows);
        }
        try { Files.createDirectories(getDataFolder().toPath()); yml.save(getDataFolder().toPath().resolve("history.yml").toFile()); }
        catch (IOException ex) { getLogger().warning("Couldn't save result history: " + ex.getMessage()); }
    }

    private void cancelPlayerSearch(Player player, boolean deleteCheckpoint) {
        SearchJob job = jobs.remove(player.getUniqueId());
        if (job != null) { job.cancelled = true; if (job.task != null) job.task.cancel(); player.sendMessage(ChatColor.RED + "FindBlock search cancelled."); }
        else if (deleteCheckpoint && !Files.exists(checkpointPath(player.getUniqueId()))) player.sendMessage(ChatColor.YELLOW + "You don't have an active search or saved checkpoint.");
        if (deleteCheckpoint) { deleteCheckpoint(player.getUniqueId()); player.sendMessage(ChatColor.GRAY + "Saved search progress deleted."); }
    }
    private boolean isCurrent(SearchJob job) { return !job.cancelled && jobs.get(job.player.getUniqueId()) == job; }
    private void finishError(SearchJob job, String message) { jobs.remove(job.player.getUniqueId(), job); if (job.player.isOnline()) job.player.sendMessage(ChatColor.RED + message); }
    private void sendHelp(CommandSender s) {
        s.sendMessage(ChatColor.AQUA + "FindBlock commands:");
        s.sendMessage(ChatColor.YELLOW + "/findblock <block[,block...]> [radius] " + ChatColor.GRAY + "- nearby loaded chunks");
        s.sendMessage(ChatColor.YELLOW + "/findblock world <block[,block...]> " + ChatColor.GRAY + "- saved world chunks (permission)");
        s.sendMessage(ChatColor.YELLOW + "/findblock resume | cancel | history | tp");
        s.sendMessage(ChatColor.GRAY + "Admin: /findblock admin reload | /findblock admin cancel <player>");
    }
    private static String display(Material m) { return m.name().toLowerCase(Locale.ROOT); }
    private static String displayTargets(List<Material> list) { return String.join(" or ", list.stream().map(FindBlockPlugin::display).toList()); }
    private static long chunkDistanceSquared(ChunkPos pos, int x, int z) {
        long dx = (((long) pos.x << 4) + 8) - x, dz = (((long) pos.z << 4) + 8) - z; return dx * dx + dz * dz;
    }
    private static List<ChunkPos> listExistingChunks(Path regionDir) throws IOException {
        List<ChunkPos> chunks = new ArrayList<>(); if (!Files.isDirectory(regionDir)) return chunks;
        try (var paths = Files.list(regionDir)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList()) {
                String[] parts = path.getFileName().toString().split("\\."); int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    if (file.length() < 4096) continue; byte[] header = new byte[4096]; file.readFully(header);
                    for (int slot = 0; slot < 1024; slot++) { int off = slot * 4; int sector = ((header[off] & 255) << 16) | ((header[off + 1] & 255) << 8) | (header[off + 2] & 255); if (sector != 0) chunks.add(new ChunkPos(rx * 32 + (slot & 31), rz * 32 + (slot >> 5))); }
                } catch (IOException ignored) { /* skip damaged region */ }
            }
        }
        return chunks;
    }
    @Override public void onDisable() { for (SearchJob job : jobs.values()) { job.cancelled = true; saveCheckpoint(job); if (job.task != null) job.task.cancel(); } jobs.clear(); saveHistory(); }
    private record ChunkPos(int x, int z) {}
    private record SavedResult(String world, int x, int y, int z, String type) {}
    private static final class SearchJob {
        final Player player; final World world; final List<Material> targets; final int originX, originY, originZ; final boolean fullWorld;
        volatile boolean cancelled; List<ChunkPos> chunks = List.of(); int index, radius; Location nearest; Material nearestType; double bestDistanceSquared = Double.POSITIVE_INFINITY; BukkitTask task;
        SearchJob(Player p, World w, List<Material> t, int x, int y, int z, boolean full) { player=p; world=w; targets=t; originX=x; originY=y; originZ=z; fullWorld=full; }
    }
}
