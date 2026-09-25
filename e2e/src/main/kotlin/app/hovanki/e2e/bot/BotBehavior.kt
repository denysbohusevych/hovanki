package app.hovanki.e2e.bot

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a bot does on its own when the game asks something of it, like the person holding the phone. */
data class BotBehavior(
    /** Hider: reaction to a seeker's catch claim ("Show the code" screen). */
    val onClaim: ClaimReaction = ClaimReaction.ShowCode(),
    /** Anyone outside a dispute: how to vote on it. */
    val onDispute: VoteReaction = VoteReaction.Abstain,
)

sealed interface ClaimReaction {
    /** Holds up the screen with the code after [after]; a seeker reads it off the screen. */
    data class ShowCode(val after: Duration = 1.seconds) : ClaimReaction

    /** Presses "Dispute" after [after]. */
    data class Dispute(val after: Duration = 1.seconds) : ClaimReaction

    /** Does nothing: silence counts as caught once the code timeout passes. */
    data object Ignore : ClaimReaction
}

sealed interface VoteReaction {
    data class Vote(val confirm: Boolean, val after: Duration = 1.seconds) : VoteReaction

    data object Abstain : VoteReaction
}
