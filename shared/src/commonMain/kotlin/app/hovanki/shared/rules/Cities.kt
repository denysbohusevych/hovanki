package app.hovanki.shared.rules

/**
 * The cities a player may pick for the city leaderboard (docs/adr/0022-city-leaderboard.md): their own choice in
 * the profile, never guessed from the phone's position. Ids only; the app names them in its language. A fixed list, so
 * there is nothing to moderate and the same city is always one id. New ids may be added; never rename or remove one
 * (accounts keep them).
 */
object Cities {
    /** The oblast centres outside the occupation and a few other big cities, roughly by size. */
    val IDS: List<String> = listOf(
        "kyiv",
        "kharkiv",
        "odesa",
        "dnipro",
        "lviv",
        "zaporizhzhia",
        "kryvyi_rih",
        "mykolaiv",
        "vinnytsia",
        "poltava",
        "chernihiv",
        "cherkasy",
        "khmelnytskyi",
        "zhytomyr",
        "sumy",
        "rivne",
        "ivano_frankivsk",
        "kropyvnytskyi",
        "ternopil",
        "lutsk",
        "kherson",
        "bila_tserkva",
        "kremenchuk",
        "uzhhorod",
        "chernivtsi",
    )

    private val known = IDS.toSet()

    fun isKnown(id: String): Boolean = id in known
}
