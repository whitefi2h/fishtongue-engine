package com.fishtongue.lexurgy.desktop

import com.meamoria.lexurgy.api.SessionAffinity
import com.meamoria.lexurgy.api.Timeouts
import com.meamoria.lexurgy.api.configureSerialization
import com.meamoria.lexurgy.api.inflect.v1.runInflectV1
import com.meamoria.lexurgy.api.sc.v1.cancelScv1
import com.meamoria.lexurgy.api.sc.v1.pollScV1
import com.meamoria.lexurgy.api.sc.v1.runScV1
import com.meamoria.lexurgy.api.sc.v1.runScV1Validate
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.system.exitProcess

const val DESKTOP_PROTOCOL_VERSION = 1
const val ENGINE_VERSION = "1.7.6-fishtongue.1"

data class DesktopArguments(
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val authToken: String,
) {
    companion object {
        fun parse(arguments: Array<String>): DesktopArguments {
            val values = arguments.associate { raw ->
                val argument = raw.removePrefix("--")
                val separator = argument.indexOf('=')
                require(separator > 0) { "Arguments must use key=value syntax" }
                argument.substring(0, separator) to argument.substring(separator + 1)
            }
            val host = values["host"] ?: error("host is required")
            require(host == "127.0.0.1") { "Only 127.0.0.1 is allowed" }
            val port = values["port"]?.toIntOrNull() ?: error("port is required")
            require(port in 0..65535) { "port is invalid" }
            val protocolVersion =
                values["protocolVersion"]?.toIntOrNull() ?: error("protocolVersion is required")
            require(protocolVersion == DESKTOP_PROTOCOL_VERSION) { "Unsupported protocol version" }
            val authToken = values["authToken"] ?: error("authToken is required")
            require(authToken.length >= 43) { "authToken must contain at least 256 bits" }
            return DesktopArguments(host, port, protocolVersion, authToken)
        }
    }
}

@Serializable
data class ReadyHandshake(
    val event: String,
    val protocolVersion: Int,
    val port: Int,
    val engineVersion: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val protocolVersion: Int,
    val engineVersion: String,
)

@Serializable
data class CancelResponse(val cancelled: Boolean)

fun main(arguments: Array<String>) {
    val config = try {
        DesktopArguments.parse(arguments)
    } catch (error: IllegalArgumentException) {
        System.err.println("FishTongue desktop API configuration error: ${error.message}")
        exitProcess(2)
    }

    val shutdown = CompletableDeferred<Unit>()
    val selectedPort = if (config.port == 0) findAvailableLoopbackPort() else config.port
    val server = embeddedServer(
        Netty,
        host = config.host,
        port = selectedPort,
        module = {
            desktopModule(config.authToken) {
                shutdown.complete(Unit)
            }
        },
    )
    server.start(wait = false)
    println(
        Json.encodeToString(
            ReadyHandshake(
                event = "ready",
                protocolVersion = DESKTOP_PROTOCOL_VERSION,
                port = selectedPort,
                engineVersion = ENGINE_VERSION,
            )
        )
    )
    System.out.flush()
    runBlocking { shutdown.await() }
    server.stop(gracePeriodMillis = 250, timeoutMillis = 3_000)
}

private fun findAvailableLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

fun Application.desktopModule(authToken: String, requestShutdown: () -> Unit = {}) {
    configureSerialization()
    install(Timeouts) {
        singleStepTimeoutSeconds = 5.0
        requestTimeoutSeconds = 0.5
        totalTimeoutSeconds = 120.0
    }
    install(SessionAffinity) {
        affinityHeaders = emptyMap()
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val authorization = call.request.header(HttpHeaders.Authorization)
        if (authorization != "Bearer $authToken") {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UNAUTHORIZED"))
            finish()
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ready",
                    protocolVersion = DESKTOP_PROTOCOL_VERSION,
                    engineVersion = ENGINE_VERSION,
                )
            )
        }
        post("/scv1/validate") {
            call.runScV1Validate()
        }
        post("/scv1") {
            call.runScV1()
        }
        get("/scv1/poll/{jobId}") {
            call.pollScV1(call.parameters["jobId"].orEmpty())
        }
        delete("/scv1/poll/{jobId}") {
            call.respond(CancelResponse(cancelScv1(call.parameters["jobId"].orEmpty())))
        }
        post("/inflectv1") {
            call.runInflectV1()
        }
        post("/shutdown") {
            call.respond(mapOf("status" to "shuttingDown"))
            requestShutdown()
        }
    }
}
