package com.warband.ai;

import com.warband.config.WarbandConfig;
import com.warband.entity.MobData;
import com.warband.entity.Role;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Multiplayer combat memory, budgets, party scaling, and debug summaries. */
public final class MultiplayerDirector {

    private static final int THREAT_DECAY_INTERVAL = 20;
    private static final double PARTY_RADIUS = 64.0;
    private static final double COMBAT_RADIUS = 96.0;
    private static final double INTEL_RADIUS = 48.0;
    private static final Map<Integer, Map<UUID, Threat>> THREAT_BY_SQUAD = new HashMap<>();
    private static final List<DeathReliefArea> DEATH_RELIEF_AREAS = new ArrayList<>();
    private static int tickCounter;

    private MultiplayerDirector() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(MultiplayerDirector::afterDamage);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive || !WarbandConfig.multiplayerFeaturesEnabled || WarbandConfig.multiplayerDeathMercySeconds <= 0) {
                return;
            }
            if (!(oldPlayer.level() instanceof ServerLevel level)) return;
            DEATH_RELIEF_AREAS.add(new DeathReliefArea(
                    level.dimension().toString(),
                    oldPlayer.blockPosition(),
                    level.getGameTime() + WarbandConfig.multiplayerDeathMercySeconds * 20L));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter < THREAT_DECAY_INTERVAL) return;
            tickCounter = 0;
            long now = server.overworld().getGameTime();
            decayThreat();
            DEATH_RELIEF_AREAS.removeIf(area -> area.expiresAt <= now);
        });
    }

    private static void afterDamage(LivingEntity entity, DamageSource source,
                                    float baseDamageTaken, float damageTaken, boolean blocked) {
        if (!WarbandConfig.multiplayerFeaturesEnabled || damageTaken <= 0.0f) return;
        if (!(entity instanceof Mob mob) || !(source.getEntity() instanceof ServerPlayer player)) return;
        MobData data = MobData.get(mob);
        if (!data.inSquad()) return;
        recordThreat(data.squadId(), player, damageTaken * 10.0);
    }

    public static void recordThreat(int squadId, ServerPlayer player, double amount) {
        if (!WarbandConfig.multiplayerFeaturesEnabled || squadId == MobData.NO_SQUAD) return;
        Map<UUID, Threat> threats = THREAT_BY_SQUAD.computeIfAbsent(squadId, ignored -> new HashMap<>());
        Threat threat = threats.get(player.getUUID());
        double updated = (threat == null ? 0.0 : threat.value) + amount;
        threats.put(player.getUUID(), new Threat(player, Math.min(250.0, updated)));
    }

    public static @Nullable LivingEntity chooseSquadTarget(Squad squad, Mob observer, List<LivingEntity> visible) {
        if (!WarbandConfig.multiplayerFeaturesEnabled || visible.isEmpty()) return visible.isEmpty() ? null : visible.getFirst();
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<UUID, Threat> threats = THREAT_BY_SQUAD.getOrDefault(squad.id(), Map.of());
        for (LivingEntity candidate : visible) {
            double score = 25.0 - observer.distanceTo(candidate);
            if (candidate instanceof ServerPlayer player) {
                Threat threat = threats.get(player.getUUID());
                if (threat != null) score += threat.value;
                score -= squadTargeters(squad, player) * WarbandConfig.multiplayerDogpilePenalty;
                Role role = MobData.get(observer).role();
                if (role == Role.MARKSMAN && observer.distanceTo(player) > 8.0) score += 10.0;
                if (role == Role.BRUISER && observer.distanceTo(player) < 8.0) score += 10.0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static void shareIntel(Squad source, Collection<Squad> squads) {
        if (!WarbandConfig.multiplayerFeaturesEnabled) return;
        BlockPos lastKnown = source.lastKnownPos();
        if (lastKnown == null) return;
        Vec3 center = source.center();
        for (Squad other : squads) {
            if (other == source || other.level() != source.level() || other.isEmpty()) continue;
            if (other.center().distanceToSqr(center) > INTEL_RADIUS * INTEL_RADIUS) continue;
            other.alertTo(lastKnown);
        }
    }

    public static int extraPartyPlayers(ServerLevel level, BlockPos pos) {
        if (!WarbandConfig.multiplayerFeaturesEnabled) return 0;
        return Math.max(0, playersNear(level, pos, PARTY_RADIUS).size() - 1);
    }

    public static int effectiveSmartBudget(ServerLevel level, BlockPos pos) {
        int extra = extraPartyPlayers(level, pos);
        return WarbandConfig.maxSmartMobsPerPlayer + extra * WarbandConfig.multiplayerSmartMobsPerExtraPlayer;
    }

    public static boolean underSmartBudget(ServerLevel level, BlockPos pos) {
        int budget = effectiveSmartBudget(level, pos);
        AABB box = AABB.ofSize(Vec3.atCenterOf(pos), COMBAT_RADIUS * 2.0, COMBAT_RADIUS, COMBAT_RADIUS * 2.0);
        int smart = level.getEntitiesOfClass(Mob.class, box, MultiplayerDirector::hasWarbandAi).size();
        return smart < budget;
    }

    public static double encounterChanceMultiplier(ServerLevel level, BlockPos pos) {
        if (!WarbandConfig.multiplayerFeaturesEnabled) return 1.0;
        double multiplier = 1.0 + extraPartyPlayers(level, pos) * WarbandConfig.multiplayerEncounterBonusPerExtraPlayer;
        if (deathReliefActive(level, pos)) {
            multiplier *= 1.0 - WarbandConfig.multiplayerDeathMercyStrength;
        }
        return Math.max(0.0, multiplier);
    }

    public static int revengePartyBonus(ServerLevel level, BlockPos pos) {
        if (!WarbandConfig.multiplayerFeaturesEnabled) return 0;
        return extraPartyPlayers(level, pos);
    }

    public static List<String> playerDebugLines(ServerLevel level, BlockPos pos) {
        List<String> lines = new ArrayList<>();
        List<ServerPlayer> players = playersNear(level, pos, COMBAT_RADIUS);
        lines.add("Players near combat area: " + players.size());
        for (ServerPlayer player : players) {
            Float score = player.getAttached(com.warband.entity.WarbandAttachments.PLAYER_SCORE);
            boolean relief = player.getAttached(com.warband.entity.WarbandAttachments.DEATH_RELIEF) != null;
            lines.add(String.format("  %s score=%.2f relief=%s pos=%d %d %d",
                    player.getName().getString(),
                    score != null ? score : 0.0f,
                    relief,
                    player.getBlockX(), player.getBlockY(), player.getBlockZ()));
        }
        lines.add("smartBudget=" + effectiveSmartBudget(level, pos)
                + " deathMercy=" + deathReliefActive(level, pos));
        return lines;
    }

    public static String threatSummary(Squad squad) {
        Map<UUID, Threat> threats = THREAT_BY_SQUAD.get(squad.id());
        if (threats == null || threats.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (Threat threat : threats.values()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(threat.name).append('=').append(String.format("%.0f", threat.value));
        }
        return out.toString();
    }

    private static List<ServerPlayer> playersNear(ServerLevel level, BlockPos pos, double radius) {
        AABB box = AABB.ofSize(Vec3.atCenterOf(pos), radius * 2.0, radius, radius * 2.0);
        return level.getEntitiesOfClass(ServerPlayer.class, box,
                player -> player.isAlive() && !player.isSpectator());
    }

    private static boolean deathReliefActive(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().toString();
        for (DeathReliefArea area : DEATH_RELIEF_AREAS) {
            if (!area.dimension.equals(dimension)) continue;
            if (area.pos.distSqr(pos) <= WarbandConfig.multiplayerDeathMercyRadius * WarbandConfig.multiplayerDeathMercyRadius) {
                return true;
            }
        }
        return false;
    }

    private static int squadTargeters(Squad squad, Player player) {
        int count = 0;
        for (Mob mob : squad.members()) {
            if (mob.getTarget() == player) count++;
        }
        return count;
    }

    private static void decayThreat() {
        Iterator<Map.Entry<Integer, Map<UUID, Threat>>> squadIterator = THREAT_BY_SQUAD.entrySet().iterator();
        while (squadIterator.hasNext()) {
            Map<UUID, Threat> threats = squadIterator.next().getValue();
            threats.replaceAll((uuid, threat) -> new Threat(threat.name, threat.value * 0.94));
            threats.values().removeIf(threat -> threat.value < 1.0);
            if (threats.isEmpty()) squadIterator.remove();
        }
    }

    private static boolean hasWarbandAi(Mob mob) {
        MobData data = MobData.get(mob);
        return data.inSquad() || data.tactics() != 0;
    }

    private record Threat(String name, double value) {
        Threat(ServerPlayer player, double value) {
            this(player.getName().getString(), value);
        }
    }

    private record DeathReliefArea(String dimension, BlockPos pos, long expiresAt) {
    }
}
