package app.hovanki.client.session

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A new id for a request the app may have to send again ([app.hovanki.shared.rules.RequestIds]): a random UUID, from
 * the platform's secure random generator, since a join's id gives back its player.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun newRequestId(): String = Uuid.random().toString()
