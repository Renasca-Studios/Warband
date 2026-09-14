package com.warband.ai.goal;

import com.warband.ai.Squad;
import com.warband.ai.TemporaryTacticBlocks;
import com.warband.WarbandDebug;
import com.warband.compat.ZombieBreakAndBuildCompat;
import com.warband.config.WarbandConfig;
import com.warband.entity.MobData;
import com.warband.entity.Tactic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Breaks through a wall when the target cannot be reached any other way.
 *
 * <p>This is the answer to the oldest cheese in the game: stand somewhere with no
 * valid path and the world has nothing to say. Warband already had squads, morale
 * and a shared blackboard, but a fence post ended every one of those systems, and
 * illager crusades that muster at a player's base were arriving only to mill about
 * outside.
 *
 * <p>Deliberately narrow. It only engages when
 * <ol>
 *   <li>pathfinding has actually failed — never as a shortcut past open ground,</li>
 *   <li>the obstructing block is listed in the configured block tag, so packs decide
 *       what is soft, and reinforcing with obsidian or metal remains real
 *       counterplay rather than a removed option,</li>
 *   <li>the {@code mobGriefing} gamerule allows it,</li>
 *   <li>the target is close enough that this reads as a breach and not as tunnelling
 *       in from the horizon.</li>
 * </ol>
 *
 * <p>Damage is reverted by {@link TemporaryTacticBlocks} unless
 * {@code siegeMiningPermanent} is set. Digging is telegraphed with real block-break
 * progress, so a player hears and sees the wall going before it goes.
 */
public final class SiegeMineGoal extends SquadGoal {

    /** Below this the mob is not considered smart enough to dig. */
    private static final double MIN_DIFFICULTY = 0.55;
    /** Beyond this the target is too far away for digging to read as a breach. */
    private static final double MAX_TARGET_DISTANCE = 24.0;
    /** Re-evaluate (and re-path) at most this often; path creation is not free. */
    private static final int DECISION_INTERVAL = 30;
    /** Ticks to chew through an average block at minimum difficulty. */
    private static final int BASE_DIG_TICKS = 60;
    /** Ticks to chew through an average block at difficulty 1.0. */
    private static final int FAST_DIG_TICKS = 24;
    /** Vanilla destroy speed treated as "average" — cobblestone is 2.0. */
    private static final float REFERENCE_HARDNESS = 2.0f;
    /** Clamps on the hardness factor, so nothing is instant and nothing takes forever. */
    private static final double MIN_HARDNESS_FACTOR = 0.4;
    private static final double MAX_HARDNESS_FACTOR = 2.5;
    /** Blocks removed per activation, so a breach is a hole and not a tunnel. */
    private static final int MAX_BLOCKS_PER_BREACH = 4;
    /** How far ahead the hitbox is swept when looking for the obstruction. */
    private static final double STEP_PROBE = 0.9;
    /** Search far enough to find the wall even when vanilla gives up pathing early. */
    private static final double MAX_OBSTRUCTION_SCAN = 8.0;

    private BlockPos digTarget;
    private @Nullable LivingEntity siegeTarget;
    private int digTicks;
    private int digTicksNeeded;
    private int blocksBroken;
    private int lastProgressStage = -1;

    public SiegeMineGoal(Mob mob, Squad squad) {
        super(mob, squad, 1.0);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!WarbandConfig.siegeMiningEnabled) return false;
        // Zombie Break & Build owns mob block manipulation far more thoroughly than one
        // Warband behaviour can, so hand the domain over rather than digging alongside it.
        if (WarbandConfig.siegeMiningDeferToOtherMods
                && ZombieBreakAndBuildCompat.isLoaded()) {
            return false;
        }
        if (frightened()) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (!Boolean.TRUE.equals(level.getGameRules().get(GameRules.MOB_GRIEFING))) return false;
        if (MobData.get(mob).difficulty() < MIN_DIFFICULTY) return false;
        if (blocksBroken >= MAX_BLOCKS_PER_BREACH) return false;
        if (!decisionReady(DECISION_INTERVAL)) return false;

        LivingEntity target = visibleTarget();
        BlockPos goal = target != null ? target.blockPosition() : rememberedTargetPos();
        if (goal == null) return false;
        if (mob.distanceToSqr(Vec3.atCenterOf(goal)) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) return false;
        // Only dig when walking there genuinely does not work.
        if (canReach(target, goal)) return false;
        siegeTarget = target;

