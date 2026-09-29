package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The radar, the quests and the phone's abilities (docs/adr/0010-nearby-radar.md,
// docs/adr/0011-quests-sparks-and-sensors.md). Everything here is behind two switches: the operator turns a feature on
// for the whole server in the admin ([ServerFeature]), and the host turns it on for one game in the lobby
// ([GameFeatures]). Old apps know none of it: every field has a default.

/**
 * A feature the operator switches on for everybody on the server (the admin, docs/adr/0008-admin.md): the app has
 * them all built in and offers one in the lobby only while the server says it is on ([GameSnapshot.enabledFeatures]);
 * the server refuses a setup that uses one that is off ([ErrorReason.FEATURE_DISABLED]). On the wire by name, as
 * strings: an app ignores a name it doesn't know.
 */
@Serializable
enum class ServerFeature {
    /** The radar by Bluetooth: «warm / hot» for the seekers ([GameFeatures.radar]). */
    RADAR,

    /** The hider's sense: «a seeker is near» from the pocket ([GameFeatures.hiderSense]). */
    HIDER_SENSE,

    /** The precision radar by UWB: meters and an arrow while both look at their phones ([GameFeatures.precisionRadar]). */
    PRECISION_RADAR,

    /** A catch claim only up close by the radar ([GameFeatures.proximityCatch]). */
    PROXIMITY_CATCH,

    /** Quests for sparks ([GameFeatures.quests]). */
    QUESTS,

    /** Perks, bought for sparks or picked up on the map ([GameFeatures.perks]). */
    PERKS,

    /** Checkpoints the host places, reached by GPS or by scanning a code ([GameFeatures.checkpoints]). */
    CHECKPOINTS,

    /** Perks lying on the map for the first one to get there ([GameFeatures.pickups]). */
    PICKUPS,

    /** The phones tell whether their player is running ([GameFeatures.activity]). */
    ACTIVITY,

    /** The pocket hides: a hider whose phone is in the pocket reads colder ([GameFeatures.pocketStealth]). */
    POCKET_STEALTH,
}

/** How a feature applies to a game: off, for the players whose phones have it, or every phone must have it. */
@Serializable
enum class FeatureMode {
    OFF,

    /** Players whose phones have it use it; the others play without it and everybody sees who. */
    OPTIONAL,

    /** Every phone has to have it: the game doesn't start otherwise, and a hider who turns it off is revealed. */
    REQUIRED,
}

/**
 * What the host turned on for one game (docs/adr/0010-nearby-radar.md, section 4). All off by default: a game of an
 * older app is the game as before.
 */
@Serializable
data class GameFeatures(
    /** The radar by Bluetooth: the seekers feel «warm / hot / burning» towards the hiders. */
    val radar: FeatureMode = FeatureMode.OFF,
    /** The hider's sense: a buzz from the pocket when a seeker is near (needs [radar]). */
    val hiderSense: Boolean = false,
    /** The precision radar by UWB between phones of one kind, while both look at their phones («peeking»). */
    val precisionRadar: Boolean = false,
    /** A hider who peeks sees the arrow too; off: peeking only helps the seekers. */
    val precisionForHiders: Boolean = true,
    /** Fair mode: the precision radar only when every seeker–hider pair can use it. */
    val fairOnly: Boolean = true,
    /** A catch claim only when the radar says «burning» (needs [radar]); the code stays the proof. */
    val proximityCatch: Boolean = false,
    /** Quests for sparks: the catalog ones the host picked ([GameSettings.quests]) and quest points on the map. */
    val quests: Boolean = false,
    /** Perks: bought for sparks in the round, or found on the map ([pickups]). */
    val perks: Boolean = false,
    /** Checkpoints the host placed: reached by GPS, or scanned from a code the host printed. */
    val checkpoints: Boolean = false,
    /** Perks lying on the map: the first one there takes it. */
    val pickups: Boolean = false,
    /** The phones report whether their player runs; the «Sprint» quest and the running statistics need it. */
    val activity: Boolean = false,
    /**
     * The pocket hides (needs [radar]): the body's damping of the signal is evened out for everybody
     * (`ProximityRules.POCKET_OFFSET_DB`), and on top of that a hider whose phone is in the pocket, screen off, reads
     * `ProximityRules.STEALTH_DB` colder to the seekers: about a band. Want to be harder to feel, walk blind.
     */
    val pocketStealth: Boolean = false,
) {
    val hasRadar: Boolean get() = radar != FeatureMode.OFF

    /** Sparks are earned and shown in this game. */
    val hasSparks: Boolean get() = quests || checkpoints || pickups || perks

    /** Anything of the map board: items the host places in the lobby. */
    val hasBoard: Boolean get() = quests || checkpoints || pickups

    /** The server features this setup uses: every one of them has to be on for the server to take it. */
    fun uses(): Set<ServerFeature> = buildSet {
        if (hasRadar) add(ServerFeature.RADAR)
        if (hiderSense) add(ServerFeature.HIDER_SENSE)
        if (precisionRadar) add(ServerFeature.PRECISION_RADAR)
        if (proximityCatch) add(ServerFeature.PROXIMITY_CATCH)
        if (quests) add(ServerFeature.QUESTS)
        if (perks) add(ServerFeature.PERKS)
        if (checkpoints) add(ServerFeature.CHECKPOINTS)
        if (pickups) add(ServerFeature.PICKUPS)
        if (activity) add(ServerFeature.ACTIVITY)
        if (pocketStealth) add(ServerFeature.POCKET_STEALTH)
    }

    /** This setup without the features the server has off, and without what depends on the radar when it is off. */
    fun limitedTo(enabled: Set<ServerFeature>): GameFeatures {
        val radar = if (ServerFeature.RADAR in enabled) radar else FeatureMode.OFF
        val withRadar = radar != FeatureMode.OFF
        return GameFeatures(
            radar = radar,
            hiderSense = hiderSense && withRadar && ServerFeature.HIDER_SENSE in enabled,
            precisionRadar = precisionRadar && ServerFeature.PRECISION_RADAR in enabled,
            precisionForHiders = precisionForHiders,
            fairOnly = fairOnly,
            proximityCatch = proximityCatch && withRadar && ServerFeature.PROXIMITY_CATCH in enabled,
            quests = quests && ServerFeature.QUESTS in enabled,
            perks = perks && ServerFeature.PERKS in enabled,
            checkpoints = checkpoints && ServerFeature.CHECKPOINTS in enabled,
            pickups = pickups && ServerFeature.PICKUPS in enabled,
            activity = activity && ServerFeature.ACTIVITY in enabled,
            pocketStealth = pocketStealth && withRadar && ServerFeature.POCKET_STEALTH in enabled,
        )
    }

    companion object {
        val NONE = GameFeatures()
    }
}

