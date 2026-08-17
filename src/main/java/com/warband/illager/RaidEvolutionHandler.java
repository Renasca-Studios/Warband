package com.warband.illager;

import com.warband.WarbandMod;
import com.warband.config.WarbandConfig;
import com.warband.entity.WarbandAttachments;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import com.warband.text.WarbandText;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;

/**
 * Raid finale + rival interception logic.
 *
 * <p>On high-ominous raids, the final wave summons a faction bounty hunter
 * to finish the raid personally — a dedicated single threat instead of just
 * "more pillagers." On medium-or-higher ominous raids, a rival faction
 * patrol spawns to harass the raiders (player can turn this to their
 * advantage).
 */
public final class RaidEvolutionHandler {

    private static final int TICK_INTERVAL = 40;
    /** Minimum ominous level before the finale bounty hunter is summoned. */
    private static final int FINALE_BOUNTY_MIN_OMEN = 3;
    /** Minimum ominous level before a rival patrol intercepts. */
    private static final int RIVAL_MIN_OMEN = 2;
    private static final int RIVAL_BASE_CHANCE_PERCENT = 35;

    private static final Set<String> FINALE_BOUNTY_DONE = new HashSet<>();
    private static final Set<String> RIVALS_DONE = new HashSet<>();
    private static int tickCounter;

