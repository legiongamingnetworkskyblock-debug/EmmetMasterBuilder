package com.emmetbuilder;

import com.google.gson.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class EmmetPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Integer> npcs = new HashMap<>();
    private final Set<UUID> talking = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Plan> pending = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> history = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final Gson gson = new Gson();

    record Plan(String type, int width, int depth, int height, String material, String description) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("build")).setExecutor(this);
        Objects.requireNonNull(getCommand("emmet")).setExecutor(this);
        getLogger().info("Emmet Master Builder enabled. Configure API key and allowed-worlds.");
    }

    @Override public void onDisable() {
        for (int id : npcs.values()) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc != null) npc.destroy();
        }
        npcs.clear();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Player only."); return true; }
        if (cmd.getName().equalsIgnoreCase("build")) {
            Integer id = npcs.remove(p.getUniqueId());
            if (id != null) {
                NPC existing = CitizensAPI.getNPCRegistry().getById(id);
                if (existing != null) existing.destroy();
                talking.remove(p.getUniqueId());
                p.sendMessage(ChatColor.YELLOW + "Emmet dismissed. Type /build to summon him again.");
            } else {
                NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Emmet");
                npc.setProtected(true);
                npc.spawn(p.getLocation().add(p.getLocation().getDirection().multiply(2)));
                npcs.put(p.getUniqueId(), npc.getId());
                p.sendMessage(ChatColor.GOLD + "Emmet - The Master Builder has arrived! Right-click him to chat.");
                p.sendMessage(ChatColor.GRAY + "Tip: Citizens skin can be set separately using /npc skin while selecting him.");
            }
            return true;
        }
        if (args.length == 0) { p.sendMessage("/emmet <message>, /emmet confirm, /emmet cancel"); return true; }
        String msg = String.join(" ", args);
        if (msg.equalsIgnoreCase("cancel")) { pending.remove(p.getUniqueId()); p.sendMessage("Emmet: Build cancelled."); return true; }
        if (msg.equalsIgnoreCase("confirm")) {
            Plan plan = pending.remove(p.getUniqueId());
            if (plan == null) { p.sendMessage("Emmet: No build is waiting for confirmation."); return true; }
            build(p, plan);
            return true;
        }
        if (!talking.contains(p.getUniqueId())) { p.sendMessage("Right-click your Emmet NPC first."); return true; }
        ask(p, msg);
        return true;
    }

    @EventHandler public void onClick(NPCRightClickEvent event) {
        Player p = event.getClicker();
        if (!Objects.equals(npcs.get(p.getUniqueId()), event.getNPC().getId())) return;
        talking.add(p.getUniqueId());
        p.sendMessage(ChatColor.GOLD + "Emmet: Hey! I'm Emmet, your Master Builder! What should we create?");
        p.sendMessage(ChatColor.GRAY + "Type in chat or use /emmet <message>. /emmet confirm to build; /emmet cancel to stop.");
        // Open the client's normal chat input; never rely on a modded GUI.
        p.sendTitle("", "Talk to Emmet in chat!", 5, 40, 10);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (!talking.contains(p.getUniqueId())) return;
        event.setCancelled(true);
        String msg = event.getMessage();
        Bukkit.getScheduler().runTask(this, () -> ask(p, msg));
    }

    private void ask(Player p, String msg) {
        if (msg.equalsIgnoreCase("bye") || msg.equalsIgnoreCase("goodbye")) {
            talking.remove(p.getUniqueId()); p.sendMessage("Emmet: See you later! Right-click me to talk again."); return;
        }
        String key = getConfig().getString("openai-api-key", "");
        if (key.isBlank()) { p.sendMessage(ChatColor.RED + "Emmet: Ask the server owner to configure my AI API key first."); return; }
        p.sendMessage(ChatColor.GRAY + "Emmet is thinking...");
        UUID id = p.getUniqueId();
        List<String> past = new ArrayList<>(history.getOrDefault(id, List.of()));
        CompletableFuture.supplyAsync(() -> ai(key, past, msg)).whenComplete((result, err) ->
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (err != null) { p.sendMessage(ChatColor.RED + "Emmet: AI request failed; check server logs."); getLogger().warning(err.toString()); return; }
                p.sendMessage(ChatColor.GOLD + "Emmet: " + result.reply);
                List<String> h = new ArrayList<>(past);
                h.add("Player: " + msg); h.add("Emmet: " + result.reply);
                history.put(id, h.subList(Math.max(0, h.size()-12), h.size()));
                if (result.plan != null) {
                    pending.put(id, result.plan);
                    p.sendMessage(ChatColor.GREEN + "Proposed " + result.plan.type + " (" + result.plan.width + "x" + result.plan.depth + "x" + result.plan.height + ") using " + result.plan.material);
                    p.sendMessage(ChatColor.YELLOW + "Use /emmet confirm to build at your position, or /emmet cancel.");
                }
            }));
    }

    private record AIResult(String reply, Plan plan) {}
    private AIResult ai(String key, List<String> past, String msg) {
        try {
            JsonObject body = new JsonObject(); body.addProperty("model", getConfig().getString("model", "gpt-4.1-mini"));
            body.addProperty("temperature", 0.6);
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject(); system.addProperty("role", "system");
            system.addProperty("content", "You are Emmet, a cheerful enthusiastic Minecraft master builder. Reply concisely and conversationally. When the user has clearly asked you to build and you have enough detail, append exactly one line BUILD_PLAN:{\"type\":\"house\",\"width\":9,\"depth\":9,\"height\":6,\"material\":\"OAK_PLANKS\",\"description\":\"...\"}. Supported types: house, tower, castle. Width/depth 5-35, height 5-25. Supported materials: OAK_PLANKS, STONE_BRICKS, DEEPSLATE_BRICKS, QUARTZ_BLOCK. A build plan creates only a simple procedural prototype, not a detailed artistic structure. Never claim to have built until confirmed. Do not emit a plan for casual conversation.");
            messages.add(system);
            for (String s : past) { JsonObject m = new JsonObject(); m.addProperty("role", "user"); m.addProperty("content", s); messages.add(m); }
            JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.addProperty("content", msg); messages.add(user);
            body.add("messages", messages);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(40)).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("AI HTTP status " + response.statusCode());
            String output = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            int marker = output.indexOf("BUILD_PLAN:");
            if (marker < 0) return new AIResult(output.strip(), null);
            String reply = output.substring(0, marker).strip();
            try {
                String json = output.substring(marker + "BUILD_PLAN:".length()).strip().split("\\n")[0];
                Plan raw = gson.fromJson(json, Plan.class);
                if (raw == null || raw.type == null || raw.material == null) return new AIResult(reply, null);
                String type = raw.type.toLowerCase(Locale.ROOT);
                if (!Set.of("house", "tower", "castle").contains(type)) return new AIResult(reply, null);
                if (!Set.of("OAK_PLANKS", "STONE_BRICKS", "DEEPSLATE_BRICKS", "QUARTZ_BLOCK").contains(raw.material)) return new AIResult(reply, null);
                Plan plan = new Plan(type, Math.max(5, Math.min(35, raw.width)), Math.max(5, Math.min(35, raw.depth)), Math.max(5, Math.min(25, raw.height)), raw.material, raw.description);
                return new AIResult(reply, plan);
            } catch (RuntimeException ignored) { return new AIResult(reply, null); }
        } catch (Exception ex) { throw new CompletionException(ex); }
    }

    private void build(Player p, Plan plan) {
        if (!getConfig().getStringList("allowed-worlds").contains(p.getWorld().getName())) {
            p.sendMessage(ChatColor.RED + "Emmet: Building is disabled in this world. Ask an admin to configure a safe test world."); return;
        }
        int width = plan.width, depth = plan.depth, height = plan.height;
        long count = (long) width * depth * height;
        if (count > getConfig().getInt("max-build-blocks", 12000)) { p.sendMessage("Emmet: This build exceeds the configured size limit."); return; }
        Location base = p.getLocation().getBlock().getLocation().add(3, 0, 3);
        if (base.getBlockY() + height >= p.getWorld().getMaxHeight()) { p.sendMessage("Emmet: Not enough height here."); return; }
        Material wall = Material.valueOf(plan.material);
        // Queue limited block changes per tick to avoid a large synchronous spike.
        List<Map.Entry<Location, Material>> blocks = new ArrayList<>();
        for (int x=0; x<width; x++) for (int y=0; y<height; y++) for (int z=0; z<depth; z++) {
            boolean edge = x==0 || z==0 || x==width-1 || z==depth-1;
            Material m = y==0 ? wall : y==height-1 ? Material.STONE_BRICK_SLAB : edge ? wall : Material.AIR;
            if (plan.type.equals("tower") && y==height-1) m = Material.STONE_BRICKS;
            if (edge && y==2 && (x==0 || x==width-1) && z%4==2) m = Material.GLASS_PANE;
            if (edge && y==2 && (z==0 || z==depth-1) && x%4==2) m = Material.GLASS_PANE;
            if (z==0 && (x==width/2 || x==width/2-1) && (y==1 || y==2)) m = Material.AIR;
            blocks.add(Map.entry(base.clone().add(x,y,z),m));
        }
        p.sendMessage(ChatColor.GOLD + "Emmet: Let's build! This is a basic " + plan.type + " prototype.");
        new org.bukkit.scheduler.BukkitRunnable() {
            int index = 0;
            @Override public void run() {
                int limit = Math.min(index+250, blocks.size());
                while (index < limit) {
                    var e = blocks.get(index++);
                    e.getKey().getBlock().setType(e.getValue(), false);
                }
                if (index >= blocks.size()) { cancel(); if (p.isOnline()) p.sendMessage(ChatColor.GREEN + "Emmet: All done! Awesome!"); }
            }
        }.runTaskTimer(this, 1L, 1L);
    }
}
