package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Paid extras of an account (docs/adr/0023-entitlements.md): the base of monetization. The server decides what an
// account has; the app only shows it. No payments yet: admins grant extras in the admin (testers, promos).

/**
 * A paid extra an account can have. Nothing is gated by one yet: each names a candidate of the plan, and the code that
 * locks it checks [UserProfile.has] on the phone and the server's own records on the server
 * ([ErrorReason.ENTITLEMENT_REQUIRED]). Never sparks, perks or anything else that wins a round (docs/adr/0013).
 *
 * On the wire an account's extras are their [id]s ([UserProfile.entitlements]): an app that doesn't know a newer one
 * skips it instead of failing to read the profile. New values are only ever added.
 */
@Serializable
enum class Entitlement(val id: String) {
    /** The host's extras: more players, a bigger zone. */
    PREMIUM_HOST("premium_host"),

    /** Map styles beyond the default one. */
    MAP_STYLES("map_styles"),

    /** Looks: avatars, colors, frames on the results. */
    COSMETICS("cosmetics"),
    ;

    companion object {
        /** The extra of [id], or null for one this version doesn't know. */
        fun fromId(id: String): Entitlement? = entries.firstOrNull { it.id == id }
    }
}

/** Whether the account has [entitlement] now. */
fun UserProfile.has(entitlement: Entitlement): Boolean = entitlement.id in entitlements

/** The account's extras this version knows, in the catalog's order. */
val UserProfile.knownEntitlements: List<Entitlement>
    get() = Entitlement.entries.filter { has(it) }
