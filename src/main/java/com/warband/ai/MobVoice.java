package com.warband.ai;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.spider.Spider;
import org.jetbrains.annotations.Nullable;

/**
 * The sound a mob uses to speak, so Warband's audio cues come out in that species'
 * own voice.
 *
 * <p>Originally every cue used one fixed sound for every mob, which meant a creeper
 * announcing that it had spotted you groaned like something else entirely. That is
 * both wrong and actively confusing: the noise a mob makes is a large part of how a
 * player identifies what is coming, and overriding it with a shared cue throws that
 * information away.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>the mob's own {@code getAmbientSound()}, which covers zombies, skeletons,
 *       spiders, illagers, piglins and — importantly — any modded mob for free,</li>
 *   <li>an explicit fallback for the species vanilla leaves silent (creepers have no
 *       ambient sound at all, so their iconic hiss stands in),</li>
 *   <li>a neutral gear-shift for anything still unresolved, so a cue is never lost.</li>
 * </ol>
 *
 * <p>Cue <i>meaning</i> is then carried by pitch and by a layered accent sound rather
 * than by swapping the voice — same speaker, different tone. See {@link TacticalBarks}.
 */
public final class MobVoice {

    private MobVoice() {
    }

    /** Never null: falls through to a neutral sound so a cue is always audible. */
    public static SoundEvent of(Mob mob) {
        SoundEvent ambient = mob.getAmbientSound();
        if (ambient != null) return ambient;

        SoundEvent fallback = silentSpeciesVoice(mob);
        return fallback != null ? fallback : SoundEvents.ARMOR_EQUIP_LEATHER.value();
    }

    /**
     * Voices for mobs vanilla gives no ambient sound. Creepers are the important case —
     * they are silent by design, and the hiss is the single most recognisable warning
     * sound in the game.
     */
    private static @Nullable SoundEvent silentSpeciesVoice(Mob mob) {
        if (mob instanceof Creeper) return SoundEvents.CREEPER_PRIMED;
        if (mob instanceof Slime) return SoundEvents.SLIME_SQUISH;
        if (mob instanceof Spider) return SoundEvents.SPIDER_AMBIENT;
        if (mob instanceof Ghast) return SoundEvents.GHAST_WARN;
        if (mob instanceof Phantom) return SoundEvents.PHANTOM_AMBIENT;
        if (mob instanceof Guardian) return SoundEvents.GUARDIAN_AMBIENT;
        if (mob instanceof Shulker) return SoundEvents.SHULKER_AMBIENT;
        return null;
    }
}
