package com.warband.ai.goal;

import com.warband.ai.Squad;
import com.warband.ai.TacticalEffects;
import com.warband.ai.TemporaryTacticBlocks;
import com.warband.entity.Tactic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Throws a short-lived cobweb to deny ground near a visible target.
 *
 * <p>Zoning tool, not a stun. Three rules keep it that way, after reports of webs
 * being "unbearable" and leaving players unable to fight back:
 *
 * <ul>
 *   <li><b>Leads a moving target</b> instead of landing on its own block. Webbing
 *       the player's exact position meant it hit the face with no counterplay —
 *       there is nowhere to dodge to when the trap spawns on top of you. Now
 *       movement is the answer, and standing still is what gets punished.</li>
 *   <li><b>Never stacks or re-traps.</b> A web already next to the aim point
 *       cancels the throw, so several spiders cannot chain-web one spot and a
 *       player already stuck is not re-webbed the instant they break free.</li>
 *   <li><b>Bites in melee.</b> Point-blank spiders attack rather than web, so a
 *       spider on top of you is a fight and not a cocoon.</li>
 * </ul>
 */
public final class SpiderWebGoal extends SquadGoal {

    private static final int WINDUP_TICKS = 14;
    private static final int COOLDOWN_TICKS = 20 * 7;
    private static final int WEB_LIFETIME_TICKS = 20 * 8;
    /** Below this, bite instead — webbing point blank left players unable to swing back. */
    private static final double MIN_RANGE = 4.0;
    private static final double MAX_RANGE = 10.0;
    /** How far ahead of a moving target to aim, in blocks. */
    private static final double LEAD_BLOCKS = 1.5;
    /** Chebyshev radius checked for existing webbing before throwing. */
    private static final int STACK_CHECK_RADIUS = 1;

    private BlockPos webPos;
    private int fireAtTick;

    public SpiderWebGoal(Mob mob, Squad squad) {
        super(mob, squad, 1.0);
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = visibleTarget();
        if (target == null) return false;
        if (!cooldownReady()) return false;
        double distance = mob.distanceToSqr(target);
        if (distance < MIN_RANGE * MIN_RANGE || distance > MAX_RANGE * MAX_RANGE) return false;

        BlockPos aim = aimPos(target);
        if (!mob.level().getBlockState(aim).isAir()) return false;
        if (webbingNear(aim)) return false;

        webPos = aim;
        return true;
    }

    /** The target's own tile when stationary, otherwise a lead on where it is heading. */
    private BlockPos aimPos(LivingEntity target) {
        Vec3 velocity = target.getDeltaMovement();
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        Vec3 aim = target.position();
        if (horizontal.lengthSqr() > 0.004) {
            aim = aim.add(horizontal.normalize().scale(LEAD_BLOCKS));
        }
        return BlockPos.containing(aim.x, target.getY(), aim.z);
    }

    /**
     * True when webbing already covers the aim point's neighbourhood. Also what
     * stops a trapped player being re-webbed: a stationary target aims at its own
     * tile, which is the web it is currently stuck in.
     */
    private boolean webbingNear(BlockPos aim) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -STACK_CHECK_RADIUS; dx <= STACK_CHECK_RADIUS; dx++) {
            for (int dy = -STACK_CHECK_RADIUS; dy <= STACK_CHECK_RADIUS; dy++) {
                for (int dz = -STACK_CHECK_RADIUS; dz <= STACK_CHECK_RADIUS; dz++) {
                    cursor.set(aim.getX() + dx, aim.getY() + dy, aim.getZ() + dz);
                    if (mob.level().getBlockState(cursor).is(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        resetCooldown(COOLDOWN_TICKS);
        fireAtTick = mob.tickCount + WINDUP_TICKS;
        tick();
    }

    @Override
    public boolean canContinueToUse() {
        return webPos != null && mob.tickCount <= fireAtTick + 1;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (webPos == null) return;

        ServerLevel level = (ServerLevel) mob.level();
        Vec3 from = mob.position().add(0.0, mob.getBbHeight() * 0.65, 0.0);
        Vec3 to = Vec3.atCenterOf(webPos);
        TacticalEffects.webTrail(level, from, to);

        if (mob.tickCount < fireAtTick) return;
        // Re-check on impact: the windup is a tell, so the target has had time to
        // move and something else may have filled the tile meanwhile.
        if (level.getBlockState(webPos).isAir() && !webbingNear(webPos)) {
            if (TemporaryTacticBlocks.place(level, webPos, Blocks.COBWEB, WEB_LIFETIME_TICKS)) {
                announceTactic(Tactic.SPIDER_WEB);
            }
        }
        webPos = null;
    }
}
