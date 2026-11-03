package com.example.pleos_pa.connection

/**
 * The connection status the screen shows. Held only in memory, so a new process starts at
 * [ConnectionStatus.Stopped]. Main-thread use.
 */
object ConnectionStatusBoard {
    var status: ConnectionStatus = ConnectionStatus.Stopped
        private set

    private var listener: ((ConnectionStatus) -> Unit)? = null

    fun publish(next: ConnectionStatus) {
        status = next
        listener?.invoke(next)
    }

    fun observe(observer: (ConnectionStatus) -> Unit) {
        listener = observer
        observer(status)
    }

    /** Only [observer]'s own registration: a newer screen may already have replaced it. */
    fun remove(observer: (ConnectionStatus) -> Unit) {
        if (listener === observer) listener = null
    }
}
