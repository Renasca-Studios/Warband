package com.warband.ai;

import com.warband.ai.goal.CreeperBreachGoal;
import com.warband.ai.goal.DreadAvoidGoal;
import com.warband.ai.goal.SiegeMineGoal;
import com.warband.ai.goal.WarbandDoorGoal;
import com.warband.mixin.MobGoalSelectorAccessor;
import com.warband.spawn.SpawnDirector;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;

/** In-engine checks for the goal bindings that failed silently in 1.4.0. */
public final class WarbandAiGameTests {

    @GameTest
    public void coreAntiCheeseGoalsBindToSoloMobs(GameTestHelper helper) {
        Mob zombie = helper.spawn(EntityTypes.ZOMBIE, 1, 2, 1);
        SpawnDirector.stampVanillaAi(zombie, 0.90);
        SquadCoordinator.bindStampedSolo(zombie, helper.getLevel());
        assertGoal(helper, zombie, SiegeMineGoal.class, 0);
        assertGoal(helper, zombie, DreadAvoidGoal.class, -1);
        helper.succeed();
    }

    @GameTest
    public void creeperBreachCanPreemptOrdinaryMovement(GameTestHelper helper) {
        Mob creeper = helper.spawn(EntityTypes.CREEPER, 1, 2, 1);
        SpawnDirector.stampVanillaAi(creeper, 0.90);
        SquadCoordinator.bindStampedSolo(creeper, helper.getLevel());

        assertGoal(helper, creeper, CreeperBreachGoal.class, 0);
        assertGoal(helper, creeper, DreadAvoidGoal.class, -1);
        helper.succeed();
    }

    @GameTest
    public void vindicatorGetsDoorAndSiegeGoals(GameTestHelper helper) {
        Mob vindicator = helper.spawn(EntityTypes.VINDICATOR, 1, 2, 1);
        SpawnDirector.stampVanillaAi(vindicator, 0.90);
        SquadCoordinator.bindStampedSolo(vindicator, helper.getLevel());

        assertGoal(helper, vindicator, WarbandDoorGoal.class, 2);
        assertGoal(helper, vindicator, SiegeMineGoal.class, 0);
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void zombieActuallyFinishesMiningABlockingWall(GameTestHelper helper) {
        Mob zombie = helper.spawn(EntityTypes.ZOMBIE, 1, 1, 2);
        var target = helper.spawn(EntityTypes.ARMOR_STAND, 5, 1, 2);
        target.setInvulnerable(true);
        zombie.setTarget(target);
        SpawnDirector.stampVanillaAi(zombie, 0.90);
        SquadCoordinator.bindStampedSolo(zombie, helper.getLevel());
        Squad memory = new Squad(-1, helper.getLevel());
        memory.alertTo(target.blockPosition());
        ((MobGoalSelectorAccessor) zombie).warband$goalSelector()
                .addGoal(0, new SiegeMineGoal(zombie, memory));

        // The squad memory models a player who was seen before sealing the wall.
        for (int y = 1; y <= 3; y++) {
            for (int x = 4; x <= 6; x++) {
                for (int z = 1; z <= 3; z++) {
                    if (x == 4 || x == 6 || z == 1 || z == 3) {
                        helper.setBlock(x, y, z, Blocks.COBBLESTONE);
                    }
                }
            }
        }

        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(new net.minecraft.core.BlockPos(4, 1, 2)).isAir()
                        || helper.getBlockState(new net.minecraft.core.BlockPos(4, 2, 2)).isAir(),
                "Zombie never completed the breach"));
    }

    private static void assertGoal(GameTestHelper helper, Mob mob,
                                   Class<? extends Goal> type, int priority) {
        for (WrappedGoal wrapped : ((MobGoalSelectorAccessor) mob).warband$goalSelector().getAvailableGoals()) {
            if (type.isInstance(wrapped.getGoal()) && wrapped.getPriority() == priority) return;
        }
        helper.fail("Missing " + type.getSimpleName() + " at priority " + priority);
    }
}
