package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields

/** What the SDK reports. Calls may arrive on any thread. */
interface NaviSdkListener {
    /** The first service connection is up. */
    fun connected()

    /** The SDK gave up on its first connection. Not retried here. */
    fun connectionFailed()

    /**
     * One `onDestinationInfo` callback and the UTC millis it was received at. The SDK attaches no
     * request identifier, so the reader decides which request, if any, it answers.
     */
    fun destination(snapshot: DestinationFields, receivedAtMillis: Long)
}

/**
 * The only part of the navigation read path that touches NaviHelper, and the only part tests
 * replace. No route changes: only the current destination is read.
 */
interface NaviSdk {
    /** Starts the service connection once and reports to [listener] from then on. */
    fun open(listener: NaviSdkListener)

    /**
     * Asks for the current destination. The answer, if any, arrives as
     * [NaviSdkListener.destination]. [failed] runs when the call itself throws, for example a
     * `SecurityException` without `NAVI_ROUTE`; no callback follows then.
     */
    fun requestDestination(failed: (Exception) -> Unit)

    /**
     * Stops issuing calls and releases the SDK. [closed] runs once no later SDK call can start;
     * callbacks of calls already issued are ignored by the reader.
     */
    fun close(closed: () -> Unit)
}
