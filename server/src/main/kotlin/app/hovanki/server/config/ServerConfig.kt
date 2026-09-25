package app.hovanki.server.config

import app.hovanki.shared.protocol.protocolJson
import kotlinx.serialization.json.Json
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class ServerConfig {
    /** Same JSON settings as the mobile client; Spring Boot uses this bean for `@Serializable` bodies. */
    @Bean
    fun json(): Json = protocolJson

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@ConfigurationProperties("hovanki.games")
data class GameProperties(
    /** How long a finished game (and all its location data) is kept, e.g. for the post-game review. */
    val finishedRetention: Duration = Duration.ofMinutes(30),
    /** Games without any request for this long are dropped. */
    val idleRetention: Duration = Duration.ofHours(6),
    val cleanupInterval: Duration = Duration.ofMinutes(1),
)
