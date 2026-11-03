package com.example.pleos_pa.vehicle

import java.util.concurrent.CopyOnWriteArrayList

/** Runs queued work when told to, on a clock only the test moves. */
internal class ManualScheduler : ReadScheduler {
    private var now = 0L
    private val queue = ArrayDeque<() -> Unit>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()

    override fun nowMillis() = now

    override fun execute(action: () -> Unit) {
        queue.add(action)
    }

    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val timer = (now + delayMillis) to action
        timers += timer
        return { timers.remove(timer) }
    }

    fun runAll() {
        while (true) (queue.removeFirstOrNull() ?: return)()
    }

    fun advance(millis: Long) {
        now += millis
        timers
            .filter { it.first <= now }
            .forEach {
                timers.remove(it)
                it.second()
            }
        runAll()
    }
}

/** Records calls and answers only when the test says so. */
internal class FakeVehicleSdk : VehicleSdk {
    val calls = CopyOnWriteArrayList<String>()
    val reads = CopyOnWriteArrayList<String>()
    val unsupported = mutableSetOf<String>()
    var throwNext: Exception? = null
    @Volatile private var capabilityDone: ((PropertyCapability) -> Unit)? = null
    @Volatile private var capabilityFailed: ((Exception) -> Unit)? = null
    @Volatile private var readDone: ((Any?, Long) -> Unit)? = null
    @Volatile private var readFailed: ((Exception, Long) -> Unit)? = null

    override fun supports(property: String) = property !in unsupported

    override fun checkCapability(
        property: String,
        active: () -> Boolean,
        complete: (PropertyCapability) -> Unit,
        failed: (Exception) -> Unit,
    ) {
        calls += "capability:$property"
        throwNext?.let {
            throwNext = null
            throw it
        }
        capabilityDone = complete
        capabilityFailed = failed
    }

    override fun read(
        property: String,
        area: String?,
        active: () -> Boolean,
        complete: (Any?, Long) -> Unit,
        failed: (Exception, Long) -> Unit,
    ) {
        calls += "read:$property:$area"
        reads += "${property.lowercase()}:$area"
        readDone = complete
        readFailed = failed
    }

    override fun close(closed: () -> Unit) {
        closed()
    }

    fun answerCapability(capability: PropertyCapability) = capabilityDone!!(capability)

    fun failCapability(error: Exception) = capabilityFailed!!(error)

    fun answerRead(value: Any?) = readDone!!(value, RECEIVED)

    fun failRead(error: Exception) = readFailed!!(error, RECEIVED)

    fun pendingRead(): (Any?) -> Unit {
        val done = readDone!!
        return { done(it, RECEIVED) }
    }

    fun pendingCapability(): (PropertyCapability) -> Unit = capabilityDone!!

    private companion object {
        const val RECEIVED = 1_793_000_000_000L
    }
}
