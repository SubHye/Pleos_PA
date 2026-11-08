package com.example.pleos_pa.sweep

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val MCP_URL = "http://127.0.0.1:18766/mcp"
private const val CONSOLE_URL = "http://127.0.0.1:8088"
private val pretty = Json { prettyPrint = true }

/**
 * `.\gradlew.bat :vehicle-sweep:run --args="<report.json> [console-map.json]"`, from the repository
 * root, with the app connected and `adb forward tcp:18766` (and `tcp:8088` with a console map).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: vehicle-sweep <report.json> [console-map.json]")
        exitProcess(2)
    }
    val catalog =
        Catalog(
            Json.parseToJsonElement(File("contracts/vehicle-read/catalog.json").readText())
                .jsonObject
        )
    val consoleMap =
        args.getOrNull(1)?.let {
            ConsoleRow.parse(Json.parseToJsonElement(File(it).readText()).jsonArray)
        } ?: emptyList()
    val report = runBlocking {
        val client = Client(Implementation("pleos-pa-vehicle-sweep", "0"))
        client.connect(StreamableHttpClientTransport(HttpClient(CIO), MCP_URL))
        try {
            val offered = client.listTools().tools.map { it.name }.toSet()
            val caller = ToolCaller { name, arguments ->
                client.callTool(name, arguments).structuredContent
            }
            Sweep(catalog, caller, offered, log = ::println)
                .run(HttpConsole(CONSOLE_URL), consoleMap)
        } finally {
            client.close()
        }
    }
    File(args[0])
        .apply { parentFile?.mkdirs() }
        .writeText(pretty.encodeToString(JsonObject.serializer(), report))
    println("report written to ${args[0]}")
    exitProcess(0)
}

private class HttpConsole(private val base: String) : VirtualConsole {
    private val http = java.net.http.HttpClient.newHttpClient()

    override fun state(): JsonObject =
        send(HttpRequest.newBuilder(URI("$base/vehicle/state")).GET())

    override fun put(endpoint: String, params: Map<String, String>) {
        val query =
            params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        send(
            HttpRequest.newBuilder(URI("$base$endpoint?$query"))
                .PUT(HttpRequest.BodyPublishers.noBody())
        )
    }

    private fun send(request: HttpRequest.Builder): JsonObject {
        val response =
            http.send(
                request.timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        if (response.statusCode() !in 200..299) {
            throw SweepStopped(
                "console ${response.request().method()} refused: ${response.statusCode()}"
            )
        }
        return runCatching { Json.parseToJsonElement(response.body()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}
