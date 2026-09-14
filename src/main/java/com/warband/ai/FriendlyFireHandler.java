package com.warband.ai;

import com.warband.compat.IllagerKinds;
import com.warband.entity.MobData;
import com.warband.spawn.BossDirector;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

/** Suppresses Warband family/squad friendly fire so vanilla retaliation does not fracture squads. */
public final class FriendlyFireHandler {

    private FriendlyFireHandler() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity attacker = source.getEntity();
            if (!(entity instanceof Mob victim) || !(attacker instanceof Mob attackerMob)) return true;
            if (BossDirector.isWitherMinionFriendly(victim, attackerMob)) {
                clearIfIntentional(victim, attackerMob, source.getDirectEntity());
                return false;
            }
            if (!MobData.isStamped(victim) || !MobData.isStamped(attackerMob)) return true;

            if (sameSquad(victim, attackerMob) || sameWarbandFamily(victim, attackerMob)) {
                clearIfIntentional(victim, attackerMob, source.getDirectEntity());
                return false;
            }
            return true;
        });
    }

    /**
     * Only drop targets when the attacker actually meant to hit the victim, i.e.
     * a direct melee swing at its current target. Splash/projectile collateral
     * shouldn't make both mobs forget the player they were chasing.
     */
    private static void clearIfIntentional(Mob victim, Mob attacker, Entity direct) {
        if (direct == attacker && attacker.getTarget() == victim) {
            victim.setTarget(null);
            attacker.setTarget(null);
        }
    }

    private static boolean sameSquad(Mob a, Mob b) {
        MobData da = MobData.get(a);
        MobData db = MobData.get(b);
        return da.inSquad() && da.squadId() == db.squadId();
    }

    private static boolean sameWarbandFamily(Mob a, Mob b) {
        if (isZombieFamily(a) || isZombieFamily(b)) return isZombieFamily(a) && isZombieFamily(b);
        if (a instanceof AbstractSkeleton || b instanceof AbstractSkeleton) {
            return a instanceof AbstractSkeleton && b instanceof AbstractSkeleton;
        }
        if (a instanceof Spider || b instanceof Spider) return a instanceof Spider && b instanceof Spider;
        if (a instanceof AbstractPiglin || b instanceof AbstractPiglin) {
            return a instanceof AbstractPiglin && b instanceof AbstractPiglin;
        }
        if (a instanceof Hoglin || a instanceof Zoglin || b instanceof Hoglin || b instanceof Zoglin) {
            return (a instanceof Hoglin || a instanceof Zoglin) && (b instanceof Hoglin || b instanceof Zoglin);
        }
        if (a instanceof Slime || a instanceof MagmaCube || b instanceof Slime || b instanceof MagmaCube) {
            return (a instanceof Slime || a instanceof MagmaCube) && (b instanceof Slime || b instanceof MagmaCube);
        }
        if (a instanceof Blaze || b instanceof Blaze) return a instanceof Blaze && b instanceof Blaze;
        if (IllagerKinds.isIllagerLike(a) || IllagerKinds.isIllagerLike(b)) {
            return IllagerKinds.isIllagerLike(a) && IllagerKinds.isIllagerLike(b);
        }
        return a.getType() == b.getType();
    }

    private static boolean isZombieFamily(LivingEntity entity) {
        return entity instanceof Zombie || entity instanceof Drowned || entity instanceof ZombifiedPiglin;
    }
}
