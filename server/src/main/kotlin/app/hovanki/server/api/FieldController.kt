package app.hovanki.server.api

import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.lab.FieldRunService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.protocolJson
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.NativeWebRequest

/**
 * The field log's join (docs/adr/0018-field-test-build.md §3.1): a phone of the field build joins its game's log with
 * the player's game token; its uploads then go to the lab's route ([LabController.events]). While the server has
 * [app.hovanki.shared.protocol.ServerFeature.FIELD_LOG] off, 404 before the token or the body are looked at, like the
 * lab's routes. The rules are in [FieldRunService].
 */
@RestController
class FieldController(private val fields: FieldRunService, private val registry: GameRegistry) {
    @PostMapping(ApiRoutes.GAME_FIELD_JOIN)
    fun join(
        @PathVariable gameId: String,
        @RequestBody(required = false) body: String?,
        webRequest: NativeWebRequest,
    ): FieldJoinResponse {
        fields.requireEnabled()
        val token = webRequest.bearerToken() ?: throw GameException(ErrorCode.UNAUTHORIZED, "Missing bearer token")
        val player = registry.resolveToken(token)
            ?: throw GameException(ErrorCode.UNAUTHORIZED, "Unknown or expired token")
        val request = try {
            protocolJson.decodeFromString(FieldJoinRequest.serializer(), body.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw GameException(ErrorCode.BAD_REQUEST, "Malformed request body")
        }
        return fields.join(player, GameId(gameId), request)
    }
}
