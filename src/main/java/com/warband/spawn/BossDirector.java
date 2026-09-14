package com.warband.spawn;

import com.warband.config.WarbandConfig;
import com.warband.entity.WarbandAttachments;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonChargePlayerPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonStrafePlayerPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adds Warband phase abilities to vanilla bosses without replacing their core AI. */
public final class BossDirector {

    private static final Map<UUID, Long> NEXT_WITHER_ATTACK = new HashMap<>();
    private static final Map<UUID, Long> NEXT_DRAGON_ATTACK = new HashMap<>();
    private static final Map<UUID, Long> NEXT_DRAGON_BLINK = new HashMap<>();
    private static final Map<UUID, PendingBlink> PENDING_BLINKS = new HashMap<>();
    private static final Map<UUID, UUID> WITHER_MINION_OWNER = new HashMap<>();
    private static final Map<UUID, BossFightState> WITHER_STATE = new HashMap<>();
    private static final Map<UUID, BossFightState> DRAGON_STATE = new HashMap<>();
    /** Boss ability intensity is fixed; Peaceful skips abilities entirely. */
    private static double bossIntensity(ServerLevel level) {
        return level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL ? 0.0 : 1.0;
    }
    private static int tickCounter;
    private static boolean spawningWitherMinion;
    private static final int WITHER_SPAWN_GRACE_TICKS = 20 * 18;

    private static final boolean TRUE_ENDING_MOD_PRESENT = FabricLoader.getInstance().isModLoaded("mr_true_ending");
    private static boolean trueEndingDatapackPresent;

    private BossDirector() {
    }

    public static boolean dragonAbilitiesActive() {
        return WarbandConfig.enderDragonAbilitiesEnabled
                && !TRUE_ENDING_MOD_PRESENT
                && !trueEndingDatapackPresent;
    }

