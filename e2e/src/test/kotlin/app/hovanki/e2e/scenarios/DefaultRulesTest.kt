package app.hovanki.e2e.scenarios

import app.hovanki.client.session.GameSessionManager
import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario 6.4: a game with the rules and timers the app really uses (`GameSessionManager.defaultSettings`, only the
 * hiding time shortened to a minute), where every other scenario shortens them. The thresholds fit together in real
 * time: a catch by the code, one by silence after the full code timeout, a reveal after 45 s without GPS.
 *
 * Several minutes long: tagged `slow`, left out of `./gradlew :e2e:test` and run nightly
 * (`./gradlew :e2e:test -Pe2e.slow=true`).
 */
@Tag("slow")
class DefaultRulesTest {
    @Test
    fun aGameWithTheDefaultRules() = scenario("A game with the default rules", timeout = 12.minutes) {
        val settings = GameSessionManager.defaultSettings(PARK).copy(hidingSeconds = 60)
        val rules = settings.rules
        check(rules == GameRules(), "the default rules")
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val boris = player("Boris", at = PARK, behavior = BotBehavior(onClaim = ClaimReaction.Ignore))
        val vera = player("Vera", at = PARK)

        sam.createsGame(settings)
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 60.0))
        boris.walksTo(PARK.offset(eastMeters = -60.0))
        vera.walksTo(PARK.offset(northMeters = 70.0))
        awaitPhase(GamePhase.SEEKING, within = 90.seconds)

        // Vera's GPS goes off as seeking starts: seen after 45 s of silence.
        vera.turnsGpsOff()
        val silentSince = state().serverTimeMillis
        val staleWithin = (rules.staleLocationRevealSeconds + 15).seconds
        awaitReveal(vera, VisibilityReason.STALE_SIGNAL, to = sam, within = staleWithin)
        check(
            state().serverTimeMillis - silentSince >= (rules.staleLocationRevealSeconds - 5) * 1000L,
            "not before the silence lasted ${rules.staleLocationRevealSeconds} s",
        )

        // Anna shows the code.
        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna, within = 20.seconds)
        awaitCatch(anna, CatchStatus.CONFIRMED, within = 20.seconds)

        // Boris stays silent: caught when the full code timeout is over.
        sam.catchesUpWith(boris)
        sam.claimsCatch(boris)
        val silence = awaitCatch(boris, CatchStatus.CONFIRMED, within = (rules.catchCodeTimeoutSeconds + 15).seconds)
        check(
            silence.deadlineMillis - silence.createdAtMillis == rules.catchCodeTimeoutSeconds * 1000L,
            "confirmed by silence after ${rules.catchCodeTimeoutSeconds} s",
        )

        // Vera, seen where her GPS went silent: GPS can't disprove the claim, she shows the code.
        sam.claimsCatch(vera)
        sam.entersCodeShownBy(vera, within = 20.seconds)
        awaitCatch(vera, CatchStatus.CONFIRMED, within = 20.seconds)
        awaitPhase(GamePhase.FINISHED, within = 20.seconds)
        check(state().players.filter { it.id != sam.id }.all { it.status == PlayerStatus.CAUGHT }, "all caught")
    }
}
