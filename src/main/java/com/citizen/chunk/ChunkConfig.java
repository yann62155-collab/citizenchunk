package com.citizen.chunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fichier config/citizenchunk.json, modifiable aussi en jeu (/cchunk vitesse). */
public final class ChunkConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Nombre maximum de chunks générés en parallèle. */
    public int vitesseMax = 12;
    /** Au-dessus de ce temps par tick (ms), le mod ralentit tout seul pour ne pas faire laguer les joueurs. */
    public int msptCible = 40;
    /** Reprendre automatiquement une génération en cours après un redémarrage du serveur. */
    public boolean repriseAutomatique = true;
    /** Annonce aux admins tous les X % (0 = jamais). */
    public int annonceTousLesPourcents = 10;
    /** Rayon maximum accepté, en blocs (sécurité contre une faute de frappe). */
    public int rayonMaxBlocs = 50000;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("citizenchunk.json");
    }

    public static ChunkConfig load() {
        Path f = file();
        ChunkConfig cfg = null;
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(r, ChunkConfig.class);
            } catch (Exception e) {
                CitizenChunk.LOGGER.warn("[CitizenChunk] config illisible, valeurs par défaut utilisées", e);
            }
        }
        if (cfg == null) cfg = new ChunkConfig();
        cfg.vitesseMax = Math.max(1, Math.min(64, cfg.vitesseMax));
        cfg.msptCible = Math.max(10, Math.min(50, cfg.msptCible));
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            CitizenChunk.LOGGER.warn("[CitizenChunk] impossible d'écrire la config", e);
        }
    }
}
