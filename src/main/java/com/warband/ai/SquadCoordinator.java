package com.warband.ai;

import com.warband.ai.goal.BreakLosGoal;
import com.warband.ai.goal.BlazeHoverGoal;
import com.warband.ai.goal.CallBackupGoal;
import com.warband.ai.goal.CeilingCrawlGoal;
import com.warband.ai.goal.CreeperStalkGoal;
import com.warband.ai.goal.CreeperBreachGoal;
import com.warband.ai.goal.ClimbToTargetGoal;
import com.warband.ai.goal.DreadAvoidGoal;
import com.warband.ai.goal.EndermanDisruptGoal;
import com.warband.ai.goal.ExtendedMobTacticGoal;
import com.warband.ai.goal.FlankGoal;
import com.warband.ai.goal.FrostWalkerGoal;
import com.warband.ai.goal.HoglinStampedeGoal;
import com.warband.ai.goal.IllagerCommandGoal;
import com.warband.ai.goal.IllagerDoctrineGoal;
import com.warband.ai.goal.IllagerRaidAssaultGoal;
import com.warband.ai.goal.InvestigateLastKnownGoal;
import com.warband.ai.goal.KiteGoal;
import com.warband.ai.goal.PhantomHarassGoal;
import com.warband.ai.goal.SeekShelterGoal;
import com.warband.ai.goal.SiegeMineGoal;
import com.warband.ai.goal.SkeletonPerchGoal;
import com.warband.ai.goal.PiglinSocialGoal;
import com.warband.ai.goal.PressureUnreachableGoal;
import com.warband.ai.goal.RegroupGoal;
import com.warband.ai.goal.RangedRepositionGoal;
import com.warband.ai.goal.RetreatWhenLowGoal;
import com.warband.ai.goal.SkeletonSmokeGoal;
import com.warband.ai.goal.SlimeSurgeGoal;
import com.warband.ai.goal.SquadTargetGoal;
import com.warband.ai.goal.SpiderLeapGoal;
import com.warband.ai.goal.SpiderWebGoal;
import com.warband.ai.goal.StickyPathGoal;
import com.warband.ai.goal.WaterCommitGoal;
import com.warband.ai.goal.SuspicionPauseGoal;
import com.warband.ai.goal.WarbandDoorGoal;
import com.warband.ai.goal.WarbandGoal;
import com.warband.ai.goal.WitchSupportGoal;
import com.warband.ai.goal.ZombieHordeGoal;
import com.warband.ai.goal.ZombiePackGoal;
import com.warband.compat.IllagerKinds;
import com.warband.compat.RaidCompat;
import com.warband.config.WarbandConfig;
import com.warband.entity.MobData;
import com.warband.entity.MobPools;
import com.warband.entity.Role;
import com.warband.entity.Tactic;
import com.warband.mixin.MobGoalSelectorAccessor;
import com.warband.spawn.SpawnDirector;
import com.warband.entity.WarbandAttachments;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Registry and throttled server tick driver for tactical squads.
 *
 * <p>Squads are runtime blackboards; mob attachments persist only the stable
 * squad id and role. On spawn/debug creation this coordinator attaches goals
 * and keeps the blackboard fresh.
 */
public final class SquadCoordinator {

    private static final Map<Integer, Squad> SQUADS = new HashMap<>();
    private static final double JOIN_RADIUS = 18.0;
    private static final double BACKUP_RADIUS = 32.0;
    private static final double SMART_SCAN_RADIUS = 96.0;
    /** Below this difficulty a mob fights to the death, only smarter mobs retreat. */
    private static final double RETREAT_MIN_DIFFICULTY = 0.35;
    /** Tactical-AI threshold, below this a mob stays vanilla. */
    private static final double SMART_MIN_DIFFICULTY = 0.20;
    /** Difficulty needed to crown a leader. */
    private static final double LEADER_MIN_DIFFICULTY = 0.40;
    private static int nextSquadId = 1;
    private static boolean spawningSquadmate;
    private static int squadTickCounter;

