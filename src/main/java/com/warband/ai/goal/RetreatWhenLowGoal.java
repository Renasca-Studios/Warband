package com.warband.ai.goal;

import com.warband.ai.Squad;
import com.warband.compat.RaidCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Pulls a mob back from a threat when it is badly wounded or its squad is
 * routing (the leader just fell).
 *
 * <p>Retreat is leashed: after {@link #LEASH_TICKS} of continuous fleeing the
 * mob commits and fights for {@link #COMMIT_TICKS}, so a wounded mob cannot flee
 * forever, the player still gets the kill.
 */
public final class RetreatWhenLowGoal extends SquadGoal {

    /** Longest a mob flees continuously before it is forced to commit. */
    private static final int LEASH_TICKS = 20 * 6;
    /** How long the mob then stands and fights before it may flee again. */
    private static final int COMMIT_TICKS = 20 * 5;
    private static final int COOLDOWN_TICKS = 20;

    private int retreatingSince = -1;
    private int commitUntil;
    private BlockPos retreat;

    public RetreatWhenLowGoal(Mob mob, Squad squad) {
        super(mob, squad, 1.25);
    }

    @Override
    public boolean canUse() {
        if (RaidCompat.isActiveRaider(mob)) return false;

        float ratio = mob.getHealth() / mob.getMaxHealth();
        boolean wounded = ratio <= 0.35f;
        // Pre-emptive pull: at 60% HP, if no allies are in support range, the
        // mob reads the fight as unwinnable solo and tries to regroup instead
        // of dying alone. Squad-aware caution, not panic.
        boolean outmatched = !wounded && ratio <= 0.60f && !hasSupportNearby();
        if (!wounded && !outmatched && !squad.isRouting()) {
            retreatingSince = -1;
            return false;
        }
        if (mob.tickCount < commitUntil) return false; // committed, stand and fight
        if (!cooldownReady()) return false;

        if (retreatingSince < 0) {
            retreatingSince = mob.tickCount;
        }
        if (mob.tickCount - retreatingSince > LEASH_TICKS) {
            // Fled long enough, commit, so the mob cannot kite away forever.
            commitUntil = mob.tickCount + COMMIT_TICKS;
            retreatingSince = -1;
            return false;
        }

        LivingEntity target = visibleTarget();
        BlockPos threat = target != null ? target.blockPosition() : rememberedTargetPos();
        if (threat == null) return false;

        retreat = awayFrom(Vec3.atCenterOf(threat), 10.0);
        return retreat != null;
    }

    @Override
    public void start() {
        resetCooldown(COOLDOWN_TICKS);
        if (retreat != null) {
            moveTo(retreat);
        }
    }

    private boolean hasSupportNearby() {
        for (Mob ally : squad.members()) {
            if (ally == mob || !ally.isAlive()) continue;
            if (mob.distanceToSqr(ally) < 10.0 * 10.0) return true;
        }
        return false;
    }
}
