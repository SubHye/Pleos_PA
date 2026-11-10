workspace "Pleos_PA" "Current app, first-phase target and separately identified reference implementation; only what the current system describes has been migrated." {
    model {
        current = softwareSystem "Pleos_PA - current" "Android connection app whose embedded MCP server offers the adopted vehicle read tools over the Vehicle SDK and the adopted navigation tools over NaviHelper (ADR-0008)." {
            scaffold = container "Android app" "Connection control screen, Foreground Service, MCP server on loopback 127.0.0.1:18766/mcp (no authentication), read gateway and a Vehicle SDK boundary covering the catalog." "Kotlin / MCP Kotlin SDK / Ktor CIO / Vehicle SDK 2.3.2"
            sweep = container "vehicle-sweep" "Test support on the development PC: calls every offered tool and compares Virtual Console values to build catalog verification evidence." "Kotlin / JVM"
            placeCheck = container "nav-place-check" "Test support APK, not part of the app: started from adb, sets each fixed place as the destination and reads it back to build place list evidence. The only component that calls cancelRoute." "Kotlin / NaviHelper 3.0.5"
        }
        client = softwareSystem "Local MCP-capable client" "Reference execution environment; exact AI product integration must be revalidated."
        vehicle = softwareSystem "Pleos Emulator Vehicle Service" "Reference SDK backend; not a live vehicle."
        navi = softwareSystem "Pleos Emulator Navi Service" "Navi Service with the stock navigation app; not a live vehicle."
        poc = softwareSystem "pleostest - reference only" "Source reviewed at 0d288c8. The connection service, Bridge, read slot and DOOR_POSITION SDK call were selectively migrated; the rest was not." {
            mcp = container "External MCP server" "Four read tools and HTTP bridge client." "Node.js / stdio"
            android = container "Reference Android app" "Foreground Service owns Bridge and Vehicle SDK adapter." "Kotlin / Vehicle SDK 2.3.2"
        }
        client -> mcp "Calls read tools" "MCP / stdio"
        client -> scaffold "Calls read tools (verified with Codex CLI, other clients unverified) and the destination tools (verified by direct MCP calls only, no AI client)" "MCP Streamable HTTP via adb forward tcp:18766"
        sweep -> scaffold "Calls every offered tool" "MCP Streamable HTTP via adb forward tcp:18766"
        scaffold -> vehicle "Queries catalog vehicle properties" "Vehicle SDK"
        scaffold -> navi "Reads the current destination; sets a place of the fixed list as the destination and confirms it by reading back. No driving-restriction check yet" "NaviHelper getDestinationInfo, requestRoute"
        placeCheck -> navi "Sets each listed place, reads it back and cancels; each run approved" "NaviHelper requestRoute, getDestinationInfo, cancelRoute"
        mcp -> android "Reads via PC loopback and ADB forward" "HTTP POST /v2/vehicle-read :18765"
        android -> vehicle "Queries supported vehicle properties" "Vehicle SDK"

        driver = person "Driver - target" "Asks for departure help and approves destination changes and messages."
        targetSystem = softwareSystem "Pleos_PA - first-phase target (not implemented)" "Goal structure; nothing here exists in the repository yet." {
            targetApp = container "Android app - target" "Serves MCP and owns SDK permissions, execution, session lifecycle, driving restrictions and result display through Service, MCP server and Adapter. Keeps call records in memory only." "Kotlin / Vehicle SDK, NaviHelper"
        }
        targetAi = softwareSystem "ChatGPT or Claude - target" "Interprets schedule, selects tools and runs approval dialogue. Exact product, version and connection path must be verified per client."
        targetCalendar = softwareSystem "Calendar service - target" "Schedule source connected to the AI; account data is not copied into the vehicle app."
        targetMessaging = softwareSystem "Messaging service - target" "Sends only after explicit approval; support must be verified."
        targetVehicle = softwareSystem "Pleos navigation and vehicle services - target" "Destination, ETA and vehicle data; support must be verified."
        driver -> targetAi "Requests departure help and approves actions"
        driver -> targetApp "Checks results and stops or resumes the connection"
        targetAi -> targetCalendar "Reads appointment"
        targetAi -> targetMessaging "Sends approved message"
        targetAi -> targetApp "Calls tools" "MCP; adb forward from a development PC. A path without PC (COM-15) is undecided"
        targetApp -> targetVehicle "Sets destination and reads ETA and vehicle data" "Vehicle SDK / NaviHelper"

        deploymentEnvironment "Scaffold installation target - unverified" {
            deploymentNode "Development Android device or emulator" "Intended APK installation target, not a confirmed deployment." "Android" {
                containerInstance scaffold
                containerInstance placeCheck
            }
            deploymentNode "Development PC" "ADB host for the AI client and test support." "Desktop OS" {
                containerInstance sweep
            }
        }
        deploymentEnvironment "Reference development setup" {
            deploymentNode "Development PC" "Local MCP runtime and ADB host." "Desktop OS" {
                containerInstance mcp
                deploymentNode "Pleos emulator" "Reference Android runtime; not verified in this task." "Android" {
                    containerInstance android
                    softwareSystemInstance vehicle
                }
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
        systemContext poc "ReferenceContext" {
            include *
            autoLayout lr
        }
        container poc "ReferenceContainers" {
            include *
            autoLayout lr
        }
        deployment poc "Reference development setup" "ReferenceDeployment" {
            include *
            autoLayout lr
        }
    }
}
