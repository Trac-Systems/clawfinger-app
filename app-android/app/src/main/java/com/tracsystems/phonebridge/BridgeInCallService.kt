package com.tracsystems.phonebridge

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object InCallStateHolder {
    @Volatile
    var currentCall: Call? = null

    @Volatile
    var currentState: Int = Call.STATE_NEW

    @Volatile
    private var inCallService: BridgeInCallService? = null

    fun bindService(service: BridgeInCallService) {
        inCallService = service
    }

    fun unbindService(service: BridgeInCallService) {
        if (inCallService == service) {
            inCallService = null
        }
    }

    fun hasLiveCall(): Boolean = currentCall != null && currentState != Call.STATE_DISCONNECTED

    fun hasAnyTrackedCall(): Boolean = inCallService?.hasTrackedCalls() == true

    fun disconnectAllCalls(): Boolean {
        val service = inCallService ?: return false
        return service.disconnectAllCalls()
    }

    fun setCallMuted(enabled: Boolean): Boolean {
        val service = inCallService ?: return false
        return service.applyCallMute(enabled)
    }

    fun setSpeakerRoute(enabled: Boolean): Boolean {
        val service = inCallService ?: return false
        return service.applySpeakerRoute(enabled)
    }
}

class BridgeInCallService : InCallService() {
    private val trackedCalls = mutableSetOf<Call>()
    @Volatile
    private var rootPrewarmInFlight = false
    @Volatile
    private var lastRootPrewarmAtMs = 0L
    @Volatile
    private var pendingCallMetadata: CallMetadata? = null

    private data class IncomingCallPolicy(
        val autoAnswer: Boolean,
        val autoAnswerDelayMs: Long,
        val allowAll: Boolean,
        val unknownAllowed: Boolean,
        val allowedNumbers: Set<String>,
        val disallowedNumbers: Set<String>,
    )

    private data class CallSessionPolicy(
        val maxDurationSec: Int,
        val maxDurationMessage: String,
        val sessionLogEnabled: Boolean,
        val gatewayReportEnabled: Boolean,
    )

