package app.hovanki.client.account

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.ApiResult
import app.hovanki.shared.rules.Cities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The city of the city leaderboard is where the phone is now (docs/adr/0022-city-leaderboard.md): one fix, the city
 * around it by [Cities.at], and only the city's id goes to the account. The position is neither sent nor kept.
 */
class CityLocator(
    private val account: AccountManager,
    private val location: LocationProvider,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    /** Takes one fix and updates the account's city when it changed. */
    suspend fun locate(): CityLookup {
        val user = account.state.value.user ?: return CityLookup.NoAccount
        if (!location.hasPermission()) return CityLookup.NoPermission
        val fix = try {
            withTimeoutOrNull(timeoutMillis) {
                location.locationUpdates(INTERVAL_MILLIS).first { it.accuracyMeters <= MAX_ACCURACY_METERS }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Location access lost meanwhile.
            null
        } ?: return CityLookup.NoFix
        val city = Cities.at(fix.point)
        if (city != user.city) {
            val saved = account.setCity(city)
            if (saved !is ApiResult.Success) return CityLookup.Failed(saved)
        }
        return CityLookup.Found(city)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val INTERVAL_MILLIS = 1_000L

        /** A city is tens of kilometres wide: a rough fix will do, a cell tower's too. */
        const val MAX_ACCURACY_METERS = 3_000.0
    }
}

/** What [CityLocator.locate] found. */
sealed interface CityLookup {
    data object NoAccount : CityLookup

    data object NoPermission : CityLookup

    /** No fix in time, or location access lost. */
    data object NoFix : CityLookup

    /** The phone is in [city]; null: outside every city of the list. */
    data class Found(val city: String?) : CityLookup

    /** The server did not take the city. */
    data class Failed(val result: ApiResult<Unit>) : CityLookup
}
