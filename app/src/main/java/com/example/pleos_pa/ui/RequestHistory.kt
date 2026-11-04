package com.example.pleos_pa.ui

/**
 * Process-local, bounded presentation data. No persistence, raw HTTP, identity or conversation
 * data.
 */
class RequestHistory(private val capacity: Int = 100) {
    class Token internal constructor(val sequence: Long)

    data class Record(
        val token: Token,
        val requestId: String,
        val tool: String,
        val arguments: String,
        val receivedAt: String,
        val state: String = "RECEIVED",
        val source: String? = null,
        val details: String = "",
    )

    private val records = ArrayDeque<Record>()
    private val observers = mutableSetOf<() -> Unit>()
    private var sequence = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun begin(requestId: String, tool: String, arguments: String, receivedAt: String): Token {
        val token = Token(++sequence)
        records.addFirst(
            Record(
                token,
                requestId.take(36),
                tool.take(100),
                arguments.take(4096),
                receivedAt.take(40),
            )
        )
        while (records.size > capacity) records.removeLast()
        notifyChanged()
        return token
    }

    /**
     * Identity is per attempt, even for duplicate requestIds. Evicted/cleared/terminal rows never
     * revive.
     */
    @Synchronized
    fun finish(token: Token, state: String, source: String?, details: String): Boolean {
        val row = records.firstOrNull { it.token === token } ?: return false
        if (row.state != "RECEIVED") return false
        val next =
            row.copy(state = state.take(40), source = source?.take(40), details = bounded(details))
        val updated = records.map { if (it.token === token) next else it }
        records.clear()
        records.addAll(updated)
        notifyChanged()
        return true
    }

    @Synchronized fun snapshot(): List<Record> = records.toList()

    @Synchronized
    fun clear() {
        records.clear()
        notifyChanged()
    }

    @Synchronized
    fun observe(observer: () -> Unit) {
        observers.add(observer)
        observer()
    }

    @Synchronized
    fun remove(observer: () -> Unit) {
        observers.remove(observer)
    }

    private fun notifyChanged() {
        observers.toList().forEach { it() }
    }

    private fun bounded(text: String): String =
        if (text.length <= MAX_DETAILS) text else text.take(MAX_DETAILS - 20) + "\n[일부 내용 생략]"

    companion object {
        // Working limit for OQ-09. A device UI/heap review is still needed before final adoption.
        const val MAX_DETAILS = 16_384
    }
}
