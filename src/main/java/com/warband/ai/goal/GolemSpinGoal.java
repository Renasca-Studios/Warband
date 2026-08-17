package com.warband.ai.goal;

import com.warband.ai.TacticalEffects;
import com.warband.entity.MobData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/** Emergency 360 knockback pulse when an iron golem is surrounded. */
public final class GolemSpinGoal extends Goal implements WarbandGoal {

    private static final double RADIUS = 4.0;
    private static final int COOLDOWN_TICKS = 20 * 12;

    private final IronGolem golem;
    private int nextSpinTick;

    public GolemSpinGoal(IronGolem golem) {
        this.golem = golem;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (golem.tickCount < nextSpinTick) return false;

        AABB box = AABB.ofSize(golem.position(), RADIUS * 2.0, RADIUS, RADIUS * 2.0);
        List<Mob> threats = ((ServerLevel) golem.level()).getEntitiesOfClass(Mob.class, box,
                mob -> mob instanceof Enemy && MobData.isStamped(mob) && mob.isAlive());
        return threats.size() >= 4 || (threats.size() >= 3 && golem.getHealth() / golem.getMaxHealth() < 0.55f);
    }

    @Override
    public void start() {
        nextSpinTick = golem.tickCount + COOLDOWN_TICKS;
        ServerLevel level = (ServerLevel) golem.level();

        AABB box = AABB.ofSize(golem.position(), RADIUS * 2.0, RADIUS, RADIUS * 2.0);
        List<Mob> threats = level.getEntitiesOfClass(Mob.class, box,
                mob -> mob instanceof Enemy && MobData.isStamped(mob) && mob.isAlive());

        level.playSound(null, golem.getX(), golem.getY(), golem.getZ(),
                SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.NEUTRAL, 1.0f, 0.85f);
        TacticalEffects.signal(level, golem);

        for (Mob threat : threats) {
            Vec3 away = threat.position().subtract(golem.position());
            if (away.lengthSqr() < 0.001) continue;
            Vec3 dir = away.normalize();
            threat.knockback(1.8, -dir.x, -dir.z, golem.damageSources().mobAttack(golem), 0.0f);
            threat.hurtServer(level, golem.damageSources().mobAttack(golem), 5.0f);
        }

        golem.setYRot(golem.getYRot() + 360.0f);
        golem.setTarget(nearestStillAlive(threats));
    }

    private static LivingEntity nearestStillAlive(List<Mob> threats) {
        for (Mob threat : threats) {
            if (threat.isAlive()) return threat;
        }
        return null;
    }
}
