package app.hovanki.server.features

import app.hovanki.shared.protocol.ServerFeature
import org.springframework.boot.context.properties.ConfigurationProperties

/** `hovanki.features.*`: what this server does with the server features. */
@ConfigurationProperties("hovanki.features")
data class FeaturesProperties(
    /**
     * Features that are a shadow only (the field test's server, docs/adr/0018-field-test-build.md §3.3): treated as
     * off for every game, the admin can't turn them on, and the field log still computes what they would answer
     * (the proximity rule at a claim, the pocket stealth's band). Empty by default, as on production.
     */
    val shadowOnly: Set<ServerFeature> = emptySet(),
)
