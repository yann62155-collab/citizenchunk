package com.citizen.chunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Moteur de pré-génération.
 *
 * Fonctionnement (sans bloquer le thread principal du serveur) :
 *  1. sur le thread serveur, on pose un ticket « citizenchunk » sur le chunk pour qu'il reste chargé le temps de sa génération ;
 *  2. la demande de chunk part depuis un petit thread annexe : Minecraft la traite alors entre deux ticks, sans attendre ;
 *  3. quand le chunk est complet, le résultat revient dans une file lue au tick suivant, on retire le ticket
 *     et Minecraft sauvegarde puis décharge le chunk tout seul.
 * Le nombre de chunks en parallèle s'adapte au temps par tick (ms/tick) pour ne pas faire laguer les joueurs.
 */
public final class PregenManager {
    public enum Status { AUCUN, EN_COURS, PAUSE, TERMINE }

    private static final PregenManager INSTANCE = new PregenManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("citizenchunk", Comparator.comparingLong(ChunkPos::toLong));
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH'h'mm").withZone(ZONE);

    public static PregenManager get() { return INSTANCE; }

    private ChunkConfig config;
    private MinecraftServer server;

    // ---- tâche en cours ----
    private Status status = Status.AUCUN;
    private String dimension = Level.OVERWORLD.location().toString();
    private int centerChunkX;
    private int centerChunkZ;
    private int radiusChunks;
    private long total;
    private long taken;        // positions sorties de la spirale
    private long done;         // chunks terminés (réussis + échecs)
    private long failed;
    private long activeMillis; // temps passé « en cours » (hors pause)
    private SpiralIterator spiral = new SpiralIterator();
    private final ArrayDeque<Long> retry = new ArrayDeque<>();   // chunks en vol au moment d'un arrêt serveur
    private final Set<UUID> followers = new HashSet<>();
    private int lastAnnouncedStep;

