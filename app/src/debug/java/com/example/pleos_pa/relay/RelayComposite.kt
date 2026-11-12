package com.example.pleos_pa.relay

import com.example.pleos_pa.bridge.BridgeEndpoint
import com.example.pleos_pa.connection.EndpointHook
import com.example.pleos_pa.mcp.EmbeddedMcpServer

/**
 * The session's own endpoint plus the relay link of the COM-55 experiment. The relay is served by a
 * second MCP endpoint that is given no navigation tool, so through the relay only the adopted
 * vehicle read tools can be listed or called, whatever the relay forwards.
 */
class RelayComposite(
    private val local: BridgeEndpoint,
    private val vehicleOnly: EmbeddedMcpServer,
    private val relay: RelayClientEndpoint,
    private val onState: (state: String, code: String) -> Unit,
) : BridgeEndpoint {
    /** A failure of the experiment's parts is reported and leaves the session as it was. */
    override fun start() {
        local.start()
        try {
            vehicleOnly.start()
        } catch (e: Exception) {
            onState("FAILED", "VEHICLE_ONLY_BIND")
            return
        }
        relay.start()
    }

    /** The link first, so nothing arrives for an endpoint that is closing. */
    override fun stop() {
        relay.stop()
        vehicleOnly.stop()
        local.stop()
    }
}

/** Sets and clears the hook that adds the relay link to the next session. */
object RelayExperiment {
    fun install(config: RelayConfig, onState: (state: String, code: String) -> Unit) {
        EndpointHook.decorate = { local, catalog, tool ->
            val vehicleOnly = EmbeddedMcpServer(catalog, tool, port = 0)
            RelayComposite(
                local,
                vehicleOnly,
                RelayClientEndpoint(config, { vehicleOnly.localPort }, onState),
                onState,
            )
        }
    }

    fun clear() {
        EndpointHook.decorate = { endpoint, _, _ -> endpoint }
    }
}
