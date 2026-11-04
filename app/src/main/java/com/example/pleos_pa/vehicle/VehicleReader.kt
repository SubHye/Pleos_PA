package com.example.pleos_pa.vehicle

import com.example.pleos_pa.contract.ItemCapability
import com.example.pleos_pa.contract.ItemKey
import com.example.pleos_pa.contract.ItemStatus
import com.example.pleos_pa.contract.Outcome
import com.example.pleos_pa.contract.PropertyEntry
import com.example.pleos_pa.contract.ReadStage
import com.example.pleos_pa.contract.SdkFailureClassifier
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadItem
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadResponse
import com.example.pleos_pa.contract.VehicleReadValidator
import java.time.Instant

/** Identifies one admitted request. Cancelling a finished request's ticket does nothing. */
class ReadTicket internal constructor()

sealed interface ReadAdmission {
    data class Accepted(val ticket: ReadTicket) : ReadAdmission

    data object Busy : ReadAdmission

    data object Unavailable : ReadAdmission

    /** The selection leaves nothing to read, for example only properties without an area. */
    data object NothingSelected : ReadAdmission
}

/**
 * The one read slot shared by every request of the app. read, cancel, close and every state change
 * run on [scheduler]. Each property is checked once, then its items are read one at a time under
 * the request's single deadline. A null [source] means the device could not be identified, so no
 * value may leave the app.
 */
