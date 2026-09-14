package com.warband.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.warband.ai.Squad;
import com.warband.ai.SquadCoordinator;
import com.warband.ai.MultiplayerDirector;
import com.warband.WarbandDebug;
import com.warband.WarbandMod;
import com.warband.config.WarbandConfig;
import com.warband.difficulty.DifficultyManager;
import com.warband.difficulty.RegionalDifficulty;
import com.warband.entity.MobData;
import com.warband.entity.Tactic;
import com.warband.entity.WarbandAttachments;
import com.warband.illager.IllagerFaction;
import com.warband.illager.IllagerGrudgeSystem;
import com.warband.mixin.MobGoalSelectorAccessor;
import com.warband.spawn.SpawnDirector;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/**
 * The {@code /warband} command. Ships {@code /warband difficulty} and
 * {@code /warband mobs}; more subcommands arrive with later phases.
 *
 * <p><b>Output here is deliberately literal, not translatable.</b> Everything the mod shows
 * a player goes through a translation key — see {@code com.warband.text.WarbandText} — but
 * this class is an operator's diagnostic readout: goal stacks, difficulty scalars, squad
 * ids, tactic bitmasks. Those lines are formatted numbers whose labels match the debug
 * event names in the logs, and keeping the two spellings identical is worth more than
 * translating them. Adding ~40 keys nobody would translate would also bury the keys that
 * matter in {@code en_us.json}.
 *
 * <p>The exception is {@code /warband intel}, which a normal player runs to read their own
 * faction standing. That output is built as components by
 * {@code IllagerGrudgeSystem.intelLines} and is fully translatable.
 */
public final class WarbandCommand {

    /** Edge length of the cube scanned by {@code /warband mobs}. */
    private static final double MOB_SCAN_SIZE = 64.0;
    private static final double MOB_DEBUG_SIZE = 24.0;

    private WarbandCommand() {
    }

