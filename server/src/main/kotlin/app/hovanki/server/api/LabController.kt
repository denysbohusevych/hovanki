package app.hovanki.server.api

import app.hovanki.server.game.GameException
import app.hovanki.server.lab.LabBatchBounds
import app.hovanki.server.lab.LabDeviceRef
import app.hovanki.server.lab.LabRunService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.LabAdvanceRequest
import app.hovanki.shared.protocol.LabEventsResponse
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabJoinResponse
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.LabUwbTokenRequest
import app.hovanki.shared.protocol.protocolJson
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The radio lab's phone routes (docs/adr/0017-radar-techniques-and-big-run.md §5): a test phone of the debug build
 * joins a run by its code, follows its plan and uploads its lab log. They exist only while the server has
 * [app.hovanki.shared.protocol.ServerFeature.RADIO_LAB] on (404 otherwise); the rules are in [LabRunService].
 */
@RestController
class LabController(private val labs: LabRunService) {
    /** The body is read only after the flag's check: while the lab is off, nothing says the route exists. */
    @PostMapping(ApiRoutes.LAB_JOIN)
    fun join(@RequestBody(required = false) body: String?, http: HttpServletRequest): LabJoinResponse {
        labs.requireEnabled()
        val request = try {
            protocolJson.decodeFromString(LabJoinRequest.serializer(), body.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw GameException(ErrorCode.BAD_REQUEST, "Malformed request body")
        }
        return labs.join(request, http.remoteAddr)
    }

    @GetMapping(ApiRoutes.LAB_STATE)
    fun state(device: LabDeviceRef, @PathVariable runId: String): LabRunStateView = labs.state(device.of(runId))

    @PostMapping(ApiRoutes.LAB_ADVANCE)
    fun advance(
        device: LabDeviceRef,
        @PathVariable runId: String,
        @RequestBody request: LabAdvanceRequest,
    ): LabRunStateView = labs.advance(device.of(runId), request.action)

    @PostMapping(ApiRoutes.LAB_UWB)
    fun uwb(
        device: LabDeviceRef,
        @PathVariable runId: String,
        @RequestBody request: LabUwbTokenRequest,
    ): LabRunStateView = labs.setUwbToken(device.of(runId), request.token)

    /**
     * The body: the log's lines (JSONL, [LabUpload.CONTENT_TYPE]), gzipped with `Content-Encoding: gzip`; read from
     * the request by [LabRunService.acceptChunk] after the flag, the token and the rate limit, and only so much of it.
     */
    @PostMapping(ApiRoutes.LAB_EVENTS)
    fun events(
        device: LabDeviceRef,
        @PathVariable runId: String,
        @RequestParam(LabUpload.PARAM_SEQ_FROM) seqFrom: Long,
        @RequestParam(LabUpload.PARAM_SEQ_TO) seqTo: Long,
        @RequestParam(LabUpload.PARAM_COUNT) count: Int,
        @RequestParam(LabUpload.PARAM_T_FROM, required = false) tFrom: Long?,
        @RequestParam(LabUpload.PARAM_T_TO, required = false) tTo: Long?,
        @RequestHeader(HttpHeaders.CONTENT_ENCODING, required = false) encoding: String?,
        http: HttpServletRequest,
    ): LabEventsResponse {
        val gzipped = when (encoding?.trim()?.lowercase()) {
            null, "", "identity" -> false
            "gzip" -> true
            else -> throw GameException(ErrorCode.BAD_REQUEST, "Only gzip")
        }
        val bounds = LabBatchBounds(seqFrom, seqTo, count, tFrom, tTo)
        return labs.acceptChunk(device.of(runId), bounds, http.inputStream, http.contentLengthLong, gzipped)
    }

    private fun LabDeviceRef.of(runId: String): LabDeviceRef {
        if (runId != this.runId) throw GameException(ErrorCode.FORBIDDEN, "Not this device's run")
        return this
    }
}

/**
 * Resolves a [LabDeviceRef] controller parameter from `Authorization: Bearer <token>`: the device token of a lab
 * join, stored only as its SHA-256. While the lab is off, 404 before anything else, like the routes themselves.
 */
class LabDeviceArgumentResolver(private val labs: LabRunService) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == LabDeviceRef::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): LabDeviceRef {
        labs.requireEnabled()
        val token = webRequest.bearerToken() ?: throw GameException(ErrorCode.UNAUTHORIZED, "Missing bearer token")
        return labs.device(token) ?: throw GameException(ErrorCode.UNAUTHORIZED, "Unknown or expired token")
    }
}
