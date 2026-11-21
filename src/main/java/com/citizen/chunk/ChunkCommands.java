package com.citizen.chunk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

import java.util.UUID;

/**
 * /citizenchunk (alias /cchunk) — admins uniquement (OP niveau 2).
 *   lancer <rayon> [dimension] [x z]   lancer bordure [dimension]
 *   pause | reprendre | arreter | etat | suivre | vitesse <1-64> | aide
 */
public final class ChunkCommands {
    private ChunkCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("citizenchunk")
                .requires(s -> s.hasPermission(2))
                .executes(ChunkCommands::help)
                .then(Commands.literal("aide").executes(ChunkCommands::help))
                .then(Commands.literal("lancer")
                        .then(Commands.literal("bordure")
                                .executes(ctx -> startBorder(ctx, ctx.getSource().getLevel()))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .executes(ctx -> startBorder(ctx, DimensionArgument.getDimension(ctx, "dimension")))))
                        .then(Commands.argument("rayon", IntegerArgumentType.integer(16))
                                .executes(ctx -> startRadius(ctx, ctx.getSource().getLevel(), null))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .executes(ctx -> startRadius(ctx, DimensionArgument.getDimension(ctx, "dimension"), null))
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> startRadius(ctx, DimensionArgument.getDimension(ctx, "dimension"),
                                                                new int[]{IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z")})))))))
                .then(Commands.literal("pause").executes(ChunkCommands::pause))
                .then(Commands.literal("reprendre").executes(ChunkCommands::resume))
                .then(Commands.literal("arreter").executes(ChunkCommands::stopAsk)
                        .then(Commands.literal("confirmer").executes(ChunkCommands::stop)))
                .then(Commands.literal("etat").executes(ChunkCommands::status))
                .then(Commands.literal("suivre").executes(ChunkCommands::follow))
                .then(Commands.literal("vitesse")
                        .executes(ctx -> ok(ctx, "Vitesse max actuelle : " + PregenManager.get().config().vitesseMax + " chunks en parallèle."))
                        .then(Commands.argument("valeur", IntegerArgumentType.integer(1, 64))
                                .executes(ChunkCommands::speed))));

        dispatcher.register(Commands.literal("cchunk")
                .requires(s -> s.hasPermission(2))
                .executes(ChunkCommands::help)
                .redirect(root));
    }

    // ------------------------------------------------------------------

    private static int startRadius(CommandContext<CommandSourceStack> ctx, ServerLevel level, int[] center) {
        int radius = IntegerArgumentType.getInteger(ctx, "rayon");
        int max = PregenManager.get().config().rayonMaxBlocs;
        if (radius > max) {
            return err(ctx, "Rayon trop grand (" + radius + " blocs). Maximum : " + max + " (modifiable dans config/citizenchunk.json).");
        }
        int cx, cz;
        if (center != null) {
            cx = center[0];
            cz = center[1];
        } else if (level.dimension() == Level.OVERWORLD) {
            BlockPos spawn = level.getSharedSpawnPos();
            cx = spawn.getX();
            cz = spawn.getZ();
        } else {
            cx = 0;
            cz = 0;
        }
        return start(ctx, level, cx, cz, radius);
    }

    private static int startBorder(CommandContext<CommandSourceStack> ctx, ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        int radius = (int) Math.ceil(border.getSize() / 2.0);
        int max = PregenManager.get().config().rayonMaxBlocs;
        if (radius > max) {
            return err(ctx, "La bordure de ce monde est trop grande (rayon " + radius + " blocs). "
                    + "Règle-la d'abord avec /worldborder set <taille>, ou utilise /cchunk lancer <rayon>.");
        }
        return start(ctx, level, (int) Math.floor(border.getCenterX()), (int) Math.floor(border.getCenterZ()), radius);
    }

    private static int start(CommandContext<CommandSourceStack> ctx, ServerLevel level, int cx, int cz, int radius) {
        PregenManager m = PregenManager.get();
        if (m.isActive()) {
            return err(ctx, "Une génération existe déjà (" + m.status().name().toLowerCase().replace('_', ' ')
                    + "). Utilise /cchunk reprendre, ou /cchunk arreter avant d'en lancer une nouvelle.");
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        UUID uuid = player != null ? player.getUUID() : null;
        m.start(ctx.getSource().getServer(), level, cx, cz, radius, uuid);
        long side = 2L * (long) Math.ceil(radius / 16.0) + 1;
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(
                "Génération lancée dans " + level.dimension().location() + " : rayon " + radius + " blocs autour de X " + cx + " Z " + cz
                        + " → " + PregenManager.fmtInt(side * side) + " chunks. "
                        + (player != null ? "Barre de progression affichée (/cchunk suivre pour la masquer)." : ""))
                .withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> ctx) {
        PregenManager m = PregenManager.get();
        if (m.status() != PregenManager.Status.EN_COURS) return err(ctx, "Aucune génération en cours.");
        m.pause();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Génération en pause à " + PregenManager.fmtPct(m.percent())
                + " %. Reprends avec /cchunk reprendre (la progression est gardée même après un redémarrage).").withStyle(ChatFormatting.GOLD)), true);
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> ctx) {
        PregenManager m = PregenManager.get();
        if (m.status() != PregenManager.Status.PAUSE) return err(ctx, "Aucune génération en pause.");
        m.resume();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Génération reprise à " + PregenManager.fmtPct(m.percent()) + " %.")
                .withStyle(ChatFormatting.GREEN)), true);
        return 1;
    }

    private static int stopAsk(CommandContext<CommandSourceStack> ctx) {
        if (!PregenManager.get().isActive()) return err(ctx, "Aucune génération à arrêter.");
        return ok(ctx, "Arrêter efface la progression (les chunks déjà générés restent sur le disque). "
                + "Tape /cchunk arreter confirmer pour valider, ou /cchunk pause pour simplement mettre en pause.");
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        PregenManager m = PregenManager.get();
        if (!m.isActive()) return err(ctx, "Aucune génération à arrêter.");
        double pct = m.percent();
        m.stop();
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Génération arrêtée à " + PregenManager.fmtPct(pct)
                + " %. Les chunks déjà générés sont conservés.").withStyle(ChatFormatting.RED)), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> PregenManager.get().statusReport(), false);
        return 1;
    }

    private static int follow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean on = PregenManager.get().toggleFollow(player);
        return ok(ctx, on ? "Barre de progression affichée (elle revient aussi à ta reconnexion)." : "Barre de progression masquée.");
    }

    private static int speed(CommandContext<CommandSourceStack> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "valeur");
        PregenManager.get().setMaxSpeed(v);
        return ok(ctx, "Vitesse max réglée à " + v + " chunks en parallèle. Le mod ralentit quand même tout seul si le serveur lague.");
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        MutableComponent c = Component.literal("━━━ Citizen Chunk — commandes admin ━━━\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        String[][] lines = {
                {"/cchunk lancer <rayon>", "génère un carré autour du spawn (rayon en blocs)"},
                {"/cchunk lancer <rayon> <monde> <x> <z>", "choisit le monde et le centre"},
                {"/cchunk lancer bordure [monde]", "génère tout l'intérieur de la bordure du monde"},
                {"/cchunk pause", "met en pause (progression gardée)"},
                {"/cchunk reprendre", "reprend là où ça s'était arrêté"},
                {"/cchunk arreter", "arrête et efface la progression"},
                {"/cchunk etat", "pourcentage, chunks, vitesse, temps restant"},
                {"/cchunk suivre", "affiche/masque la barre de progression"},
                {"/cchunk vitesse <1-64>", "chunks générés en parallèle (défaut 12)"},
        };
        for (String[] l : lines) {
            c.append(Component.literal(l[0]).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" — " + l[1] + "\n").withStyle(ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> c, false);
        return 1;
    }

    // ------------------------------------------------------------------

    private static MutableComponent prefix() {
        return Component.literal("[Citizen Chunk] ").withStyle(ChatFormatting.DARK_RED);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(msg).withStyle(ChatFormatting.WHITE)), false);
        return 1;
    }

    private static int err(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(prefix().append(Component.literal(msg).withStyle(ChatFormatting.RED)));
        return 0;
    }
}
