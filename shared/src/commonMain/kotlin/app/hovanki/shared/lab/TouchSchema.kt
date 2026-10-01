package app.hovanki.shared.lab

/**
 * The journal's kinds of the touch calibration and the shadow's classifiers (docs/adr/0017-radar-techniques-and-big-run.md
 * §3 and §2.3 «Конкурируют», docs/radar-run.md step 4), still [LabSchema.VERSION] 2: optional fields, a reader skips
 * what it doesn't know.
 *
 * - [TOUCH]: two phones touched. [TouchFields.SRC] = [TouchFields.BUTTON]: the tester pressed «We touched» with
 *   [TouchFields.PARTNER] (the partner's label in the run: in a game's run the player's id), the truth the detector is
 *   checked against; [TouchFields.SRC] = [TouchFields.IMPACT]: the phone's accelerometer felt a lone sharp jolt of
 *   [TouchFields.G] (g beyond gravity), a candidate: the report pairs the candidates of two phones with the pair's
 *   RSSI ([TouchDetector]).
 * - `shadow` ([LabRadarKinds.SHADOW]) with [ShadowFields.STATE]: a classifier in the shadow said a new state
 *   ([CarryTechs]: `carry.v1` the game's, `carry.v2` the candidate), written when it changes; never used by the game.
 */
object TouchKinds {
    const val TOUCH = "touch"
}

/** `touch`. */
object TouchFields {
    /** [BUTTON] or [IMPACT]. */
    const val SRC = "src"
    const val BUTTON = "button"
    const val IMPACT = "impact"

    /** The partner's label in the run (a game's run: the player's id), with [BUTTON]. */
    const val PARTNER = "partner"

    /** The jolt's peak beyond gravity, in g, with [IMPACT]. */
    const val G = "g"
}

/** `shadow` of a classifier: its [TECH], the [STATE] it says now and in a word [WHY]. */
object ShadowFields {
    const val TECH = "tech"
    const val STATE = "state"
    const val WHY = "why"
}

/** The pocket's classifiers that compete (ADR 0017 §2.3): the game's and the candidate of radio-lab.md §7.3. */
object CarryTechs {
    const val V1 = "carry.v1"
    const val V2 = "carry.v2"
    val ALL = listOf(V1, V2)
}
