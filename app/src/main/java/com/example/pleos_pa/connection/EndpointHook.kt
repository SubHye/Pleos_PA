package com.example.pleos_pa.connection

import com.example.pleos_pa.bridge.BridgeEndpoint
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.mcp.VehicleReadTool

/**
 * Where a debug-only experiment may wrap the endpoint of the next session (COM-55). Nothing in
 * `main` sets it, so a release build always gets the endpoint unchanged.
 */
object EndpointHook {
    @Volatile
    var decorate: (BridgeEndpoint, VehicleReadCatalog, VehicleReadTool) -> BridgeEndpoint =
        { endpoint, _, _ ->
            endpoint
        }
}