    private static void refreshDatapackDetection(MinecraftServer server) {
        trueEndingDatapackPresent = server.getResourceManager().listPacks()
                .anyMatch(pack -> pack.getNamespaces(net.minecraft.server.packs.PackType.SERVER_DATA).contains("true_ending"));
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(BossDirector::refreshDatapackDetection);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> refreshDatapackDetection(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            boolean dragonActive = dragonAbilitiesActive();
            if (!WarbandConfig.witherAbilitiesEnabled && !dragonActive) return;
            if (dragonActive && !PENDING_BLINKS.isEmpty()) {
                for (ServerLevel level : server.getAllLevels()) {
                    for (Map.Entry<UUID, PendingBlink> entry : PENDING_BLINKS.entrySet()) {
                        PendingBlink pending = entry.getValue();
                        if (!pending.dimension().equals(level.dimension())) continue;
                        if (!(level.getEntity(entry.getKey()) instanceof EnderDragon dragon) || !dragon.isAlive()) continue;
                        dragon.setPos(pending.freezeAt().x, pending.freezeAt().y, pending.freezeAt().z);
                        dragon.setDeltaMovement(Vec3.ZERO);
                    }
                }
            }
            if (++tickCounter < 10) return;
            tickCounter = 0;

            for (ServerLevel level : server.getAllLevels()) {
                double intensity = bossIntensity(level);
                if (intensity <= 0.0) continue;
                AABB active = activePlayerBounds(level);
                if (active == null) continue;
                if (WarbandConfig.witherAbilitiesEnabled) {
                    for (WitherBoss wither : level.getEntitiesOfClass(WitherBoss.class, active, WitherBoss::isAlive)) {
                        tickWither(level, wither, intensity);
                    }
                }
                if (dragonActive) {
                    for (EnderDragon dragon : level.getEntitiesOfClass(EnderDragon.class, active, EnderDragon::isAlive)) {
                        tickDragon(level, dragon, intensity);
                    }
                    resolvePendingBlinks(level);
                }
            }
            cleanupRuntimeState(server.getAllLevels());
        });
    }

    public static boolean isWitherMinionFriendly(Mob a, Mob b) {
        UUID ownerA = WITHER_MINION_OWNER.get(a.getUUID());
        UUID ownerB = WITHER_MINION_OWNER.get(b.getUUID());
        return (ownerA != null && (ownerA.equals(b.getUUID()) || ownerA.equals(ownerB)))
                || (ownerB != null && ownerB.equals(a.getUUID()));
    }

    public static boolean isSpawningWitherMinion() {
        return spawningWitherMinion;
    }

    private static void tickWither(ServerLevel level, WitherBoss wither, double intensity) {
        long now = level.getGameTime();
        LivingEntity target = nearestTarget(level, wither.position(), 96.0);
        if (target == null) return;
        BossFightState state = witherState(wither);

        if (state == BossFightState.OPENING
                && wither.tickCount > WITHER_SPAWN_GRACE_TICKS
                && !Boolean.TRUE.equals(wither.getAttached(WarbandAttachments.BOSS_PHASE_TRIGGERED))
                && wither.getHealth() <= wither.getMaxHealth() * 0.5f) {
            WITHER_STATE.put(wither.getUUID(), BossFightState.PHASE_TWO);
            wither.setAttached(WarbandAttachments.BOSS_PHASE_TRIGGERED, true);
            witherNova(level, wither, intensity);
            summonWitherSkeletons(level, wither, target, 4);
            NEXT_WITHER_ATTACK.put(wither.getUUID(), now + 80);
            return;
        }

        if (state != BossFightState.LAST_STAND
                && wither.tickCount > WITHER_SPAWN_GRACE_TICKS
                && !Boolean.TRUE.equals(wither.getAttached(WarbandAttachments.BOSS_LAST_STAND_TRIGGERED))
                && wither.getHealth() <= wither.getMaxHealth() * 0.18f) {
            WITHER_STATE.put(wither.getUUID(), BossFightState.LAST_STAND);
            wither.setAttached(WarbandAttachments.BOSS_LAST_STAND_TRIGGERED, true);
            witherLastStand(level, wither, target, intensity);
            NEXT_WITHER_ATTACK.put(wither.getUUID(), now + 60);
            return;
        }

        long next = NEXT_WITHER_ATTACK.getOrDefault(wither.getUUID(), 0L);
        if (now < next) return;
        NEXT_WITHER_ATTACK.put(wither.getUUID(), now + 120 + wither.getRandom().nextInt(80));

        if (wither.getRandom().nextBoolean()) {
            witherDash(level, wither, target, intensity);
        } else {
            skullBarrage(level, wither, target.position(), 8, 0.9);
        }
    }

    private static void witherNova(ServerLevel level, WitherBoss wither, double intensity) {
        Vec3 center = wither.position();
        level.playSound(null, center.x, center.y, center.z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.0f, 0.8f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 1.0, center.z, 3, 0.4, 0.4, 0.4, 0.0);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y + 1.0, center.z, 80, 3.0, 2.0, 3.0, 0.08);

        AABB blast = AABB.ofSize(center, 20.0, 12.0, 20.0);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, blast,
                entity -> entity != wither && entity.isAlive() && !isMinionOf(entity, wither));
        for (LivingEntity victim : victims) {
            double distance = Math.max(1.0, victim.distanceTo(wither));
            float damage = (float) Math.max(3.0, (18.0 - distance) * intensity);
            victim.hurtServer(level, wither.damageSources().mobAttack(wither), damage);
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 20 * 8, 1, false, true));
            Vec3 shove = victim.position().subtract(center).normalize().scale(1.35).add(0.0, 0.65, 0.0);
            victim.setDeltaMovement(victim.getDeltaMovement().add(shove));
        }

        skullBarrage(level, wither, center, 16, 1.0);
    }

    private static void witherDash(ServerLevel level, WitherBoss wither, LivingEntity target, double intensity) {
        Vec3 direction = target.position().subtract(wither.position()).normalize();
        wither.setDeltaMovement(direction.scale(1.35).add(0.0, 0.25, 0.0));
        level.playSound(null, wither.getX(), wither.getY(), wither.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.4f, 0.65f);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, wither.getX(), wither.getY() + 1.2, wither.getZ(), 35, 1.0, 0.8, 1.0, 0.04);

        Vec3 impactCenter = wither.position().add(direction.scale(4.0));
        AABB hitbox = AABB.ofSize(impactCenter, 6.0, 5.0, 6.0);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, hitbox,
                entity -> entity != wither && entity.isAlive() && !isMinionOf(entity, wither))) {
            victim.hurtServer(level, wither.damageSources().mobAttack(wither), (float) (10.0 * intensity));
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 20 * 5, 0, false, true));
        }
    }

    private static void witherLastStand(ServerLevel level, WitherBoss wither, LivingEntity target, double intensity) {
        Vec3 center = wither.position();
        level.playSound(null, center.x, center.y, center.z, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.45f);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.2f, 0.55f);
        level.sendParticles(ParticleTypes.SOUL, center.x, center.y + 1.5, center.z, 120, 3.5, 2.5, 3.5, 0.12);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y + 1.5, center.z, 90, 2.5, 2.0, 2.5, 0.08);

        wither.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 10, 1, false, true));
        wither.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 18, 1, false, true));
        wither.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 18, 0, false, true));

        Vec3 focus = target.position();
        for (int i = 0; i < 6; i++) {
            Vec3 start = center.add(
                    wither.getRandom().nextDouble() * 8.0 - 4.0,
                    wither.getRandom().nextDouble() * 4.0,
                    wither.getRandom().nextDouble() * 8.0 - 4.0);
            Vec3 direction = focus.subtract(start).normalize();
            for (int step = 0; step < 10; step++) {
                Vec3 point = start.add(direction.scale(step * 1.5));
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }

        skullBarrage(level, wither, focus, 12, 1.05);
        witherDash(level, wither, target, intensity);
    }

    private static void skullBarrage(ServerLevel level, WitherBoss wither, Vec3 focus, int count, double speed) {
        Vec3 center = wither.position().add(0.0, 1.4, 0.0);
        for (int i = 0; i < count; i++) {
            Vec3 direction;
            if (focus.distanceToSqr(wither.position()) < 4.0) {
                double angle = (Math.PI * 2.0 * i) / count;
                direction = new Vec3(Math.cos(angle), (i % 5 - 2) * 0.08, Math.sin(angle)).normalize();
            } else {
                Vec3 jitter = new Vec3(wither.getRandom().nextDouble() - 0.5, wither.getRandom().nextDouble() * 0.35, wither.getRandom().nextDouble() - 0.5).scale(3.0);
                direction = focus.add(jitter).subtract(center).normalize();
            }
            Entity skull = EntityTypes.WITHER_SKULL.create(level, EntitySpawnReason.EVENT);
            if (skull == null) continue;
            if (skull instanceof Projectile projectile) {
                projectile.setOwner(wither);
            }
            skull.setPos(center.x + direction.x * 1.8, center.y, center.z + direction.z * 1.8);
            skull.setDeltaMovement(direction.scale(speed));
            level.addFreshEntity(skull);
        }
        level.playSound(null, center.x, center.y, center.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.3f, 0.85f);
    }

    private static void summonWitherSkeletons(ServerLevel level, WitherBoss wither, LivingEntity target, int count) {
        BlockPos origin = wither.blockPosition();
        for (int i = 0; i < count; i++) {
            BlockPos pos = origin.offset(wither.getRandom().nextInt(9) - 4, 0, wither.getRandom().nextInt(9) - 4);
            WitherSkeleton skeleton;
            spawningWitherMinion = true;
            try {
                skeleton = EntityTypes.WITHER_SKELETON.spawn(level, pos, EntitySpawnReason.EVENT);
            } finally {
                spawningWitherMinion = false;
            }
            if (skeleton != null) {
                WITHER_MINION_OWNER.put(skeleton.getUUID(), wither.getUUID());
                skeleton.setTarget(target);
                skeleton.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 20, 0, false, true));
            }
        }
    }

    private static boolean isMinionOf(LivingEntity entity, WitherBoss wither) {
        return entity instanceof Mob mob && wither.getUUID().equals(WITHER_MINION_OWNER.get(mob.getUUID()));
    }

    private static void tickDragon(ServerLevel level, EnderDragon dragon, double intensity) {
        long now = level.getGameTime();
        LivingEntity target = nearestTarget(level, dragon.position(), 64.0);
        if (target == null) return;
        BossFightState state = dragonState(dragon);

        long next = NEXT_DRAGON_ATTACK.getOrDefault(dragon.getUUID(), 0L);
        if (now < next) {
            EnderDragonPhase<?> current = dragon.getPhaseManager().getCurrentPhase().getPhase();
            if (current == EnderDragonPhase.HOLDING_PATTERN) {
                dragon.getPhaseManager().setPhase(EnderDragonPhase.STRAFE_PLAYER);
                DragonStrafePlayerPhase strafe = dragon.getPhaseManager().getPhase(EnderDragonPhase.STRAFE_PLAYER);
                if (strafe != null) {
                    strafe.setTarget(target);
                }
            }
            return;
        }
        if (state != BossFightState.LAST_STAND && dragon.getHealth() <= dragon.getMaxHealth() * 0.25f) {
            DRAGON_STATE.put(dragon.getUUID(), BossFightState.LAST_STAND);
            state = BossFightState.LAST_STAND;
        } else if (state == BossFightState.OPENING && dragon.getHealth() <= dragon.getMaxHealth() * 0.6f) {
            DRAGON_STATE.put(dragon.getUUID(), BossFightState.PHASE_TWO);
            state = BossFightState.PHASE_TWO;
        }
        NEXT_DRAGON_ATTACK.put(dragon.getUUID(), state == BossFightState.LAST_STAND
                ? now + 55 + dragon.getRandom().nextInt(45)
                : now + 80 + dragon.getRandom().nextInt(60));

        boolean blinkReady = now >= NEXT_DRAGON_BLINK.getOrDefault(dragon.getUUID(), 0L)
                && !PENDING_BLINKS.containsKey(dragon.getUUID());
        if (blinkReady && state != BossFightState.OPENING && dragon.getRandom().nextInt(5) == 0) {
            beginDragonBlink(level, dragon, target);
            return;
        }

        int roll = dragon.getRandom().nextInt(state == BossFightState.OPENING ? 3 : 4);
        if (roll <= 1) {
            dragonCharge(level, dragon, target, intensity);
        } else if (roll == 2) {
            dragonEnderGale(level, dragon, target, intensity);
        } else {
            dragonBreathLine(level, dragon, target, intensity);
        }
    }

    private static void beginDragonBlink(ServerLevel level, EnderDragon dragon, LivingEntity target) {
        Vec3 origin = dragon.position();
        Vec3 behind = target.position().subtract(target.getLookAngle().scale(18.0)).add(0.0, 10.0, 0.0);
        Vec3 destination = new Vec3(behind.x, Math.max(level.getMinY() + 16.0, behind.y), behind.z);

        long now = level.getGameTime();
        PENDING_BLINKS.put(dragon.getUUID(), new PendingBlink(level.dimension(), now + 34, destination, target.getUUID(), origin));
        NEXT_DRAGON_BLINK.put(dragon.getUUID(), now + 20 * 45L + dragon.getRandom().nextInt(20 * 25));
        NEXT_DRAGON_ATTACK.put(dragon.getUUID(), now + 80);

        dragon.setDeltaMovement(Vec3.ZERO);
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.6f, 0.55f);
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 1.2f, 0.65f);
        level.sendParticles(ParticleTypes.PORTAL, origin.x, origin.y + 2.0, origin.z, 180, 5.0, 3.0, 5.0, 0.18);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, destination.x, destination.y + 2.0, destination.z, 90, 4.0, 2.5, 4.0, 0.08);
    }

    private static void resolvePendingBlinks(ServerLevel level) {
        long now = level.getGameTime();
        PENDING_BLINKS.entrySet().removeIf(entry -> {
            PendingBlink pending = entry.getValue();
            if (!pending.dimension().equals(level.dimension())) return false;
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof EnderDragon dragon) || !dragon.isAlive()) return true;

            if (now < pending.executeAt()) {
                dragon.setDeltaMovement(Vec3.ZERO);
                Vec3 pos = dragon.position();
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 2.0, pos.z, 70, 5.0, 3.0, 5.0, 0.03);
                level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 2.0, pos.z, 35, 4.0, 2.0, 4.0, 0.01);
                return false;
            }

            Vec3 old = dragon.position();
            Vec3 destination = pending.destination();
            dragon.setPos(destination.x, destination.y, destination.z);

            Entity targetEntity = level.getEntity(pending.targetId());
            if (targetEntity instanceof LivingEntity target && target.isAlive()) {
                dragon.setDeltaMovement(target.position().subtract(dragon.position()).normalize().scale(1.05));
            }

            level.playSound(null, old.x, old.y, old.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.55f);
            level.playSound(null, destination.x, destination.y, destination.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.85f);
            level.sendParticles(ParticleTypes.PORTAL, old.x, old.y + 2.0, old.z, 120, 4.0, 2.0, 4.0, 0.35);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, destination.x, destination.y + 2.0, destination.z, 160, 4.0, 2.5, 4.0, 0.25);
            return true;
        });
    }

    private static void dragonCharge(ServerLevel level, EnderDragon dragon, LivingEntity target, double intensity) {
        Vec3 direction = target.position().subtract(dragon.position()).normalize();
        dragon.getPhaseManager().setPhase(EnderDragonPhase.CHARGING_PLAYER);
        DragonChargePlayerPhase charge = dragon.getPhaseManager().getPhase(EnderDragonPhase.CHARGING_PLAYER);
        if (charge != null) {
            charge.setTarget(target.position());
        }
        dragon.setDeltaMovement(direction.scale(1.65).add(0.0, -0.05, 0.0));
        level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.7f, 0.8f);
        level.sendParticles(ParticleTypes.PORTAL, dragon.getX(), dragon.getY() + 2.0, dragon.getZ(), 45, 2.0, 1.0, 2.0, 0.08);

        AABB impact = AABB.ofSize(target.position(), 9.0, 6.0, 9.0);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, impact, entity -> entity != dragon && entity.isAlive())) {
            victim.hurtServer(level, dragon.damageSources().mobAttack(dragon), (float) (12.0 * intensity));
            victim.setDeltaMovement(victim.getDeltaMovement().add(direction.scale(1.2)).add(0.0, 0.45, 0.0));
        }
    }

    private static void dragonBreathLine(ServerLevel level, EnderDragon dragon, LivingEntity target, double intensity) {
        Vec3 start = dragon.position().add(0.0, 2.0, 0.0);
        double distanceToTarget = target.position().distanceTo(start);
        if (distanceToTarget > 32.0) {
            dragonCharge(level, dragon, target, intensity);
            return;
        }
        int maxRange = (int) Math.min(28.0, distanceToTarget + 4.0);
        Vec3 direction = target.position().subtract(start).normalize();
        level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 1.5f, 0.9f);

        for (int i = 2; i <= maxRange; i += 2) {
            Vec3 point = start.add(direction.scale(i));
            level.sendParticles(ParticleTypes.WITCH, point.x, point.y, point.z, 12, 1.2, 0.6, 1.2, 0.04);
            AABB cloud = AABB.ofSize(point, 4.0, 3.0, 4.0);
            for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, cloud, entity -> entity != dragon && entity.isAlive())) {
                victim.hurtServer(level, dragon.damageSources().magic(), (float) (4.0 * intensity));
            }
        }
    }

    private static void dragonEnderGale(ServerLevel level, EnderDragon dragon, LivingEntity target, double intensity) {
        Vec3 center = target.position();
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 1.8f, 0.65f);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.9f, 0.8f);

        for (int ring = 0; ring < 3; ring++) {
            double radius = 3.5 + ring * 2.5;
            for (int i = 0; i < 18; i++) {
                double angle = (Math.PI * 2.0 * i) / 18.0 + ring * 0.35;
                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, center.y + 0.4 + ring * 0.35, z, 3, 0.08, 0.08, 0.08, 0.0);
            }
        }

        AABB gale = AABB.ofSize(center, 18.0, 10.0, 18.0);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, gale, entity -> entity != dragon && entity.isAlive())) {
            Vec3 pull = center.subtract(victim.position());
            double distance = Math.max(1.0, pull.length());
            Vec3 motion = pull.normalize().scale(Math.min(1.1, 4.0 / distance)).add(0.0, 0.35, 0.0);
            victim.setDeltaMovement(victim.getDeltaMovement().add(motion));
            if (distance < 4.0) {
                victim.hurtServer(level, dragon.damageSources().magic(), (float) (5.0 * intensity));
            }
        }
    }

    private static LivingEntity nearestTarget(ServerLevel level, Vec3 pos, double radius) {
        Player player = level.getNearestPlayer(pos.x, pos.y, pos.z, radius, false);
        return player != null && player.isAlive() ? player : null;
    }

    private static AABB activePlayerBounds(ServerLevel level) {
        if (level.players().isEmpty()) return null;
        AABB bounds = null;
        for (ServerPlayer player : level.players()) {
            AABB box = AABB.ofSize(player.position(), 768.0, 384.0, 768.0);
            bounds = bounds == null ? box : bounds.minmax(box);
        }
        return bounds;
    }

    private static BossFightState witherState(WitherBoss wither) {
        BossFightState existing = WITHER_STATE.get(wither.getUUID());
        if (existing != null) return existing;
        BossFightState state = Boolean.TRUE.equals(wither.getAttached(WarbandAttachments.BOSS_LAST_STAND_TRIGGERED))
                ? BossFightState.LAST_STAND
                : Boolean.TRUE.equals(wither.getAttached(WarbandAttachments.BOSS_PHASE_TRIGGERED))
                ? BossFightState.PHASE_TWO
                : BossFightState.OPENING;
        WITHER_STATE.put(wither.getUUID(), state);
        return state;
    }

    private static BossFightState dragonState(EnderDragon dragon) {
        return DRAGON_STATE.computeIfAbsent(dragon.getUUID(), ignored -> BossFightState.OPENING);
    }

    private static void cleanupRuntimeState(Iterable<ServerLevel> levels) {
        NEXT_WITHER_ATTACK.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        NEXT_DRAGON_ATTACK.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        NEXT_DRAGON_BLINK.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        PENDING_BLINKS.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        WITHER_MINION_OWNER.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        WITHER_STATE.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
        DRAGON_STATE.keySet().removeIf(uuid -> !entityAlive(levels, uuid));
    }

    private static boolean entityAlive(Iterable<ServerLevel> levels, UUID uuid) {
        for (ServerLevel level : levels) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive()) return true;
        }
        return false;
    }

    private enum BossFightState {
        OPENING,
        PHASE_TWO,
        LAST_STAND
    }

    private record PendingBlink(net.minecraft.resources.ResourceKey<Level> dimension, long executeAt, Vec3 destination, UUID targetId, Vec3 freezeAt) {
    }
}
