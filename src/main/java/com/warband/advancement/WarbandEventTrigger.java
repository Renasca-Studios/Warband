package com.warband.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * One generic trigger for Warband-specific milestones, keyed by a string kind.
 * Each advancement specifies the kind it wants in its JSON, so we don't need a
 * dedicated trigger class per event.
 */
public class WarbandEventTrigger extends SimpleCriterionTrigger<WarbandEventTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, String kind) {
        trigger(player, instance -> instance.matches(kind));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, String kind)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(b -> b.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.STRING.fieldOf("kind").forGetter(TriggerInstance::kind)
        ).apply(b, TriggerInstance::new));

        public boolean matches(String kind) {
            return this.kind.equals(kind);
        }
    }
}
