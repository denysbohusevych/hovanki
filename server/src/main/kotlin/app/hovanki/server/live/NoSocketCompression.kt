package app.hovanki.server.live

import app.hovanki.shared.protocol.ApiRoutes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriTemplate
import java.util.Collections
import java.util.Enumeration

/**
 * Keeps the live channel's frames uncompressed: the upgrade of [ApiRoutes.SOCKET] reaches Tomcat without the app's
 * `Sec-WebSocket-Extensions`, so no permessage-deflate is agreed. Tomcat holds a zlib deflater and inflater for every
 * compressed socket, about 220 KB outside the heap for the socket's whole life: 900 sockets killed a server with
 * production's 640 MB (docs/perf.md, «Первые находки»). A frame is plain JSON instead, ~6.5 KB a sync. Tomcat has no
 * setting to leave the extension out, Spring's choice of extensions doesn't reach Tomcat's own: the header is hidden.
 */
class NoSocketCompression : OncePerRequestFilter() {
    private val socket = UriTemplate(ApiRoutes.SOCKET)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !socket.matches(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        chain.doFilter(WithoutExtensions(request), response)
    }

    private class WithoutExtensions(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        override fun getHeader(name: String): String? = if (isExtensions(name)) null else super.getHeader(name)

        override fun getHeaders(name: String): Enumeration<String> =
            if (isExtensions(name)) Collections.emptyEnumeration() else super.getHeaders(name)

        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList().filterNot(::isExtensions))

        private fun isExtensions(name: String) = name.equals(EXTENSIONS, ignoreCase = true)
    }

    private companion object {
        const val EXTENSIONS = "Sec-WebSocket-Extensions"
    }
}
