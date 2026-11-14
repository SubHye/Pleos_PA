workspace "Pleos_PA" "Current app and the target structure planned before the second-round presentation; only what the current system describes exists in the repository." {
    model {
        current = softwareSystem "Pleos_PA - current" "Android connection app whose embedded MCP server offers adopted vehicle read tools and navigation tools over the Vehicle SDK and NaviHelper (ADR-0008)." {
            scaffold = container "Android app" "Connection and a bounded process-memory record of vehicle reads and navigation tool states, shown as request and answer cards under display Car UX restrictions; Foreground Service owns the MCP server on loopback 127.0.0.1:18766/mcp (no authentication), SDK adapters and the fresh Car UX guard before navigation changes." "Kotlin / MCP Kotlin SDK / Ktor CIO / Vehicle SDK 2.3.2"
            sweep = container "vehicle-sweep" "Test support on the development PC: calls every offered tool and compares Virtual Console values to build catalog verification evidence." "Kotlin / JVM"
            placeCheck = container "nav-place-check" "Test support APK, not part of the app: started from adb, sets each fixed place as the destination and reads it back to build place list evidence. The only component that calls cancelRoute." "Kotlin / NaviHelper 3.0.5"
        }
        client = softwareSystem "Local MCP-capable client" "Reference execution environment; exact AI product integration must be revalidated."
        vehicle = softwareSystem "Pleos Emulator Vehicle Service" "Reference SDK backend; not a live vehicle."
        navi = softwareSystem "Pleos Emulator Navi Service" "Navi Service with the stock navigation app; not a live vehicle."
        client -> scaffold "Calls read tools (verified with Codex CLI, other clients unverified) and the destination tools (verified by direct MCP calls only, no AI client)" "MCP Streamable HTTP via adb forward tcp:18766"
        sweep -> scaffold "Calls every offered tool" "MCP Streamable HTTP via adb forward tcp:18766"
        scaffold -> vehicle "Queries catalog vehicle properties" "Vehicle SDK"
        scaffold -> navi "Reads the current destination; sets a place of the fixed list as the destination and confirms it by reading back. Navigation changes require fresh unrestricted Car UX and driver MAIN display mapping; unknown blocks" "NaviHelper getDestinationInfo, requestRoute"
        placeCheck -> navi "Sets each listed place, reads it back and cancels; each run approved" "NaviHelper requestRoute, getDestinationInfo, cancelRoute"

        driver = person "Driver - target" "Asks the AI they already use for navigation, vehicle status and driving records. On the vehicle screen only links or unlinks the AI and turns recording on or off."
        targetSystem = softwareSystem "Pleos_PA - target before the second-round presentation (not implemented)" "Goal structure from the submission plan, sections 5.1 and 5.2. The relay server, record store, plugins, location recorder, account linking and auto start do not exist in the repository yet." {
            targetApp = container "Android app - target" "Serves the embedded MCP tools, opens the connection to the relay server first, shows a one-time code and QR code to link an AI account, starts on boot after the first launch, and records the route and trip summary only while the driver has recording on. Encrypts records kept on the device and deletes them after upload." "Kotlin / Vehicle SDK, NaviHelper, FusedLocation"
            targetRelay = container "Relay server - target" "Remote MCP endpoint for AI apps. Accepts the connection opened by the vehicle app and forwards only authenticated requests within the allowed tools. Hosting, authentication and exposure are undecided and go to a separate ADR (COM-17)." "Undecided"
            targetStore = container "Record store - target" "Keeps the last vehicle status with its timestamp, trip summaries and routes under a pseudonymised vehicle identifier. Storage technology and retention period are undecided." "Undecided"
            targetPlugin = container "AI app plugin - target" "ChatGPT and Claude plugins that bundle the relay MCP connection settings and a basic skill. Distributed from the team repository before any official directory listing." "Plugin with SKILL.md"
        }
        targetAi = softwareSystem "ChatGPT or Claude - target" "Interprets the request, selects tools, explains results and runs approval dialogue. Supported plans, apps and write actions must be verified per client (COM-64)."
        targetCalendar = softwareSystem "Calendar service - target" "Schedule source connected to the AI; account data is not copied into the vehicle app."
        targetMessaging = softwareSystem "Messaging service - target" "Sends only after explicit approval; support must be verified."
        targetVehicle = softwareSystem "Pleos navigation, vehicle and location services - target" "Destination, ETA, vehicle data and vehicle location. Verified on the emulator only, to the extent the contracts record."
        driver -> targetAi "Requests and approves actions"
        driver -> targetApp "Links or unlinks the AI, turns recording on or off and checks results"
        targetAi -> targetCalendar "Reads appointment"
        targetAi -> targetMessaging "Sends approved message"
        targetAi -> targetPlugin "Uses the installed connection settings and skill"
        targetAi -> targetRelay "Calls tools" "Remote MCP over HTTPS"
        targetApp -> targetRelay "Opens the connection first and executes forwarded tool calls" "TLS 1.2 or later; protocol undecided"
        targetRelay -> targetStore "Stores and reads the last vehicle status, trip summaries and routes"
        targetApp -> targetVehicle "Sets destination and waypoints and reads ETA, vehicle data and location" "Vehicle SDK / NaviHelper / FusedLocation"

        deploymentEnvironment "Scaffold installation target - unverified" {
            deploymentNode "Development Android device or emulator" "Intended APK installation target, not a confirmed deployment." "Android" {
                containerInstance scaffold
                containerInstance placeCheck
            }
            deploymentNode "Development PC" "ADB host for the AI client and test support." "Desktop OS" {
                containerInstance sweep
            }
        }
    }
    views {
        systemContext current "CurrentContext" {
            include *
            autoLayout lr
        }
        container current "CurrentContainers" {
            include *
            autoLayout lr
        }
        deployment current "Scaffold installation target - unverified" "CurrentDeploymentTarget" {
            include *
            autoLayout lr
        }
        systemContext targetSystem "TargetContext" {
            include *
            autoLayout lr
        }
        container targetSystem "TargetContainers" {
            include *
            autoLayout lr
        }
    }
}
