package com.warband.illager;

import com.warband.config.WarbandConfig;
import com.warband.entity.WarbandAttachments;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Gives faction members a visible carried banner without overwriting weapons. */
public final class FactionBanner {

    private FactionBanner() {
    }

    public static void equipIfNeeded(Mob mob) {
        if (!WarbandConfig.illagerFactionsEnabled || !WarbandConfig.illagerFactionBannersEnabled) return;
        IllagerFactionData data = mob.getAttached(WarbandAttachments.ILLAGER_FACTION);
        if (data == null) return;
        if (!mob.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return;
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(bannerItem(data.faction())));
        mob.setDropChance(EquipmentSlot.OFFHAND, 0.05f);
    }

    private static Item bannerItem(IllagerFaction faction) {
        return switch (faction) {
            case BLACK_HORN -> Items.BANNER.black();
            case RED_LEDGER -> Items.BANNER.red();
            case PALE_AXE -> Items.BANNER.white();
            case ASH_BANNER -> Items.BANNER.orange();
            case IRON_CHOIR -> Items.BANNER.gray();
        };
    }
}