    private RaidEvolutionHandler() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!WarbandConfig.illagerFactionsEnabled) return;
            if (++tickCounter < TICK_INTERVAL) return;
            tickCounter = 0;
            for (ServerLevel level : server.getAllLevels()) {
                scanRaids(level);
            }
        });
        // Faction-colored trophy drop on Warmarshal kill and patrol-captain kill.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!WarbandConfig.illagerFactionsEnabled) return;
            if (!WarbandConfig.illagerFactionBannersEnabled) return;
            if (!(entity instanceof Raider raider)) return;
            if (!(raider.level() instanceof ServerLevel level)) return;
            boolean trophy = Boolean.TRUE.equals(raider.getAttached(WarbandAttachments.WARMARSHAL))
                    || (raider instanceof PatrollingMonster patrol && patrol.isPatrolLeader());
            if (!trophy) return;
            IllagerFaction faction = IllagerFactionSystem.factionOrDefault(raider);
            ItemStack banner = new ItemStack(bannerItemFor(faction));
            banner.set(DataComponents.CUSTOM_NAME,
                    Component.translatableWithFallback("warband.item.trophy_banner", "%s Trophy Banner",
                            faction.displayName()));
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    level, raider.getX(), raider.getY() + 0.5, raider.getZ(), banner);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        });
    }

    private static net.minecraft.world.item.Item bannerItemFor(IllagerFaction faction) {
        return switch (faction) {
            case BLACK_HORN -> Items.BANNER.black();
            case RED_LEDGER -> Items.BANNER.red();
            case PALE_AXE -> Items.BANNER.white();
            case ASH_BANNER -> Items.BANNER.orange();
            case IRON_CHOIR -> Items.BANNER.gray();
        };
    }

    private static void scanRaids(ServerLevel level) {
        // No public iterator on Raids — group loaded raiders by their current
        // raid, scoped to each online player's neighborhood. The world-border
        // bounds AABB is effectively infinite and overflows entity-section
        // lookups on Quilt/Fabric; a per-player box is the safe alternative.
        Set<Raid> seen = new HashSet<>();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            net.minecraft.world.phys.AABB box = net.minecraft.world.phys.AABB.ofSize(
                    player.position(), 192.0, 96.0, 192.0);
            for (Raider raider : level.getEntitiesOfClass(Raider.class, box)) {
                Raid raid = raider.getCurrentRaid();
                if (raid == null || raid.isOver() || raid.isStopped()) continue;
                if (!seen.add(raid)) continue;
                handleRaid(level, raid);
            }
        }
    }

    private static void handleRaid(ServerLevel level, Raid raid) {
        String key = raidKey(level, raid);
        int omen = raid.getRaidOmenLevel();

        if (WarbandConfig.raidFinaleBountyEnabled
                && omen >= FINALE_BOUNTY_MIN_OMEN
                && !FINALE_BOUNTY_DONE.contains(key)) {
            int numGroups = raid.getNumGroups(level.getDifficulty());
            // Final wave starting: groupsSpawned has hit numGroups.
            if (raid.getGroupsSpawned() >= numGroups && raid.hasFirstWaveSpawned() && !raid.isBetweenWaves()) {
                if (summonFinaleBountyHunter(level, raid)) {
                    FINALE_BOUNTY_DONE.add(key);
                }
            }
        }

        if (WarbandConfig.raidRivalInterceptEnabled
                && omen >= RIVAL_MIN_OMEN
                && !RIVALS_DONE.contains(key)
                && raid.hasFirstWaveSpawned()) {
            // Roll once per raid, on or after first-wave spawn.
            RIVALS_DONE.add(key);
            if (level.getRandom().nextInt(100) < RIVAL_BASE_CHANCE_PERCENT) {
                spawnRivalInterceptors(level, raid);
            }
        }

        if (raid.isVictory() || raid.isLoss() || raid.isStopped()) {
            FINALE_BOUNTY_DONE.remove(key);
            RIVALS_DONE.remove(key);
        }
    }

    private static boolean summonFinaleBountyHunter(ServerLevel level, Raid raid) {
        Raider anchor = null;
        for (Raider r : raid.getAllRaiders()) { if (r.isAlive()) { anchor = r; break; } }
        if (anchor == null) return false;
        IllagerFaction faction = IllagerFactionSystem.factionOrDefault(anchor);
        ServerPlayer target = level.getNearestPlayer(anchor.getX(), anchor.getY(), anchor.getZ(), 96.0, false)
                instanceof ServerPlayer sp ? sp : null;
        if (target == null) return false;
        double difficulty = Math.min(1.0, 0.75 + (raid.getRaidOmenLevel() - FINALE_BOUNTY_MIN_OMEN) * 0.05);
        boolean ok = IllagerGrudgeSystem.spawnBountyHunterFor(target, faction, difficulty);
        if (ok) {
            WarbandMod.LOGGER.info("[Warband] Raid finale bounty hunter summoned ({} faction) for {}",
                    faction.name(), target.getName().getString());
        }
        return ok;
    }

    private static void spawnRivalInterceptors(ServerLevel level, Raid raid) {
        // Pick a raider as anchor for the rival faction choice.
        Raider anchor = null;
        for (Raider r : raid.getAllRaiders()) {
            if (r.isAlive()) { anchor = r; break; }
        }
        if (anchor == null) return;
        IllagerFaction raidersFaction = IllagerFactionSystem.factionOrDefault(anchor);
        IllagerFaction rival = raidersFaction.rival();
        if (rival == raidersFaction) return; // self-rival factions don't intercept

        BlockPos center = anchor.blockPosition();
        int size = 2 + level.getRandom().nextInt(2);
        Raider firstSpawn = null;
        for (int i = 0; i < size; i++) {
            BlockPos pos = center.offset(level.getRandom().nextInt(17) - 8, 0, level.getRandom().nextInt(17) - 8);
            EntityType<? extends Mob> type = i == 0 ? EntityTypes.VINDICATOR : EntityTypes.PILLAGER;
            Mob spawned = type.spawn(level, pos, EntitySpawnReason.EVENT);
            if (!(spawned instanceof Raider rivalRaider)) continue;
            IllagerFactionSystem.setFaction(rivalRaider, rival);
            rivalRaider.setTarget(anchor);
            if (firstSpawn == null) firstSpawn = rivalRaider;
        }
        if (firstSpawn != null) {
            firstSpawn.setCustomName(WarbandText.titleOfFaction("warband.title.rival_skirmisher",
                    "Rival Skirmisher", rival));
            broadcastNear(level, raid, Component.translatableWithFallback("warband.rival.intercept_raid",
                    "Rivals from the %s move to intercept the raid.", rival.displayName()));
        }
    }

    private static void broadcastNear(ServerLevel level, Raid raid, Component message) {
        BlockPos found = null;
        for (Raider r : raid.getAllRaiders()) { if (r.isAlive()) { found = r.blockPosition(); break; } }
        if (found == null) return;
        final BlockPos center = found;
        for (net.minecraft.server.level.ServerPlayer p : level.getPlayers(p -> p.blockPosition().distSqr(center) < 96 * 96)) {
            p.sendSystemMessage(message, true);
        }
    }

    private static String raidKey(ServerLevel level, Raid raid) {
        int id = level.getRaids().getId(raid).orElse(-1);
        return level.dimension().registry() + "#" + id;
    }
}
