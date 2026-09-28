package app.hovanki.server.api

import app.hovanki.shared.protocol.AdminLoginRequest
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.protocolJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The /admin page itself (docs/adr/0008-admin.md): served with a strict content security policy. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPageTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun thePageAllowsOnlyItsOwnScripts() {
        mvc.get("/admin").andExpect { status { isFound() } }.andReturn().response.let {
            assertEquals("/admin/", it.redirectedUrl)
        }
        assertEquals("/admin/index.html", mvc.get("/admin/").andReturn().response.forwardedUrl)
        val page = mvc.get("/admin/index.html").andExpect { status { isOk() } }.andReturn().response
        assertContains(page.getContentAsString(Charsets.UTF_8), "admin.js")
        val policy = page.getHeader("Content-Security-Policy").orEmpty()
        for (rule in listOf("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'", "base-uri 'none'")) {
            assertContains(policy, rule)
        }
        assertEquals("DENY", page.getHeader("X-Frame-Options"))
        assertEquals("no-referrer", page.getHeader("Referrer-Policy"))
        mvc.get("/admin/admin.js").andExpect { status { isOk() } }
        mvc.get("/admin/map.js").andExpect { status { isOk() } }
    }
}

/** Without a secret key the admin is off: its routes don't exist. */
@SpringBootTest(properties = ["hovanki.admin.secret-key="])
@AutoConfigureMockMvc
class AdminOffTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun noAdminWithoutAKey() {
        val body = mvc.post(ApiRoutes.ADMIN_LOGIN) {
            contentType = MediaType.APPLICATION_JSON
            content = protocolJson.encodeToString(AdminLoginRequest("anyone", "password"))
            header(ApiRoutes.ADMIN_HEADER, "1")
        }.andExpect { status { isNotFound() } }.andReturn().response.contentAsString
        assertEquals(ErrorCode.NOT_FOUND, protocolJson.decodeFromString<ApiError>(body).code)
    }
}
