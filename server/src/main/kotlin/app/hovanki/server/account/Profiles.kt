package app.hovanki.server.account

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserProfile
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * An account as its owner sees it: the lab's screen for staff while the lab is on (ADR 0018 §4.D) and the paid extras
 * active now (docs/adr/0023-entitlements.md).
 */
@Component
class Profiles(
    private val features: FeatureFlags,
    private val entitlements: EntitlementRepository,
    private val clock: Clock,
) {
    fun of(record: UserRecord): UserProfile = record.toProfile(
        labOn = features.isEnabled(ServerFeature.RADIO_LAB),
        entitlements = entitlements.active(record.id, clock.instant()).map { it.entitlement },
    )
}
