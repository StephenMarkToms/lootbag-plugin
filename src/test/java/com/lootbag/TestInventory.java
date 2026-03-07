package com.lootbag;

import net.runelite.api.InventoryID;

public class TestInventory {
    public static void main(String[] args) {
        for (InventoryID id : InventoryID.values()) {
            System.out.println(id.name() + " = " + id.getId());
        }
    }
}
