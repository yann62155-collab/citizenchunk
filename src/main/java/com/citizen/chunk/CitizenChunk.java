package com.citizen.chunk;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Citizen Chunk : pré-génération des chunks, réservée aux admins (OP niveau 2).
 * Mod 100 % serveur : aucun paquet réseau, les clients vanilla ou moddés peuvent se connecter sans l'avoir.
 */
@Mod(CitizenChunk.MOD_ID)
public final class CitizenChunk {
    public static final String MOD_ID = "citizenchunk";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CitizenChunk(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChunkCommands.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        PregenManager.get().onServerStarted(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        PregenManager.get().onServerStopping(event.getServer());
    }

    private void onTickPre(ServerTickEvent.Pre event) {
        PregenManager.get().onTickStart();
    }

    private void onTickPost(ServerTickEvent.Post event) {
        PregenManager.get().onTickEnd(event.getServer());
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PregenManager.get().onPlayerLogin(player);
        }
    }
}
