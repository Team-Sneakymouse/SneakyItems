package com.sneakyitems.api

/**
 * Where a player holds or wears a magic item, for [SneakyItemsAPI.whileHolding].
 *
 * "Main hand" is the selected hotbar slot. Armor is the four armor slots (head, chest, legs, feet). Storage slots and
 * unselected hotbar slots never count here; use [SneakyItemsAPI.whileCarrying] for "anywhere in the inventory".
 */
enum class HeldSlot {
  /** The selected hotbar slot. */
  MAIN_HAND,

  /** The offhand slot. */
  OFF_HAND,

  /** The main hand or the offhand. */
  EITHER_HAND,

  /** Any of the four armor slots. */
  ARMOR,

  /** Either hand or any armor slot. */
  ANY,
}
