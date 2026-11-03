package com.example.pleos_pa.diagnostics

import com.example.pleos_pa.safety.ExecutionBoundary

/** No verified capabilities, approval endpoint, navigation adapter or sender. */
object DiagnosticSession {
    private val boundary = ExecutionBoundary()
    private var observerOwner: Any? = null
    private var observerClose: java.util.function.Consumer<String>? = null

    @Synchronized
    fun attachObserver(owner: Any, close: java.util.function.Consumer<String>) {
        if (observerOwner !== owner) stop("observer_replaced")
        observerOwner = owner
        observerClose = close
    }

    @Synchronized
    fun detachObserver(owner: Any) {
        if (observerOwner !== owner) return
        try {
            stop("observer_detached")
        } finally {
            observerOwner = null
            observerClose = null
        }
    }

    @JvmOverloads
    @Synchronized
    fun stop(reason: String = "session_stop") {
        boundary.stop()
        observerClose?.accept(reason)
    }

    fun unknown() = boundary.observeUx(ExecutionBoundary.UxState.UNKNOWN)

    fun navigationAllowed() = boundary.allows(ExecutionBoundary.Action.NAVIGATION_CHANGE)
}
