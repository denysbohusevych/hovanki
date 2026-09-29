package app.hovanki.client.session

import app.hovanki.client.network.ApiResult
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.SettingsPreviewResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.StreetZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How far the zone by streets of a draft [zone] is: [streets] once [DraftZoneState.READY]. */
data class DraftZone(val zone: ZoneSchedule, val state: DraftZoneState, val streets: StreetZone? = null)

enum class DraftZoneState {
    /** The server is building it. */
    LOADING,
    READY,

    /** No zone by streets can be built here: saved, the game would use the circles. */
    NO_STREETS,

    /** The phone could not learn it (no network, too many drafts): the zone shows after saving. */
    UNKNOWN,
}

/**
 * The zone by streets of the host's draft in the settings, asked from the server while the host chooses
 * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): the map shows the blocks before «Save». A tap
 * on «By streets» is asked right away; a slider or the pin is asked [SETTLE_MILLIS] after the host's last change, so a
 * drag sends one draft, not twenty. While the server builds it, it is asked again every [POLL_MILLIS]. [fetch]: the
 * server's answer about one draft.
 */
class DraftZonePreview(
    private val scope: CoroutineScope,
    private val fetch: suspend (GameSettings) -> ApiResult<SettingsPreviewResponse>,
) {
    private val mutableZone = MutableStateFlow<DraftZone?>(null)

    /** The draft's zone by streets; null: nothing to show (a circle, the game's own zone, the settings closed). */
    val zone: StateFlow<DraftZone?> = mutableZone.asStateFlow()

    private var job: Job? = null
    private var lastDraft: GameSettings? = null

    /** The last zone built: back to it (by streets, circle, by streets again) it shows at once. */
    private var lastBuilt: DraftZone? = null

    /**
     * The host's [draft] now, over the game's [saved] setup; null: the settings closed. Only a draft by streets whose
     * zone the game doesn't have yet is asked for.
     */
    fun show(draft: GameSettings?, saved: GameSettings?) {
        val previous = lastDraft
        lastDraft = draft
        val isSaved = saved?.zoneShape == ZoneShape.STREETS && saved.zone == draft?.zone
        val wanted = draft?.takeIf { it.zoneShape == ZoneShape.STREETS && !isSaved }
        if (wanted != null && wanted.zone == mutableZone.value?.zone) return
        job?.cancel()
        job = null
        if (wanted == null) {
            mutableZone.value = null
            return
        }
        lastBuilt?.takeIf { it.zone == wanted.zone }?.let { built ->
            mutableZone.value = built
            return
        }
        mutableZone.value = DraftZone(wanted.zone, DraftZoneState.LOADING)
        val tapped = previous != null && previous.zoneShape != ZoneShape.STREETS && previous.zone == wanted.zone
        job = scope.launch {
            if (!tapped) delay(SETTLE_MILLIS)
            mutableZone.value = ask(wanted).also { if (it.state == DraftZoneState.READY) lastBuilt = it }
        }
    }

    private suspend fun ask(draft: GameSettings): DraftZone {
        val zone = draft.zone
        repeat(MAX_ASKS) { asked ->
            if (asked > 0) delay(POLL_MILLIS)
            when (val result = fetch(draft)) {
                is ApiResult.Success -> {
                    val response = result.value
                    when (response.streetZone) {
                        StreetZoneState.READY -> return built(zone, response)
                        StreetZoneState.UNAVAILABLE -> return DraftZone(zone, DraftZoneState.NO_STREETS)
                        StreetZoneState.LOADING -> Unit
                        null -> return DraftZone(zone, DraftZoneState.UNKNOWN)
                    }
                }

                // Asking again won't change a refusal (not the host any more, too many drafts).
                is ApiResult.Rejected -> return DraftZone(zone, DraftZoneState.UNKNOWN)

                is ApiResult.Network -> Unit
            }
        }
        return DraftZone(zone, DraftZoneState.UNKNOWN)
    }

    /** One polygon per stage of the draft's schedule, each a closed ring; anything else is not drawn. */
    private fun built(zone: ZoneSchedule, response: SettingsPreviewResponse): DraftZone {
        val stages = response.stages
        val usable = stages.size == zone.stages.size + 1 && stages.all { it.outline.size >= MIN_OUTLINE_POINTS }
        if (!usable) return DraftZone(zone, DraftZoneState.UNKNOWN)
        return DraftZone(zone, DraftZoneState.READY, StreetZone(stages))
    }

    companion object {
        /** A slider or the pin: the draft is asked for once the host stopped for this long. */
        const val SETTLE_MILLIS = 500L

        /** Asked again this often while the server builds the zone... */
        const val POLL_MILLIS = 1_000L

        /** ... this many times at most (the server gives up on a draft after 30 s). */
        const val MAX_ASKS = 30
        private const val MIN_OUTLINE_POINTS = 4
    }
}
