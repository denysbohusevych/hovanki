package app.hovanki.server.history

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `hovanki.history.*` (docs/adr/0007-game-history-and-routes.md). */
@ConfigurationProperties("hovanki.history")
data class HistoryProperties(
    /** Saved routes are deleted this long after they were saved (DataRetention). */
    val routeRetention: Duration = Duration.ofDays(90),
    /** Game recordings (docs/adr/0011-spectators-and-recordings.md) are deleted this long after they were saved. */
    val recordingRetention: Duration = Duration.ofDays(90),
)
