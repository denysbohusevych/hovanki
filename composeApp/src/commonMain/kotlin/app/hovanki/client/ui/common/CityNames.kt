package app.hovanki.client.ui.common

import app.hovanki.client.resources.Res
import app.hovanki.client.resources.city_bila_tserkva
import app.hovanki.client.resources.city_cherkasy
import app.hovanki.client.resources.city_chernihiv
import app.hovanki.client.resources.city_chernivtsi
import app.hovanki.client.resources.city_dnipro
import app.hovanki.client.resources.city_ivano_frankivsk
import app.hovanki.client.resources.city_kharkiv
import app.hovanki.client.resources.city_kherson
import app.hovanki.client.resources.city_khmelnytskyi
import app.hovanki.client.resources.city_kremenchuk
import app.hovanki.client.resources.city_kropyvnytskyi
import app.hovanki.client.resources.city_kryvyi_rih
import app.hovanki.client.resources.city_kyiv
import app.hovanki.client.resources.city_lutsk
import app.hovanki.client.resources.city_lviv
import app.hovanki.client.resources.city_mykolaiv
import app.hovanki.client.resources.city_odesa
import app.hovanki.client.resources.city_poltava
import app.hovanki.client.resources.city_rivne
import app.hovanki.client.resources.city_sumy
import app.hovanki.client.resources.city_ternopil
import app.hovanki.client.resources.city_uzhhorod
import app.hovanki.client.resources.city_vinnytsia
import app.hovanki.client.resources.city_zaporizhzhia
import app.hovanki.client.resources.city_zhytomyr
import org.jetbrains.compose.resources.StringResource

// The cities of the city leaderboard (docs/adr/0022-city-leaderboard.md) by name, in the app's language.

/** The name of the city [id] in the app's language; null for an id this app doesn't know (a newer server's). */
fun cityName(id: String): StringResource? = when (id) {
    "kyiv" -> Res.string.city_kyiv
    "kharkiv" -> Res.string.city_kharkiv
    "odesa" -> Res.string.city_odesa
    "dnipro" -> Res.string.city_dnipro
    "lviv" -> Res.string.city_lviv
    "zaporizhzhia" -> Res.string.city_zaporizhzhia
    "kryvyi_rih" -> Res.string.city_kryvyi_rih
    "mykolaiv" -> Res.string.city_mykolaiv
    "vinnytsia" -> Res.string.city_vinnytsia
    "poltava" -> Res.string.city_poltava
    "chernihiv" -> Res.string.city_chernihiv
    "cherkasy" -> Res.string.city_cherkasy
    "khmelnytskyi" -> Res.string.city_khmelnytskyi
    "zhytomyr" -> Res.string.city_zhytomyr
    "sumy" -> Res.string.city_sumy
    "rivne" -> Res.string.city_rivne
    "ivano_frankivsk" -> Res.string.city_ivano_frankivsk
    "kropyvnytskyi" -> Res.string.city_kropyvnytskyi
    "ternopil" -> Res.string.city_ternopil
    "lutsk" -> Res.string.city_lutsk
    "kherson" -> Res.string.city_kherson
    "bila_tserkva" -> Res.string.city_bila_tserkva
    "kremenchuk" -> Res.string.city_kremenchuk
    "uzhhorod" -> Res.string.city_uzhhorod
    "chernivtsi" -> Res.string.city_chernivtsi
    else -> null
}
