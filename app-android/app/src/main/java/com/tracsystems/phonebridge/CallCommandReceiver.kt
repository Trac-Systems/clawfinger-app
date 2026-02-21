package com.tracsystems.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Base64

class CallCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val command = parseCommand(intent)
            CallCommandExecutor(TelecomControllerRegistry.controller()).execute(context, command)
        }.onFailure { error ->
            Log.e(TAG, "failed to process call command", error)
            CommandAuditLog.add("receiver_error:${error.message}")
        }
    }

    private fun parseCommand(intent: Intent): CallCommand {
        val payloadBase64 = intent.getStringExtra(EXTRA_PAYLOAD_B64)
        if (!payloadBase64.isNullOrBlank()) {
            val decodedPayload = String(Base64.decode(payloadBase64, Base64.DEFAULT), Charsets.UTF_8)
            return CallCommandParser.parse(decodedPayload)
        }

        val rawPayload = intent.getStringExtra(EXTRA_PAYLOAD)
        if (!rawPayload.isNullOrBlank()) {
            return CallCommandParser.parse(rawPayload)
        }

        val type = intent.getStringExtra(EXTRA_TYPE)
        if (type.isNullOrBlank()) {
            throw IllegalArgumentException("received empty command payload")
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: "adb-${System.currentTimeMillis()}"
        val payload = mutableMapOf<String, Any?>()
        intent.extras?.keySet()?.forEach { key ->
            if (key !in RESERVED_KEYS) {
                payload[key] = intent.extras?.get(key)
            }
        }
        return CallCommand(id = id, type = type, payload = payload)
    }

    private companion object {
        private const val TAG = "CallCommandReceiver"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_PAYLOAD_B64 = "payload_b64"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "type"
        private val RESERVED_KEYS = setOf(
            EXTRA_PAYLOAD,
            EXTRA_PAYLOAD_B64,
            EXTRA_ID,
            EXTRA_TYPE,
        )
    }
}
