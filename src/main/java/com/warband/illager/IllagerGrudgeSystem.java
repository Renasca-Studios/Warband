package com.warband.illager;

import com.warband.advancement.WarbandCriteria;
import com.warband.ai.SquadCoordinator;
import com.warband.ai.TacticalEffects;
import com.warband.ai.MultiplayerDirector;
import com.warband.compat.IllagerKinds;
import com.warband.compat.RaidCompat;
import com.warband.compat.StructureCompat;
import com.warband.config.WarbandConfig;
import com.warband.entity.MobData;
import com.warband.entity.Role;
import com.warband.entity.WarbandAttachments;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import com.warband.entity.IllagerIdentity;
import com.warband.text.WarbandText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local "raid ledger" loop for named illager survivors and revenge patrols. */
public final class IllagerGrudgeSystem {

    private static final int SURVIVE_CONFIRM_TICKS = 20 * 60;
    private static final int SURVIVOR_EXPIRY_TICKS = 20 * 60 * 10;
    private static final int REVENGE_DELAY_TICKS = 20 * 60 * 3;
    private static final int RETRY_DELAY_TICKS = 20 * 60 * 3;
    /** If the player is within this of a grudge's origin, revenge musters there. */
    private static final int ORIGIN_MUSTER_RANGE = 160;
    private static final int SCAN_INTERVAL_TICKS = 20 * 10;
    private static final int MAX_GRUDGES_PER_PLAYER = 8;
    private static final int MAX_REPUTATION_RECORDS = 5;
    private static final int BOUNTY_HEAT_THRESHOLD = 100;
    private static final int BOUNTY_RETRY_TICKS = 20 * 60 * 10;
    private static final int SPAWN_MIN_DISTANCE = 28;
    private static final int SPAWN_RANGE = 18;
    private static final double WITNESS_RADIUS = 28.0;
    private static final int RIVAL_INTERCEPT_CHANCE = 25;
    /** Faction heat added per illager killed inside that faction's mansion. */
    private static final int MANSION_HEAT_PER_KILL = 8;
    /** Small heat drip for any factioned illager killed in the open. Builds toward bounty heat over time. */
    private static final int FIELD_HEAT_PER_KILL = 2;
    /** Witness count above which the notability filter is relaxed — a crowd remembers regardless of rank. */
    private static final int CROWD_WITNESS_THRESHOLD = 3;
    /** Cooldown between unprompted WAR-state patrols (per faction, per player). */
    private static final int WAR_PATROL_COOLDOWN_TICKS = 20 * 60 * 12;
    /** Cooldown between full CRUSADE assaults (per faction, per player). */
    private static final int CRUSADE_COOLDOWN_TICKS = 20 * 60 * 30;
    /** Heat shed by a faction whenever it commits a crusade — wars cannot stay perpetually maxed. */
    private static final int CRUSADE_HEAT_RELIEF = 35;

    private static final Map<UUID, PendingSurvivor> PENDING = new HashMap<>();
    private static int tickCounter;

    private IllagerGrudgeSystem() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(IllagerGrudgeSystem::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(IllagerGrudgeSystem::afterDeath);
        ServerTickEvents.END_SERVER_TICK.register(IllagerGrudgeSystem::tick);
    }

