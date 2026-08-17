package com.warband.spawn;

import com.warband.config.WarbandConfig;
import com.warband.ai.MultiplayerDirector;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player encounter pacing for whether fresh mobs receive Warband pressure. */
public final class EncounterDirector {

    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final double PLAYER_RADIUS = 96.0;
    private static final int MAX_EXTRA_PLAYERS = 8;
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static int tickCounter;

    private EncounterDirector() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!WarbandConfig.encounterDirectorEnabled) return;
            if (++tickCounter < UPDATE_INTERVAL_TICKS) return;
            tickCounter = 0;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                STATES.computeIfAbsent(player.getUUID(), ignored -> new State()).tick();
            }
            STATES.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        });
    }

    public static boolean allowsEnhancement(ServerLevel level, BlockPos pos, Mob mob) {
        if (!WarbandConfig.encounterDirectorEnabled) return true;

        ServerPlayer player = (ServerPlayer) level.getNearestPlayer(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, PLAYER_RADIUS, false);
        if (player == null) return true;

        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        double chance = switch (state.phase) {
            case BUILD_UP -> WarbandConfig.directorBuildUpEnhancementChance;
            case PEAK -> 1.0;
            case RELAX -> WarbandConfig.directorRelaxEnhancementChance;
        };
        // More players sharing the region raise the enhancement rate, volume,
        // not per-mob intensity (which the difficulty scalar caps at 1.0).
        if (chance < 1.0) {
            chance += WarbandConfig.encounterPlayerBonus * extraNearbyPlayers(level, pos);
        }
        chance *= MultiplayerDirector.encounterChanceMultiplier(level, pos);
        return mob.getRandom().nextDouble() < chance;
    }

    /** Players sharing the region beyond the first, capped. */
    private static int extraNearbyPlayers(ServerLevel level, BlockPos pos) {
        AABB box = AABB.ofSize(Vec3.atCenterOf(pos),
                PLAYER_RADIUS * 2.0, PLAYER_RADIUS * 2.0, PLAYER_RADIUS * 2.0);
        int players = level.getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && !p.isSpectator()).size();
        return Math.min(MAX_EXTRA_PLAYERS, Math.max(0, players - 1));
    }

    private enum Phase {
        BUILD_UP,
        PEAK,
        RELAX
    }

    private static final class State {
        private Phase phase = Phase.BUILD_UP;
        private int ticksInPhase;

        private void tick() {
            ticksInPhase += UPDATE_INTERVAL_TICKS;
            int limit = switch (phase) {
                case BUILD_UP -> WarbandConfig.directorBuildUpSeconds * 20;
                case PEAK -> WarbandConfig.directorPeakSeconds * 20;
                case RELAX -> WarbandConfig.directorRelaxSeconds * 20;
            };
            if (ticksInPhase < limit) return;
            ticksInPhase = 0;
            phase = switch (phase) {
                case BUILD_UP -> Phase.PEAK;
                case PEAK -> Phase.RELAX;
                case RELAX -> Phase.BUILD_UP;
            };
        }
    }
}
