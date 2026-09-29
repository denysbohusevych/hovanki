package app.hovanki.server.api

import app.hovanki.server.admin.AdminProperties
import app.hovanki.server.admin.Staff
import app.hovanki.server.admin.StaffAuthService
import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Duration

/**
 * Resolves a [Staff] controller parameter from the admin session cookie (docs/adr/0008-admin.md); when the request
 * rotated the session's token, the answer carries the new cookie.
 */
class StaffArgumentResolver(private val auth: StaffAuthService) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == Staff::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Staff {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
        val token = request?.cookies?.firstOrNull { it.name == ApiRoutes.ADMIN_COOKIE }?.value
        if (token.isNullOrBlank()) {
            throw GameException(ErrorCode.UNAUTHORIZED, "Log in to the admin", ErrorReason.SESSION_EXPIRED)
        }
        val authentication = auth.authenticate(token)
        authentication.newToken?.let { newToken ->
            webRequest.getNativeResponse(HttpServletResponse::class.java)?.addHeader(
                HttpHeaders.SET_COOKIE,
                AdminWebConfig.sessionCookie(newToken, authentication.cookieMaxAge),
            )
        }
        return authentication.staff
    }
}

/**
 * In front of every `/api/v1/admin` route: 404 while the admin is off (no secret key), 403 without the
 * [ApiRoutes.ADMIN_HEADER] header. A form or an image on another site can't set a header, and a script there can't
 * without CORS, which the server never allows: together with `SameSite=Strict`, no cross-site request gets through.
 */
class AdminGuard(private val properties: AdminProperties) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!properties.enabled) throw GameException(ErrorCode.NOT_FOUND, "The admin is off")
        if (request.getHeader(ApiRoutes.ADMIN_HEADER) != "1") {
            throw GameException(ErrorCode.FORBIDDEN, "Missing the ${ApiRoutes.ADMIN_HEADER} header")
        }
        return true
    }
}

/**
 * Security headers of the admin page and its API: only this server's own scripts and styles, no framing, no referrer;
 * the API's answers are never cached.
 */
class AdminHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Frame-Options", "DENY")
        if (path.startsWith(ApiRoutes.ADMIN)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        } else {
            response.setHeader("Content-Security-Policy", PAGE_POLICY)
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
        }
        chain.doFilter(request, response)
    }

    private companion object {
        const val PAGE_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    }
}

@Configuration(proxyBeanMethods = false)
class AdminWebConfig(private val auth: StaffAuthService, private val properties: AdminProperties) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(StaffArgumentResolver(auth))
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AdminGuard(properties)).addPathPatterns("${ApiRoutes.ADMIN}/**")
    }

    /** The page is `static/admin/index.html`, at `/admin/`. */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController(PAGE, "$PAGE/")
        registry.addViewController("$PAGE/").setViewName("forward:$PAGE/index.html")
    }

    @Bean
    fun adminHeadersFilter(): FilterRegistrationBean<AdminHeadersFilter> =
        FilterRegistrationBean(AdminHeadersFilter()).apply {
            addUrlPatterns(PAGE, "$PAGE/*", "${ApiRoutes.ADMIN}/*")
        }

    companion object {
        const val PAGE = "/admin"

        /** The session cookie: only for this host, only over HTTPS, never for scripts, never sent cross-site. */
        fun sessionCookie(token: String, maxAge: Duration): String = ResponseCookie.from(ApiRoutes.ADMIN_COOKIE, token)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()
            .toString()

        fun clearedCookie(): String = sessionCookie("", Duration.ZERO)
    }
}
