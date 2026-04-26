package org.example.plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless utility service for managing companion inventory.
 *
 * Items are stored in {@link CompanionProfile#getInventoryItems()} as a
 * comma-separated string of entries.  Each entry uses the format:
 * <pre>itemId:quantity:slotIndex</pre>
 * Example: {@code "Ore_Gold:25:0,Weapon_Sword:1:3"}
 *
 * Grid layout: 5 columns × 4 rows = 20 slots (indices 0–19).
 * Stacking: adding an item first looks for an existing stack with the same
 * itemId.  If found the quantity is incremented; otherwise the first free
 * slot is used.
 */
public final class ScrubyInventoryService {

    // --- Grid constants ---

    public static final int MAX_SLOTS    = 20;
    public static final int GRID_COLUMNS = 5;
    public static final int GRID_ROWS    = 4;

    // --- Inner data class ---

    public static final class InventoryEntry {

        private final String itemId;
        private int quantity;
        private final int slotIndex;

        public InventoryEntry(@Nonnull String itemId, int quantity, int slotIndex) {
            this.itemId    = itemId;
            this.quantity  = quantity;
            this.slotIndex = slotIndex;
        }

        @Nonnull
        public String getItemId() { return itemId; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public int getSlotIndex() { return slotIndex; }
    }

    // --- Parsing & serialization ---

    /**
     * Parses the inventory string from the profile and returns all entries.
     * Returns an empty list if the string is blank or malformed.
     */
    @Nonnull
    public List<InventoryEntry> getItems(@Nonnull CompanionProfile profile) {
        List<InventoryEntry> result = new ArrayList<>();
        String raw = profile.getInventoryItems();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] parts = raw.split(",", -1);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] fields = trimmed.split(":", -1);
            if (fields.length != 3) continue;
            try {
                String itemId    = fields[0];
                int    quantity  = Integer.parseInt(fields[1]);
                int    slotIndex = Integer.parseInt(fields[2]);
                if (itemId.isEmpty() || quantity <= 0 || slotIndex < 0 || slotIndex >= MAX_SLOTS) {
                    continue;
                }
                result.add(new InventoryEntry(itemId, quantity, slotIndex));
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
        return result;
    }

    /**
     * Serializes the given entries back into the profile's inventory string.
     */
    public void saveItems(@Nonnull CompanionProfile profile, @Nonnull List<InventoryEntry> items) {
        if (items.isEmpty()) {
            profile.setInventoryItems("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            InventoryEntry e = items.get(i);
            sb.append(e.getItemId())
              .append(':')
              .append(e.getQuantity())
              .append(':')
              .append(e.getSlotIndex());
        }
        profile.setInventoryItems(sb.toString());
    }

    // --- Slot queries ---

    /**
     * Returns {@code true} when all 20 slots are occupied.
     */
    public boolean isFull(@Nonnull CompanionProfile profile) {
        return getOccupiedSlotCount(profile) >= MAX_SLOTS;
    }

    /**
     * Returns the number of occupied slots (one entry = one slot).
     */
    public int getOccupiedSlotCount(@Nonnull CompanionProfile profile) {
        return getItems(profile).size();
    }

    /**
     * Returns the first slot index (0–19) that is not occupied, or {@code -1}
     * if the inventory is full.
     */
    public int findFreeSlot(@Nonnull CompanionProfile profile) {
        List<InventoryEntry> items = getItems(profile);
        boolean[] occupied = new boolean[MAX_SLOTS];
        for (InventoryEntry e : items) {
            occupied[e.getSlotIndex()] = true;
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!occupied[i]) return i;
        }
        return -1;
    }

    // --- Mutation ---

    /**
     * Adds {@code quantity} of {@code itemId} to the inventory.
     *
     * <ul>
     *   <li>If the same itemId already exists the quantity is increased on
     *       the existing stack (first match wins).</li>
     *   <li>Otherwise the item is placed in the first free slot.</li>
     * </ul>
     *
     * @return {@code true} on success, {@code false} if the inventory is full
     *         and no matching stack was found.
     */
    public boolean addItem(@Nonnull CompanionProfile profile, @Nonnull String itemId, int quantity) {
        List<InventoryEntry> items = getItems(profile);

        // Try to stack onto an existing entry with the same itemId.
        for (InventoryEntry e : items) {
            if (e.getItemId().equals(itemId)) {
                e.setQuantity(e.getQuantity() + quantity);
                saveItems(profile, items);
                return true;
            }
        }

        // No existing stack — find a free slot.
        int freeSlot = findFreeSlotFromList(items);
        if (freeSlot == -1) {
            return false;
        }
        items.add(new InventoryEntry(itemId, quantity, freeSlot));
        saveItems(profile, items);
        return true;
    }

    /**
     * Result of a slot-targeted add operation.
     */
    public static final class SlotAddResult {
        private final boolean success;
        private final int remainingQuantity;

        public SlotAddResult(boolean success, int remainingQuantity) {
            this.success = success;
            this.remainingQuantity = remainingQuantity;
        }

        public boolean isSuccess() { return success; }
        /** Quantity that did not fit (e.g. due to stack limit). 0 if everything fit. */
        public int getRemainingQuantity() { return remainingQuantity; }
    }

    /**
     * Default stack limit. Hytale API does not expose ItemStack.getMaxStackSize(),
     * so we use a hardcoded fallback.
     */
    public static final int DEFAULT_MAX_STACK = 100;

    /**
     * Places {@code quantity} of {@code itemId} into a specific {@code targetSlot}.
     *
     * <ul>
     *   <li>Target empty → item placed directly.</li>
     *   <li>Target occupied with same itemId → stack up to maxStack, return remainder.</li>
     *   <li>Target occupied with different itemId → fail (caller should use swap).</li>
     * </ul>
     *
     * @param maxStack the maximum stack size (use DEFAULT_MAX_STACK or Hytale-native value)
     * @return result indicating success and any remaining quantity
     */
    @Nonnull
    public SlotAddResult addItemToSlot(@Nonnull CompanionProfile profile,
                                       @Nonnull String itemId,
                                       int quantity,
                                       int targetSlot,
                                       int maxStack) {
        if (targetSlot < 0 || targetSlot >= MAX_SLOTS) {
            return new SlotAddResult(false, quantity);
        }

        List<InventoryEntry> items = getItems(profile);
        InventoryEntry existing = null;
        for (InventoryEntry e : items) {
            if (e.getSlotIndex() == targetSlot) {
                existing = e;
                break;
            }
        }

        if (existing == null) {
            // Empty slot — place directly (cap at maxStack)
            int toPlace = Math.min(quantity, maxStack);
            items.add(new InventoryEntry(itemId, toPlace, targetSlot));
            saveItems(profile, items);
            return new SlotAddResult(true, quantity - toPlace);
        }

        if (existing.getItemId().equals(itemId)) {
            // Same item — stack up to max
            int canAdd = maxStack - existing.getQuantity();
            if (canAdd <= 0) {
                return new SlotAddResult(false, quantity);
            }
            int toAdd = Math.min(quantity, canAdd);
            existing.setQuantity(existing.getQuantity() + toAdd);
            saveItems(profile, items);
            return new SlotAddResult(true, quantity - toAdd);
        }

        // Different item — caller should use swap
        return new SlotAddResult(false, quantity);
    }

    /**
     * Swaps two slots within the Scruby inventory.
     * Works whether slots are empty or occupied.
     *
     * @return {@code true} if at least one slot was non-empty (i.e. something happened)
     */
    public boolean swapSlots(@Nonnull CompanionProfile profile, int slotA, int slotB) {
        if (slotA == slotB) return false;
        if (slotA < 0 || slotA >= MAX_SLOTS || slotB < 0 || slotB >= MAX_SLOTS) return false;

        List<InventoryEntry> items = getItems(profile);
        InventoryEntry entryA = null;
        InventoryEntry entryB = null;
        for (InventoryEntry e : items) {
            if (e.getSlotIndex() == slotA) entryA = e;
            if (e.getSlotIndex() == slotB) entryB = e;
        }

        if (entryA == null && entryB == null) return false;

        // Rebuild list with swapped slot indices
        List<InventoryEntry> updated = new ArrayList<>();
        for (InventoryEntry e : items) {
            if (e.getSlotIndex() == slotA) {
                updated.add(new InventoryEntry(e.getItemId(), e.getQuantity(), slotB));
            } else if (e.getSlotIndex() == slotB) {
                updated.add(new InventoryEntry(e.getItemId(), e.getQuantity(), slotA));
            } else {
                updated.add(e);
            }
        }
        saveItems(profile, updated);
        return true;
    }

    /**
     * Result of a cross-grid swap: the displaced Scruby item that needs to go to the player.
     */
    public static final class DisplacedItem {
        private final String itemId;
        private final int quantity;

        public DisplacedItem(@Nonnull String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        @Nonnull
        public String getItemId() { return itemId; }
        public int getQuantity() { return quantity; }
    }

    /**
     * Cross-grid swap: removes the existing Scruby item at {@code scrubySlot}
     * and places the player's item there instead.
     *
     * @return the displaced Scruby item (so the caller can give it to the player),
     *         or {@code null} if the Scruby slot was empty (no swap needed)
     */
    @Nullable
    public DisplacedItem crossGridSwap(@Nonnull CompanionProfile profile,
                                       int scrubySlot,
                                       @Nonnull String playerItemId,
                                       int playerQty) {
        if (scrubySlot < 0 || scrubySlot >= MAX_SLOTS) return null;

        List<InventoryEntry> items = getItems(profile);
        InventoryEntry existing = null;
        List<InventoryEntry> remaining = new ArrayList<>();
        for (InventoryEntry e : items) {
            if (e.getSlotIndex() == scrubySlot && existing == null) {
                existing = e;
            } else {
                remaining.add(e);
            }
        }

        if (existing == null) return null; // slot was empty, no swap

        // Place player item in the now-empty slot
        remaining.add(new InventoryEntry(playerItemId, playerQty, scrubySlot));
        saveItems(profile, remaining);

        return new DisplacedItem(existing.getItemId(), existing.getQuantity());
    }

    /**
     * Smart stacking for double-click quick-transfer.
     * First tries to stack onto an existing entry with the same itemId (up to maxStack).
     * If no existing stack or stack is full, places in the first free slot.
     *
     * @param maxStack the maximum stack size
     * @return remaining quantity that could not be added (0 = everything fit)
     */
    public int smartStack(@Nonnull CompanionProfile profile,
                          @Nonnull String itemId,
                          int quantity,
                          int maxStack) {
        List<InventoryEntry> items = getItems(profile);
        int remaining = quantity;

        // First pass: try to fill existing stacks of the same item
        for (InventoryEntry e : items) {
            if (remaining <= 0) break;
            if (e.getItemId().equals(itemId)) {
                int canAdd = maxStack - e.getQuantity();
                if (canAdd > 0) {
                    int toAdd = Math.min(remaining, canAdd);
                    e.setQuantity(e.getQuantity() + toAdd);
                    remaining -= toAdd;
                }
            }
        }

        // Second pass: place remainder in free slots
        while (remaining > 0) {
            int freeSlot = findFreeSlotFromList(items);
            if (freeSlot == -1) break;
            int toPlace = Math.min(remaining, maxStack);
            items.add(new InventoryEntry(itemId, toPlace, freeSlot));
            remaining -= toPlace;
        }

        saveItems(profile, items);
        return remaining;
    }

    /**
     * Removes the entry at {@code slotIndex} and returns it, or {@code null}
     * if no entry occupies that slot.
     */
    @Nullable
    public InventoryEntry removeItem(@Nonnull CompanionProfile profile, int slotIndex) {
        List<InventoryEntry> items = getItems(profile);
        InventoryEntry removed = null;
        List<InventoryEntry> remaining = new ArrayList<>();
        for (InventoryEntry e : items) {
            if (e.getSlotIndex() == slotIndex && removed == null) {
                removed = e;
            } else {
                remaining.add(e);
            }
        }
        if (removed != null) {
            saveItems(profile, remaining);
        }
        return removed;
    }

    /**
     * Removes up to {@code maxQty} items from {@code slotIndex}. If the slot
     * has fewer items, the slot is emptied. Returns the actual number removed
     * (0 if the slot was empty).
     */
    public int removeFromSlotPartial(@Nonnull CompanionProfile profile, int slotIndex, int maxQty) {
        if (maxQty <= 0) return 0;
        List<InventoryEntry> items = getItems(profile);
        List<InventoryEntry> rebuilt = new ArrayList<>(items.size());
        int removed = 0;
        boolean found = false;
        for (InventoryEntry e : items) {
            if (!found && e.getSlotIndex() == slotIndex) {
                found = true;
                int take = Math.min(e.getQuantity(), maxQty);
                removed = take;
                int leftover = e.getQuantity() - take;
                if (leftover > 0) {
                    rebuilt.add(new InventoryEntry(e.getItemId(), leftover, e.getSlotIndex()));
                }
            } else {
                rebuilt.add(e);
            }
        }
        if (found) {
            saveItems(profile, rebuilt);
        }
        return removed;
    }

    /**
     * Returns the entry at {@code slotIndex}, or {@code null} if the slot is
     * empty.
     */
    @Nullable
    public InventoryEntry getItemAt(@Nonnull CompanionProfile profile, int slotIndex) {
        for (InventoryEntry e : getItems(profile)) {
            if (e.getSlotIndex() == slotIndex) {
                return e;
            }
        }
        return null;
    }

    /**
     * Removes all items from the inventory.
     */
    public void clearInventory(@Nonnull CompanionProfile profile) {
        profile.setInventoryItems("");
    }

    // --- Private helpers ---

    /** Finds the first free slot given an already-parsed item list. */
    private int findFreeSlotFromList(@Nonnull List<InventoryEntry> items) {
        boolean[] occupied = new boolean[MAX_SLOTS];
        for (InventoryEntry e : items) {
            occupied[e.getSlotIndex()] = true;
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!occupied[i]) return i;
        }
        return -1;
    }
}
