package app.hovanki.radar

import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.radar.channel.ibeacon.IBeaconRegionChannel
import app.hovanki.radar.channel.name.NameChannel
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.servicedata.AdLayout
import app.hovanki.radar.channel.servicedata.ServiceDataChannel
import app.hovanki.shared.rules.AppleData

/**
 * Every channel of the radar (docs/adr/0017-radar-techniques-and-big-run.md, sections 2.1–2.3): the one place that
 * knows them by name; the hosts use them as [RadarChannel]s. The order is the advertisement's priority: a part that
 * doesn't fit after the ones before it is left out ([AdJoin]).
 *
 * The game's channels ([ChannelUse.GAME]) are read by every phone in every build; the shadow's
 * ([ChannelUse.SHADOW]: `ble.overflow`, `ble.ibeacon.region`) only while a journal is written ([RadioTrace.isListening]:
 * the debug build's lab, the field build's journal, ADR 0018), and their readings go into it, never to the game.
 */
object RadarCatalog {
    val NAME: RadarChannel = NameChannel()
    val IBEACON: RadarChannel = IBeaconChannel()
    val SCAN_RESPONSE: RadarChannel = ServiceDataChannel(AdLayout.SCAN_RESPONSE)
    val BARE: RadarChannel = ServiceDataChannel(AdLayout.BARE)
    val MFR: RadarChannel = ServiceDataChannel(AdLayout.MFR)
    val OVERFLOW: RadarChannel = OverflowChannel()
    val REGION: RadarChannel = IBeaconRegionChannel()

    /** Every channel, in the order of the advertisement's priority. */
    val channels: List<RadarChannel> = listOf(NAME, IBEACON, SCAN_RESPONSE, BARE, MFR, OVERFLOW, REGION)

    /**
     * The Android hider's layouts in the order the players get them round the circle ([hiderLayout]), the one the
     * game uses without a journal first.
     */
    val hiderLayouts: List<RadarChannel> = listOf(SCAN_RESPONSE, BARE, MFR)

    /**
     * The layout of an Android hider's advertisement: with a journal ([experiment]: the field build's test, ADR 0018
     * §4 B) the three take turns by the player's number in the game, so the report shows which one every model hears;
     * without one `.scan_response`, the layout an iPhone's scan for the game's service lets through.
     */
    fun hiderLayout(playerNumber: Int, experiment: Boolean): RadarChannel =
        if (experiment) hiderLayouts[playerNumber.mod(hiderLayouts.size)] else SCAN_RESPONSE

    /** What this phone advertises, as [role] on [platform]: every channel's part, the shadow's with [shadow] only. */
    fun parts(
        token: String,
        role: AirRole,
        platform: AirPlatform,
        options: RadioOptions,
        shadow: Boolean,
    ): List<AdPart> {
        val layout = hiderLayout(options.playerNumber, experiment = shadow)
        return channels
            .filter { it.use == ChannelUse.GAME || shadow }
            .filter { it !in hiderLayouts || it === layout }
            .mapNotNull { it.adPart(token, role, platform) }
    }

    /** The advertisement itself: the parts joined by the platform's rules. */
    fun advert(token: String, role: AirRole, platform: AirPlatform, options: RadioOptions, shadow: Boolean): Advert =
        AdJoin.join(parts(token, role, platform, options, shadow), platform)

    /** The channels a listener reads: every game channel (all three layouts), the shadow's with [shadow] only. */
    fun listening(shadow: Boolean): List<RadarChannel> =
        if (shadow) channels else channels.filter { it.use == ChannelUse.GAME }

    /** What a scan on [platform] must let through for [listening]'s channels, without repeats. */
    fun interests(platform: AirPlatform, shadow: Boolean): List<ScanInterest> =
        listening(shadow).flatMap { it.interests(platform) }.distinct()

    /** What a frame none of the channels read is, for the journal's `air` (ADR 0017 §4). */
    fun foreign(frame: HeardFrame): ForeignFrame {
        OverflowChannel.bitsOf(frame)?.let { (bits, _) -> return ForeignFrame(ForeignKind.MASK, bits) }
        if (frame.iBeacon != null) return ForeignFrame(ForeignKind.IBEACON)
        val apple = frame.manufacturerData[GameAir.APPLE_COMPANY_ID] ?: return ForeignFrame(ForeignKind.OTHER)
        return if (AppleData.iBeacon(AirHex.bytes(apple)) != null) {
            ForeignFrame(ForeignKind.IBEACON)
        } else {
            ForeignFrame(ForeignKind.APPLE)
        }
    }
}

/** How the game runs the radio besides its token and role. */
data class RadioOptions(
    /**
     * The player's number in the game (the order they joined, from 0): an Android hider's layout goes round the
     * circle by it while the journal is written ([RadarCatalog.hiderLayout]).
     */
    val playerNumber: Int = 0,
)

/** What kind of frame of somebody else's a scan let through. */
enum class ForeignKind { IBEACON, MASK, APPLE, OTHER }

/** A frame none of the channels read: its kind and, for a mask, its bits. */
data class ForeignFrame(val kind: ForeignKind, val bits: Set<Int> = emptySet())
