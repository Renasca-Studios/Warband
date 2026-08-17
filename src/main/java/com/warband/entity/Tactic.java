package com.warband.entity;

import com.warband.compat.IllagerKinds;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.EnumSet;

/** Difficulty-gated situational tactics assigned when Warband stamps a mob. */
public enum Tactic {

    SPIDER_WEB(1 << 0),
    STICKY_PATH(1 << 1),
    FROST_WALKER(1 << 2),
    WATER_COMMIT(1 << 3),
    PRESSURE_UNREACHABLE(1 << 4),
    SKELETON_SMOKE(1 << 5),
    CREEPER_STALK(1 << 6),
    ZOMBIE_HORDE(1 << 7),
    ENDERMAN_DISRUPT(1 << 8),
    PIGLIN_SOCIAL(1 << 9),
    BLAZE_HOVER(1 << 10),
    WITCH_SUPPORT(1 << 11),
    SLIME_SURGE(1 << 12),
    HOGLIN_STAMPEDE(1 << 13),
    ILLAGER_COMMAND(1 << 14),
    PHANTOM_HARASS(1 << 15),
    LEAP_UNREACHABLE(1 << 16),
    GUARDIAN_SURGE(1 << 18),
    SHULKER_LOCKDOWN(1 << 19),
    GHAST_REPOSITION(1 << 20),
    CAVE_SPIDER_AMBUSH(1 << 21),
    RAVAGER_BREAKER(1 << 22),
    WARDEN_PRESSURE(1 << 23),
    CEILING_CRAWL(1 << 24),
    RANGED_REPOSITION(1 << 25),
    BOGGED_BACKDASH(1 << 26),
    STRAY_JUMP_SHOT(1 << 27),
    SIEGE_MINE(1 << 28),
    CREEPER_BREACH(1 << 29);

    private final int bit;

