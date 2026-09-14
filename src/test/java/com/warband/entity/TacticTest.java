package com.warband.entity;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticTest {

    @Test
    void zombieFamilyThresholds() {
        int beforeHorde = choose(Tactic.Subject.ZOMBIE_FAMILY, 0.39);
        int horde = choose(Tactic.Subject.ZOMBIE_FAMILY, 0.40);
        int water = choose(Tactic.Subject.ZOMBIE_FAMILY, 0.70);
        int leap = choose(Tactic.Subject.ZOMBIE_FAMILY, 0.80);

        assertFalse(Tactic.has(beforeHorde, Tactic.ZOMBIE_HORDE));
        assertTrue(Tactic.has(horde, Tactic.ZOMBIE_HORDE));
        assertTrue(Tactic.has(water, Tactic.WATER_COMMIT));
        assertTrue(Tactic.has(leap, Tactic.LEAP_UNREACHABLE));
    }

    @Test
    void caveSpiderGetsAmbushAndPressure() {
        int mask = Tactic.chooseForSubjects(
                EnumSet.of(Tactic.Subject.SPIDER, Tactic.Subject.CAVE_SPIDER), 0.45, Role.NONE);

        assertTrue(Tactic.has(mask, Tactic.SPIDER_WEB));
        assertTrue(Tactic.has(mask, Tactic.CAVE_SPIDER_AMBUSH));
        assertTrue(Tactic.has(mask, Tactic.PRESSURE_UNREACHABLE));
    }

    @Test
    void strayGetsSkeletonKitAndFrostWalker() {
        int mask = Tactic.chooseForSubjects(
                EnumSet.of(Tactic.Subject.ABSTRACT_SKELETON, Tactic.Subject.STRAY), 0.60, Role.NONE);

        assertTrue(Tactic.has(mask, Tactic.PRESSURE_UNREACHABLE));
        assertTrue(Tactic.has(mask, Tactic.SKELETON_SMOKE));
        assertTrue(Tactic.has(mask, Tactic.FROST_WALKER));
    }

    @Test
    void hoglinUnlocksClimbAtHigherDifficulty() {
        int stampedeOnly = choose(Tactic.Subject.HOGLIN_FAMILY, 0.45);
        int climb = choose(Tactic.Subject.HOGLIN_FAMILY, 0.65);

        assertTrue(Tactic.has(stampedeOnly, Tactic.HOGLIN_STAMPEDE));
        assertFalse(Tactic.has(stampedeOnly, Tactic.LEAP_UNREACHABLE));
        assertTrue(Tactic.has(climb, Tactic.LEAP_UNREACHABLE));
    }

    @Test
    void skirmisherSpiderGetsStickyPathEarly() {
        int mask = Tactic.chooseForSubjects(EnumSet.of(Tactic.Subject.SPIDER), 0.20, Role.SKIRMISHER);

        assertTrue(Tactic.has(mask, Tactic.STICKY_PATH));
    }

    @Test
    void endermanGetsDisruptAtThreshold() {
        int before = choose(Tactic.Subject.ENDERMAN, 0.54);
        int mask = choose(Tactic.Subject.ENDERMAN, 0.55);

        assertFalse(Tactic.has(before, Tactic.ENDERMAN_DISRUPT));
        assertTrue(Tactic.has(mask, Tactic.ENDERMAN_DISRUPT));
        assertTrue(Tactic.has(mask, Tactic.PRESSURE_UNREACHABLE));
    }

    @Test
    void coreAntiCheeseThresholdsMatchAdvertisedFamilies() {
        assertFalse(Tactic.has(core(Tactic.Subject.ZOMBIE_FAMILY, 0.54), Tactic.SIEGE_MINE));
        assertTrue(Tactic.has(core(Tactic.Subject.ZOMBIE_FAMILY, 0.55), Tactic.SIEGE_MINE));
        assertFalse(Tactic.has(core(Tactic.Subject.ILLAGER_LIKE, 0.59), Tactic.SIEGE_MINE));
        assertTrue(Tactic.has(core(Tactic.Subject.ILLAGER_LIKE, 0.60), Tactic.SIEGE_MINE));
        assertFalse(Tactic.has(core(Tactic.Subject.CREEPER, 0.59), Tactic.CREEPER_BREACH));
        assertTrue(Tactic.has(core(Tactic.Subject.CREEPER, 0.60), Tactic.CREEPER_BREACH));
    }

    private static int choose(Tactic.Subject subject, double difficulty) {
        return Tactic.chooseForSubjects(EnumSet.of(subject), difficulty, Role.NONE);
    }

    private static int core(Tactic.Subject subject, double difficulty) {
        return Tactic.coreAntiCheeseForSubjects(EnumSet.of(subject), difficulty);
    }
}
