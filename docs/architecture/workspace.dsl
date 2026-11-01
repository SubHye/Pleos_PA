workspace "Pleos_PA and reference PoC" "Current scaffold and separately identified reference implementation; no migration claimed." {
    model {
        current = softwareSystem "Pleos_PA - current scaffold" "Android resources and manifest only; no Activity, SDK, Bridge or MCP." {
            scaffold = container "Android scaffold APK" "Empty app module; no user workflow implemented." "Android / Gradle"
        }
        client = softwareSystem "Local MCP-capable client" "Reference execution environment; exact AI product integration must be revalidated."
        vehicle = softwareSystem "Pleos Emulator Vehicle Service" "Reference SDK backend; not a live vehicle."
        poc = softwareSystem "pleostest - reference only" "Source reviewed at 0d288c8; not migrated into Pleos_PA." {
            mcp = container "External MCP server" "Four read tools and HTTP bridge client." "Node.js / stdio"
            android = container "Reference Android app" "Foreground Service owns Bridge and Vehicle SDK adapter." "Kotlin / Vehicle SDK 2.3.2"
        }
        client -> mcp "Calls read tools" "MCP / stdio"
        mcp -> android "Reads via PC loopback and ADB forward" "HTTP POST /v2/vehicle-read :18765"
        android -> vehicle "Queries supported vehicle properties" "Vehicle SDK"
        deploymentEnvironment "Scaffold installation target - unverified" {
            deploymentNode "Development Android device or emulator" "Intended APK installation target, not a confirmed deployment." "Android" {
                containerInstance scaffold
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
