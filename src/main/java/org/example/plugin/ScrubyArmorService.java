package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.InventoryHelper;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages armor equipment for Scruby companions.
 * Provides HP lookup table, slot validation, and equip/unequip operations.
 */
public final class ScrubyArmorService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Number of armor slots: 0=Head, 1=Chest, 2=Hands, 3=Legs */
    public static final int SLOT_COUNT = 4;
    public static final int SLOT_HEAD = 0;
    public static final int SLOT_CHEST = 1;
    public static final int SLOT_HANDS = 2;
    public static final int SLOT_LEGS = 3;

    private static final String[] SLOT_SUFFIXES = {"_Head", "_Chest", "_Hands", "_Legs"};

    /**
     * Static HP bonus table: ItemId -> flat HP bonus.
     * Extracted from Hytale reference assets (StatModifiers.Health.Amount).
     * 4 tiers: Copper(T1), Standard(T2), Fortgeschritten(T3), Endgame(T4).
     */
    private static final Map<String, Integer> HP_TABLE = new HashMap<>();

    static {
        // Tier 1: Copper — Head:15, Chest:27, Legs:21, Hands:12
        addSet("Copper", 15, 27, 21, 12);

        // Tier 2: Standard — Head:27, Chest:51, Legs:39, Hands:21
        for (String mat : new String[]{
                "Bronze", "Bronze_Ornate", "Iron", "Steel", "Steel_Ancient",
                "Cloth_Cindercloth", "Cloth_Cotton", "Cloth_Linen", "Cloth_Silk", "Cloth_Wool",
                "Leather_Heavy", "Leather_Light", "Leather_Medium", "Leather_Raven", "Leather_Soft",
                "Wood", "Wool", "Onyxium", "Prisma", "Diving_Crude",
                "Kweebec", "Trooper", "Trork"
        }) {
            addSet(mat, 27, 51, 39, 21);
        }

        // Tier 3: Advanced — Head:36, Chest:66, Legs:51, Hands:30
        addSet("Cobalt", 36, 66, 51, 30);
        addSet("Thorium", 36, 66, 51, 30);

        // Tier 4: Endgame — Head:42, Chest:72, Legs:57, Hands:33
        addSet("Adamantite", 42, 72, 57, 33);
        addSet("Mithril", 42, 72, 57, 33);
    }

    private static void addSet(String material, int head, int chest, int legs, int hands) {
        HP_TABLE.put("Armor_" + material + "_Head", head);
        HP_TABLE.put("Armor_" + material + "_Chest", chest);
        HP_TABLE.put("Armor_" + material + "_Legs", legs);
        HP_TABLE.put("Armor_" + material + "_Hands", hands);
    }

    /**
     * Returns the HP bonus for a given item ID, or 0 if unknown.
     */
    public int getItemHpBonus(@Nonnull String itemId) {
        return HP_TABLE.getOrDefault(itemId, 0);
    }

    /**
     * Returns the total HP bonus from all 4 equipped armor slots.
     */
    public int getTotalHpBonus(@Nonnull CompanionProfile profile) {
        return getItemHpBonus(profile.getEquipHead())
             + getItemHpBonus(profile.getEquipChest())
             + getItemHpBonus(profile.getEquipLegs())
             + getItemHpBonus(profile.getEquipHands());
    }

    /**
     * Checks if an item ID is valid armor for the given slot index.
     * Requires: starts with "Armor_", ends with correct slot suffix.
     */
    public boolean isValidArmorForSlot(@Nonnull String itemId, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return false;
        if (!itemId.startsWith("Armor_")) return false;
        return itemId.endsWith(SLOT_SUFFIXES[slotIndex]);
    }

    /**
     * Returns the item ID currently in the given armor slot, or "" if empty.
     */
    @Nonnull
    public String getEquippedItem(@Nonnull CompanionProfile profile, int slotIndex) {
        return switch (slotIndex) {
            case SLOT_HEAD -> profile.getEquipHead();
            case SLOT_CHEST -> profile.getEquipChest();
            case SLOT_LEGS -> profile.getEquipLegs();
            case SLOT_HANDS -> profile.getEquipHands();
            default -> "";
        };
    }

    /**
     * Sets the item ID in the given armor slot. Pass "" to clear.
     */
    public void setEquippedItem(@Nonnull CompanionProfile profile, int slotIndex, @Nonnull String itemId) {
        switch (slotIndex) {
            case SLOT_HEAD -> profile.setEquipHead(itemId);
            case SLOT_CHEST -> profile.setEquipChest(itemId);
            case SLOT_LEGS -> profile.setEquipLegs(itemId);
            case SLOT_HANDS -> profile.setEquipHands(itemId);
        }
    }

    /**
     * Applies the profile's equipped armor visually onto the given companion NPC entity.
     * Clears the armor container first so unequip and swap are reflected correctly.
     * Safe to call on any spawn or equip/unequip event — silently no-ops if the NPC
     * entity, inventory or armor container is unavailable.
     */
    public static void applyVisualArmor(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull CompanionProfile profile
    ) {
        try {
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity == null) return;
            Inventory inv = npcEntity.getInventory();
            if (inv == null || inv.getArmor() == null) return;
            inv.getArmor().clear();
            String head = profile.getEquipHead();
            String chest = profile.getEquipChest();
            String legs = profile.getEquipLegs();
            String hands = profile.getEquipHands();
            if (!head.isEmpty())  InventoryHelper.useArmor(inv.getArmor(), head);
            if (!chest.isEmpty()) InventoryHelper.useArmor(inv.getArmor(), chest);
            if (!legs.isEmpty())  InventoryHelper.useArmor(inv.getArmor(), legs);
            if (!hands.isEmpty()) InventoryHelper.useArmor(inv.getArmor(), hands);
        } catch (Throwable t) {
            LOGGER.atInfo().log("[Scruby-Armor] applyVisualArmor failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
