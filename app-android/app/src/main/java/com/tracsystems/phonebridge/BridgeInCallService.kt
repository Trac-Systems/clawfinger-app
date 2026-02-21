package com.tracsystems.phonebridge

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

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
                    GatewayCallAssistantService.start(applicationContext)
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
        InCallStateHolder.currentCall = call
        InCallStateHolder.currentState = call.state
        call.registerCallback(callback)
        trackedCalls += call
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

    private companion object {
        private const val TAG = "BridgeInCallService"
        private const val ROOT_PREWARM_THROTTLE_MS = 12_000L
        private const val ENABLE_ROUTE_REAPPLY_BURST = false
    }
}