        digTarget = chooseBlock(level, goal);
        if (digTarget == null) return false;
        digTicks = 0;
        digTicksNeeded = digTicksFor(MobData.get(mob).difficulty(), level, digTarget);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (digTarget == null) return false;
        if (frightened()) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (!isBreachable(level, digTarget)) return false;
        // Do not re-path while the cracking animation is active. Minecraft can
        // transiently report a partial/recomputed route as reachable near an
        // obstruction; cancelling here reset progress just before the block broke.
        // canUse() already established that a breach was necessary, and one block is
        // a small enough commitment to finish before reconsidering the route.
        // The obstruction may have been found several blocks ahead. Keep the goal
        // while approaching it; only start applying crack progress once in reach.
        return mob.distanceToSqr(Vec3.atCenterOf(digTarget))
                <= MAX_OBSTRUCTION_SCAN * MAX_OBSTRUCTION_SCAN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        if (mob.distanceToSqr(Vec3.atCenterOf(digTarget)) <= 9.0) {
            mob.getNavigation().stop();
        } else {
            moveTo(digTarget);
        }
        WarbandDebug.event("SIEGE_DIG_START", mob, String.format(
                "diff=%.2f block=%s target=%s digTicks=%d broken=%d",
                MobData.get(mob).difficulty(),
                BuiltInRegistries.BLOCK.getKey(mob.level().getBlockState(digTarget).getBlock()),
                digTarget.getX() + " " + digTarget.getY() + " " + digTarget.getZ(),
                digTicksNeeded, blocksBroken));
    }

    @Override
    public void tick() {
        if (digTarget == null || !(mob.level() instanceof ServerLevel level)) return;

        mob.getLookControl().setLookAt(Vec3.atCenterOf(digTarget));
        if (mob.distanceToSqr(Vec3.atCenterOf(digTarget)) > 9.0) {
            moveTo(digTarget);
            return;
        }
        mob.getNavigation().stop();
        digTicks++;

        // Vanilla block-break cracks: the telegraph. A player should always get the
        // chance to hear a wall being worked on and respond to it.
        int stage = Math.min(9, (digTicks * 10) / Math.max(1, digTicksNeeded));
        if (stage != lastProgressStage) {
            lastProgressStage = stage;
            level.destroyBlockProgress(mob.getId(), digTarget, stage);
        }

        if (digTicks < digTicksNeeded) return;

        if (TemporaryTacticBlocks.mine(level, digTarget, WarbandConfig.siegeMiningRestoreSeconds * 20)) {
            blocksBroken++;
            announceTactic(Tactic.SIEGE_MINE);
        }
        level.destroyBlockProgress(mob.getId(), digTarget, -1);
        digTarget = null;
        lastProgressStage = -1;
        resetCooldown(DECISION_INTERVAL);
    }

    @Override
    public void stop() {
        if (digTarget != null && mob.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(mob.getId(), digTarget, -1);
        }
        digTarget = null;
        siegeTarget = null;
        lastProgressStage = -1;
        blocksBroken = 0;
    }

    /**
     * Dig time from difficulty <i>and</i> the block's own hardness.
     *
     * <p>A flat timer meant dirt and stone brick fell at identical speed, which reads as
     * arbitrary and made "reinforce with something tougher" a binary choice between
     * breakable and not. Scaling by vanilla destroy speed makes soft cover give way fast
     * and hard cover genuinely cost the attacker time, so partial reinforcement pays off
     * proportionally.
     */
    private int digTicksFor(double difficulty, ServerLevel level, BlockPos pos) {
        double t = Math.max(0.0, Math.min(1.0, (difficulty - MIN_DIFFICULTY) / (1.0 - MIN_DIFFICULTY)));
        double base = BASE_DIG_TICKS + (FAST_DIG_TICKS - BASE_DIG_TICKS) * t;

        float hardness = level.getBlockState(pos).getDestroySpeed(level, pos);
        double factor = Math.max(MIN_HARDNESS_FACTOR,
                Math.min(MAX_HARDNESS_FACTOR, hardness / REFERENCE_HARDNESS));
        return Math.max(8, (int) Math.round(base * factor));
    }

    /** Where the squad is trying to get to: a seen target, else the shared last-known position. */
    /**
     * Whether the mob can simply walk there.
     *
     * <p>Prefers pathing to the <b>entity</b> when there is one. Pathing to a player's
     * own {@code BlockPos} routinely reports unreachable even when the player is
     * plainly walkable-to, because the destination node is the block the player is
     * standing in — vanilla's {@code MeleeAttackGoal} paths to the entity for exactly
     * this reason. Using the block position here made sieges fire constantly against
     * reachable players: 26 dig attempts to 3 completed blocks in one test session,
     * with mobs freezing at walls instead of attacking.
     */
    private boolean canReach(@Nullable LivingEntity target, BlockPos goal) {
        Path path = target != null
                ? mob.getNavigation().createPath(target, 0)
                : mob.getNavigation().createPath(goal, 0);
        return path != null && path.canReach();
    }

    /**
     * The block that actually blocks this mob: the mob's own hitbox is stepped toward
     * the target, and the nearest block whose <b>collision shape genuinely intersects</b>
     * that swept box wins.
     *
     * <p>The previous version guessed a single position one step ahead at foot and head
     * height. That is wrong in several ordinary situations, and each one wastes a dig on a
     * block that was never in the way:
     * <ul>
     *   <li>wide mobs — a ravager is nearly two blocks across, so a centre-line guess
     *       misses whatever its shoulders are actually caught on,</li>
     *   <li>partial blocks — a fence or slab blocks movement while the guessed position
     *       lands in the air above it,</li>
     *   <li>diagonal approaches, where the obstruction is not on either axis,</li>
     *   <li>blocks with no collision at all, which are not obstructions no matter what
     *       the block tag says.</li>
     * </ul>
     *
     * <p>Falls back to digging up or down when the target is mostly above or below and
     * nothing lateral is in the way.
     */
    private @Nullable BlockPos chooseBlock(ServerLevel level, BlockPos goal) {
        Vec3 toGoal = Vec3.atCenterOf(goal).subtract(mob.position());
        Vec3 flat = new Vec3(toGoal.x, 0.0, toGoal.z);
        if (flat.lengthSqr() > 0.01) {
            Vec3 direction = flat.normalize();
            double scanDistance = Math.min(flat.length(), MAX_OBSTRUCTION_SCAN);
            for (double distance = STEP_PROBE; distance <= scanDistance; distance += STEP_PROBE) {
                BlockPos blocking = nearestBlockingBlock(level, direction.scale(distance));
                if (blocking != null) return blocking;
            }
        }

        BlockPos feet = mob.blockPosition();
        double rise = goal.getY() - mob.getY();
        BlockPos vertical = rise > 2.0 ? feet.above(2) : rise < -2.0 ? feet.below() : null;
        return vertical != null && isBreachable(level, vertical) ? vertical.immutable() : null;
    }

    /**
     * Sweeps the mob's bounding box by {@code offset} and returns the nearest breachable
     * block whose collision shape overlaps the swept box.
     */
    private @Nullable BlockPos nearestBlockingBlock(ServerLevel level, Vec3 offset) {
        AABB swept = mob.getBoundingBox().move(offset);
        Vec3 origin = mob.getBoundingBox().getCenter();

        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = Mth.floor(swept.minX); x <= Mth.floor(swept.maxX - 1.0E-7); x++) {
            for (int y = Mth.floor(swept.minY); y <= Mth.floor(swept.maxY - 1.0E-7); y++) {
                for (int z = Mth.floor(swept.minZ); z <= Mth.floor(swept.maxZ - 1.0E-7); z++) {
                    cursor.set(x, y, z);
                    if (!isBreachable(level, cursor)) continue;

                    VoxelShape collision = level.getBlockState(cursor).getCollisionShape(level, cursor);
                    if (collision.isEmpty()) continue;
                    if (!collision.bounds().move(x, y, z).intersects(swept)) continue;

                    double distance = origin.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cursor.immutable();
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isBreachable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        // Never immediately re-mine something we only just resealed.
        if (TemporaryTacticBlocks.recentlyRestored(level, pos)) return false;
        // Never chew on the unbreakable; also skips bedrock/barriers generically.
        if (state.getDestroySpeed(level, pos) < 0.0F) return false;
        if (state.is(BlockTags.WITHER_IMMUNE)) return false;
        if (state.hasBlockEntity()) return false;
        return state.is(breakableTag());
    }

    /** Resolved per call so {@code /warband reload} can repoint the tag without a restart. */
    private static TagKey<Block> breakableTag() {
        Identifier id = Identifier.tryParse(WarbandConfig.siegeMiningBlockTag);
        return TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                id == null ? Identifier.fromNamespaceAndPath("warband", "siege_breakable") : id);
    }
}
