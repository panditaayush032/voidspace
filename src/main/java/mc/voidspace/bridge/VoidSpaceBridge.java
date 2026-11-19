package mc.voidspace.bridge;

import net.milkbowl.vault.economy.Economy;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class VoidSpaceBridge extends JavaPlugin implements Listener {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoidSpaceBridge-HTTP"); t.setDaemon(true); return t;
    });
    private String baseUrl;
    private String serviceKey;
    private String serverId;
    private Economy economy;
    private LuckPerms luckPerms;
    private long taskId = -1;

    @Override public void onEnable() {
        saveDefaultConfig();
        baseUrl = getConfig().getString("supabase.url", "").replaceAll("/+$", "");
        serviceKey = getConfig().getString("supabase.service-role-key", "");
        serverId = getConfig().getString("server-id", "main-paper");
        if (!baseUrl.startsWith("https://") || baseUrl.contains("YOUR_PROJECT_REF") || serviceKey.isBlank() || serviceKey.contains("PASTE_")) {
            getLogger().severe("Supabase is not configured. Edit plugins/VoidSpaceBridge/config.yml and restart.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        RegisteredServiceProvider<Economy> eco = getServer().getServicesManager().getRegistration(Economy.class);
        if (eco != null) economy = eco.getProvider();
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            RegisteredServiceProvider<LuckPerms> lp = getServer().getServicesManager().getRegistration(LuckPerms.class);
            if (lp != null) luckPerms = lp.getProvider();
        }
        getServer().getPluginManager().registerEvents(this, this);
        int interval = Math.max(15, getConfig().getInt("sync.interval-seconds", 30));
        taskId = Bukkit.getScheduler().runTaskTimer(this, () -> syncSnapshot(), 40L, interval * 20L).getTaskId();
        // Initial snapshot after startup. Join events that happen after enable are tracked normally.
        Bukkit.getScheduler().runTaskLater(this, this::syncSnapshot, 20L);
        getLogger().info("Enabled for server-id='" + serverId + "'. Economy provider: " + (economy == null ? "NOT FOUND (money will be 0)" : economy.getName()));
    }

    @Override public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        // Mark all rows for this backend as offline when this plugin/server shuts down.
        if (baseUrl != null && serviceKey != null && !serviceKey.isBlank() && !baseUrl.contains("YOUR_PROJECT_REF")) {
            try { request("DELETE", "/rest/v1/online_players?server_id=eq." + enc(serverId), "", true).join(); }
            catch (Exception e) { getLogger().warning("Could not clear online list during shutdown: " + e.getMessage()); }
        }
        io.shutdown();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            upsertOnline(p);
            writeEvent(p, "join");
            syncPlayer(p);
        }, 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        // Capture final statistics before the player object becomes unavailable.
        syncPlayer(p);
        writeEvent(p, "leave");
        io.submit(() -> {
            try { request("DELETE", "/rest/v1/online_players?uuid=eq." + enc(p.getUniqueId().toString()) + "&server_id=eq." + enc(serverId), "", true).join(); }
            catch (Exception e) { logWarn("Failed to remove online player " + p.getName(), e); }
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("voidspacebridge")) return false;
        if (!sender.hasPermission("voidspacebridge.admin")) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("sync")) {
            syncSnapshot(); sender.sendMessage("§dVoidSpace Bridge: sync queued."); return true;
        }
        sender.sendMessage("§5VoidSpace Bridge §7| server: §f" + serverId);
        sender.sendMessage("§7Online players on this Paper server: §d" + Bukkit.getOnlinePlayers().size());
        sender.sendMessage("§7Supabase: §fconfigured");
        sender.sendMessage("§7Economy: §f" + (economy == null ? "Vault provider not found" : economy.getName()));
        return true;
    }

    private void syncSnapshot() {
        if (!isEnabled()) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player p : online) { upsertOnline(p); syncPlayer(p); }
        // A server_id-scoped stale cleanup is done with individual UUID deletes for players
        // known in the previous snapshot; no global table deletion is used.
        // The client website should subscribe to Supabase Realtime or poll these tables.
    }

    private void upsertOnline(Player p) {
        Map<String,Object> row = new HashMap<>();
        row.put("uuid", p.getUniqueId().toString()); row.put("username", p.getName());
        row.put("server_id", serverId); row.put("updated_at", Instant.now().toString());
        request("POST", "/rest/v1/online_players?on_conflict=uuid", Json.stringify(row), true, "resolution=merge-duplicates,return=minimal");
    }

    private void syncPlayer(OfflinePlayer p) {
        UUID uuid = p.getUniqueId();
        String name = p.getName() == null ? uuid.toString() : p.getName();
        Map<String,Object> row = new HashMap<>();
        row.put("uuid", uuid.toString()); row.put("username", name); row.put("rank", getRank(uuid));
        row.put("money", getMoney(p)); row.put("kills", safeStatistic(p, Statistic.PLAYER_KILLS));
        row.put("deaths", safeStatistic(p, Statistic.DEATHS)); row.put("playtime_seconds", getPlaytimeSeconds(p));
        row.put("updated_at", Instant.now().toString());
        request("POST", "/rest/v1/player_stats?on_conflict=uuid", Json.stringify(row), true, "resolution=merge-duplicates,return=minimal");
    }

    private String getRank(UUID uuid) {
        if (luckPerms == null) return "member";
        try {
            User user = luckPerms.getUserManager().getUser(uuid);
            return user == null ? "member" : user.getPrimaryGroup();
        } catch (Exception ignored) { return "member"; }
    }

    private double getMoney(OfflinePlayer player) {
        if (economy == null) return 0.0;
        try { return Math.max(0.0, economy.getBalance(player)); }
        catch (Exception e) { logWarn("Could not read economy balance for " + player.getName(), e); return 0.0; }
    }

    private long safeStatistic(OfflinePlayer p, Statistic statistic) {
        try { return Math.max(0, p.getStatistic(statistic)); }
        catch (Exception ignored) { return 0L; }
    }

    private long getPlaytimeSeconds(OfflinePlayer p) {
        // PLAY_ONE_MINUTE is legacy Bukkit naming; value is ticks (20 ticks/sec).
        try { return Math.max(0, p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L); }
        catch (Exception ignored) { return 0L; }
    }

    private void writeEvent(Player p, String type) {
        Map<String,Object> row = new HashMap<>();
        row.put("player_uuid", p.getUniqueId().toString()); row.put("username", p.getName());
        row.put("event_type", type); row.put("server_id", serverId); row.put("occurred_at", Instant.now().toString());
        request("POST", "/rest/v1/player_events", Json.stringify(row), true, "return=minimal");
    }

    private CompletableFuture<Void> request(String method, String path, String body, boolean async) {
        return request(method, path, body, async, "return=minimal");
    }
    private CompletableFuture<Void> request(String method, String path, String body, boolean async, String prefer) {
        return CompletableFuture.runAsync(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(12)).header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", "application/json").header("Prefer", prefer);
                if (method.equals("POST")) b.POST(HttpRequest.BodyPublishers.ofString(body));
                else if (method.equals("DELETE")) b.DELETE();
                else throw new IllegalArgumentException("Unsupported method: " + method);
                HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Supabase returned HTTP " + response.statusCode() + " for " + path + ": " + response.body());
                } else if (getConfig().getBoolean("logging.verbose", true)) {
                    getLogger().fine("Supabase sync OK: " + path);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); logWarn("Supabase request interrupted", e); }
            catch (Exception e) { logWarn("Supabase request failed for " + path, e); }
        }, io);
    }
    private String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
    private void logWarn(String message, Exception e) { getLogger().log(Level.WARNING, message + ": " + e.getMessage()); }

    // Minimal JSON serializer for the simple primitive/string objects sent by this plugin.
    private static final class Json {
        static String stringify(Map<String,Object> map) {
            StringBuilder out = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<String,Object> e : map.entrySet()) {
                if (!first) out.append(','); first = false;
                out.append(quote(e.getKey())).append(':'); Object v=e.getValue();
                if (v == null) out.append("null");
                else if (v instanceof Number || v instanceof Boolean) out.append(v);
                else out.append(quote(String.valueOf(v)));
            }
            return out.append('}').toString();
        }
        static String quote(String s) {
            StringBuilder b=new StringBuilder("\"");
            for (char c:s.toCharArray()) { switch(c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t"); default -> { if(c<32)b.append(String.format("\\u%04x",(int)c)); else b.append(c); } } }
            return b.append('"').toString();
        }
    }
}
