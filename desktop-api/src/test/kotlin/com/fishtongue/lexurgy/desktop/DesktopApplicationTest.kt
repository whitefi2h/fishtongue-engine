package com.fishtongue.lexurgy.desktop

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopApplicationTest {
    @Test
    fun argumentsRejectNonLoopbackHost() {
        assertFailsWith<IllegalArgumentException> {
            DesktopArguments.parse(
                arrayOf(
                    "host=0.0.0.0",
                    "port=0",
                    "protocolVersion=1",
                    "authToken=${"a".repeat(43)}",
                )
            )
        }
    }

    @Test
    fun argumentsRejectWrongProtocol() {
        assertFailsWith<IllegalArgumentException> {
            DesktopArguments.parse(
                arrayOf(
                    "host=127.0.0.1",
                    "port=0",
                    "protocolVersion=2",
                    "authToken=${"a".repeat(43)}",
                )
            )
        }
    }

    @Test
    fun healthRequiresBearerToken() = testApplication {
        application { desktopModule("secret-token") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/health").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/health") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }.status,
        )
    }

    @Test
    fun validateUsesLexurgyCore() = testApplication {
        application { desktopModule("secret-token") }
        val response = client.post("/scv1/validate") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"changes":"Test:\na => e"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