@Serializable
enum class Platform { ANDROID, IOS, OTHER }

/** Whether the phone can take part in the radar right now. */
@Serializable
enum class BluetoothState {
    ON,

    /** Bluetooth is switched off in the system. */
    OFF,

    /** The app may not use Bluetooth (the permission was refused). */
    DENIED,

    /** The player switched the radar off for their phone in the game's menu. */
    OFF_BY_PLAYER,

    /** No Bluetooth LE, or an app without the radar. */
    UNSUPPORTED,
}

/** What the phone's motion sensors say the player is doing ([GameFeatures.activity]). */
@Serializable
enum class Activity { UNKNOWN, STILL, WALKING, RUNNING, IN_VEHICLE }

/**
 * Where the phone is, by its own sensors ([DeviceReport.carry]): the body damps the radio a lot, so the radar evens
 * it out, and the pocket may hide ([GameFeatures.pocketStealth]). Only ever «in the pocket» with the screen off: a
 * phone the player looks at is in the hand, whatever the sensors say.
 */
@Serializable
enum class Carry { UNKNOWN, IN_HAND, IN_POCKET }

/**
 * What the phone tells the server with every `sync` ([SyncRequest.device]): what it can do and what state it is in,
 * so the lobby shows who has the radar, the server knows whom to pair by UWB, and the rules see who runs. Never a
 * position.
 */
@Serializable
data class DeviceReport(
    val platform: Platform = Platform.OTHER,
    val bluetooth: BluetoothState = BluetoothState.UNSUPPORTED,
    /** The phone has a UWB chip and the app may use it. */
    val uwb: Boolean = false,
    /** The app is on the screen (not in the pocket): the precision radar works only then. */
    val onScreen: Boolean = true,
    /** The phone's UWB discovery token while it is on the screen, for the peers the server pairs it with. */
    val uwbToken: String? = null,
    /** What the player is doing by the phone's sensors, when the game asks for it ([GameFeatures.activity]). */
    val activity: Activity = Activity.UNKNOWN,
    /** The phone can tell running from walking. */
    val activitySensor: Boolean = false,
    /** Where the phone is: in the hand, in the pocket (screen off), or the phone can't tell. */
    val carry: Carry = Carry.UNKNOWN,
    /**
     * The phone's model («Pixel 8», «iPhone15,2»), only in a game with the radar: the server keeps the radio's
     * readings by model, without players or games, to learn how loud each kind of phone is
     * (docs/adr/0010-nearby-radar.md, «Калибровка»). Never shown to the other players.
     */
    val model: String? = null,
)

/** What everybody in the lobby sees of a player's phone ([PlayerView.capabilities]): no positions, no tokens. */
@Serializable
data class Capabilities(
    val platform: Platform = Platform.OTHER,
    val bluetooth: BluetoothState = BluetoothState.UNSUPPORTED,
    val uwb: Boolean = false,
    val onScreen: Boolean = true,
    val activitySensor: Boolean = false,
)
