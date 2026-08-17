package com.warband.ai.goal;

import com.warband.WarbandDebug;
import com.warband.config.WarbandConfig;
import com.warband.entity.MobData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Scatter from things that are about to end you: a swelling creeper, lit TNT, or a
 * warden.
 *
 * <p>The cheapest large gain in <i>perceived</i> intelligence in the whole mod. A
 * horde that keeps walking into a creeper detonation reads as scenery; one that
 * breaks and runs reads as aware. It also composes with squads in a way a per-mob
 * AI mod cannot match — a whole formation peeling away from one creeper is a
 * legible group reaction.
 *
 * <p>Deliberately outranks Warband's own tactics but not vanilla's attack goals at
 * priority 1-2, so it interrupts flanking and regrouping without letting a mob
 * ignore a player who is hitting it.
 */
public final class DreadAvoidGoal extends Goal implements WarbandGoal {

    private static final double MIN_DIFFICULTY = 0.35;
    /** Creepers and TNT: roughly blast radius plus a margin. */
    private static final double BLAST_SCAN = 7.0;
    /** Wardens are avoided from further out; they are a slow, obvious doom. */
    private static final double WARDEN_SCAN = 12.0;
    private static final double FLEE_DISTANCE = 12.0;
    private static final int RECHECK_TICKS = 10;
    /** Cooldown after a scatter, so one lingering threat cannot pin a mob permanently. */
    private static final int REARM_TICKS = 20 * 5;

    private final Mob mob;
    private Vec3 fleeFrom;
    private String threatKind = "?";
    private int recheckCounter;
    private int rearmAtTick;

    public DreadAvoidGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!WarbandConfig.explosionAvoidanceEnabled) return false;
        if (MobData.get(mob).difficulty() < MIN_DIFFICULTY) return false;
        // A creeper is not afraid of its own trade, and a warden fears nothing.
        if (mob instanceof Creeper || mob instanceof Warden) return false;
        if (mob.tickCount < rearmAtTick) return false;
        if (--recheckCounter > 0) return false;
        recheckCounter = RECHECK_TICKS;

        Entity threat = nearestThreat();
        if (threat == null) return false;
        fleeFrom = threat.position();
        threatKind = threat.getType().toShortString();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return fleeFrom != null && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (fleeFrom == null) return;
        Vec3 away = mob.position().subtract(fleeFrom);
        if (away.lengthSqr() < 0.01) {
            away = new Vec3(mob.getRandom().nextDouble() - 0.5, 0.0, mob.getRandom().nextDouble() - 0.5);
        }
        Vec3 destination = mob.position().add(away.normalize().scale(FLEE_DISTANCE));
        BlockPos pos = BlockPos.containing(destination.x, destination.y, destination.z);
        boolean moving = mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.25);
        WarbandDebug.event("DREAD_AVOID", mob, "threat=" + threatKind + " fled=" + moving);
    }

    @Override
    public void stop() {
        fleeFrom = null;
        rearmAtTick = mob.tickCount + REARM_TICKS;
    }

    private Entity nearestThreat() {
        AABB blastBox = mob.getBoundingBox().inflate(BLAST_SCAN, 4.0, BLAST_SCAN);
        List<Entity> imminent = mob.level().getEntities(mob, blastBox,
                e -> (e instanceof Creeper creeper && creeper.getSwellDir() > 0)
                        || e instanceof PrimedTnt
                        // 26.2's sulfur cube. Only the explosive archetype can prime, and
                        // it detonates at power 3 — the same as a creeper — so BLAST_SCAN
                        // is already the right radius. isPrimed() is the exact analogue of
                        // a creeper's swell: a cube that merely exists is not a threat.
                        || (e instanceof SulfurCube cube && cube.isPrimed()));
        if (!imminent.isEmpty()) return closest(imminent);

        AABB wardenBox = mob.getBoundingBox().inflate(WARDEN_SCAN, 6.0, WARDEN_SCAN);
        List<Entity> wardens = mob.level().getEntities(mob, wardenBox, e -> e instanceof Warden);
        return wardens.isEmpty() ? null : closest(wardens);
    }

    private Entity closest(List<Entity> candidates) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            double distance = mob.distanceToSqr(candidate);
            if (distance < bestDist) {
                bestDist = distance;
                best = candidate;
            }
        }
        return best;
    }
}