    // ---- exécution ----
    private int generation;
    private final Set<Long> inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<Object[]> results = new ConcurrentLinkedQueue<>();
    private final ExecutorService requester = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CitizenChunk-Requester");
        t.setDaemon(true);
        return t;
    });
    private int effectiveConcurrency = 4;
    private double msptAverage = 0;
    private long tickStartNanos;
    private long lastWallMillis;
    private int tickCounter;
    private final ArrayDeque<long[]> speedSamples = new ArrayDeque<>();
    private ServerBossEvent bossBar;

    private PregenManager() {}

    public ChunkConfig config() {
        if (config == null) config = ChunkConfig.load();
        return config;
    }

    // =====================================================================
    //  Cycle de vie serveur
    // =====================================================================

    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        this.config = ChunkConfig.load();
        loadState();
        if (status == Status.EN_COURS && !config.repriseAutomatique) {
            status = Status.PAUSE;
            CitizenChunk.LOGGER.info("[CitizenChunk] génération mise en pause (reprise automatique désactivée)");
        }
        if (status == Status.EN_COURS || status == Status.PAUSE) {
            CitizenChunk.LOGGER.info("[CitizenChunk] tâche retrouvée : {} à {} %", status, fmtPct(percent()));
        }
        lastWallMillis = System.currentTimeMillis();
        refreshBossBar();
    }

    public void onServerStopping(MinecraftServer server) {
        saveState();
        generation++;
        inFlight.clear();
        results.clear();
        if (bossBar != null) bossBar.removeAllPlayers();
        bossBar = null;
        this.server = null;
    }

    public void onTickStart() {
        tickStartNanos = System.nanoTime();
    }

    public void onTickEnd(MinecraftServer server) {
        long now = System.currentTimeMillis();
        double ms = (System.nanoTime() - tickStartNanos) / 1_000_000.0;
        msptAverage = msptAverage == 0 ? ms : msptAverage * 0.9 + ms * 0.1;

        drainResults(server);

        if (status == Status.EN_COURS) {
            activeMillis += Math.min(1000, Math.max(0, now - lastWallMillis));
            ServerLevel level = level();
            if (level == null) {
                notifyAdmins(Component.literal("Le monde " + dimension + " est introuvable, génération mise en pause.").withStyle(ChatFormatting.RED));
                status = Status.PAUSE;
            } else {
                submitChunks(level);
                if (taken >= total && retry.isEmpty() && inFlight.isEmpty()) finish();
            }
        }
        lastWallMillis = now;

        if (++tickCounter % 20 == 0) {
            adaptSpeed();
            sampleSpeed(now);
            announceProgress();
            refreshBossBar();
        }
        if (tickCounter % 600 == 0 && (status == Status.EN_COURS || status == Status.PAUSE)) {
            saveState();
        }
    }

    public void onPlayerLogin(ServerPlayer player) {
        if (followers.contains(player.getUUID()) && player.hasPermissions(2) && bossBar != null) {
            bossBar.addPlayer(player);
        }
    }

    // =====================================================================
    //  Génération
    // =====================================================================

    private void submitChunks(ServerLevel level) {
        int max = effectiveConcurrency;
        long deadline = System.nanoTime() + 5_000_000L; // 5 ms max de travail par tick
        int submittedThisTick = 0;
        while (inFlight.size() < max && submittedThisTick < max && System.nanoTime() < deadline) {
            long packed;
            if (!retry.isEmpty()) {
                packed = retry.poll();
            } else if (taken < total) {
                int[] rel = spiral.next();
                taken++;
                packed = ChunkPos.asLong(centerChunkX + rel[0], centerChunkZ + rel[1]);
            } else {
                break;
            }
            if (!inFlight.add(packed)) continue;
            request(level, new ChunkPos(packed));
            submittedThisTick++;
        }
    }

    private void request(ServerLevel level, ChunkPos pos) {
        ServerChunkCache cache = level.getChunkSource();
        final int gen = generation;
        cache.addRegionTicket(TICKET, pos, 0, pos);
        CompletableFuture
                .supplyAsync(() -> cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true), requester)
                .thenCompose(f -> f)
                .whenComplete((ChunkResult<ChunkAccess> res, Throwable err) -> {
                    boolean ok = err == null && res != null && res.isSuccess();
                    results.add(new Object[]{level, pos, gen, ok});
                });
    }

    private void drainResults(MinecraftServer server) {
        Object[] r;
        while ((r = results.poll()) != null) {
            ServerLevel level = (ServerLevel) r[0];
            ChunkPos pos = (ChunkPos) r[1];
            int gen = (Integer) r[2];
            boolean ok = (Boolean) r[3];
            level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            if (gen != generation) continue;
            if (!inFlight.remove(pos.toLong())) continue;
            done++;
            if (!ok) failed++;
        }
    }

    private void adaptSpeed() {
        int max = config().vitesseMax;
        int target = config().msptCible;
        if (msptAverage > target) {
            effectiveConcurrency = Math.max(1, effectiveConcurrency - Math.max(1, effectiveConcurrency / 4));
        } else if (msptAverage < target * 0.75) {
            effectiveConcurrency = Math.min(max, effectiveConcurrency + 1);
        }
        if (effectiveConcurrency > max) effectiveConcurrency = max;
    }

    private void sampleSpeed(long now) {
        if (status != Status.EN_COURS) {
            speedSamples.clear();
            return;
        }
        speedSamples.addLast(new long[]{now, done});
        while (speedSamples.size() > 2 && now - speedSamples.peekFirst()[0] > 60_000) speedSamples.pollFirst();
    }

    /** Chunks par seconde, moyenne glissante sur la dernière minute. */
    public double speed() {
        if (speedSamples.size() < 2) return 0;
        long[] a = speedSamples.peekFirst();
        long[] b = speedSamples.peekLast();
        double dt = (b[0] - a[0]) / 1000.0;
        return dt <= 0 ? 0 : (b[1] - a[1]) / dt;
    }

    /** Secondes restantes estimées, ou -1 si inconnu. */
    public long etaSeconds() {
        double s = speed();
        if (s <= 0.01) return -1;
        return (long) Math.ceil((total - done) / s);
    }

    public double percent() {
        return total <= 0 ? 0 : Math.min(100.0, done * 100.0 / total);
    }

    private void finish() {
        status = Status.TERMINE;
        saveState();
        String msg = "Génération terminée : " + fmtInt(done) + " chunks en " + fmtDuration(activeMillis / 1000)
                + (failed > 0 ? " (" + fmtInt(failed) + " échecs)" : "") + ".";
        CitizenChunk.LOGGER.info("[CitizenChunk] {}", msg);
        notifyAdmins(Component.literal(msg).withStyle(ChatFormatting.GREEN));
        refreshBossBar();
    }

    private void announceProgress() {
        int step = config().annonceTousLesPourcents;
        if (status != Status.EN_COURS || step <= 0) return;
        int reached = (int) (percent() / step);
        if (reached > lastAnnouncedStep && percent() < 100) {
            lastAnnouncedStep = reached;
            String msg = "Génération : " + fmtPct(percent()) + " % — reste " + fmtEta();
            CitizenChunk.LOGGER.info("[CitizenChunk] {}", msg);
            notifyAdmins(Component.literal(msg).withStyle(ChatFormatting.GOLD));
        }
    }

    // =====================================================================
    //  Actions (appelées par les commandes)
    // =====================================================================

    public boolean isActive() {
        return status == Status.EN_COURS || status == Status.PAUSE;
    }

    public void start(MinecraftServer server, ServerLevel level, int centerBlockX, int centerBlockZ, int radiusBlocks, UUID starter) {
        this.server = server;
        generation++;
        inFlight.clear();
        retry.clear();
        speedSamples.clear();
        dimension = level.dimension().location().toString();
        centerChunkX = centerBlockX >> 4;
        centerChunkZ = centerBlockZ >> 4;
        radiusChunks = (int) Math.ceil(radiusBlocks / 16.0);
        long side = 2L * radiusChunks + 1;
        total = side * side;
        taken = 0;
        done = 0;
        failed = 0;
        activeMillis = 0;
        lastAnnouncedStep = 0;
        spiral = new SpiralIterator();
        effectiveConcurrency = Math.min(4, config().vitesseMax);
        status = Status.EN_COURS;
        lastWallMillis = System.currentTimeMillis();
        if (starter != null) followers.add(starter);
        saveState();
        refreshBossBar();
        CitizenChunk.LOGGER.info("[CitizenChunk] lancement : {} centre {} {} rayon {} blocs ({} chunks)",
                dimension, centerBlockX, centerBlockZ, radiusBlocks, total);
    }

    public void pause() {
        status = Status.PAUSE;
        speedSamples.clear();
        saveState();
        refreshBossBar();
    }

    public void resume() {
        status = Status.EN_COURS;
        lastWallMillis = System.currentTimeMillis();
        saveState();
        refreshBossBar();
    }

    public void stop() {
        generation++;
        inFlight.clear();
        retry.clear();
        speedSamples.clear();
        status = Status.AUCUN;
        total = 0;
        done = 0;
        taken = 0;
        failed = 0;
        saveState();
        refreshBossBar();
    }

    /** Active/désactive la barre de progression pour un admin. Renvoie true si elle est maintenant affichée. */
    public boolean toggleFollow(ServerPlayer player) {
        boolean nowFollowing;
        if (followers.remove(player.getUUID())) {
            if (bossBar != null) bossBar.removePlayer(player);
            nowFollowing = false;
        } else {
            followers.add(player.getUUID());
            nowFollowing = true;
        }
        refreshBossBar();
        saveState();
        return nowFollowing;
    }

    public void setMaxSpeed(int value) {
        config().vitesseMax = value;
        config().save();
        if (effectiveConcurrency > value) effectiveConcurrency = value;
    }

    // =====================================================================
    //  Affichage
    // =====================================================================

    public Component statusReport() {
        MutableComponent c = Component.literal("━━━ Citizen Chunk ━━━\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (status == Status.AUCUN) {
            return c.append(Component.literal("Aucune génération. Lance-en une avec /cchunk lancer <rayon>").withStyle(ChatFormatting.GRAY));
        }
        c.append(line("État", statusLabel(), statusColor()));
        c.append(line("Monde", dimension + " — centre X " + (centerChunkX * 16 + 8) + " Z " + (centerChunkZ * 16 + 8)
                + " — rayon " + fmtInt(radiusChunks * 16L) + " blocs", ChatFormatting.WHITE));
        c.append(Component.literal("Progression : ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(bar(percent(), 20) + " ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(fmtPct(percent()) + " %\n").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        c.append(line("Chunks", fmtInt(done) + " / " + fmtInt(total) + (failed > 0 ? "  (échecs : " + fmtInt(failed) + ")" : ""), ChatFormatting.WHITE));
        c.append(line("Temps écoulé", fmtDuration(activeMillis / 1000), ChatFormatting.WHITE));
        if (status == Status.EN_COURS) {
            c.append(line("Vitesse", String.format(Locale.FRANCE, "%.1f", speed()) + " chunks/s — " + effectiveConcurrency + "/" + config().vitesseMax
                    + " en parallèle — serveur " + String.format(Locale.FRANCE, "%.0f", msptAverage) + " ms/tick", ChatFormatting.WHITE));
            long eta = etaSeconds();
            String end = eta < 0 ? "calcul en cours…" : "~" + fmtDuration(eta) + " (vers " + HOUR.format(Instant.now().plusSeconds(eta)) + ")";
            c.append(line("Fin estimée", end, ChatFormatting.AQUA));
        } else if (status == Status.PAUSE) {
            c.append(line("Fin estimée", "en pause — /cchunk reprendre", ChatFormatting.GRAY));
        }
        return c;
    }

    private static MutableComponent line(String label, String value, ChatFormatting color) {
        return Component.literal(label + " : ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(value + "\n").withStyle(color));
    }

    private String statusLabel() {
        return switch (status) {
            case AUCUN -> "Aucune";
            case EN_COURS -> "En cours";
            case PAUSE -> "En pause";
            case TERMINE -> "Terminée";
        };
    }

    private ChatFormatting statusColor() {
        return switch (status) {
            case EN_COURS -> ChatFormatting.GREEN;
            case PAUSE -> ChatFormatting.GOLD;
            case TERMINE -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }

    private String fmtEta() {
        long eta = etaSeconds();
        return eta < 0 ? "calcul…" : "~" + fmtDuration(eta);
    }

    private void refreshBossBar() {
        if (server == null) return;
        boolean show = status == Status.EN_COURS || status == Status.PAUSE;
        if (!show) {
            if (bossBar != null) {
                bossBar.removeAllPlayers();
                bossBar = null;
            }
            return;
        }
        if (bossBar == null) {
            bossBar = new ServerBossEvent(Component.literal("Citizen Chunk"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
        }
        String text = status == Status.PAUSE
                ? "⏸ Citizen Chunk — en pause — " + fmtPct(percent()) + " %"
                : "Citizen Chunk — " + fmtPct(percent()) + " % — " + fmtInt(done) + "/" + fmtInt(total)
                  + " — reste " + fmtEta();
        bossBar.setName(Component.literal(text));
        bossBar.setColor(status == Status.PAUSE ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.YELLOW);
        bossBar.setProgress((float) Math.max(0, Math.min(1, percent() / 100.0)));

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean wants = followers.contains(p.getUUID()) && p.hasPermissions(2);
            boolean has = bossBar.getPlayers().contains(p);
            if (wants && !has) bossBar.addPlayer(p);
            if (!wants && has) bossBar.removePlayer(p);
        }
    }

    private void notifyAdmins(Component msg) {
        if (server == null) return;
        Component full = Component.literal("[Citizen Chunk] ").withStyle(ChatFormatting.DARK_RED).append(msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) p.sendSystemMessage(full);
        }
    }

    private ServerLevel level() {
        if (server == null) return null;
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    //  Sauvegarde (world/citizenchunk/etat.json)
    // =====================================================================

    private Path stateFile() {
        return server.getWorldPath(LevelResource.ROOT).resolve("citizenchunk").resolve("etat.json");
    }

    private void saveState() {
        if (server == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("status", status.name());
        o.addProperty("dimension", dimension);
        o.addProperty("centerChunkX", centerChunkX);
        o.addProperty("centerChunkZ", centerChunkZ);
        o.addProperty("radiusChunks", radiusChunks);
        o.addProperty("total", total);
        o.addProperty("taken", taken);
        o.addProperty("done", done);
        o.addProperty("failed", failed);
        o.addProperty("activeMillis", activeMillis);
        o.addProperty("lastAnnouncedStep", lastAnnouncedStep);
        o.add("spiral", spiral.save());
        JsonArray pending = new JsonArray();
        for (Long l : retry) pending.add(l);
        for (Long l : inFlight) pending.add(l);
        o.add("pending", pending);
        JsonArray f = new JsonArray();
        for (UUID u : followers) f.add(u.toString());
        o.add("followers", f);
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("etat.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CitizenChunk.LOGGER.warn("[CitizenChunk] sauvegarde de l'état impossible", e);
        }
    }

    private void loadState() {
        Path file = stateFile();
        status = Status.AUCUN;
        retry.clear();
        inFlight.clear();
        followers.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            status = Status.valueOf(o.get("status").getAsString());
            dimension = o.get("dimension").getAsString();
            centerChunkX = o.get("centerChunkX").getAsInt();
            centerChunkZ = o.get("centerChunkZ").getAsInt();
            radiusChunks = o.get("radiusChunks").getAsInt();
            total = o.get("total").getAsLong();
            taken = o.get("taken").getAsLong();
            done = o.get("done").getAsLong();
            failed = o.get("failed").getAsLong();
            activeMillis = o.get("activeMillis").getAsLong();
            lastAnnouncedStep = o.has("lastAnnouncedStep") ? o.get("lastAnnouncedStep").getAsInt() : 0;
            spiral = SpiralIterator.load(o.getAsJsonObject("spiral"));
            for (JsonElement e : o.getAsJsonArray("pending")) retry.add(e.getAsLong());
            if (o.has("followers")) {
                for (JsonElement e : o.getAsJsonArray("followers")) followers.add(UUID.fromString(e.getAsString()));
            }
        } catch (Exception e) {
            CitizenChunk.LOGGER.warn("[CitizenChunk] état illisible, ignoré", e);
            status = Status.AUCUN;
        }
    }

    // =====================================================================
    //  Formatage
    // =====================================================================

    static String fmtInt(long v) {
        // espace classique (les espaces fines du format français ne s'affichent pas dans Minecraft)
        return String.format(Locale.FRANCE, "%,d", v).replace(' ', ' ').replace(' ', ' ');
    }

    static String fmtPct(double p) {
        return String.format(Locale.FRANCE, "%.1f", p);
    }

    static String fmtDuration(long seconds) {
        if (seconds < 60) return seconds + " s";
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) return d + " j " + h + " h";
        if (h > 0) return h + " h " + String.format("%02d", m) + " min";
        return m + " min";
    }

    static String bar(double pct, int width) {
        int filled = (int) Math.round(pct / 100.0 * width);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) sb.append(i < filled ? '█' : '░');
        return sb.append(']').toString();
    }

    public Status status() { return status; }
}