class VehicleReader(
    private val catalog: VehicleReadCatalog,
    private val sdk: VehicleSdk,
    private val scheduler: ReadScheduler,
    private val source: String?,
) {
    private val rules = VehicleReadValidator(catalog)

    private inner class Pending(
        val request: VehicleReadRequest,
        val deadline: Long,
        val complete: (VehicleReadResponse) -> Unit,
    ) {
        val ticket = ReadTicket()
        val properties = ArrayDeque(selectedProperties(request))
        val reads = ArrayDeque<Planned>()
        val planned = LinkedHashSet(selectedKeys(request))
        val items = LinkedHashMap<ItemKey, VehicleReadItem>()

        /** The one SDK callback still awaited, consumed by its first delivery. */
        var awaited: Any? = null
        var cancelTimer: (() -> Unit)? = null
        @Volatile var finished = false
        val wanted: () -> Boolean = { !finished && scheduler.nowMillis() < deadline }
    }

    private class Planned(val key: ItemKey, val capability: ItemCapability)

    private var pending: Pending? = null
    private var closed = false

    /** [budgetMillis] is what the request may still spend, with the reply already allowed for. */
    fun read(
        request: VehicleReadRequest,
        budgetMillis: Long,
        complete: (VehicleReadResponse) -> Unit,
    ): ReadAdmission {
        if (closed || source == null) return ReadAdmission.Unavailable
        if (pending != null) return ReadAdmission.Busy
        val properties = selectedProperties(request)
        if (properties.isEmpty()) return ReadAdmission.NothingSelected
        if (!properties.all(sdk::supports)) return ReadAdmission.Unavailable

        val admitted = Pending(request, scheduler.nowMillis() + budgetMillis, complete)
        pending = admitted
        if (budgetMillis < 1) {
            finish(admitted, Outcome.TIMEOUT)
            return ReadAdmission.Accepted(admitted.ticket)
        }
        admitted.cancelTimer = scheduler.after(budgetMillis) { finish(admitted, Outcome.TIMEOUT) }
        next(admitted)
        return ReadAdmission.Accepted(admitted.ticket)
    }

    fun cancel(ticket: ReadTicket) {
        pending?.takeIf { it.ticket === ticket }?.let { finish(it, Outcome.CANCELLED) }
    }

    /** Ends the request in progress, then releases the SDK. [closed] runs once it is released. */
    fun close(closed: () -> Unit) {
        if (this.closed) {
            closed()
            return
        }
        this.closed = true
        try {
            pending?.let { finish(it, Outcome.CANCELLED) }
        } finally {
            sdk.close(closed)
        }
    }

    private fun selectedProperties(request: VehicleReadRequest): List<String> {
        val all = catalog.propertiesOf(request.query).toList().sorted()
        val chosen =
            if (request.properties.isEmpty()) all else all.filter(request.properties::contains)
        if (request.areas.isEmpty()) return chosen
        return chosen.filter { areaEntriesFor(it, request.areas).isNotEmpty() }
    }

    /** Items known before any capability answers: the fixed ones and those without an area. */
    private fun selectedKeys(request: VehicleReadRequest): List<ItemKey> {
        val fixed = catalog.tools.getValue(request.query).fixedItems
        return selectedProperties(request).flatMap { property ->
            val ofProperty = fixed.filter { it.property == property }
            when {
                ofProperty.isNotEmpty() -> ofProperty
                catalog.properties.getValue(property).areaType == null ->
                    listOf(ItemKey(property, null))
                else -> emptyList()
            }
        }
    }

    private fun areaEntriesFor(property: String, areas: Set<String>): List<String> {
        val type = catalog.properties.getValue(property).areaType ?: return emptyList()
        val vocabulary = catalog.areas.getValue(type)
        return areas.filter { catalog.isAreaOf(it, vocabulary) }
    }

    /** Where a property's items fall when capability reports nothing usable. */
    private fun fallbackAreas(request: VehicleReadRequest, entry: PropertyEntry): List<String> =
        if (request.areas.isEmpty()) {
            catalog.areas.getValue(entry.areaType!!).toList()
        } else {
            areaEntriesFor(entry.id, request.areas)
        }

    private fun live(request: Pending): Boolean {
        if (pending !== request) return false
        if (scheduler.nowMillis() >= request.deadline) {
            finish(request, Outcome.TIMEOUT)
            return false
        }
        return true
    }

    /** True only for the first delivery of the awaited callback while the request is live. */
    private fun consume(request: Pending, token: Any): Boolean {
        if (request.awaited !== token) return false
        request.awaited = null
        return live(request)
    }

    private fun next(request: Pending) {
        if (!live(request)) return
        val read = request.reads.removeFirstOrNull()
        if (read != null) {
            readItem(request, read)
            return
        }
        val property = request.properties.removeFirstOrNull()
        if (property == null) {
            val items = request.items.values.toList()
            val successes = items.count { it.status == ItemStatus.OK }
            val outcome =
                when (successes) {
                    items.size -> Outcome.COMPLETE
                    0 -> Outcome.FAILED
                    else -> Outcome.PARTIAL
                }
            finish(request, outcome)
            return
        }
        checkCapability(request, catalog.properties.getValue(property))
    }

    private fun checkCapability(request: Pending, entry: PropertyEntry) {
        val token = Any()
        request.awaited = token
        fun failed(error: Exception) {
            val failure = SdkFailureClassifier.classify(ReadStage.CAPABILITY, error)
            for (key in keysWithoutCapability(request, entry)) {
                request.items[key] =
                    VehicleReadItem(
                        key.property,
                        key.area,
                        failure.status,
                        errorType = failure.errorType,
                        errorCode = failure.errorCode,
                    )
            }
            next(request)
        }
        try {
            sdk.checkCapability(
                entry.id,
                request.wanted,
                { capability ->
                    scheduler.execute {
                        if (consume(request, token)) onCapability(request, entry, capability)
                    }
                },
                { error -> scheduler.execute { if (consume(request, token)) failed(error) } },
            )
        } catch (error: Exception) {
            if (consume(request, token)) failed(error)
        }
    }

    private fun keysWithoutCapability(request: Pending, entry: PropertyEntry): List<ItemKey> {
        val known = request.planned.filter { it.property == entry.id }
        if (known.isNotEmpty()) return known
        return fallbackAreas(request.request, entry).map { ItemKey(entry.id, it) }
    }

    private fun onCapability(
        request: Pending,
        entry: PropertyEntry,
        capability: PropertyCapability,
    ) {
        for (key in keysFor(request, entry, capability)) {
            val reported = capability.areas?.filter { sameArea(it.area, key.area) }
            val single = reported?.singleOrNull()
            val itemCapability =
                ItemCapability(
                    capability.access,
                    reported?.size,
                    capability.areas?.joinToString(", ") { it.area },
                    single?.minimum ?: capability.minimum,
                    single?.maximum ?: capability.maximum,
                )
            val status =
                when {
                    capability.access !in READABLE -> ItemStatus.READ_UNAVAILABLE
                    reported == null -> null
                    reported.isEmpty() -> ItemStatus.AREA_UNSUPPORTED
                    reported.size != 1 -> ItemStatus.SUPPORT_UNCONFIRMED
                    else -> null
                }
            if (status != null) {
                request.items[key] = VehicleReadItem(key.property, key.area, status, itemCapability)
            } else {
                request.planned += key
                request.reads.add(Planned(key, itemCapability))
            }
        }
        next(request)
    }

    /** The fixed or area-less items, or else one per reported area the catalog knows. */
    private fun keysFor(
        request: Pending,
        entry: PropertyEntry,
        capability: PropertyCapability,
    ): List<ItemKey> {
        val known = request.planned.filter { it.property == entry.id }
        if (known.isNotEmpty()) return known
        val selected = request.request.areas
        val vocabulary = catalog.areas.getValue(entry.areaType!!)
        val reported =
            capability.areas
                .orEmpty()
                .map { it.area }
                .filter { catalog.isAreaOf(it, vocabulary) }
                .filter { area -> selected.isEmpty() || selected.any { sameArea(it, area) } }
        val areas =
            when {
                selected.isNotEmpty() -> areaEntriesFor(entry.id, selected)
                reported.isNotEmpty() -> reported
                else -> fallbackAreas(request.request, entry)
            }
        return areas.map { ItemKey(entry.id, it) }
    }

    /** `A+B` and `B+A` name the same grouped area. */
    private fun sameArea(left: String, right: String?): Boolean =
        right != null && left.split('+').toSet() == right.split('+').toSet()

    private fun readItem(request: Pending, planned: Planned) {
        val token = Any()
        request.awaited = token
        val key = planned.key
        fun failed(error: Exception, receivedAt: Long) {
            val failure = SdkFailureClassifier.classify(ReadStage.READ, error)
            request.items[key] =
                VehicleReadItem(
                    key.property,
                    key.area,
                    failure.status,
                    planned.capability,
                    receivedAt =
                        if (failure.status == ItemStatus.NO_VALUE) iso(receivedAt) else null,
                    errorType = failure.errorType,
                    errorCode = failure.errorCode,
                )
            next(request)
        }
        try {
            sdk.read(
                key.property,
                key.area,
                request.wanted,
                { value, receivedAt ->
                    scheduler.execute {
                        if (consume(request, token)) {
                            request.items[key] = interpret(planned, value, receivedAt)
                            next(request)
                        }
                    }
                },
                { error, receivedAt ->
                    scheduler.execute { if (consume(request, token)) failed(error, receivedAt) }
                },
            )
        } catch (error: Exception) {
            if (consume(request, token)) failed(error, 0)
        }
    }

    private fun interpret(planned: Planned, value: Any?, receivedAt: Long): VehicleReadItem {
        val key = planned.key
        val entry = catalog.properties.getValue(key.property)
        val capability = planned.capability
        val at = iso(receivedAt)
        return when {
            value == null ->
                VehicleReadItem(
                    key.property,
                    key.area,
                    ItemStatus.NO_VALUE,
                    capability,
                    receivedAt = at,
                )
            rules.isConfirmed(entry, value, capability) ->
                VehicleReadItem(
                    key.property,
                    key.area,
                    ItemStatus.OK,
                    capability,
                    value,
                    rules.meaningOf(entry.id, value),
                    entry.unit,
                    at,
                )
            else ->
                VehicleReadItem(
                    key.property,
                    key.area,
                    ItemStatus.UNKNOWN_VALUE,
                    capability,
                    value,
                    receivedAt = at,
                )
        }
    }

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun finish(request: Pending, outcome: Outcome) {
        if (pending !== request) return
        pending = null
        request.finished = true
        request.awaited = null
        request.cancelTimer?.invoke()
        val unfinished = outcome == Outcome.TIMEOUT || outcome == Outcome.CANCELLED
        val notRead =
            if (unfinished) {
                (request.planned - request.items.keys).map {
                    VehicleReadItem(it.property, it.area, ItemStatus.NOT_READ)
                }
            } else {
                emptyList()
            }
        request.complete(
            VehicleReadResponse(
                request.request.requestId,
                request.request.query,
                outcome,
                source!!,
                catalog.sdkVersion,
                request.items.values + notRead,
            )
        )
    }

    private companion object {
        val READABLE = setOf("READ", "READ_WRITE")
    }
}
