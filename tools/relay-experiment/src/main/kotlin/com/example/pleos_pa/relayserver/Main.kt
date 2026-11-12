package com.example.pleos_pa.relayserver

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import org.json.JSONObject

private const val CATALOG = "contracts/vehicle-read/catalog.json"
private const val RUN_FILE = "build/relay-experiment/run.json"

/**
 * Runs the relay until Ctrl+C. Start it from the repository root. The per-run secrets go to
 * [RUN_FILE] only, never to the console, and the file is deleted on exit.
 */
fun main(args: Array<String>) {
    val port = portOf(args)
    val catalog = File(CATALOG)
    if (port == null || !catalog.isFile) {
        System.err.println("usage (from the repository root): relay-experiment [--port <1-65535>]")
        exitProcess(2)
    }
    val allowed = RelayServer.adoptedTools(catalog.readText())
    val secret = randomText()
    val carToken = randomText()
    val relay = RelayServer(secret, carToken, allowed, port)
    val runFile = File(RUN_FILE)
    val done = CountDownLatch(1)
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
                relay.stop()
                runFile.delete()
                done.countDown()
            }
        )
    relay.start()
    runFile.parentFile.mkdirs()
    runFile.writeText(
        JSONObject()
            .put("port", port)
            .put("mcpPath", "/r/$secret/mcp")
            .put("carPath", RelayServer.CAR_PATH)
            .put("carToken", carToken)
            .toString(2)
    )
    println("event=relay_started host=${RelayServer.HOST} port=$port allowedTools=${allowed.size}")
    println("event=run_file path=$RUN_FILE")
    done.await()
}

private fun portOf(args: Array<String>): Int? =
    when {
        args.isEmpty() -> RelayServer.DEFAULT_PORT
        args.size == 2 && args[0] == "--port" -> args[1].toIntOrNull()?.takeIf { it in 1..65535 }
        else -> null
    }

/** 256 random bits as URL-safe text. */
private fun randomText(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