    Tactic(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public static boolean has(int mask, Tactic tactic) {
        return (mask & tactic.bit) != 0;
    }

    public static int chooseFor(Mob mob, double difficulty, Role role) {
        return chooseForSubjects(subjectsFor(mob), difficulty, role);
    }

    static int chooseForSubjects(EnumSet<Subject> subjects, double difficulty, Role role) {
        int mask = 0;
        if (subjects.contains(Subject.SPIDER)) {
            if (difficulty >= 0.45) mask |= SPIDER_WEB.bit;
            if (difficulty >= 0.65 || role == Role.SKIRMISHER) mask |= STICKY_PATH.bit;
            if (difficulty >= 0.55) mask |= CEILING_CRAWL.bit;
            if (difficulty >= 0.70) mask |= LEAP_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.CAVE_SPIDER) && difficulty >= 0.45) {
            mask |= CAVE_SPIDER_AMBUSH.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.ABSTRACT_SKELETON)) {
            if (difficulty >= 0.50) mask |= PRESSURE_UNREACHABLE.bit;
            if (difficulty >= 0.60) mask |= SKELETON_SMOKE.bit;
        }
        if (subjects.contains(Subject.STRAY) && difficulty >= 0.55) {
            mask |= FROST_WALKER.bit | STRAY_JUMP_SHOT.bit;
        }
        if (subjects.contains(Subject.ZOMBIE_FAMILY)) {
            if (difficulty >= 0.40) mask |= ZOMBIE_HORDE.bit;
        }
        if (subjects.contains(Subject.ZOMBIE_FAMILY) && difficulty >= 0.70) {
            mask |= WATER_COMMIT.bit;
        }
        if (subjects.contains(Subject.ZOMBIE_FAMILY) && difficulty >= 0.80) {
            mask |= LEAP_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.CREEPER) && difficulty >= 0.55) {
            mask |= PRESSURE_UNREACHABLE.bit | CREEPER_STALK.bit;
        }
        if (subjects.contains(Subject.CREEPER) && difficulty >= 0.60) {
            mask |= CREEPER_BREACH.bit;
        }
        // Siege digging is the answer to a target that simply cannot be pathed to.
        // Zombies lead, illagers follow at a higher band (they are meant to feel
        // organised rather than mindless), ravagers earliest — they already read as
        // siege engines.
        if (subjects.contains(Subject.ZOMBIE_FAMILY) && difficulty >= 0.55) {
            mask |= SIEGE_MINE.bit;
        }
        if (subjects.contains(Subject.ILLAGER_LIKE) && difficulty >= 0.60) {
            mask |= SIEGE_MINE.bit;
        }
        if (subjects.contains(Subject.RAVAGER) && difficulty >= 0.50) {
            mask |= SIEGE_MINE.bit;
        }
        if (difficulty >= 0.75) {
            mask |= PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.ENDERMAN) && difficulty >= 0.55) {
            mask |= ENDERMAN_DISRUPT.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.ABSTRACT_PIGLIN) && difficulty >= 0.35) {
            mask |= PIGLIN_SOCIAL.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.BLAZE) && difficulty >= 0.45) {
            mask |= BLAZE_HOVER.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.WITCH) && difficulty >= 0.45) {
            mask |= WITCH_SUPPORT.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.SLIME_FAMILY) && difficulty >= 0.50) {
            mask |= SLIME_SURGE.bit;
        }
        if (subjects.contains(Subject.HOGLIN_FAMILY) && difficulty >= 0.45) {
            mask |= HOGLIN_STAMPEDE.bit;
            if (difficulty >= 0.65) mask |= LEAP_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.ILLAGER_LIKE) && difficulty >= 0.45) {
            mask |= ILLAGER_COMMAND.bit | PRESSURE_UNREACHABLE.bit;
            if (difficulty >= 0.70) mask |= LEAP_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.PHANTOM) && difficulty >= 0.45) {
            mask |= PHANTOM_HARASS.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.GUARDIAN) && difficulty >= 0.45) {
            mask |= GUARDIAN_SURGE.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.SHULKER) && difficulty >= 0.50) {
            mask |= SHULKER_LOCKDOWN.bit;
        }
        if (subjects.contains(Subject.GHAST) && difficulty >= 0.45) {
            mask |= GHAST_REPOSITION.bit;
        }
        if (subjects.contains(Subject.RAVAGER) && difficulty >= 0.50) {
            mask |= RAVAGER_BREAKER.bit | PRESSURE_UNREACHABLE.bit;
        }
        if (subjects.contains(Subject.BOGGED) && difficulty >= 0.50) {
            mask |= BOGGED_BACKDASH.bit;
        }
        if (subjects.contains(Subject.RANGED_ATTACK) && difficulty >= 0.45) {
            mask |= RANGED_REPOSITION.bit;
        }
        if (subjects.contains(Subject.WARDEN) && difficulty >= 0.35) {
            mask |= WARDEN_PRESSURE.bit | PRESSURE_UNREACHABLE.bit;
        }
        return mask;
    }

    /**
     * The behaviour pools this mob belongs to: derived from its vanilla class, plus
     * anything the user declared via {@code customMobPools}. Drives both tactic
     * selection and squad-family matching, so a modded mob mapped to
     * {@code ZOMBIE_FAMILY} both gains zombie tactics and squads with real zombies.
     */
    public static EnumSet<Subject> subjectsFor(Mob mob) {
        EnumSet<Subject> subjects = MobPools.subjectsFor(mob);
        if (mob instanceof Spider) subjects.add(Subject.SPIDER);
        if (mob instanceof CaveSpider) subjects.add(Subject.CAVE_SPIDER);
        if (mob instanceof AbstractSkeleton) subjects.add(Subject.ABSTRACT_SKELETON);
        if (mob instanceof Stray) subjects.add(Subject.STRAY);
        if ("bogged".equals(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath())) subjects.add(Subject.BOGGED);
        if (mob instanceof RangedAttackMob) subjects.add(Subject.RANGED_ATTACK);
        if (mob instanceof Zombie || mob instanceof Drowned || mob instanceof ZombifiedPiglin) subjects.add(Subject.ZOMBIE_FAMILY);
        if (mob instanceof Creeper) subjects.add(Subject.CREEPER);
        if (mob instanceof EnderMan) subjects.add(Subject.ENDERMAN);
        if (mob instanceof AbstractPiglin) subjects.add(Subject.ABSTRACT_PIGLIN);
        if (mob instanceof Blaze) subjects.add(Subject.BLAZE);
        if (mob instanceof Witch) subjects.add(Subject.WITCH);
        if (mob instanceof Slime || mob instanceof MagmaCube) subjects.add(Subject.SLIME_FAMILY);
        if (mob instanceof Hoglin || mob instanceof Zoglin) subjects.add(Subject.HOGLIN_FAMILY);
        if (IllagerKinds.isIllagerLike(mob)) subjects.add(Subject.ILLAGER_LIKE);
        if (mob instanceof Phantom) subjects.add(Subject.PHANTOM);
        if (mob instanceof Guardian) subjects.add(Subject.GUARDIAN);
        if (mob instanceof Shulker) subjects.add(Subject.SHULKER);
        if (mob instanceof Ghast) subjects.add(Subject.GHAST);
        if (mob instanceof Ravager) subjects.add(Subject.RAVAGER);
        if (mob instanceof Warden) subjects.add(Subject.WARDEN);
        return subjects;
    }

    /** Behaviour pools. Also the vocabulary users write in {@code customMobPools}. */
    public enum Subject {
        SPIDER,
        CAVE_SPIDER,
        ABSTRACT_SKELETON,
        STRAY,
        BOGGED,
        RANGED_ATTACK,
        ZOMBIE_FAMILY,
        CREEPER,
        ENDERMAN,
        ABSTRACT_PIGLIN,
        BLAZE,
        WITCH,
        SLIME_FAMILY,
        HOGLIN_FAMILY,
        ILLAGER_LIKE,
        PHANTOM,
        GUARDIAN,
        SHULKER,
        GHAST,
        RAVAGER,
        WARDEN
    }
}
