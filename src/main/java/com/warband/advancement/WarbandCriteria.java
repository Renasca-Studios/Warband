package com.warband.advancement;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Holds Warband's custom advancement triggers and the string kinds they fire with. */
public final class WarbandCriteria {

    public static final String FACTION_NOTICED = "faction_noticed";
    public static final String FIRST_GRUDGE = "first_grudge";
    public static final String FIRST_REVENGE = "first_revenge";
    public static final String BOUNTY_SUMMONED = "bounty_summoned";
    public static final String WARMARSHAL_SLAIN = "warmarshal_slain";
    public static final String FACTION_AT_WAR = "faction_at_war";
    public static final String CRUSADE_CALLED = "crusade_called";

    /**
     * 26.2 made {@code CriteriaTriggers.register} private and moved trigger types into a
     * real registry, so custom triggers go in through {@code BuiltInRegistries.TRIGGER_TYPES}
     * instead. The resulting id is still {@code warband:event}, so the advancement JSON in
     * {@code data/warband/advancement/} needs no change.
     */
    public static final WarbandEventTrigger EVENT = Registry.register(
            BuiltInRegistries.TRIGGER_TYPES,
            Identifier.fromNamespaceAndPath("warband", "event"),
            new WarbandEventTrigger());

    private WarbandCriteria() {
    }

    public static void init() {
        // Class-load triggers the static field above. Called from onInitialize.
    }

    public static void fire(ServerPlayer player, String kind) {
        EVENT.trigger(player, kind);
    }
}