    private data class CallMetadata(
        val callerNumber: String?,
        val direction: String,
        val startedAtMs: Long,
        val maxDurationSec: Int,
        val maxDurationMessage: String,
        val sessionLogEnabled: Boolean,
    )

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            InCallStateHolder.currentCall = call
            InCallStateHolder.currentState = state
            CommandAuditLog.add("call:state:${stateName(state)}")
            Log.i(TAG, "state=${stateName(state)}")
            if (state == Call.STATE_CONNECTING || state == Call.STATE_DIALING || state == Call.STATE_RINGING) {
                prewarmRootBridge()
            }
            when (state) {
                Call.STATE_ACTIVE -> {
                    InCallStateHolder.setSpeakerRoute(false)
                    val meta = pendingCallMetadata
                    GatewayCallAssistantService.start(
                        applicationContext,
                        callerNumber = meta?.callerNumber,
                        direction = meta?.direction,
                        maxDurationSec = meta?.maxDurationSec ?: 0,
                        maxDurationMessage = meta?.maxDurationMessage,
                        sessionLogEnabled = meta?.sessionLogEnabled ?: false,
                    )
                    if (GatewayCallAssistantService.enforceCallMute()) {
                        InCallStateHolder.setCallMuted(true)
                    }
                    if (ENABLE_ROUTE_REAPPLY_BURST) {
                        scheduleRouteReapplyBurst()
                    }
                    CommandAuditLog.add("call:voice_assistant:start")
                }
                Call.STATE_DISCONNECTED -> {
                    GatewayCallAssistantService.stop(applicationContext)
                    CommandAuditLog.add("call:voice_assistant:stop")
                }
            }
        }
    }

    fun applyCallMute(enabled: Boolean): Boolean {
        return runCatching {
            setMuted(enabled)
            Log.i(TAG, "call mute=$enabled")
            CommandAuditLog.add("call:mute:$enabled")
            true
        }.getOrElse { error ->
            Log.e(TAG, "failed to set call mute", error)
            CommandAuditLog.add("call:mute_error:${error.message}")
            false
        }
    }

    fun applySpeakerRoute(enabled: Boolean): Boolean {
        return runCatching {
            setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
            Log.i(TAG, "speaker route=$enabled")
            CommandAuditLog.add("call:speaker:$enabled")
            true
        }.getOrElse { error ->
            Log.e(TAG, "failed to set speaker route", error)
            CommandAuditLog.add("call:speaker_error:${error.message}")
            false
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        InCallStateHolder.bindService(this)

        val isIncoming = call.state == Call.STATE_RINGING

        // Priority check: if a call is already active, reject the new incoming
        if (isIncoming && trackedCalls.isNotEmpty()) {
            Log.i(TAG, "rejecting incoming call: existing call in progress")
            CommandAuditLog.add("call:incoming:reject:busy")
            call.reject(false, null)
            return
        }

        InCallStateHolder.currentCall = call
        InCallStateHolder.currentState = call.state
        call.registerCallback(callback)
        trackedCalls += call

        if (isIncoming) {
            handleIncomingCall(call)
        } else {
            // Outgoing call — load session policy for max duration + logging
            val sessionPolicy = loadCallSessionPolicy()
            pendingCallMetadata = CallMetadata(
                callerNumber = call.details?.handle?.schemeSpecificPart,
                direction = "outgoing",
                startedAtMs = System.currentTimeMillis(),
                maxDurationSec = sessionPolicy.maxDurationSec,
                maxDurationMessage = sessionPolicy.maxDurationMessage,
                sessionLogEnabled = sessionPolicy.sessionLogEnabled,
            )
            callback.onStateChanged(call, call.state)
        }
    }

    private fun handleIncomingCall(call: Call) {
        val callerNumber = call.details?.handle?.schemeSpecificPart
        val policy = loadIncomingCallPolicy()

        CommandAuditLog.add("call:incoming:from:${callerNumber ?: "unknown"}")
        Log.i(TAG, "incoming call from: $callerNumber, autoAnswer=${policy.autoAnswer}")

        if (!policy.autoAnswer) {
            CommandAuditLog.add("call:incoming:auto_answer_disabled")
            // Let it ring normally — callback handles state transitions
            val sessionPolicy = loadCallSessionPolicy()
            pendingCallMetadata = CallMetadata(
                callerNumber = callerNumber,
                direction = "incoming",
                startedAtMs = System.currentTimeMillis(),
                maxDurationSec = sessionPolicy.maxDurationSec,
                maxDurationMessage = sessionPolicy.maxDurationMessage,
                sessionLogEnabled = sessionPolicy.sessionLogEnabled,
            )
            callback.onStateChanged(call, call.state)
            return
        }

        if (!isNumberAllowed(policy, callerNumber)) {
            CommandAuditLog.add("call:incoming:reject:not_allowed:${callerNumber ?: "unknown"}")
            Log.i(TAG, "rejecting incoming call: number not allowed")
            call.reject(false, null)
            trackedCalls -= call
            runCatching { call.unregisterCallback(callback) }
            return
        }

        // Auto-answer after profile-configured delay
        CommandAuditLog.add("call:incoming:auto_answer:${callerNumber ?: "unknown"}")
        Log.i(TAG, "auto-answering incoming call from $callerNumber (delay=${policy.autoAnswerDelayMs}ms)")
        val delayMs = policy.autoAnswerDelayMs
        val sessionPolicy = loadCallSessionPolicy()
        pendingCallMetadata = CallMetadata(
            callerNumber = callerNumber,
            direction = "incoming",
            startedAtMs = System.currentTimeMillis(),
            maxDurationSec = sessionPolicy.maxDurationSec,
            maxDurationMessage = sessionPolicy.maxDurationMessage,
            sessionLogEnabled = sessionPolicy.sessionLogEnabled,
        )

        Thread({
            Thread.sleep(delayMs)
            runCatching {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }.onFailure { e ->
                Log.e(TAG, "auto-answer failed", e)
                CommandAuditLog.add("call:incoming:auto_answer_error:${e.message}")
            }
        }, "pb-auto-answer").start()

        // Trigger state callback for RINGING to prewarm root
        callback.onStateChanged(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callback) }
        trackedCalls -= call
        if (InCallStateHolder.currentCall == call) {
            val replacement = trackedCalls.firstOrNull()
            InCallStateHolder.currentCall = replacement
            InCallStateHolder.currentState = replacement?.state ?: Call.STATE_DISCONNECTED
        }
        if (trackedCalls.isEmpty()) {
            GatewayCallAssistantService.stop(applicationContext)
            InCallStateHolder.unbindService(this)
            CommandAuditLog.add("call:all_removed")
        }
    }

    @Synchronized
    fun hasTrackedCalls(): Boolean = trackedCalls.isNotEmpty()

    @Synchronized
    fun disconnectAllCalls(): Boolean {
        if (trackedCalls.isEmpty()) {
            return false
        }
        var disconnectedAny = false
        trackedCalls.toList().forEach { call ->
            runCatching {
                call.disconnect()
                disconnectedAny = true
            }.onFailure { error ->
                Log.w(TAG, "disconnectAllCalls failed", error)
            }
        }
        if (disconnectedAny) {
            CommandAuditLog.add("call:disconnect_all")
        }
        return disconnectedAny
    }

    override fun onDestroy() {
        trackedCalls.toList().forEach {
            runCatching { it.unregisterCallback(callback) }
        }
        trackedCalls.clear()
        InCallStateHolder.unbindService(this)
        super.onDestroy()
    }

    private fun stateName(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "new"
            Call.STATE_DIALING -> "dialing"
            Call.STATE_RINGING -> "ringing"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTING -> "disconnecting"
            Call.STATE_DISCONNECTED -> "disconnected"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "select_phone_account"
            Call.STATE_CONNECTING -> "connecting"
            Call.STATE_PULLING_CALL -> "pulling_call"
            Call.STATE_AUDIO_PROCESSING -> "audio_processing"
            Call.STATE_SIMULATED_RINGING -> "simulated_ringing"
            else -> "unknown:$state"
        }
    }

    private fun prewarmRootBridge() {
        val now = System.currentTimeMillis()
        if (rootPrewarmInFlight || now - lastRootPrewarmAtMs < ROOT_PREWARM_THROTTLE_MS) {
            return
        }
        rootPrewarmInFlight = true
        Thread({
            try {
                val probe = RootShellRuntime.ensureReady()
                if (probe.ok) {
                    CommandAuditLog.add("root:prewarm:call_state:ok")
                } else {
                    CommandAuditLog.add("root:prewarm:call_state:err")
                }
            } finally {
                lastRootPrewarmAtMs = System.currentTimeMillis()
                rootPrewarmInFlight = false
            }
        }, "pb-call-prewarm").start()
    }

    private fun scheduleRouteReapplyBurst() {
        Thread({
            runCatching {
                Thread.sleep(500)
                if (InCallStateHolder.hasLiveCall()) {
                    GatewayCallAssistantService.requestRouteReapply(applicationContext, "post_active_500ms")
                }
                Thread.sleep(1_500)
                if (InCallStateHolder.hasLiveCall()) {
                    GatewayCallAssistantService.requestRouteReapply(applicationContext, "post_active_2000ms")
                }
            }
        }, "pb-route-reapply-burst").start()
    }

    private fun fetchGatewayCallConfig(): JSONObject? {
        val profileFile = resolveProfileFile() ?: return null
        val root = runCatching { JSONObject(profileFile.readText()) }.getOrNull() ?: return null
        val gateway = root.optJSONObject("gateway") ?: return null
        val baseUrl = gateway.optString("base_url").ifBlank { return null }
        val bearer = gateway.optString("bearer").ifBlank { "" }

        return runCatching {
            val url = URL("$baseUrl/api/config/call")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                if (bearer.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearer")
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else {
                Log.w(TAG, "gateway call config fetch failed: $code")
                null
            }
        }.onFailure { e ->
            Log.w(TAG, "gateway call config fetch error", e)
        }.getOrNull()
    }

    private fun loadIncomingCallPolicy(): IncomingCallPolicy {
        val default = IncomingCallPolicy(
            autoAnswer = false, autoAnswerDelayMs = 500L,
            allowAll = true, unknownAllowed = true,
            allowedNumbers = emptySet(), disallowedNumbers = emptySet(),
        )

        val gw = fetchGatewayCallConfig()
        if (gw != null) {
            val allowlist = parseJsonStringArray(gw.optJSONArray("caller_allowlist"))
            val blocklist = parseJsonStringArray(gw.optJSONArray("caller_blocklist"))
            return IncomingCallPolicy(
                autoAnswer = gw.optBoolean("auto_answer", false),
                autoAnswerDelayMs = gw.optLong("auto_answer_delay_ms", 500L),
                allowAll = allowlist.isEmpty(),
                unknownAllowed = gw.optBoolean("unknown_callers_allowed", true),
                allowedNumbers = allowlist,
                disallowedNumbers = blocklist,
            )
        }

        // Fallback: reject call when gateway is unreachable (safe default)
        Log.w(TAG, "gateway unreachable — rejecting call (safe default)")
        return IncomingCallPolicy(
            autoAnswer = false, autoAnswerDelayMs = 0L,
            allowAll = false, unknownAllowed = false,
            allowedNumbers = emptySet(), disallowedNumbers = emptySet(),
        )
    }

    private fun loadCallSessionPolicy(): CallSessionPolicy {
        val defaultMsg = "I'm sorry, but we have reached the maximum call duration. Goodbye!"
        val default = CallSessionPolicy(
            maxDurationSec = 300, maxDurationMessage = defaultMsg,
            sessionLogEnabled = true, gatewayReportEnabled = false,
        )

        val gw = fetchGatewayCallConfig()
        if (gw != null) {
            return CallSessionPolicy(
                maxDurationSec = gw.optInt("max_duration_sec", 300),
                maxDurationMessage = gw.optString("max_duration_message", defaultMsg).ifBlank { defaultMsg },
                sessionLogEnabled = loadSessionLogFromProfile(),
                gatewayReportEnabled = loadGatewayReportFromProfile(),
            )
        }

        // Fallback to profile for session_log + gateway_report
        val profileFile = resolveProfileFile() ?: return default
        val root = runCatching { JSONObject(profileFile.readText()) }.getOrNull() ?: return default
        val session = root.optJSONObject("call_session") ?: return default

        return CallSessionPolicy(
            maxDurationSec = session.optInt("max_duration_sec", 300),
            maxDurationMessage = session.optString("max_duration_message", defaultMsg).ifBlank { defaultMsg },
            sessionLogEnabled = session.optBoolean("session_log", true),
            gatewayReportEnabled = session.optBoolean("gateway_report", false),
        )
    }

    private fun loadSessionLogFromProfile(): Boolean {
        val profileFile = resolveProfileFile() ?: return true
        val root = runCatching { JSONObject(profileFile.readText()) }.getOrNull() ?: return true
        val session = root.optJSONObject("call_session") ?: return true
        return session.optBoolean("session_log", true)
    }

    private fun loadGatewayReportFromProfile(): Boolean {
        val profileFile = resolveProfileFile() ?: return false
        val root = runCatching { JSONObject(profileFile.readText()) }.getOrNull() ?: return false
        val session = root.optJSONObject("call_session") ?: return false
        return session.optBoolean("gateway_report", false)
    }

    private fun parseJsonStringArray(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val result = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)?.trim()?.replace("[\\s\\-()]".toRegex(), "")
            if (!s.isNullOrBlank()) result += s
        }
        return result
    }

    private fun isNumberAllowed(policy: IncomingCallPolicy, callerNumber: String?): Boolean {
        val normalized = callerNumber?.trim()?.replace("[\\s\\-()]".toRegex(), "")
        if (normalized.isNullOrBlank()) return policy.unknownAllowed
        if (policy.disallowedNumbers.contains(normalized)) return false
        if (policy.allowAll) return true
        return policy.allowedNumbers.contains(normalized)
    }

    private fun resolveProfileFile(): File? {
        val candidates = listOf(
            File(filesDir, "profiles/profile.json"),
            File(filesDir, "profiles/active-profile.json"),
        )
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    private companion object {
        private const val TAG = "BridgeInCallService"
        private const val ROOT_PREWARM_THROTTLE_MS = 12_000L
        private const val ENABLE_ROUTE_REAPPLY_BURST = false
    }
}
