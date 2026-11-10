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
 * The destination one `requestRoute` asks for. Every value comes from the fixed place list
 * (`contracts/navigation/places.json`); nothing here is made up by a client.
 */
data class RouteRequest(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val address: String,
    /** The stock navigation app's own place ID, or empty. */
    val poiId: String,
)

/**
 * The only part of the navigation path that touches NaviHelper, and the only part tests replace. It
 * reads the current destination and sets a destination; no other route change is offered.
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
     * Hands [route] to `requestRoute`. [returned] runs once the call came back: with null when it
     * returned, which only means the request was passed on, or with what it threw. It does not run
     * when the SDK was closed before the call could start.
     */
    fun requestRoute(route: RouteRequest, returned: (Exception?) -> Unit)

    /**
     * Stops issuing calls and releases the SDK. [closed] runs once no later SDK call can start;
     * callbacks of calls already issued are ignored by the reader.
     */
    fun close(closed: () -> Unit)
}