    private SquadCoordinator() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!WarbandConfig.squadsEnabled) return;
            if (++squadTickCounter < 2) return;
            squadTickCounter = 0;

            Iterator<Squad> iterator = SQUADS.values().iterator();
            while (iterator.hasNext()) {
                Squad squad = iterator.next();
                squad.tick();
                MultiplayerDirector.shareIntel(squad, SQUADS.values());
                if (squad.isEmpty()) {
                    iterator.remove();
                }
            }
        });

        // Re-attach goals to mobs loaded from disk, and first-time-stamp Enemy
        // mobs that missed their finalizeSpawn (e.g. structure-piece spawns from
        // off-thread C2ME worldgen). finalizeSpawn → ENTITY_LOAD for fresh main-
        // thread spawns, so the marker is already set and we skip.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Mob mob)) return;
            if (isDyingOrGone(mob)) return;

            if (!MobData.isStamped(mob)) {
                // Off-thread worldgen spawn caught up on main-thread load.
                SpawnDirector.tryStampLoaded(mob, level);
                return;
            }
            if (!WarbandConfig.squadsEnabled) {
                bindCoreGoals(mob, level);
                return;
            }
            if (Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.WARBAND_GOALS_BOUND))) return;

            MobData data = MobData.get(mob);
            int squadId = data.squadId();
            Squad squad;
            if (squadId != MobData.NO_SQUAD) {
                Squad existing = SQUADS.get(squadId);
                squad = existing != null ? existing : new Squad(squadId, level);
                if (existing == null) SQUADS.put(squadId, squad);
                if (squadId >= nextSquadId) nextSquadId = squadId + 1;
                squad.add(mob);
            } else {
                squad = new Squad(MobData.NO_SQUAD, level);
            }
            addGoals(mob, squad, data.role());
        });
    }


    /**
     * Adds this mob to a nearby active squad, or starts a new one if difficulty
     * warrants it and the local smart-mob cap allows it.
     */
    public static boolean assignNaturalSpawn(Mob mob, double difficulty) {
        return assignNaturalSpawn(mob, difficulty, true);
    }

    public static boolean assignNaturalSpawn(Mob mob, double difficulty, boolean spawnFormation) {
        if (isDyingOrGone(mob)) return false;
        if (!WarbandConfig.squadsEnabled || difficulty < SMART_MIN_DIFFICULTY || !underSmartCap((ServerLevel) mob.level(), mob)) {
            return false;
        }
        if (!isSmartEligible(mob)) {
            return false;
        }

        ServerLevel level = (ServerLevel) mob.level();
        if (!formsNaturalSquads(mob)) {
            if (Tactic.chooseFor(mob, difficulty, Role.NONE) == 0) return false;
            if (!alwaysSoloTactic(mob) && mob.getRandom().nextFloat() > squadChance(difficulty)) return false;
            addSoloTactics(level, mob, difficulty);
            return true;
        }
        if (mob.getRandom().nextFloat() > squadChance(difficulty)) {
            return false;
        }

        Squad squad = shouldJoinExisting(mob, difficulty)
                ? nearestJoinableSquad(level, mob.blockPosition(), mob)
                : null;
        boolean newSquad = false;
        if (squad == null) {
            squad = new Squad(nextSquadId++, level);
            SQUADS.put(squad.id(), squad);
            newSquad = true;
        }

        Role role = chooseRole(mob, squad.members().size(), difficulty);
        addMob(squad, mob, role, difficulty);
        if (newSquad && spawnFormation) {
            spawnNaturalSquadmates(squad, mob, difficulty);
        }
        return true;
    }

    public static boolean isSpawningSquadmate() {
        return spawningSquadmate;
    }

    public static Squad createDebugSquad(ServerLevel level, BlockPos origin, double difficulty) {
        Squad squad = new Squad(nextSquadId++, level);
        SQUADS.put(squad.id(), squad);

        int size = Math.min(WarbandConfig.maxSquadSize, 3 + (int) Math.round(difficulty * 3.0));
        for (int i = 0; i < size; i++) {
            BlockPos pos = origin.offset((i % 3) - 1, 0, 2 + (i / 3));
            Zombie zombie = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.COMMAND);
            if (zombie == null) continue;

            Role role = switch (i) {
                case 0 -> Role.LEADER;
                case 1 -> Role.BRUISER;
                case 2 -> Role.SKIRMISHER;
                default -> Role.BRUISER;
            };
            SpawnDirector.stamp(zombie, difficulty, role, squad.id());
            addGoals(zombie, squad, role);
            squad.add(zombie);
        }
        return squad;
    }

    public static Squad createSquad(ServerLevel level, List<Mob> mobs, double difficulty) {
        Squad squad = new Squad(nextSquadId++, level);
        SQUADS.put(squad.id(), squad);

        for (int i = 0; i < mobs.size(); i++) {
            Mob mob = mobs.get(i);
            if (mob == null || isDyingOrGone(mob)) continue;
            Role role = i == 0 && difficulty >= 0.45 ? Role.LEADER : chooseRole(mob, i, difficulty);
            addMob(squad, mob, role, difficulty);
        }
        return squad;
    }

    /**
     * Make a mob the commander of a squad: guarantees squad membership, the
     * {@code ILLAGER_COMMAND} tactic and its goal kit, and a kite goal so the
     * commander fights from behind the line. This is what makes a Warmarshal the
     * <i>smartest</i> illager in its garrison, not merely the strongest.
     */
    public static void makeCommander(Mob mob, double difficulty) {
        if (isDyingOrGone(mob)) return;
        if (!WarbandConfig.squadsEnabled || !(mob.level() instanceof ServerLevel level)) return;

        Squad squad = SQUADS.get(MobData.get(mob).squadId());
        if (squad == null || squad.isEmpty()) {
            squad = nearestJoinableSquad(level, mob.blockPosition(), mob);
        }
        if (squad == null) {
            squad = new Squad(nextSquadId++, level);
            SQUADS.put(squad.id(), squad);
        }

        int tactics = MobData.get(mob).tactics() | Tactic.ILLAGER_COMMAND.bit();
        MobData.set(mob, new MobData((float) difficulty, Role.LEADER, squad.id(), tactics));
        addGoals(mob, squad, Role.LEADER);
        // A commander directs from behind the line, kite, never facetank.
        ((MobGoalSelectorAccessor) mob).warband$goalSelector().addGoal(2, new KiteGoal(mob, squad));
        squad.add(mob);
    }

    /**
     * Cue every other active squad within {@link #BACKUP_RADIUS} of {@code near}
     * to investigate. Lets a squad that is *not* yet engaged drift toward a
     * neighboring squad's fight instead of each squad fighting in isolation.
     */
    public static void broadcastDistress(Squad origin, BlockPos near) {
        for (Squad other : SQUADS.values()) {
            if (other == origin || other.isEmpty()) continue;
            if (other.level() != origin.level()) continue;
            if (other.target() != null) continue;
            if (other.center().distanceToSqr(Vec3.atCenterOf(near)) > BACKUP_RADIUS * BACKUP_RADIUS) continue;
            other.alertTo(near);
        }
    }

    public static boolean callBackup(Squad squad, BlockPos near) {
        int cap = effectiveMaxSquadSize(squad.level(), near);
        if (squad.members().size() >= cap) return false;

        AABB box = AABB.ofSize(Vec3.atCenterOf(near), BACKUP_RADIUS * 2.0, BACKUP_RADIUS, BACKUP_RADIUS * 2.0);
        List<Mob> candidates = squad.level().getEntitiesOfClass(Mob.class, box, mob -> {
            MobData data = MobData.get(mob);
            return data.squadId() != squad.id()
                    && canRecruitBackup(squad, mob)
                    && (!data.inSquad() || !isActiveSquad(data.squadId()));
        });

        // Only one backup mob per call, drip-feed, never a flood.
        if (candidates.isEmpty()) return false;
        double difficulty = squad.members().isEmpty() ? 0.5 : MobData.get(squad.members().getFirst()).difficulty();
        Mob recruit = candidates.getFirst();
        Role role = chooseRole(recruit, squad.members().size(), difficulty);
        addMob(squad, recruit, role, Math.max(difficulty, MobData.get(recruit).difficulty()));
        return true;
    }

    public static int activeSquads() {
        return SQUADS.size();
    }

    /** Attach goals for a stamped solo mob so fresh spawns match loaded mobs. */
    public static void bindStampedSolo(Mob mob, ServerLevel level) {
        if (isDyingOrGone(mob)) return;
        if (!MobData.isStamped(mob)) return;
        addGoals(mob, new Squad(MobData.NO_SQUAD, level), Role.NONE);
    }

    /** Bind non-squad capabilities without reviving role/squad tactics. */
    private static void bindCoreGoals(Mob mob, ServerLevel level) {
        if (Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.WARBAND_GOALS_BOUND))) return;

        MobGoalSelectorAccessor accessor = (MobGoalSelectorAccessor) mob;
        accessor.warband$goalSelector().removeAllGoals(goal -> goal instanceof WarbandGoal);
        accessor.warband$targetSelector().removeAllGoals(goal -> goal instanceof WarbandGoal);

        Squad solo = new Squad(MobData.NO_SQUAD, level);
        int core = Tactic.coreAntiCheeseFor(mob, MobData.get(mob).difficulty());
        if (Tactic.has(core, Tactic.CREEPER_BREACH) && WarbandConfig.tacticEnabled(Tactic.CREEPER_BREACH)) {
            accessor.warband$goalSelector().addGoal(0, new CreeperBreachGoal(mob, solo));
        }
        if (Tactic.has(core, Tactic.SIEGE_MINE) && WarbandConfig.tacticEnabled(Tactic.SIEGE_MINE)) {
            accessor.warband$goalSelector().addGoal(0, new SiegeMineGoal(mob, solo));
        }
        if (mob instanceof Zombie || mob instanceof AbstractSkeleton) {
            accessor.warband$goalSelector().addGoal(1, new SeekShelterGoal(mob));
        }
        accessor.warband$goalSelector().addGoal(-1, new DreadAvoidGoal(mob));
        accessor.warband$goalSelector().addGoal(2, new ClimbToTargetGoal(mob));
        if (canOpenDoors(mob) && WarbandDoorGoal.enableDoorPathing(mob)) {
            accessor.warband$goalSelector().addGoal(2, new WarbandDoorGoal(mob));
        }
        mob.setAttached(WarbandAttachments.WARBAND_GOALS_BOUND, true);
    }

    /** Lookup for perception hooks (e.g. arrow-miss alerts). */
    public static Squad getSquad(int id) {
        return SQUADS.get(id);
    }

    public static List<String> debugSquadLines(ServerLevel level, BlockPos pos) {
        List<String> lines = new ArrayList<>();
        for (Squad squad : SQUADS.values()) {
            if (squad.level() != level || squad.isEmpty()) continue;
            if (squad.center().distanceToSqr(Vec3.atCenterOf(pos)) > SMART_SCAN_RADIUS * SMART_SCAN_RADIUS) continue;
            String lastKnown = squad.lastKnownPos() == null
                    ? "none"
                    : squad.lastKnownPos().getX() + " " + squad.lastKnownPos().getY() + " " + squad.lastKnownPos().getZ();
            lines.add(String.format("squad=%d members=%d morale=%.2f routing=%s lastKnown=%s threat=%s",
                    squad.id(), squad.members().size(), squad.morale(), squad.isRouting(),
                    lastKnown, MultiplayerDirector.threatSummary(squad)));
        }
        if (lines.isEmpty()) {
            lines.add("No active squads nearby.");
        }
        return lines;
    }

    private static final int MAX_EXTRA_PLAYERS = 8;

    /**
     * Squad-size cap, raised above {@code maxSquadSize} for players sharing the
     * region. Multiplayer scales encounter <i>volume</i> here, the per-mob
     * difficulty scalar still caps at 1.0.
     */
    private static int effectiveMaxSquadSize(ServerLevel level, BlockPos near) {
        int base = WarbandConfig.maxSquadSize;
        if (WarbandConfig.squadPlayerBonus <= 0) return base;
        int extra = Math.min(MAX_EXTRA_PLAYERS, MultiplayerDirector.extraPartyPlayers(level, near));
        return base + WarbandConfig.squadPlayerBonus * extra;
    }

    private static void addMob(Squad squad, Mob mob, Role role, double difficulty) {
        if (isDyingOrGone(mob)) return;
        SpawnDirector.stamp(mob, difficulty, role, squad.id());
        addGoals(mob, squad, role);
        squad.add(mob);
    }

    private static void addSoloTactics(ServerLevel level, Mob mob, double difficulty) {
        if (isDyingOrGone(mob)) return;
        SpawnDirector.stamp(mob, difficulty);
        addGoals(mob, new Squad(MobData.NO_SQUAD, level), Role.NONE);
    }

    private static void addGoals(Mob mob, Squad squad, Role role) {
        if (isDyingOrGone(mob)) return;
        MobGoalSelectorAccessor accessor = (MobGoalSelectorAccessor) mob;
        accessor.warband$goalSelector().removeAllGoals(goal -> goal instanceof WarbandGoal);
        accessor.warband$targetSelector().removeAllGoals(goal -> goal instanceof WarbandGoal);
        if (role != Role.NONE) {
            accessor.warband$targetSelector().addGoal(0, new SquadTargetGoal(mob, squad));
        }

        boolean simple = isSimpleFamily(mob);
        if (role != Role.NONE) {
            // Retreat is reserved for intelligent humanoid mobs (illagers,
            // piglins, drowned). Other monsters fight to the death, so a
            // wounded creeper/blaze/skeleton doesn't break fantasy by fleeing.
            if (canRetreat(mob) && MobData.get(mob).difficulty() >= RETREAT_MIN_DIFFICULTY) {
                accessor.warband$goalSelector().addGoal(2, new RetreatWhenLowGoal(mob, squad));
            }
            accessor.warband$goalSelector().addGoal(3, new RegroupGoal(mob, squad));
            if (canCallBackup(mob)) {
                accessor.warband$goalSelector().addGoal(4, new CallBackupGoal(mob, squad));
            }
            accessor.warband$goalSelector().addGoal(6, new InvestigateLastKnownGoal(mob, squad));

            // Simple-family mobs (basic zombies, spiders, slimes, hoglins) follow
            // the squad target and regroup, but skip the more nuanced kite/flank
            // behaviors, those belong to smarter mobs. Also skip when the mob is
            // riding something (skeleton on spider, baby zombie on chicken): the
            // rider's kite/breakLos would steer the mount away from the player.
            if (!simple && !mob.isPassenger()) {
                if (role == Role.SKIRMISHER || role == Role.MARKSMAN || role == Role.SUPPORT) {
                    accessor.warband$goalSelector().addGoal(2, new KiteGoal(mob, squad));
                    accessor.warband$goalSelector().addGoal(3, new BreakLosGoal(mob, squad));
                } else {
                    accessor.warband$goalSelector().addGoal(5, new FlankGoal(mob, squad));
                }
            }
        }

        MobData data = MobData.get(mob);
        if (hasEnabledTactic(data, Tactic.SPIDER_WEB)) {
            accessor.warband$goalSelector().addGoal(3, new SpiderWebGoal(mob, squad));
        }
        if ((mob instanceof Spider || mob instanceof CaveSpider) && hasEnabledTactic(data, Tactic.SPIDER_WEB)) {
            accessor.warband$goalSelector().addGoal(3, new SpiderLeapGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.STICKY_PATH)) {
            accessor.warband$goalSelector().addGoal(7, new StickyPathGoal(mob, squad));
        }
        // Stormie's Spiders owns realistic climb/ceiling pathing. Defer to it
        // when present so the two systems don't fight over the same flags.
        if (hasEnabledTactic(data, Tactic.CEILING_CRAWL) && !com.warband.compat.StormieSpidersCompat.isLoaded()) {
            accessor.warband$goalSelector().addGoal(4, new CeilingCrawlGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.FROST_WALKER)) {
            accessor.warband$goalSelector().addGoal(7, new FrostWalkerGoal(mob));
        }
        if (hasEnabledTactic(data, Tactic.WATER_COMMIT)) {
            accessor.warband$goalSelector().addGoal(4, new WaterCommitGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.PRESSURE_UNREACHABLE)
                || hasEnabledTactic(data, Tactic.LEAP_UNREACHABLE)) {
            accessor.warband$goalSelector().addGoal(5, new PressureUnreachableGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.RANGED_REPOSITION)) {
            accessor.warband$goalSelector().addGoal(4, new RangedRepositionGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.SKELETON_SMOKE) && mob instanceof AbstractSkeleton) {
            accessor.warband$goalSelector().addGoal(2, new SkeletonSmokeGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.CREEPER_STALK)) {
            accessor.warband$goalSelector().addGoal(4, new CreeperStalkGoal(mob, squad));
        }
        // Breaching sits above the stalk: a creeper that cannot reach you should stop
        // circling for a better angle and start removing the wall.
        if (hasEnabledTactic(data, Tactic.CREEPER_BREACH)) {
            accessor.warband$goalSelector().addGoal(0, new CreeperBreachGoal(mob, squad));
        }
        // The goal itself proves that the target is unreachable before starting.
        // It must outrank vanilla melee movement once that proof succeeds; placing it
        // below melee leaves the stalled attack goal holding MOVE forever.
        if (hasEnabledTactic(data, Tactic.SIEGE_MINE)) {
            accessor.warband$goalSelector().addGoal(0, new SiegeMineGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.ZOMBIE_HORDE)) {
            // Priority 3 so the encircle preempts vanilla melee approach until
            // the mob is actually in striking range (canUse gates distance >= 3).
            accessor.warband$goalSelector().addGoal(3, new ZombieHordeGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.ENDERMAN_DISRUPT) && mob instanceof EnderMan) {
            accessor.warband$goalSelector().addGoal(3, new EndermanDisruptGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.PIGLIN_SOCIAL)) {
            accessor.warband$goalSelector().addGoal(3, new PiglinSocialGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.BLAZE_HOVER)) {
            accessor.warband$goalSelector().addGoal(3, new BlazeHoverGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.WITCH_SUPPORT)) {
            accessor.warband$goalSelector().addGoal(3, new WitchSupportGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.SLIME_SURGE)) {
            accessor.warband$goalSelector().addGoal(4, new SlimeSurgeGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.HOGLIN_STAMPEDE)) {
            accessor.warband$goalSelector().addGoal(4, new HoglinStampedeGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.ILLAGER_COMMAND)) {
            accessor.warband$goalSelector().addGoal(1, new IllagerRaidAssaultGoal(mob, squad));
            accessor.warband$goalSelector().addGoal(2, new IllagerDoctrineGoal(mob, squad));
            accessor.warband$goalSelector().addGoal(3, new IllagerCommandGoal(mob, squad));
        }
        if (hasEnabledTactic(data, Tactic.PHANTOM_HARASS)) {
            accessor.warband$goalSelector().addGoal(3, new PhantomHarassGoal(mob, squad));
        }
        if (WarbandConfig.extendedMobTacticsEnabled
                && (hasEnabledTactic(data, Tactic.GUARDIAN_SURGE)
                || hasEnabledTactic(data, Tactic.SHULKER_LOCKDOWN)
                || hasEnabledTactic(data, Tactic.GHAST_REPOSITION)
                || hasEnabledTactic(data, Tactic.CAVE_SPIDER_AMBUSH)
                || hasEnabledTactic(data, Tactic.RAVAGER_BREAKER)
                || hasEnabledTactic(data, Tactic.BOGGED_BACKDASH)
                || hasEnabledTactic(data, Tactic.STRAY_JUMP_SHOT)
                || hasEnabledTactic(data, Tactic.WARDEN_PRESSURE))) {
            accessor.warband$goalSelector().addGoal(3, new ExtendedMobTacticGoal(mob, squad));
        }
        // Universal across all stamped undead: at dawn, head for shade before
        // the first burn tick. canUse() filters out sun-immune subclasses
        // (husk, wither skeleton) via isSunSensitive.
        if (mob instanceof Zombie || mob instanceof AbstractSkeleton) {
            accessor.warband$goalSelector().addGoal(1, new SeekShelterGoal(mob));
        }
        // Universal on every stamped mob: get away from imminent detonations and
        // wardens. Priority 1 so it interrupts Warband's own positioning tactics —
        // no tactic is worth standing in a blast for.
        accessor.warband$goalSelector().addGoal(-1, new DreadAvoidGoal(mob));
        // Universal: use a ladder you are already standing on. No goal flags, so it
        // layers under the attack goal's pathing rather than fighting it.
        accessor.warband$goalSelector().addGoal(2, new ClimbToTargetGoal(mob));
        // The visible half of a suspicious state. Priority 2, deliberately *below* the
        // other two universal priority-1 MOVE goals: equal priorities cannot preempt
        // each other, so at 1 a mob frozen in a suspicious stare could not flee a
        // swelling creeper or run from the sunrise. Staring is the least urgent of the
        // three. Still above the vanilla stroll goals it exists to interrupt, and it
        // requires no target, so it never competes with a vanilla attack goal at 2.
        accessor.warband$goalSelector().addGoal(2, new SuspicionPauseGoal(mob));
        // Doors, for the mobs already treated as intelligent enough to retreat.
        // Requires opening up door pathing first, or the goal can never trigger —
        // it only fires when the current path already routes onto a door block.
        // Also flagless, so it never competes for movement.
        if (canOpenDoors(mob) && WarbandDoorGoal.enableDoorPathing(mob)) {
            accessor.warband$goalSelector().addGoal(2, new WarbandDoorGoal(mob));
            // Logged on attach so a silent failure is diagnosable: "bound but never
            // fired" is a very different bug from "never bound".
            com.warband.WarbandDebug.event("DOOR_GOAL_BOUND", mob, null);
        }
        // Passive horde-formation: out-of-combat zombies drift toward each
        // other so wanderers naturally cluster instead of starving solo.
        // Low priority so it never overrides combat/shelter behaviors.
        if (mob instanceof Zombie) {
            accessor.warband$goalSelector().addGoal(8, new ZombiePackGoal(mob));
        }
        // Passive perch: out-of-combat skeletons climb to nearby high ground at
        // dusk/night so they engage from elevation when a player wanders in.
        if (mob instanceof AbstractSkeleton) {
            accessor.warband$goalSelector().addGoal(8, new SkeletonPerchGoal(mob));
        }
        // Passive rain shelter: out-of-combat spiders dislike being out in the
        // rain and path to the nearest covered tile.
        if (mob instanceof Spider) {
            accessor.warband$goalSelector().addGoal(8, new com.warband.ai.goal.SpiderRainShelterGoal(mob));
        }
        // Natural jockey acquisition: smart-enough mobs mount a suitable wild
        // animal when out of combat (skeleton+spider, baby zombie+chicken).
        if (com.warband.ai.goal.MountJockeyGoal.isEligibleRider(mob)) {
            accessor.warband$goalSelector().addGoal(7, new com.warband.ai.goal.MountJockeyGoal(
                    mob, com.warband.ai.goal.MountJockeyGoal.mountTypeFor(mob)));
        }
        mob.setAttached(WarbandAttachments.WARBAND_GOALS_BOUND, true);
    }

    private static boolean hasEnabledTactic(MobData data, Tactic tactic) {
        return data.hasTactic(tactic) && WarbandConfig.tacticEnabled(tactic);
    }

    /**
     * The "simple" family: mobs whose AI we keep deliberately blunt, they follow
     * the squad target, regroup, and (if applicable) call backup, but skip kite,
     * flank, breakLOS, and retreat. Smarter mobs (skeletons, drowned, piglins,
     * illagers, witches, blazes, endermen) get the full kit.
     */
    /**
     * Only intelligent humanoid mobs retreat when wounded or outmatched. Other
     * hostiles (skeletons, creepers, blazes, witches, endermen, phantoms, etc.)
     * fight to the death — fleeing reads as out-of-character for them.
     */
    private static boolean canRetreat(Mob mob) {
        return mob instanceof Raider
                || mob instanceof AbstractPiglin
                || mob instanceof Drowned;
    }

    /**
     * Who understands a door handle: the same intelligent-humanoid set that knows to
     * retreat, plus modded illagers via compat. Zombies are excluded on purpose —
     * breaking the door down is their thing.
     */
    private static boolean canOpenDoors(Mob mob) {
        if (mob instanceof Zombie) return false;
        return canRetreat(mob) || IllagerKinds.isIllagerLike(mob);
    }

    private static boolean isSimpleFamily(Mob mob) {
        if (mob instanceof Drowned) return false;
        return mob instanceof Zombie
                || mob instanceof Spider
                || mob instanceof Slime
                || mob instanceof MagmaCube
                || mob instanceof Hoglin
                || mob instanceof Zoglin;
    }

    /** Ring the formation spawns into, so members start spread out rather than stacked. */
    private static final int FORMATION_MIN_RADIUS = 3;
    private static final int FORMATION_MAX_RADIUS = 7;
    /** How far up/down to look for solid footing at a formation slot. */
    private static final int FORMATION_VERTICAL_SEARCH = 4;

    /** Formations start appearing here, and become reliable at {@link #FORMATION_FULL_DIFFICULTY}. */
    private static final double FORMATION_MIN_DIFFICULTY = 0.35;
    private static final double FORMATION_FULL_DIFFICULTY = 0.60;

    private static void spawnNaturalSquadmates(Squad squad, Mob anchor, double difficulty) {
        int cap = effectiveMaxSquadSize(squad.level(), anchor.blockPosition());
        if (difficulty < FORMATION_MIN_DIFFICULTY || squad.members().size() >= cap) return;

        // Faded in rather than switched on at a single threshold. A hard gate at
        // 0.45 meant crowds appeared all at once, in the same difficulty band where
        // gear and leaders also unlocked, which is what made progression read as a
        // staircase. Also keeps total mob volume lower through the early game.
        double formationChance = Math.min(1.0,
                (difficulty - FORMATION_MIN_DIFFICULTY) / (FORMATION_FULL_DIFFICULTY - FORMATION_MIN_DIFFICULTY));
        if (formationChance < 1.0 && anchor.getRandom().nextDouble() >= formationChance) return;

        int baseSize = 2 + (int) Math.floor(difficulty * 3.0);
        if (isZombieFamily(anchor)) {
            baseSize += 1;
        }
        int desiredSize = Math.min(cap, baseSize);
        int toSpawn = desiredSize - squad.members().size();
        for (int i = 0; i < toSpawn; i++) {
            if (!underSmartCap(squad.level(), anchor)) break;

            // Placed around a ring at distinct bearings instead of inside a 7x7 box
            // at the anchor's own Y. The old box put members on top of each other
            // and, on any uneven ground, inside terrain — they were then shoved out
            // into a single heap, which is what read to players as a horde piling
            // into one corner and never reaching them.
            BlockPos pos = formationSlot(squad.level(), anchor, i, toSpawn);
            if (pos == null) continue;
            Mob spawned = spawnSameType(anchor, pos);
            if (spawned == null) continue;

            Role role = chooseRole(spawned, squad.members().size(), difficulty);
            addMob(squad, spawned, role, difficulty);
            TacticalEffects.signal(squad.level(), spawned.position());
        }
    }

    /**
     * A standable tile on a ring around the anchor, at this member's own bearing.
     * Returns null when the slot is blocked, so a squadmate is skipped rather than
     * spawned inside a wall.
     */
    private static BlockPos formationSlot(ServerLevel level, Mob anchor, int index, int total) {
        double spread = Math.max(1, total);
        double angle = (index * (Math.PI * 2.0)) / spread + anchor.getRandom().nextDouble() * 0.4;
        int radius = FORMATION_MIN_RADIUS
                + anchor.getRandom().nextInt(FORMATION_MAX_RADIUS - FORMATION_MIN_RADIUS + 1);
        BlockPos origin = anchor.blockPosition();
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int step = 0; step <= FORMATION_VERTICAL_SEARCH * 2; step++) {
            int dy = (step + 1) / 2;
            if (step % 2 == 1) dy = -dy;
            cursor.set(x, origin.getY() + dy, z);
            if (isStandable(level, cursor)) return cursor.immutable();
        }
        return null;
    }

    /** Two blocks of clearance on solid footing — room for a humanoid to exist. */
    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        return !level.getBlockState(pos.below()).isAir();
    }

    @SuppressWarnings("unchecked")
    private static Mob spawnSameType(Mob anchor, BlockPos pos) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) anchor.getType();
        spawningSquadmate = true;
        try {
            return type.spawn((ServerLevel) anchor.level(), pos, EntitySpawnReason.REINFORCEMENT);
        } finally {
            spawningSquadmate = false;
        }
    }

    private static Squad nearestJoinableSquad(ServerLevel level, BlockPos pos, Mob mob) {
        int cap = effectiveMaxSquadSize(level, pos);
        Squad best = null;
        double bestDist = JOIN_RADIUS * JOIN_RADIUS;
        for (Squad squad : SQUADS.values()) {
            if (squad.level() != level || squad.members().size() >= cap) continue;
            if (squad.members().isEmpty() || !sameSquadFamily(squad.members().getFirst(), mob)) continue;
            double dist = squad.center().distanceToSqr(Vec3.atCenterOf(pos));
            if (dist < bestDist) {
                best = squad;
                bestDist = dist;
            }
        }
        return best;
    }

    private static Role chooseRole(Mob mob, int index, double difficulty) {
        if (RaidCompat.isPatrolCaptain(mob)) return Role.LEADER;
        if (index == 0 && difficulty >= LEADER_MIN_DIFFICULTY) return Role.LEADER;
        // Consolidated roles: SUPPORT only when a compat layer flags it (e.g. witches
        // in illager invasions); MARKSMAN for anything ranged; otherwise BRUISER.
        // SKIRMISHER is no longer assigned procedurally, its kit collapses into
        // MARKSMAN, which shares the same kite/breakLOS goal block.
        if (IllagerKinds.isSupport(mob)) return Role.SUPPORT;
        if (mob instanceof RangedAttackMob) return Role.MARKSMAN;
        return Role.BRUISER;
    }

    private static boolean isActiveSquad(int squadId) {
        return SQUADS.containsKey(squadId);
    }

    private static boolean isSmartEligible(Mob mob) {
        return mob instanceof Zombie
                || mob instanceof Drowned
                || mob instanceof ZombifiedPiglin
                || mob instanceof AbstractSkeleton
                || mob instanceof Spider
                || mob instanceof Creeper
                || mob instanceof EnderMan
                || mob instanceof AbstractPiglin
                || mob instanceof Blaze
                || mob instanceof Witch
                || mob instanceof Slime
                || mob instanceof MagmaCube
                || mob instanceof Hoglin
                || mob instanceof Zoglin
                || IllagerKinds.isIllagerLike(mob)
                || mob instanceof Phantom
                || mob instanceof Guardian
                || mob instanceof Shulker
                || mob instanceof Ghast
                || mob instanceof CaveSpider
                || mob instanceof Ravager
                || mob instanceof Warden
                // Opt-in only: an explicit customMobPools entry. Deliberately not
                // "has any subject" — that would sweep in every modded RangedAttackMob.
                || MobPools.isConfigured(mob);
    }

    /**
     * Pools whose members form and share squads. Derived from {@link Tactic.Subject}
     * rather than a chain of {@code instanceof} checks, so a modded mob mapped into
     * a pool via {@code customMobPools} squads up with its vanilla counterparts.
     */
    private static final EnumSet<Tactic.Subject> SQUAD_FAMILIES = EnumSet.of(
            Tactic.Subject.ZOMBIE_FAMILY,
            Tactic.Subject.ABSTRACT_SKELETON,
            Tactic.Subject.SPIDER,
            Tactic.Subject.ABSTRACT_PIGLIN,
            Tactic.Subject.HOGLIN_FAMILY,
            Tactic.Subject.ILLAGER_LIKE);

    /** Skeletons form squads but never shout for reinforcements. */
    private static final EnumSet<Tactic.Subject> BACKUP_FAMILIES = EnumSet.of(
            Tactic.Subject.ZOMBIE_FAMILY,
            Tactic.Subject.SPIDER,
            Tactic.Subject.ABSTRACT_PIGLIN,
            Tactic.Subject.HOGLIN_FAMILY,
            Tactic.Subject.ILLAGER_LIKE);

    private static EnumSet<Tactic.Subject> squadFamilies(Mob mob) {
        EnumSet<Tactic.Subject> families = Tactic.subjectsFor(mob);
        families.retainAll(SQUAD_FAMILIES);
        return families;
    }

    private static boolean formsNaturalSquads(Mob mob) {
        return !squadFamilies(mob).isEmpty();
    }

    private static boolean alwaysSoloTactic(Mob mob) {
        return mob instanceof EnderMan;
    }

    private static boolean shouldJoinExisting(Mob mob, double difficulty) {
        if (!canCallBackup(mob) || difficulty < 0.35) return false;
        return mob.getRandom().nextFloat() < 0.45f;
    }

    private static double squadChance(double difficulty) {
        // Ramp faster: full-strength chance hits at diff ~0.70 (was 0.90).
        double t = Math.max(0.0, Math.min(1.0, (difficulty - SMART_MIN_DIFFICULTY) / 0.50));
        return WarbandConfig.naturalSquadChanceMin
                + (WarbandConfig.naturalSquadChanceMax - WarbandConfig.naturalSquadChanceMin) * t;
    }

    private static boolean canCallBackup(Mob mob) {
        EnumSet<Tactic.Subject> subjects = Tactic.subjectsFor(mob);
        subjects.retainAll(BACKUP_FAMILIES);
        return !subjects.isEmpty();
    }

    private static boolean canRecruitBackup(Squad squad, Mob candidate) {
        return !squad.members().isEmpty()
                && canCallBackup(candidate)
                && sameSquadFamily(squad.members().getFirst(), candidate);
    }

    /** Two mobs share a squad when they share a behaviour pool, else only when identical. */
    private static boolean sameSquadFamily(Mob a, Mob b) {
        EnumSet<Tactic.Subject> familiesA = squadFamilies(a);
        EnumSet<Tactic.Subject> familiesB = squadFamilies(b);
        if (familiesA.isEmpty() && familiesB.isEmpty()) {
            return a.getType() == b.getType();
        }
        return !java.util.Collections.disjoint(familiesA, familiesB);
    }

    private static boolean isZombieFamily(Mob mob) {
        return mob instanceof Zombie || mob instanceof Drowned || mob instanceof ZombifiedPiglin;
    }

    private static boolean underSmartCap(ServerLevel level, Mob mob) {
        if (WarbandConfig.multiplayerFeaturesEnabled) {
            return MultiplayerDirector.underSmartBudget(level, mob.blockPosition());
        }
        Player nearest = level.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), SMART_SCAN_RADIUS, false);
        if (nearest == null) return true;
        AABB box = AABB.ofSize(nearest.position(), SMART_SCAN_RADIUS * 2.0, SMART_SCAN_RADIUS, SMART_SCAN_RADIUS * 2.0);
        List<Mob> smart = new ArrayList<>(level.getEntitiesOfClass(Mob.class, box, SquadCoordinator::hasWarbandAi));
        return smart.size() < WarbandConfig.maxSmartMobsPerPlayer;
    }

    private static boolean hasWarbandAi(Mob mob) {
        MobData data = MobData.get(mob);
        return data.inSquad() || data.tactics() != 0;
    }

    private static boolean isDyingOrGone(Mob mob) {
        return mob.isRemoved() || mob.isDeadOrDying() || !mob.isAlive();
    }
}
