package app.hovanki.server.api

import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.PlayerRef
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Resolves a [PlayerRef] controller parameter from `Authorization: Bearer <token>`.
 * Tokens are issued on create/join and live as long as the game.
 */
class PlayerRefArgumentResolver(private val registry: GameRegistry) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == PlayerRef::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): PlayerRef {
        val header = webRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val token = header.removePrefix("${ApiRoutes.AUTH_SCHEME} ").trim()
        if (token.isEmpty() ||
            token == header.trim()
        ) {
            throw GameException(ErrorCode.UNAUTHORIZED, "Missing bearer token")
        }
        return registry.resolveToken(token) ?: throw GameException(ErrorCode.UNAUTHORIZED, "Unknown or expired token")
    }
}

@Configuration(proxyBeanMethods = false)
class WebConfig(private val registry: GameRegistry) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(PlayerRefArgumentResolver(registry))
    }
}