    /** Register the command tree. Called from {@code onInitialize}. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("warband")
                        .then(Commands.literal("difficulty")
                                .executes(WarbandCommand::reportDifficulty))
                        .then(Commands.literal("mobs")
                                .executes(WarbandCommand::reportMobs))
                        .then(Commands.literal("mobdebug")
                                .executes(WarbandCommand::reportMobDebug))
                        .then(Commands.literal("players")
                                .executes(WarbandCommand::reportPlayers))
                        .then(Commands.literal("squads")
                                .executes(WarbandCommand::reportSquads))
                        .then(Commands.literal("encounter")
                                .executes(WarbandCommand::reportEncounter))
                        .then(Commands.literal("region")
                                .executes(WarbandCommand::reportRegion))
                        .then(Commands.literal("intel")
                                .executes(WarbandCommand::reportIntel)
                                .then(Commands.argument("target", EntityArgument.player())
                                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .executes(WarbandCommand::reportIntelTarget)))
                        .then(Commands.literal("clear")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(WarbandCommand::clearAll)
                                        .then(Commands.argument("faction", StringArgumentType.word())
                                                .executes(WarbandCommand::clearFactionCmd))))
                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(WarbandCommand::reloadConfig))
                        .then(Commands.literal("debug")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("difficulty", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(WarbandCommand::debugSpawn)))
                                .then(Commands.literal("squad")
                                        .then(Commands.argument("difficulty", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(WarbandCommand::debugSquad)))
                                .then(Commands.literal("revenge")
                                        .then(Commands.argument("difficulty", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(WarbandCommand::debugRevenge)))
                                .then(Commands.literal("bounty")
                                        .then(Commands.argument("difficulty", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(WarbandCommand::debugBounty)))
                                .then(Commands.literal("stamp")
                                        .then(Commands.argument("difficulty", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(WarbandCommand::debugStamp))))));
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        WarbandConfig.load(WarbandMod.LOGGER);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Warband] config reloaded from " + WarbandConfig.configLocation()), true);
        return 1;
    }

    private static int reportRegion(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        double raw = RegionalDifficulty.rawCellValue(level, pos);
        double finalDifficulty = DifficultyManager.getDifficulty(level, pos, source.getPlayer());
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Regional cell raw %.2f, final %.2f, known cells %d",
                raw, finalDifficulty, RegionalDifficulty.knownCells(level))), false);
        source.sendSuccess(() -> Component.literal("  map: .=0, 1=<25, 2=<50, 3=<75, 4=<95, 5=max"), false);
        for (String line : RegionalDifficulty.mapAround(level, pos, 4).split("\\n")) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    private static int reportPlayers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        for (String line : MultiplayerDirector.playerDebugLines(level, pos)) {
            source.sendSuccess(() -> Component.literal("[Warband] " + line), false);
        }
        return 1;
    }

    private static int reportSquads(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        for (String line : SquadCoordinator.debugSquadLines(level, pos)) {
            source.sendSuccess(() -> Component.literal("[Warband] " + line), false);
        }
        return 1;
    }

    private static int reportEncounter(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] encounter multiplayerMultiplier=%.2f smartBudget=%d activeSquads=%d",
                MultiplayerDirector.encounterChanceMultiplier(level, pos),
                MultiplayerDirector.effectiveSmartBudget(level, pos),
                SquadCoordinator.activeSquads())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  regional timers: increaseDelay=%ds decayDelay=%ds",
                WarbandConfig.regionalIncreaseDelaySeconds,
                WarbandConfig.regionalDecayDelaySeconds)), false);
        return 1;
    }

    /** Reports detailed state for the nearest stamped mob. */
    private static int reportMobDebug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        AABB box = AABB.ofSize(source.getPosition(), MOB_DEBUG_SIZE, MOB_DEBUG_SIZE, MOB_DEBUG_SIZE);
        Mob mob = level.getEntitiesOfClass(Mob.class, box, MobData::isStamped).stream()
                .min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(source.getPosition())))
                .orElse(null);
        if (mob == null) {
            source.sendFailure(Component.literal("[Warband] No stamped mob within " + (int) MOB_DEBUG_SIZE + " blocks."));
            return 0;
        }

        MobData data = MobData.get(mob);
        boolean goalsBound = Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.WARBAND_GOALS_BOUND));
        int goalCount = warbandGoalCount(mob);
        String target = mob.getTarget() == null ? "none" : mob.getTarget().getType().toShortString();
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] %s id=%d difficulty=%.2f role=%s squad=%d tactics=%s",
                mob.getType().toShortString(), mob.getId(), data.difficulty(), data.role(), data.squadId(), tacticNames(data))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  goalsBound=%s warbandGoals=%d target=%s health=%.1f/%.1f pos=%d %d %d",
                goalsBound, goalCount, target, mob.getHealth(), mob.getMaxHealth(),
                mob.blockPosition().getX(), mob.blockPosition().getY(), mob.blockPosition().getZ())), false);
        Squad squad = SquadCoordinator.getSquad(data.squadId());
        if (squad != null) {
            BlockPos lastKnown = squad.lastKnownPos();
            source.sendSuccess(() -> Component.literal(String.format(
                    "  squadMembers=%d morale=%.2f routing=%s lastKnown=%s",
                    squad.members().size(), squad.morale(), squad.isRouting(),
                    lastKnown == null ? "none" : lastKnown.getX() + " " + lastKnown.getY() + " " + lastKnown.getZ())), false);
        }
        return 1;
    }

    private static int reportIntel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Warband] Faction intel requires a player source. Use /warband intel <player> as an op."));
            return 0;
        }
        return emitIntel(source, player, false);
    }

    private static int reportIntelTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        return emitIntel(ctx.getSource(), target, true);
    }

    private static int emitIntel(CommandSourceStack source, ServerPlayer player, boolean named) {
        String header = named
                ? "[Warband] Faction intel for " + player.getName().getString()
                : "[Warband] Faction diplomacy / intel";
        source.sendSuccess(() -> Component.literal(header), false);
        for (Component line : IllagerGrudgeSystem.intelLines(player)) {
            source.sendSuccess(() -> Component.literal("  ").append(line), false);
        }
        return 1;
    }

    private static int clearAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        int cleared = IllagerGrudgeSystem.clearAllForPlayer(target);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Warband] Cleared " + cleared + " grudge/heat entries from "
                        + target.getName().getString() + "."), true);
        return cleared;
    }

    private static int clearFactionCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        String name = StringArgumentType.getString(ctx, "faction").toUpperCase().replace('-', '_');
        IllagerFaction faction;
        try {
            faction = IllagerFaction.valueOf(name);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Warband] Unknown faction '" + name + "'. Valid: BLACK_HORN, PALE_AXE, RED_LEDGER, ASH_BANNER, IRON_CHOIR."));
            return 0;
        }
        IllagerGrudgeSystem.clearFactionForPlayer(target, faction);
        IllagerFaction f = faction;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Warband] Cleared " + f.displayName().getString() + " grudges/heat from "
                        + target.getName().getString() + "."), true);
        return 1;
    }

    private static int reportDifficulty(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        ServerPlayer player = source.getPlayer();

        double difficulty = DifficultyManager.getDifficulty(level, pos, player);

        source.sendSuccess(() -> Component.literal(
                String.format("[Warband] Local difficulty: %.2f", difficulty)), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  mode=%s  world=%s",
                WarbandConfig.difficultyMode,
                level.getLevelData().getDifficulty().getSerializedName())), false);
        if (WarbandConfig.difficultyMode == com.warband.difficulty.DifficultyMode.REGIONAL) {
            double regionalRaw = RegionalDifficulty.difficultyAt(level, pos);
            double spawnScale = DifficultyManager.regionalSpawnScale(level, pos);
            source.sendSuccess(() -> Component.literal(String.format(
                    "  regional=%.2f  spawnScale=%.2f", regionalRaw, spawnScale)), false);
        }
        double depth = DifficultyManager.overworldDepthBonus(level, pos);
        boolean spawnSafe = DifficultyManager.insideOverworldSafeRadius(level, pos);
        if (depth > 0.0 || WarbandConfig.overworldDepthDifficultyEnabled) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "  depthBonus=%.2f  applied=%.2f%s",
                    depth, spawnSafe ? 0.0 : depth, spawnSafe ? " (spawn-safe)" : "")), false);
        }
        if (player != null) {
            Float score = player.getAttached(WarbandAttachments.PLAYER_SCORE);
            source.sendSuccess(() -> Component.literal(String.format(
                    "  your power=%.2f", score != null ? score : 0.0f)), false);
        }
        return 1;
    }

    /** Spawns a zombie at the source position, stamped at the given difficulty. */
    private static int debugSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double difficulty = DoubleArgumentType.getDouble(ctx, "difficulty");
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());

        Zombie zombie = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.COMMAND);
        if (zombie == null) {
            source.sendFailure(Component.literal("[Warband] Failed to spawn debug zombie."));
            return 0;
        }
        SpawnDirector.stamp(zombie, difficulty);
        SquadCoordinator.bindStampedSolo(zombie, level);

        double health = zombie.getMaxHealth();
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Spawned zombie at difficulty %.2f, max health %.1f (vanilla 20.0)",
                difficulty, health)), false);
        return 1;
    }

    /**
     * Re-stamps every eligible mob nearby at an explicit difficulty and rebinds its
     * goals.
     *
     * <p>The testing hook the other debug commands could not provide: {@code debug
     * spawn} and {@code debug squad} only make zombies, and anything summoned with
     * {@code /summon} is stamped at the <i>local</i> difficulty, which near world
     * spawn is 0.0 — no tactics at all. That made every difficulty-gated behaviour
     * (siege mining at 0.55, creeper breaching at 0.60, ranged tuning) impossible to
     * exercise without walking thousands of blocks first.
     *
     * <p>So: summon whatever mob you want, run this, and it is instantly at the band
     * you asked for.
     */
    private static int debugStamp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double difficulty = DoubleArgumentType.getDouble(ctx, "difficulty");
        ServerLevel level = source.getLevel();
        AABB box = AABB.ofSize(source.getPosition(), MOB_DEBUG_SIZE, MOB_DEBUG_SIZE, MOB_DEBUG_SIZE);

        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
                candidate -> candidate instanceof Enemy && candidate.isAlive());
        if (mobs.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[Warband] No hostile mobs within " + (int) MOB_DEBUG_SIZE + " blocks."));
            return 0;
        }

        int stamped = 0;
        for (Mob mob : mobs) {
            // Clear the bound-goals marker so addGoals re-runs with the new tactic mask.
            mob.setAttached(WarbandAttachments.WARBAND_GOALS_BOUND, false);
            SpawnDirector.stamp(mob, difficulty);
            SquadCoordinator.bindStampedSolo(mob, level);
            stamped++;
        }

        int finalStamped = stamped;
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Re-stamped %d mob(s) at difficulty %.2f. Use /warband mobdebug to inspect tactics.",
                finalStamped, difficulty)), true);
        WarbandDebug.event("DEBUG_STAMP", String.format("count=%d diff=%.2f", finalStamped, difficulty));
        return 1;
    }

    /** Spawns a small zombie squad at the source position, stamped with roles. */
    private static int debugSquad(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double difficulty = DoubleArgumentType.getDouble(ctx, "difficulty");
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());

        Squad squad = SquadCoordinator.createDebugSquad(level, pos, difficulty);
        int count = squad.members().size();
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Spawned squad %d with %d mob(s) at difficulty %.2f",
                squad.id(), count, difficulty)), false);
        return count;
    }

    /** Forces an illager revenge party against the executing player. */
    private static int debugRevenge(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Warband] Revenge debug requires a player source."));
            return 0;
        }

        double difficulty = DoubleArgumentType.getDouble(ctx, "difficulty");
        if (!IllagerGrudgeSystem.debugSpawnRevengeParty(player, difficulty)) {
            source.sendFailure(Component.literal("[Warband] Failed to find a revenge-party spawn position."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Spawned debug revenge party at difficulty %.2f", difficulty)), false);
        return 1;
    }

    /** Forces an illager bounty hunter against the executing player. */
    private static int debugBounty(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Warband] Bounty debug requires a player source."));
            return 0;
        }
        double difficulty = DoubleArgumentType.getDouble(ctx, "difficulty");
        if (!IllagerGrudgeSystem.debugSpawnBountyHunter(player, difficulty)) {
            source.sendFailure(Component.literal("[Warband] Failed to find a bounty-hunter spawn position."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] Spawned debug bounty hunter at difficulty %.2f", difficulty)), false);
        return 1;
    }

    /** Reports Warband-stamped mobs within {@link #MOB_SCAN_SIZE} blocks. */
    private static int reportMobs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        AABB box = AABB.ofSize(source.getPosition(), MOB_SCAN_SIZE, MOB_SCAN_SIZE, MOB_SCAN_SIZE);
        List<Mob> stamped = level.getEntitiesOfClass(Mob.class, box, MobData::isStamped);

        if (stamped.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[Warband] No stamped mobs within " + (int) MOB_SCAN_SIZE + " blocks."), false);
            return 0;
        }

        double min = 1.0;
        double max = 0.0;
        double sum = 0.0;
        int squadded = 0;
        for (Mob mob : stamped) {
            MobData data = MobData.get(mob);
            double d = data.difficulty();
            min = Math.min(min, d);
            max = Math.max(max, d);
            sum += d;
            if (data.inSquad()) {
                squadded++;
            }
        }
        double avg = sum / stamped.size();

        int count = stamped.size();
        double fMin = min;
        double fMax = max;
        int fSquadded = squadded;
        int activeSquads = SquadCoordinator.activeSquads();
        source.sendSuccess(() -> Component.literal(String.format(
                "[Warband] %d stamped mob(s) nearby, %d in squads, %d active squad(s), difficulty min %.2f / avg %.2f / max %.2f",
                count, fSquadded, activeSquads, fMin, avg, fMax)), false);
        return count;
    }

    private static String tacticNames(MobData data) {
        if (data.tactics() == 0) return "none";
        StringBuilder names = new StringBuilder();
        for (Tactic tactic : Tactic.values()) {
            if (!data.hasTactic(tactic)) continue;
            if (!names.isEmpty()) names.append(',');
            names.append(tactic.name());
            if (!WarbandConfig.tacticEnabled(tactic)) {
                names.append("(disabled)");
            }
        }
        return names.toString();
    }

    private static int warbandGoalCount(Mob mob) {
        int count = 0;
        for (WrappedGoal wrapped : ((MobGoalSelectorAccessor) mob).warband$goalSelector().getAvailableGoals()) {
            if (wrapped.getGoal() instanceof com.warband.ai.goal.WarbandGoal) count++;
        }
        for (WrappedGoal wrapped : ((MobGoalSelectorAccessor) mob).warband$targetSelector().getAvailableGoals()) {
            if (wrapped.getGoal() instanceof com.warband.ai.goal.WarbandGoal) count++;
        }
        return count;
    }
}
