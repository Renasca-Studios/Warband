package com.warband.illager;

import com.warband.compat.IllagerKinds;
import com.warband.compat.StructureCompat;
import com.warband.entity.MobData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/**
 * Structure-anchored faction territories. A position is in a faction's
 * "territory" if either it sits inside a faction seat/camp, or there is a
 * meaningful cluster of that faction's members within a short radius (so the
 * area around a mansion or outpost reads as theirs even before the player
 * crosses the structure boundary).
 *
 * <p>No persistent claim data — derived on demand from loaded illagers and
 * structure tags. Cheap per-query, callers should still avoid hot-path use.
 */
public final class IllagerTerritory {

    private static final double TERRITORY_RADIUS = 64.0;
    private static final int CLUSTER_THRESHOLD = 3;

    private IllagerTerritory() {
    }

    /** Faction that owns the area around this position, or null if neutral. */
    public static IllagerFaction factionAt(ServerLevel level, BlockPos pos) {
        // Inside a stronghold: faction of any garrisoned member wins.
        if (StructureCompat.inFactionSeat(level, pos) || StructureCompat.inFactionCamp(level, pos)) {
            IllagerFaction inside = dominantFactionNear(level, pos, 24.0, 1);
            if (inside != null) return inside;
        }
        // Extended territory: a cluster of same-faction members nearby.
        return dominantFactionNear(level, pos, TERRITORY_RADIUS, CLUSTER_THRESHOLD);
    }

    private static IllagerFaction dominantFactionNear(ServerLevel level, BlockPos pos, double radius, int minCount) {
        AABB box = AABB.ofSize(Vec3.atCenterOf(pos), radius * 2, 48.0, radius * 2);
        Map<IllagerFaction, Integer> counts = new EnumMap<>(IllagerFaction.class);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> IllagerKinds.isIllagerLike(m) && MobData.isStamped(m))) {
            IllagerFaction faction = IllagerFactionSystem.factionOrDefault(mob);
            counts.merge(faction, 1, Integer::sum);
        }
        IllagerFaction best = null;
        int bestCount = 0;
        for (Map.Entry<IllagerFaction, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return bestCount >= minCount ? best : null;
    }
}
