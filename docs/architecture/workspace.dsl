workspace "Pleos_PA" "Current app, first-phase target and separately identified reference implementation; only what the current system describes has been migrated." {
    model {
        current = softwareSystem "Pleos_PA - current scaffold" "Android connection app with a loopback Bridge that reads one door property, and a stdio MCP server with no tools." {
            scaffold = container "Android scaffold APK" "Connection control screen, Foreground Service, /v3/vehicle-read Bridge and a Vehicle SDK boundary covering DOOR_POSITION." "Android / Gradle"
            currentMcp = container "MCP server" "Lists no tools until execution verification passes." "Node.js / TypeScript / stdio"
        }
        client = softwareSystem "Local MCP-capable client" "Reference execution environment; exact AI product integration must be revalidated."
        vehicle = softwareSystem "Pleos Emulator Vehicle Service" "Reference SDK backend; not a live vehicle."
        poc = softwareSystem "pleostest - reference only" "Source reviewed at 0d288c8. The connection service, Bridge, read slot and DOOR_POSITION SDK call were selectively migrated; the rest was not." {
            mcp = container "External MCP server" "Four read tools and HTTP bridge client." "Node.js / stdio"
            android = container "Reference Android app" "Foreground Service owns Bridge and Vehicle SDK adapter." "Kotlin / Vehicle SDK 2.3.2"
        }
        client -> mcp "Calls read tools" "MCP / stdio"
        client -> currentMcp "Connects and lists tools; connection must be revalidated per client" "MCP / stdio"
        mcp -> android "Reads via PC loopback and ADB forward" "HTTP POST /v2/vehicle-read :18765"
        android -> vehicle "Queries supported vehicle properties" "Vehicle SDK"

        driver = person "Driver - target" "Asks for departure help and approves destination changes and messages."
        targetSystem = softwareSystem "Pleos_PA - first-phase target (not implemented)" "Goal structure; nothing here exists in the repository yet." {
            targetMcp = container "External MCP server - target" "MCP protocol, input validation, request and error mapping. No vehicle SDK logic." "Node.js / TypeScript / stdio"
            targetApp = container "Android app - target" "Owns SDK permissions, execution, session lifecycle, driving restrictions and result display through Service, Bridge and Adapter. Keeps call records in memory only." "Kotlin / Vehicle SDK, NaviHelper"
        }
        targetAi = softwareSystem "ChatGPT or Claude - target" "Interprets schedule, selects tools and runs approval dialogue. Exact product, version and connection path must be verified per client."
        targetCalendar = softwareSystem "Calendar service - target" "Schedule source connected to the AI; account data is not copied into the vehicle app."
        targetMessaging = softwareSystem "Messaging service - target" "Sends only after explicit approval; support must be verified."
        targetVehicle = softwareSystem "Pleos navigation and vehicle services - target" "Destination, ETA and vehicle data; support must be verified."
        driver -> targetAi "Requests departure help and approves actions"
        driver -> targetApp "Checks results and stops or resumes the connection"
        targetAi -> targetCalendar "Reads appointment"
        targetAi -> targetMessaging "Sends approved message"
        targetAi -> targetMcp "Calls tools" "MCP; client connection path must be verified"
        targetMcp -> targetApp "Forwards validated requests" "Loopback HTTP / ADB forward (development)"
        targetApp -> targetVehicle "Sets destination and reads ETA and vehicle data" "Vehicle SDK / NaviHelper"

        deploymentEnvironment "Scaffold installation target - unverified" {
            deploymentNode "Development Android device or emulator" "Intended APK installation target, not a confirmed deployment." "Android" {
                containerInstance scaffold
            }
            deploymentNode "Development PC" "Local MCP runtime." "Desktop OS" {
                containerInstance currentMcp
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
