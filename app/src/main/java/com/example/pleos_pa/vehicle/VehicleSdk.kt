package com.example.pleos_pa.vehicle

/** One area a capability check reported, written as the contract writes areas (`A+B`). */
data class ReportedArea(
    val area: String,
    val minimum: Double? = null,
    val maximum: Double? = null,
)

/**
 * What one capability check reported. [areas] is null for a property without an area, whose bounds
 * are [minimum] and [maximum].
 */
data class PropertyCapability(
    val access: String,
    val areas: List<ReportedArea>? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
)

/**
 * The only part of the read path that touches the Vehicle SDK, and the only part tests replace. No
 * polling or subscription. Callbacks may arrive on any thread, and [active] may be checked from any
 * thread: queued work skips the SDK call once it is false.
 */
interface VehicleSdk {
    /** False for a property this build has no SDK call for. */
    fun supports(property: String): Boolean

    fun checkCapability(
        property: String,
        active: () -> Boolean,
        complete: (PropertyCapability) -> Unit,
        failed: (Exception) -> Unit,
    )

    /** [complete] gets the value in contract form and the UTC millis it was received at. */
    fun read(
        property: String,
        area: String?,
        active: () -> Boolean,
        complete: (Any?, Long) -> Unit,
        failed: (Exception, Long) -> Unit,
    )

    /**
     * Stops issuing calls and releases the SDK. [closed] runs once no later SDK call can start;
     * calls already issued cannot be recalled and their callbacks are ignored.
     */
    fun close(closed: () -> Unit)
}

/** The thread reads run on. Tests drive it by hand. */
interface ReadScheduler {
    fun nowMillis(): Long

    fun execute(action: () -> Unit)

    /** Returns a function that cancels the delayed action. */
    fun after(delayMillis: Long, action: () -> Unit): () -> Unit
}