    /** Bounty hunters survive a single killing blow at 50% HP — totem-style revive without the item. */
    public static boolean tryBountyRevive(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Mob mob)) return false;
        if (!Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.BOUNTY_HUNTER))) return false;
        if (Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.BOUNTY_REVIVED))) return false;
        mob.setAttached(WarbandAttachments.BOUNTY_REVIVED, true);
        mob.setHealth(mob.getMaxHealth() * 0.5f);
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.REGENERATION, 20 * 6, 1, false, true));
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.STRENGTH, 20 * 30, 1, false, true));
        if (entity.level() instanceof ServerLevel level) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                    net.minecraft.sounds.SoundEvents.TOTEM_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1.6f, 0.7f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING,
                    mob.getX(), mob.getY() + 1.0, mob.getZ(), 80, 0.6, 1.0, 0.6, 0.25);
            if (source.getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(WarbandText.quoted("warband.grudge.totem",
                        "Not yet. Not like this."), true);
            }
        }
        return true;
    }

    private static void afterDamage(LivingEntity entity, DamageSource source,
                                    float baseDamageTaken, float damageTaken, boolean blocked) {
        if (!(entity instanceof Mob mob) || damageTaken <= 0.0f || entity.isDeadOrDying()) return;
        if (!WarbandConfig.illagerGrudgesEnabled) return;
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        if (!IllagerKinds.isIllagerLike(mob) || !MobData.isStamped(mob) || RaidCompat.isActiveRaider(mob)) {
            return;
        }
        if (isGrudgeSpawned(mob)) return;
        if (isBrokenSeatMob(mob)) return;
        // Only badly wounded illagers stir their comrades, and only the first such
        // hit scans, the victim lands in PENDING below, so a drawn-out fight is
        // not a per-swing entity scan.
        if (mob.getHealth() / mob.getMaxHealth() > 0.5f) return;
        if (PENDING.containsKey(mob.getUUID())) return;
        // A mansion is a faction seat, the consequence lands on the kill (heat),
        // handled in afterDeath; no field-style witness grudges here.
        if (inFactionSeat(mob)) return;

        for (ServerPlayer participant : participantsNear((ServerLevel) mob.level(), mob, player)) {
            recordWitnesses((ServerLevel) mob.level(), mob, participant, true, false, source);
        }
    }

    private static void afterDeath(LivingEntity entity, DamageSource source) {
        boolean warmarshalSlain = handleWarmarshalDeath(entity, source);
        if (WarbandConfig.illagerGrudgesEnabled
                && entity instanceof Mob mob
                && source.getEntity() instanceof ServerPlayer player
                && IllagerKinds.isIllagerLike(mob)
                && MobData.isStamped(mob)
                && !isGrudgeSpawned(mob)
                && !RaidCompat.isActiveRaider(mob)
                && !warmarshalSlain
                && !isBrokenSeatMob(mob)) {
            IllagerFaction faction = IllagerFactionSystem.factionOrDefault(mob);
            ServerLevel serverLevel = (ServerLevel) mob.level();
            IllagerFaction territoryOwner = IllagerTerritory.factionAt(serverLevel, mob.blockPosition());
            long now = mob.level().getGameTime();
            if (inFactionSeat(mob)) {
                // Assaulting a faction's seat raises its heat (→ a bounty hunter)
                // rather than mustering revenge patrols back into the ruin.
                for (ServerPlayer participant : participantsNear(serverLevel, mob, player)) {
                    addReputation(participant, faction,
                            applyTerritoryMultiplier(MANSION_HEAT_PER_KILL, faction, territoryOwner),
                            now + BOUNTY_RETRY_TICKS);
                }
            } else {
                for (ServerPlayer participant : participantsNear(serverLevel, mob, player)) {
                    // Every factioned kill nudges heat — slow drip so /warband intel
                    // shows the faction is "watching" even before a notable grudge forms.
                    addReputation(participant, faction,
                            applyTerritoryMultiplier(FIELD_HEAT_PER_KILL, faction, territoryOwner),
                            now + BOUNTY_RETRY_TICKS);
                    boolean firstKill = !Boolean.TRUE.equals(participant.getAttached(WarbandAttachments.FIRST_KILL_GRACE_USED));
                    recordWitnesses((ServerLevel) mob.level(), mob, participant, true, firstKill, source);
                    if (firstKill) {
                        participant.setAttached(WarbandAttachments.FIRST_KILL_GRACE_USED, true);
                        WarbandCriteria.fire(participant, WarbandCriteria.FACTION_NOTICED);
                    }
                }
            }
        }
        maybeDropBountyReward(entity);
        PENDING.remove(entity.getUUID());
    }

    private static void maybeDropBountyReward(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) return;
        if (!Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.BOUNTY_HUNTER))) return;
        RandomSource random = mob.getRandom();
        mob.spawnAtLocation((ServerLevel) mob.level(), new ItemStack(Items.EMERALD, 6 + random.nextInt(7)));
        mob.spawnAtLocation((ServerLevel) mob.level(), new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + random.nextInt(4)));
        if (random.nextFloat() < 0.45f) {
            mob.spawnAtLocation((ServerLevel) mob.level(), new ItemStack(Items.GOLDEN_APPLE));
        }
        if (random.nextFloat() < 0.20f) {
            mob.spawnAtLocation((ServerLevel) mob.level(), new ItemStack(Items.DIAMOND));
        }
        if (random.nextFloat() < 0.18f) {
            mob.spawnAtLocation((ServerLevel) mob.level(), new ItemStack(Items.OMINOUS_BOTTLE));
        }
    }

    /** A slain Warmarshal breaks its faction, clears the killer's grudges and heat with it. */
    private static boolean handleWarmarshalDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Mob mob)) return false;
        if (!Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.WARMARSHAL))) return false;
        if (!(source.getEntity() instanceof ServerPlayer player)) return false;

        IllagerFaction faction = IllagerFactionSystem.factionOrDefault(mob);
        if (mob.level() instanceof ServerLevel level) {
            String key = mob.getAttached(WarbandAttachments.STRONGHOLD_SEAT_KEY);
            if (key != null && !key.isEmpty()) {
                StrongholdGarrison.markSeatBroken(level, key);
            } else {
                StrongholdGarrison.markSeatBroken(level, mob.blockPosition());
            }
        }
        clearFaction(player, faction);
        WarbandCriteria.fire((ServerPlayer) source.getEntity(), WarbandCriteria.WARMARSHAL_SLAIN);
        player.sendSystemMessage(Component.translatableWithFallback("warband.faction.disarray",
                "The %s falls into disarray, its Warmarshal is dead.", faction.displayName()));
        return true;
    }

    /**
     * One-time explainer the first time a player earns heat with any faction.
     *
     * <p>Every other faction message announces a <i>consequence</i> — a revenge
     * party, a war patrol, a bounty hunter. None of them establish that illagers
     * belong to rival factions that remember you, so players hit those messages
     * with no idea what system produced them, or whether they had triggered
     * anything at all. Sent once, in chat rather than the action bar, because
     * the action bar is where the mod's own reports said a name flashed past
     * before it could be read.
     */
    private static void introduceFactions(ServerPlayer player, IllagerFaction faction) {
        if (!WarbandConfig.illagerFactionsEnabled) return;
        if (Boolean.TRUE.equals(player.getAttached(WarbandAttachments.FACTION_INTRODUCED))) return;
        player.setAttached(WarbandAttachments.FACTION_INTRODUCED, true);

        player.sendSystemMessage(Component.translatableWithFallback("warband.faction.marked",
                "The %s have marked you.", faction.displayName()).withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatableWithFallback("warband.faction.intro",
                "Illagers belong to five rival factions. They remember who kills their own, "
                        + "and send patrols, revenge parties and bounty hunters after them. "
                        + "Helping a faction's rival cools them down.").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.translatableWithFallback("warband.faction.intro_hint",
                "Check where you stand with %s.",
                Component.literal("/warband intel").withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GRAY));
    }

    /** Wipe every faction's grudges and reputation from this player. Op-side full reset. */
    public static int clearAllForPlayer(ServerPlayer player) {
        int cleared = 0;
        List<IllagerGrudge> grudges = grudges(player);
        if (!grudges.isEmpty()) {
            cleared += grudges.size();
            player.setAttached(WarbandAttachments.ILLAGER_GRUDGES, List.of());
        }
        List<FactionReputation> reputations = reputations(player);
        if (!reputations.isEmpty()) {
            cleared += reputations.size();
            player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, List.of());
        }
        return cleared;
    }

    /** Public entry point for op-side single-faction wipe. */
    public static void clearFactionForPlayer(ServerPlayer player, IllagerFaction faction) {
        clearFaction(player, faction);
    }

    /** Wipe a faction's grudges and reputation from a player, its war with them is over. */
    private static void clearFaction(ServerPlayer player, IllagerFaction faction) {
        List<IllagerGrudge> grudges = new ArrayList<>(grudges(player));
        if (grudges.removeIf(grudge -> grudge.faction() == faction)) {
            player.setAttached(WarbandAttachments.ILLAGER_GRUDGES, List.copyOf(grudges));
        }
        List<FactionReputation> reputations = new ArrayList<>(reputations(player));
        if (reputations.removeIf(reputation -> reputation.faction() == faction)) {
            player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, List.copyOf(reputations));
        }
    }

    private static boolean inFactionSeat(Mob mob) {
        return mob.level() instanceof ServerLevel level
                && StructureCompat.inFactionSeat(level, mob.blockPosition());
    }

    private static boolean isBrokenSeatMob(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        String attachedKey = mob.getAttached(WarbandAttachments.STRONGHOLD_SEAT_KEY);
        if (attachedKey != null && !attachedKey.isEmpty() && SeatOfPowerState.get(level).isBroken(attachedKey)) {
            return true;
        }
        return StrongholdGarrison.isSeatBroken(level, mob.blockPosition());
    }

    private static void recordWitnesses(ServerLevel level, Mob victim, ServerPlayer player,
                                        boolean includeVictim, boolean relaxNotable,
                                        DamageSource source) {
        long now = level.getGameTime();
        // The lesson these witnesses take away: how they watched their ally die.
        IllagerScar scar = IllagerScar.fromDamage(source);
        long originPos = victim.blockPosition().asLong();
        String originDimension = level.dimension().toString();
        AABB box = AABB.ofSize(victim.position(), WITNESS_RADIUS * 2.0, WITNESS_RADIUS, WITNESS_RADIUS * 2.0);
        // First pass: every eligible illager in range, without the notability filter,
        // so we can apply the "crowd remembers" rule based on the raw witness count.
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, box, mob ->
                mob.isAlive()
                        && MobData.isStamped(mob)
                        && IllagerKinds.isIllagerLike(mob)
                        && !isGrudgeSpawned(mob)
                        && (includeVictim || mob != victim)
                        && (mob == victim || mob.hasLineOfSight(victim) || mob.distanceToSqr(victim) < 12.0 * 12.0));

        // A crowd (3+) remembers any kill; otherwise only notable mobs / banner-carriers
        // form grudges. relaxNotable also skips the filter (used for the player's first
        // factioned kill, so the system always introduces itself at least once).
        List<Mob> witnesses;
        if (relaxNotable || candidates.size() >= CROWD_WITNESS_THRESHOLD) {
            witnesses = candidates;
        } else {
            witnesses = new ArrayList<>();
            for (Mob candidate : candidates) {
                if (isNotable(candidate) || carriesFactionBanner(candidate)) {
                    witnesses.add(candidate);
                }
            }
        }

        for (Mob witness : witnesses) {
            if (witness == victim && witness.isDeadOrDying()) continue;
            // The bare name, never the rendered display name: this gets persisted, and save
            // data must not depend on how the mob happens to be labelled right now.
            String name = IllagerIdentity.personalName(witness);
            PENDING.put(witness.getUUID(), new PendingSurvivor(
                    player.getUUID(),
                    name,
                    IllagerFactionSystem.factionOrDefault(witness),
                    Math.max(MobData.get(witness).difficulty(), MobData.get(victim).difficulty()),
                    now + SURVIVE_CONFIRM_TICKS,
                    now + SURVIVOR_EXPIRY_TICKS,
                    originPos,
                    originDimension,
                    scar
            ));
        }
    }

    private static void tick(MinecraftServer server) {
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        long now = server.overworld().getGameTime();
        confirmSurvivors(server, now);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            decayReputation(player);
            maybeLaunchRevenge(player, now);
            maybeLaunchBountyHunter(player, now);
            maybeLaunchVengeance(player, now);
        }
    }

    /**
     * Per-tick-scan passive heat decay so a player who stays out of a faction's
     * affairs cools off over real time. ~1 heat per 10s of online time per
     * faction (scan interval is 200 ticks). Full cool-down from max heat takes
     * roughly 20 minutes online — slow enough that an active conflict still
     * bombards, fast enough that "I'll just lay low" is a viable escape.
     */
    private static void decayReputation(ServerPlayer player) {
        List<FactionReputation> reputations = reputations(player);
        if (reputations.isEmpty()) return;
        List<FactionReputation> updated = new ArrayList<>(reputations.size());
        boolean changed = false;
        for (FactionReputation r : reputations) {
            if (r.heat() <= 0) { updated.add(r); continue; }
            int newHeat = Math.max(0, r.heat() - 1);
            if (newHeat != r.heat()) changed = true;
            updated.add(new FactionReputation(r.faction(), newHeat,
                    r.bountyReadyAt(), r.warReadyAt(), r.crusadeReadyAt()));
        }
        if (changed) {
            player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, List.copyOf(updated));
        }
    }

    private static void confirmSurvivors(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, PendingSurvivor>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingSurvivor pending = iterator.next().getValue();
            if (now < pending.confirmAt) continue;

            // The witness was not killed inside the confirm window, it got away.
            // Form the grudge on the timer alone; do NOT require the mob to still
            // be loaded. (afterDeath removes killed witnesses from PENDING, and a
            // chunk unload would otherwise silently drop every grudge.)
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
            if (player != null) {
                addGrudge(player, pending);
                iterator.remove();
            } else if (now > pending.expiresAt) {
                iterator.remove();
            }
        }
    }

    private static void addGrudge(ServerPlayer player, PendingSurvivor survivor) {
        long readyAt = player.level().getGameTime() + REVENGE_DELAY_TICKS;
        List<IllagerGrudge> grudges = new ArrayList<>(grudges(player));
        for (int i = 0; i < grudges.size(); i++) {
            IllagerGrudge existing = grudges.get(i);
            if (existing.personalName().equals(survivor.name) && existing.faction() == survivor.faction) {
                grudges.set(i, existing.addAnger(20, Math.min(existing.readyAt(), readyAt))
                        .withScar(survivor.scar));
                player.setAttached(WarbandAttachments.ILLAGER_GRUDGES, trim(grudges));
                addReputation(player, survivor.faction, 10, readyAt + BOUNTY_RETRY_TICKS);
                return;
            }
        }
        grudges.add(new IllagerGrudge(survivor.name, survivor.faction, survivor.difficulty, 40, readyAt, 0,
                survivor.originPos, survivor.originDimension, survivor.scar));
        player.setAttached(WarbandAttachments.ILLAGER_GRUDGES, trim(grudges));
        addReputation(player, survivor.faction, 20, readyAt + BOUNTY_RETRY_TICKS);
        // "Slipped away" introduces a named survivor — keep this in chat, not action bar.
        player.sendSystemMessage(Component.translatableWithFallback("warband.grudge.escaped",
                "%1$s slipped away, the %2$s will remember this.",
                survivor.name, survivor.faction.displayName()));
        WarbandCriteria.fire(player, WarbandCriteria.FIRST_GRUDGE);
    }

    private static void maybeLaunchRevenge(ServerPlayer player, long now) {
        if (!WarbandConfig.illagerGrudgesEnabled) return;
        List<IllagerGrudge> grudges = new ArrayList<>(grudges(player));
        if (grudges.isEmpty()) return;

        for (int i = 0; i < grudges.size(); i++) {
            IllagerGrudge grudge = grudges.get(i);
            if (grudge.readyAt() > now || grudge.attempts() >= 3) continue;
            if (player.getRandom().nextFloat() > 0.35f) {
                // Random gate miss — reschedule without burning an attempt, otherwise
                // unlucky rolls can quietly retire a grudge in ~9 minutes with no patrol spawned.
                grudges.set(i, new IllagerGrudge(grudge.personalName(), grudge.faction(), grudge.difficulty(),
                        grudge.anger(), now + RETRY_DELAY_TICKS, grudge.attempts(),
                        grudge.originPos(), grudge.originDimension(), grudge.scar()));
                break;
            }
            List<IllagerGrudge> party = readyFactionGrudges(grudges, grudge.faction(), now);
            if (spawnRevengePatrol(player, party)) {
                grudges.removeIf(candidate -> party.contains(candidate));
            } else {
                for (int j = 0; j < grudges.size(); j++) {
                    IllagerGrudge candidate = grudges.get(j);
                    if (party.contains(candidate)) {
                        grudges.set(j, candidate.attempted(now + RETRY_DELAY_TICKS));
                    }
                }
            }
            break;
        }
        player.setAttached(WarbandAttachments.ILLAGER_GRUDGES, trim(grudges));
    }

    public static boolean debugSpawnBountyHunter(ServerPlayer player, double difficulty) {
        IllagerFaction faction = IllagerFaction.pick(player.getUUID().hashCode() ^ player.getBlockX() ^ player.getBlockZ());
        return spawnBountyHunterFor(player, faction, difficulty);
    }

    /**
     * Spawn a faction bounty hunter against this player. Public entry point for
     * non-grudge contexts (e.g. high-ominous raid finale).
     */
    public static boolean spawnBountyHunterFor(ServerPlayer player, IllagerFaction faction, double difficulty) {
        FactionReputation reputation = new FactionReputation(faction,
                Math.max(BOUNTY_HEAT_THRESHOLD, (int) Math.round(difficulty * 200)), 0L, 0L, 0L);
        return spawnBountyHunter(player, reputation);
    }

    public static boolean debugSpawnRevengeParty(ServerPlayer player, double difficulty) {
        IllagerFaction faction = IllagerFaction.pick(player.getUUID().hashCode() + player.getBlockX() * 31 + player.getBlockZ());
        IllagerGrudge grudge = new IllagerGrudge("Debug", faction, (float) difficulty, 60, 0, 0,
                player.blockPosition().asLong(), player.level().dimension().toString(), IllagerScar.BLADE);
        return spawnRevengePatrol(player, List.of(grudge));
    }

    /**
     * The {@code /warband intel} readout.
     *
     * <p>Components rather than strings: this is the one command output a normal player
     * reads rather than an operator debugging a server, so it goes through the same
     * translation path as every other player-facing message.
     */
    public static List<Component> intelLines(ServerPlayer player) {
        List<Component> lines = new ArrayList<>();
        List<IllagerGrudge> grudges = grudges(player);
        List<FactionReputation> reputations = reputations(player);
        if (grudges.isEmpty() && reputations.isEmpty()) {
            return List.of(Component.translatableWithFallback("warband.intel.none",
                    "No faction intel on you yet. Kill a factioned illager and they will start to take notice."));
        }
        if (!reputations.isEmpty()) {
            lines.add(Component.translatableWithFallback("warband.intel.heat", "Faction heat:"));
            for (FactionReputation reputation : reputations) {
                lines.add(Component.translatableWithFallback("warband.intel.heat_line", "  %1$s: %2$s (%3$s)",
                        reputation.faction().displayName(), reputation.heat(),
                        reputation.state().displayName()));
            }
        }
        if (!grudges.isEmpty()) {
            lines.add(Component.translatableWithFallback("warband.intel.survivors", "Known survivors:"));
            for (IllagerGrudge grudge : grudges) {
                lines.add(Component.translatableWithFallback("warband.intel.survivor_line",
                        "  %1$s / %2$s anger %3$s attempts %4$s %5$s",
                        grudge.personalName(), grudge.faction().displayName(),
                        grudge.anger(), grudge.attempts(), grudge.scar().label()));
            }
        } else if (!reputations.isEmpty()) {
            lines.add(Component.translatableWithFallback("warband.intel.no_survivors",
                    "No named survivors yet — no notable illagers escaped to remember you."));
        }
        return lines;
    }

    private static boolean spawnRevengePatrol(ServerPlayer player, List<IllagerGrudge> grudges) {
        if (grudges.isEmpty()) return false;
        IllagerFaction faction = grudges.getFirst().faction();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos origin = findRevengeSpawn(level, player, grudges.getFirst());
        if (origin == null) return false;

        double difficulty = Math.max(0.45, maxDifficulty(grudges));
        int anger = totalAnger(grudges);
        int size = Math.min(7 + MultiplayerDirector.revengePartyBonus(level, player.blockPosition()),
                Math.max(grudges.size(), 2 + anger / 35 + (int) Math.floor(difficulty * 2.0)
                        + MultiplayerDirector.revengePartyBonus(level, player.blockPosition())));
        List<Mob> spawned = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = origin.offset(player.getRandom().nextInt(7) - 3, 0, player.getRandom().nextInt(7) - 3);
            Mob mob = spawnMember(level, pos, difficulty, i);
            if (mob == null) continue;
            IllagerFactionSystem.setFaction(mob, faction);
            markGrudgeSpawned(mob);
            mob.setTarget(player);
            spawned.add(mob);
        }
        if (spawned.isEmpty()) return false;

        SquadCoordinator.createSquad(level, spawned, difficulty);
        // The nemesis payoff: each named survivor comes back carrying the lesson of the
        // fight it walked away from, and says so, so the player can connect the
        // adaptation to their own past tactics rather than guessing.
        List<Component> boasts = new ArrayList<>();
        for (int i = 0; i < spawned.size() && i < grudges.size(); i++) {
            Mob returned = spawned.get(i);
            IllagerGrudge grudge = grudges.get(i);
            Component name = returnedName(grudge.personalName());
            returned.setCustomName(name);
            IllagerScar scar = grudge.scar();
            if (!scar.marked()) continue;
            scar.apply(returned);
            if (boasts.size() < 2) {
                boasts.add(Component.translatableWithFallback("warband.grudge.boast", "%1$s %2$s.",
                        name, scar.boast()).withStyle(ChatFormatting.GRAY));
            }
        }
        for (Mob mob : spawned) {
            directVengeancePursuit(mob, player);
        }

        maybeSpawnRivalInterception(player, grudges.getFirst(), origin, difficulty, spawned);
        TacticalEffects.arrivalCue(level, Vec3.atCenterOf(origin), TacticalEffects.ArrivalCue.REVENGE);
        player.sendSystemMessage(Component.translatableWithFallback("warband.revenge.arrival",
                "Familiar horns answer from the %s.", faction.displayName()), true);
        for (Component boast : boasts) {
            player.sendSystemMessage(boast);
        }
        WarbandCriteria.fire(player, WarbandCriteria.FIRST_REVENGE);
        return true;
    }

    private static void maybeLaunchBountyHunter(ServerPlayer player, long now) {
        if (!WarbandConfig.illagerBountyHuntersEnabled || !WarbandConfig.illagerFactionsEnabled) return;
        List<FactionReputation> reputations = new ArrayList<>(reputations(player));
        if (reputations.isEmpty()) return;

        for (int i = 0; i < reputations.size(); i++) {
            FactionReputation reputation = reputations.get(i);
            if (reputation.heat() < BOUNTY_HEAT_THRESHOLD || reputation.bountyReadyAt() > now) continue;
            if (spawnBountyHunter(player, reputation)) {
                reputations.set(i, reputation.coolDown(now + BOUNTY_RETRY_TICKS * 2L));
            } else {
                reputations.set(i, new FactionReputation(reputation.faction(), reputation.heat(),
                        now + BOUNTY_RETRY_TICKS, reputation.warReadyAt(), reputation.crusadeReadyAt()));
            }
            player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, trimReputation(reputations));
            return;
        }
    }

    /**
     * Unprompted faction action driven purely by escalation state: WAR triggers periodic
     * field patrols, CRUSADE triggers mansion-led assaults near the player's home/respawn.
     * Both have long per-faction cooldowns so a player at max heat is harassed, not flooded.
     */
    private static void maybeLaunchVengeance(ServerPlayer player, long now) {
        if (!WarbandConfig.illagerFactionsEnabled || !WarbandConfig.illagerGrudgesEnabled) return;
        List<FactionReputation> reputations = new ArrayList<>(reputations(player));
        if (reputations.isEmpty()) return;

        boolean mutated = false;
        for (int i = 0; i < reputations.size(); i++) {
            FactionReputation reputation = reputations.get(i);
            VengeanceState state = reputation.state();
            if (state == VengeanceState.CRUSADE && reputation.crusadeReadyAt() <= now) {
                if (spawnCrusade(player, reputation)) {
                    // Keep the faction in at least WAR after a crusade — a single
                    // assault should not reset months of player-faction conflict.
                    int reducedHeat = Math.max(80, reputation.heat() - CRUSADE_HEAT_RELIEF);
                    reputations.set(i, new FactionReputation(reputation.faction(), reducedHeat,
                            reputation.bountyReadyAt(),
                            now + WAR_PATROL_COOLDOWN_TICKS,
                            now + CRUSADE_COOLDOWN_TICKS));
                } else {
                    reputations.set(i, reputation.withCrusadeReady(now + CRUSADE_COOLDOWN_TICKS / 4));
                }
                mutated = true;
                break;
            }
            if (state == VengeanceState.WAR && reputation.warReadyAt() <= now) {
                if (spawnWarPatrol(player, reputation)) {
                    reputations.set(i, reputation.withWarReady(now + WAR_PATROL_COOLDOWN_TICKS));
                } else {
                    reputations.set(i, reputation.withWarReady(now + WAR_PATROL_COOLDOWN_TICKS / 4));
                }
                mutated = true;
                break;
            }
        }
        if (mutated) {
            player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, trimReputation(reputations));
        }
    }

    private static boolean spawnWarPatrol(ServerPlayer player, FactionReputation reputation) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos origin = findSpawnPos(level, player.getRandom(), player.getBlockX(), player.getBlockZ());
        if (origin == null) return false;

        double difficulty = Math.min(1.0, 0.55 + reputation.heat() / 400.0);
        int size = 3 + player.getRandom().nextInt(2)
                + MultiplayerDirector.revengePartyBonus(level, player.blockPosition());
        List<Mob> spawned = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = origin.offset(player.getRandom().nextInt(7) - 3, 0, player.getRandom().nextInt(7) - 3);
            Mob mob = spawnMember(level, pos, difficulty, i);
            if (mob == null) continue;
            IllagerFactionSystem.setFaction(mob, reputation.faction());
            markGrudgeSpawned(mob);
            spawned.add(mob);
        }
        if (spawned.isEmpty()) return false;
        SquadCoordinator.createSquad(level, spawned, difficulty);
        for (Mob mob : spawned) directVengeancePursuit(mob, player);
        TacticalEffects.arrivalCue(level, Vec3.atCenterOf(origin), TacticalEffects.ArrivalCue.WAR_PATROL);
        player.sendSystemMessage(Component.translatableWithFallback("warband.patrol.arrival",
                "A war patrol of the %s moves to find you.", reputation.faction().displayName()), true);
        WarbandCriteria.fire(player, WarbandCriteria.FACTION_AT_WAR);
        return true;
    }

    private static boolean spawnCrusade(ServerPlayer player, FactionReputation reputation) {
        ServerLevel level = (ServerLevel) player.level();
        // Crusades muster near the player's home (respawn point if set) for that
        // "they came for my base" beat; falls back to current position otherwise.
        BlockPos anchor = player.getRespawnConfig() != null
                && player.getRespawnConfig().respawnData().dimension().equals(level.dimension())
                ? player.getRespawnConfig().respawnData().pos()
                : player.blockPosition();
        BlockPos origin = findSpawnPos(level, player.getRandom(), anchor.getX(), anchor.getZ());
        if (origin == null) {
            origin = findSpawnPos(level, player.getRandom(), player.getBlockX(), player.getBlockZ());
        }
        if (origin == null) return false;

        double difficulty = Math.min(1.0, 0.85 + reputation.heat() / 800.0);
        int size = 6 + player.getRandom().nextInt(3)
                + MultiplayerDirector.revengePartyBonus(level, player.blockPosition()) * 2;
        List<Mob> spawned = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = origin.offset(player.getRandom().nextInt(9) - 4, 0, player.getRandom().nextInt(9) - 4);
            Mob mob = spawnMember(level, pos, difficulty, i);
            if (mob == null) continue;
            IllagerFactionSystem.setFaction(mob, reputation.faction());
            markGrudgeSpawned(mob);
            spawned.add(mob);
        }
        if (spawned.isEmpty()) return false;
        SquadCoordinator.createSquad(level, spawned, difficulty);
        Mob leader = spawned.getFirst();
        leader.setCustomName(WarbandText.titleOfFaction("warband.title.crusade_captain", "Crusade Captain",
                reputation.faction()));
        for (Mob mob : spawned) directVengeancePursuit(mob, player);
        TacticalEffects.arrivalCue(level, Vec3.atCenterOf(origin), TacticalEffects.ArrivalCue.CRUSADE);
        player.sendSystemMessage(Component.translatableWithFallback("warband.crusade.arrival",
                "A crusade of the %s has come for you.", reputation.faction().displayName()), true);
        WarbandCriteria.fire(player, WarbandCriteria.CRUSADE_CALLED);
        return true;
    }

    private static boolean spawnBountyHunter(ServerPlayer player, FactionReputation reputation) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos origin = findSpawnPos(level, player.getRandom(), player.getBlockX(), player.getBlockZ());
        if (origin == null) return false;

        double difficulty = Math.min(1.0, 0.75 + reputation.heat() / 500.0);
        Mob hunter = EntityTypes.PILLAGER.spawn(level, origin, EntitySpawnReason.EVENT);
        if (hunter == null) return false;
        IllagerFactionSystem.setFaction(hunter, reputation.faction());
        markGrudgeSpawned(hunter);
        SquadCoordinator.createSquad(level, List.of(hunter), difficulty);
        hunter.setCustomName(WarbandText.titleOfFaction("warband.title.bounty_hunter", "Bounty Hunter",
                reputation.faction()));
        hunter.setAttached(WarbandAttachments.BOUNTY_HUNTER, true);
        buffBountyHunter(hunter);
        equipBountyHunter(hunter);
        directVengeancePursuit(hunter, player);
        // Jump Boost + Strength: dangerous, but no longer carried by armor enchants or high speed.
        int buffTicks = 20 * 60 * 10;
        hunter.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.STRENGTH, buffTicks, 0, false, true));
        hunter.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.JUMP_BOOST, buffTicks, 1, false, true));
        var selector = ((com.warband.mixin.MobGoalSelectorAccessor) hunter).warband$goalSelector();
        if (hunter instanceof net.minecraft.world.entity.PathfinderMob path) {
            selector.addGoal(1, new com.warband.ai.goal.BountyMeleeGoal(path));
        }
        selector.addGoal(2, new com.warband.ai.goal.BountyClimbGoal(hunter));
        selector.addGoal(2, new com.warband.ai.goal.BountyWindChargeGoal(hunter));
        // Priority 2: ambush preempts chase so a hunter that has closed without
        // LOS lies in wait instead of running through.
        selector.addGoal(2, new com.warband.ai.goal.BountyAmbushGoal(hunter));
        selector.addGoal(3, new com.warband.ai.goal.BountyChaseGoal(hunter, 1.05));
        selector.addGoal(4, new com.warband.ai.goal.BountyMarkGoal(hunter));
        selector.addGoal(4, new com.warband.ai.goal.BountyStalkGoal(hunter));
        selector.addGoal(5, new com.warband.ai.goal.BountyTauntGoal(hunter));

        // The player should hear him from the start — ominous summon at their feet, not
        // 30 blocks away at the spawn site.
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.EVOKER_PREPARE_SUMMON, net.minecraft.sounds.SoundSource.HOSTILE, 0.9f, 0.55f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.RAID_HORN.value(), net.minecraft.sounds.SoundSource.HOSTILE, 0.6f, 0.55f);
        TacticalEffects.arrivalCue(level, Vec3.atCenterOf(origin), TacticalEffects.ArrivalCue.BOUNTY);
        player.sendSystemMessage(Component.translatableWithFallback("warband.bounty.arrival",
                "A bounty hunter from the %s has your trail.", reputation.faction().displayName()), true);
        WarbandCriteria.fire(player, WarbandCriteria.BOUNTY_SUMMONED);
        return true;
    }

    private static void maybeSpawnRivalInterception(ServerPlayer player, IllagerGrudge grudge,
                                                    BlockPos origin, double difficulty, List<Mob> patrol) {
        if (!WarbandConfig.illagerRivalriesEnabled || !WarbandConfig.illagerFactionsEnabled) return;
        if (patrol.isEmpty()) return;
        // No rival pile-on at evoker tier, that revenge patrol is already a handful.
        if (difficulty >= 0.85) return;
        if (player.getRandom().nextInt(100) >= RIVAL_INTERCEPT_CHANCE) return;

        ServerLevel level = (ServerLevel) player.level();
        IllagerFaction rival = grudge.faction().rival();
        // Self-rival factions (zealot orders) don't get intercepted.
        if (rival == grudge.faction()) return;
        double rivalDifficulty = Math.max(0.45, difficulty - 0.10);
        List<Mob> spawned = new ArrayList<>();
        int size = 1 + player.getRandom().nextInt(2);
        for (int i = 0; i < size; i++) {
            BlockPos pos = origin.offset(player.getRandom().nextInt(15) - 7, 0, player.getRandom().nextInt(15) - 7);
            Mob mob = spawnMember(level, pos, rivalDifficulty, i);
            if (mob == null) continue;
            IllagerFactionSystem.setFaction(mob, rival);
            markGrudgeSpawned(mob);
            // Rivals intercept the revenge patrol, not the player, a genuine
            // third party the player can turn to their advantage.
            mob.setTarget(patrol.get(player.getRandom().nextInt(patrol.size())));
            spawned.add(mob);
        }
        if (spawned.isEmpty()) return;
        SquadCoordinator.createSquad(level, spawned, rivalDifficulty);
        Mob leader = spawned.getFirst();
        leader.setCustomName(WarbandText.titleOfFaction("warband.title.rival_scout", "Rival Scout", rival));
        player.sendSystemMessage(Component.translatableWithFallback("warband.rival.intercept",
                "Rivals from the %1$s move to intercept the %2$s.",
                rival.displayName(), grudge.faction().displayName()), true);
    }

    private static Mob spawnMember(ServerLevel level, BlockPos pos, double difficulty, int index) {
        EntityType<? extends Mob> type = EntityTypes.PILLAGER;
        if (difficulty >= 0.70 && index == 1) type = EntityTypes.VINDICATOR;
        if (difficulty >= 0.85 && index == 2) type = EntityTypes.EVOKER;
        return type.spawn(level, pos, EntitySpawnReason.EVENT);
    }

    /**
     * Where a revenge patrol musters: near the grudge's recorded origin when the
     * player is still close to it (revenge returns to the scene), otherwise near
     * the player as a fallback. Origin is a plain coordinate, so this works with
     * any pillager structure, vanilla or modded.
     */
    private static BlockPos findRevengeSpawn(ServerLevel level, ServerPlayer player, IllagerGrudge grudge) {
        if (grudge.hasOrigin() && grudge.originDimension().equals(level.dimension().toString())) {
            BlockPos origin = BlockPos.of(grudge.originPos());
            double dx = origin.getX() + 0.5 - player.getX();
            double dz = origin.getZ() + 0.5 - player.getZ();
            if (dx * dx + dz * dz < (double) ORIGIN_MUSTER_RANGE * ORIGIN_MUSTER_RANGE) {
                BlockPos atOrigin = findSpawnPos(level, player.getRandom(), origin.getX(), origin.getZ());
                if (atOrigin != null) return atOrigin;
            }
        }
        return findSpawnPos(level, player.getRandom(), player.getBlockX(), player.getBlockZ());
    }

    private static BlockPos findSpawnPos(ServerLevel level, RandomSource random, int centerX, int centerZ) {
        for (int attempts = 0; attempts < 16; attempts++) {
            int distance = SPAWN_MIN_DISTANCE + random.nextInt(SPAWN_RANGE);
            double angle = random.nextDouble() * Math.PI * 2.0;
            int x = centerX + (int) Math.round(Math.cos(angle) * distance);
            int z = centerZ + (int) Math.round(Math.sin(angle) * distance);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getWorldBorder().isWithinBounds(pos)
                    && level.noCollision(EntityTypes.PILLAGER.getSpawnAABB(pos.getX(), pos.getY(), pos.getZ()))) {
                return pos;
            }
        }
        return null;
    }

    /** A grudge-worthy illager: a squad leader, or one at notable difficulty. */
    private static boolean isNotable(Mob mob) {
        MobData data = MobData.get(mob);
        return data.role() == Role.LEADER || data.difficulty() >= 0.65f;
    }

    /** Full diamond kit, but armor is plain so the fight is not over-tuned. */
    private static void equipBountyHunter(Mob hunter) {
        equipDiamond(hunter, net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.Items.DIAMOND_HELMET);
        equipDiamond(hunter, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
        equipDiamond(hunter, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.item.Items.DIAMOND_LEGGINGS);
        equipDiamond(hunter, net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.item.Items.DIAMOND_BOOTS);

        // Crossbow stays in main hand for ranged engagement; offhand carries the sword
        // for melee swings driven by BountyMeleeGoal.
        net.minecraft.world.item.ItemStack crossbow = enchantStack(hunter,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW),
                net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE, 3);
        enchantStack(hunter, crossbow, net.minecraft.world.item.enchantment.Enchantments.PIERCING, 4);
        hunter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, crossbow);
        hunter.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.01f);

        net.minecraft.world.item.ItemStack sword = enchantStack(hunter,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD),
                net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 4);
        enchantStack(hunter, sword, net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3);
        hunter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, sword);
        hunter.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.01f);
    }

    private static void equipDiamond(Mob hunter, net.minecraft.world.entity.EquipmentSlot slot,
                                     net.minecraft.world.item.Item item) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        hunter.setItemSlot(slot, stack);
        hunter.setDropChance(slot, 0.01f);
    }

    private static net.minecraft.world.item.ItemStack enchantStack(Mob hunter, net.minecraft.world.item.ItemStack stack,
                                                                   net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key,
                                                                   int level) {
        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> ench = hunter.level().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
        stack.enchant(ench, level);
        return stack;
    }

    /** Apex-tier stats: real fight, but not a damage sponge. Diamond armor + revive carry the durability. */
    private static void buffBountyHunter(Mob hunter) {
        com.warband.spawn.SpawnDirector.addFlat(hunter, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                com.warband.spawn.SpawnDirector.warbandModifierId("bounty_health"), 16.0);
        com.warband.spawn.SpawnDirector.addFlat(hunter, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                com.warband.spawn.SpawnDirector.warbandModifierId("bounty_damage"), 6.0);
        com.warband.spawn.SpawnDirector.addFlat(hunter, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE,
                com.warband.spawn.SpawnDirector.warbandModifierId("bounty_kb"), 0.5);
        com.warband.spawn.SpawnDirector.addMultiplied(hunter, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED,
                com.warband.spawn.SpawnDirector.warbandModifierId("bounty_speed"), 0.05);
        var scale = hunter.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        if (scale != null && !scale.hasModifier(com.warband.spawn.SpawnDirector.warbandModifierId("bounty_scale"))) {
            scale.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    com.warband.spawn.SpawnDirector.warbandModifierId("bounty_scale"),
                    -0.08,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        hunter.setHealth(hunter.getMaxHealth());
    }

    /** Make a vengeance-spawned mob actively pursue the player — patrol target + nav kick + Speed buff. */
    private static void directVengeancePursuit(Mob mob, ServerPlayer player) {
        mob.setTarget(player);
        if (mob instanceof PatrollingMonster patroller) {
            patroller.setPatrolTarget(player.blockPosition());
        }
        mob.getNavigation().moveTo(player, 1.1);
        if (Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.BOUNTY_HUNTER))) return;
        // 5 minutes of Speed I so they cover distance to the player rather than dawdling.
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.SPEED, 20 * 60 * 5, 0, false, true));
    }

    /** Banner-bearing illagers represent the faction directly; their kills always register. */
    private static boolean carriesFactionBanner(Mob mob) {
        return mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).getItem()
                instanceof net.minecraft.world.item.BannerItem;
    }

    private static void markGrudgeSpawned(Mob mob) {
        mob.setAttached(WarbandAttachments.ILLAGER_GRUDGE_SPAWNED, true);
    }

    private static boolean isGrudgeSpawned(Mob mob) {
        return Boolean.TRUE.equals(mob.getAttached(WarbandAttachments.ILLAGER_GRUDGE_SPAWNED));
    }

    private static List<IllagerGrudge> readyFactionGrudges(List<IllagerGrudge> grudges, IllagerFaction faction, long now) {
        List<IllagerGrudge> ready = new ArrayList<>();
        for (IllagerGrudge grudge : grudges) {
            if (grudge.faction() == faction && grudge.readyAt() <= now && grudge.attempts() < 3) {
                ready.add(grudge);
            }
        }
        return ready;
    }

    private static float maxDifficulty(List<IllagerGrudge> grudges) {
        float max = 0.0f;
        for (IllagerGrudge grudge : grudges) {
            max = Math.max(max, grudge.difficulty());
        }
        return max;
    }

    private static int totalAnger(List<IllagerGrudge> grudges) {
        int total = 0;
        for (IllagerGrudge grudge : grudges) {
            total += grudge.anger();
        }
        return total;
    }

    /**
     * The name a survivor wears when it comes back.
     *
     * <p>Reads the bare name straight off the grudge. This used to rebuild it by searching
     * a stored display string for " of the " and keeping the last word before it — see
     * {@link IllagerGrudge} for why that is gone.
     */
    private static Component returnedName(String personalName) {
        return Component.translatableWithFallback("warband.name.returned", "%s the Returned", personalName);
    }

    /**
     * Territory-aware heat scaling: killing a faction member inside their own
     * faction's territory is a deeper offense (1.5×); killing them inside their
     * rival's territory hits less (0.75×) — the locals appreciate it.
     */
    private static int applyTerritoryMultiplier(int heat, IllagerFaction victimFaction, IllagerFaction territoryOwner) {
        if (territoryOwner == null) return heat;
        if (territoryOwner == victimFaction) return Math.max(1, (int) Math.round(heat * 1.5));
        if (territoryOwner == victimFaction.rival()) return Math.max(1, (int) Math.round(heat * 0.75));
        return heat;
    }

    private static void addReputation(ServerPlayer player, IllagerFaction faction, int heat, long bountyReadyAt) {
        if (heat > 0) {
            introduceFactions(player, faction);
        }
        // Heat is also the display value for /warband intel — track it regardless
        // of whether bounty hunters are enabled; only the hunter spawn is gated.
        List<FactionReputation> reputations = new ArrayList<>(reputations(player));
        boolean updated = false;
        for (int i = 0; i < reputations.size(); i++) {
            FactionReputation reputation = reputations.get(i);
            if (reputation.faction() == faction) {
                reputations.set(i, reputation.addHeat(heat, bountyReadyAt));
                updated = true;
                break;
            }
        }
        if (!updated) {
            reputations.add(new FactionReputation(faction, heat, bountyReadyAt));
        }

        // Rival cooldown: killing faction X's members helps X's rival, so the
        // rival's heat with this player drops. Iron Choir is self-rival and
        // never benefits anyone.
        IllagerFaction rival = faction.rival();
        if (rival != faction && heat > 0) {
            int reduction = Math.max(1, heat / 2);
            for (int i = 0; i < reputations.size(); i++) {
                FactionReputation r = reputations.get(i);
                if (r.faction() == rival) {
                    int newHeat = Math.max(0, r.heat() - reduction);
                    reputations.set(i, new FactionReputation(rival, newHeat,
                            r.bountyReadyAt(), r.warReadyAt(), r.crusadeReadyAt()));
                    break;
                }
            }
        }

        player.setAttached(WarbandAttachments.ILLAGER_REPUTATION, trimReputation(reputations));
    }

    private static List<ServerPlayer> participantsNear(ServerLevel level, Mob mob, ServerPlayer source) {
        if (!WarbandConfig.multiplayerFeaturesEnabled) return List.of(source);
        AABB box = AABB.ofSize(mob.position(), 64.0 * 2.0, 32.0, 64.0 * 2.0);
        List<ServerPlayer> participants = new ArrayList<>(level.getEntitiesOfClass(ServerPlayer.class, box,
                player -> player.isAlive() && !player.isSpectator()));
        if (!participants.contains(source)) {
            participants.add(source);
        }
        return participants;
    }

    private static List<IllagerGrudge> grudges(ServerPlayer player) {
        List<IllagerGrudge> grudges = player.getAttached(WarbandAttachments.ILLAGER_GRUDGES);
        return grudges != null ? grudges : List.of();
    }

    private static List<FactionReputation> reputations(ServerPlayer player) {
        List<FactionReputation> reputations = player.getAttached(WarbandAttachments.ILLAGER_REPUTATION);
        return reputations != null ? reputations : List.of();
    }

    private static List<IllagerGrudge> trim(List<IllagerGrudge> grudges) {
        if (grudges.size() <= MAX_GRUDGES_PER_PLAYER) return List.copyOf(grudges);
        return List.copyOf(grudges.subList(grudges.size() - MAX_GRUDGES_PER_PLAYER, grudges.size()));
    }

    private static List<FactionReputation> trimReputation(List<FactionReputation> reputations) {
        if (reputations.size() <= MAX_REPUTATION_RECORDS) return List.copyOf(reputations);
        return List.copyOf(reputations.subList(reputations.size() - MAX_REPUTATION_RECORDS, reputations.size()));
    }

    private record PendingSurvivor(UUID playerId, String name, IllagerFaction faction,
                                   float difficulty, long confirmAt, long expiresAt,
                                   long originPos, String originDimension, IllagerScar scar) {
    }
}
