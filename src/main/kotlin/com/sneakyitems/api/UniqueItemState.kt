package com.sneakyitems.api

import org.bukkit.Location
import java.util.UUID

/** Where a [ItemRules.unique] item is, as last seen. */
enum class UniqueWhere { NONE, HELD, GROUND, PLACED }

/**
 * A snapshot of one unique item's state ([ItemRuleGroup.state]); it doesn't change after it is returned.
 * [entity] is the item entity (GROUND) or the click `Interaction` (PLACED); [display] the placed `ItemDisplay`.
 * [location] is the last known spot (for HELD, where the holder was at the last check), or null when never seen.
 */
data class UniqueItemState(
    val itemId: String,
    val serial: String?,
    val where: UniqueWhere,
    val holder: UUID?,
    val entity: UUID?,
    val display: UUID?,
    val location: Location?,
)
