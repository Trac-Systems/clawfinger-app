package com.tracsystems.phonebridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat

class AndroidTelecomController : TelecomController {
    @SuppressLint("MissingPermission")
    override fun dial(context: Context, number: String): Result<Unit> = runCatching {
        prewarmRootBridge("dial")
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            error("CALL_PHONE permission missing")
        }
        val telecomManager = context.getSystemService(TelecomManager::class.java)
            ?: error("Telecom service unavailable")
        val systemInCall = if (hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
            telecomManager.isInCall
        } else {
            false
        }
        if (systemInCall || InCallStateHolder.hasLiveCall() || InCallStateHolder.hasAnyTrackedCall()) {
            error("Call already in progress")
        }
        telecomManager.placeCall(Uri.fromParts("tel", number, null), null)
        Log.i(TAG, "Dial command executed: $number")
    }

    override fun answer(context: Context): Result<Unit> = runCatching {
        InCallStateHolder.currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
            ?: error("No active incoming call available to answer")
        Log.i(TAG, "Answer command executed")
    }

    override fun reject(context: Context): Result<Unit> = runCatching {
        InCallStateHolder.currentCall?.reject(false, null)
            ?: error("No active incoming call available to reject")
        Log.i(TAG, "Reject command executed")
    }

    override fun hangup(context: Context): Result<Unit> = runCatching {
        val disconnectedAll = InCallStateHolder.disconnectAllCalls()
        if (!disconnectedAll) {
            InCallStateHolder.currentCall?.disconnect()
                ?: error("No active call available to hang up")
        }
        Log.i(TAG, "Hangup command executed")
    }

    override fun mute(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val callMuted = InCallStateHolder.setCallMuted(enabled)
        if (!callMuted) {
            Log.w(TAG, "Mute command requested without active in-call service")
        }
        Log.i(TAG, "Mute command executed: $enabled (callMuted=$callMuted)")
    }

    override fun speaker(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val callRouteSet = InCallStateHolder.setSpeakerRoute(enabled)
        if (!callRouteSet) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
        Log.i(TAG, "Speaker command executed: $enabled (callRouteSet=$callRouteSet)")
    }

    override fun bluetoothRoute(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (enabled) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
        Log.i(TAG, "Bluetooth route command executed: $enabled")
    }

    private fun prewarmRootBridge(trigger: String) {
        Thread({
            val probe = RootShellRuntime.ensureReady()
            if (probe.ok) {
                CommandAuditLog.add("root:prewarm:$trigger:ok")
            } else {
                CommandAuditLog.add("root:prewarm:$trigger:err")
            }
        }, "pb-prewarm-$trigger").start()
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        private const val TAG = "AndroidTelecomCtrl"
    }
}
