package app.hovanki.server.features

import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.ServerFeature
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The server features the operator turned on (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-
 * sensors.md), read on every game request from memory: loaded at start, again after every change in the admin, and
 * once a minute in case another instance changed them. The `sync` hot path never touches the database.
 */
@Component
class FeatureFlags(private val repository: FeatureFlagRepository) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val records = AtomicReference<Map<ServerFeature, FeatureFlagRecord>>(emptyMap())

    init {
        reload()
    }

    /** The features on right now, by name: what the snapshots tell the apps. */
    fun enabledNames(): List<String> = enabled().map { it.name }.sorted()

    fun enabled(): Set<ServerFeature> = records.get().values.filter { it.enabled }.mapTo(HashSet()) { it.feature }

    fun isEnabled(feature: ServerFeature): Boolean = records.get()[feature]?.enabled == true

    /** Every feature with its state, for the admin. */
    fun all(): List<FeatureFlagRecord?> = ServerFeature.entries.map { records.get()[it] }

    /** Refuses a setup that uses a feature the operator has off ([ErrorReason.FEATURE_DISABLED]). */
    fun requireAllowed(features: GameFeatures) {
        val missing = features.uses() - enabled()
        if (missing.isNotEmpty()) {
            throw GameException(
                ErrorCode.WRONG_STATE,
                "Not on this server yet: ${missing.joinToString { it.name }}",
                ErrorReason.FEATURE_DISABLED,
            )
        }
    }

    fun set(feature: ServerFeature, enabled: Boolean, by: String, at: Instant) {
        repository.set(feature, enabled, by, at)
        reload()
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun reload() {
        try {
            records.set(repository.all().associateBy { it.feature })
        } catch (e: Exception) {
            // The last known state stays; the next reload tries again.
            log.warn("Could not load the feature flags: {}", e.javaClass.simpleName)
        }
    }
}
