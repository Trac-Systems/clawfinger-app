package com.tracsystems.phonebridge

import android.content.Context
import android.util.Log

object CommandAuditLog {
    private val entries = mutableListOf<String>()
    @Volatile
    private var level: Level = Level.VERBOSE
    @Volatile
    private var transcriptEventsEnabled: Boolean = true
    @Volatile
    private var debugWavEventsEnabled: Boolean = true

    enum class Level {
        OFF,
        BASIC,
        NORMAL,
        VERBOSE,
        ;

        companion object {
            fun fromValue(raw: String?, default: Level = VERBOSE): Level {
                return when (raw?.trim()?.lowercase()) {
                    "off" -> OFF
                    "basic" -> BASIC
                    "normal" -> NORMAL
                    "verbose" -> VERBOSE
                    else -> default
                }
            }
        }
    }

    @Synchronized
    fun configure(
        level: Level? = null,
        transcriptEventsEnabled: Boolean? = null,
        debugWavEventsEnabled: Boolean? = null,
    ) {
        level?.let { this.level = it }
        transcriptEventsEnabled?.let { this.transcriptEventsEnabled = it }
        debugWavEventsEnabled?.let { this.debugWavEventsEnabled = it }
    }

    @Synchronized
    fun add(entry: String) {
        if (!shouldKeep(entry)) {
            return
        }
        entries += entry
        if (entries.size > 200) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    private fun shouldKeep(entry: String): Boolean {
        if (level == Level.OFF) {
            return false
        }
        if (!transcriptEventsEnabled && isTranscriptEntry(entry)) {
            return false
        }
        if (!debugWavEventsEnabled && entry.startsWith("voice_bridge:debug_wav:")) {
            return false
        }
        return when (level) {
            Level.OFF -> false
            Level.BASIC -> isBasicEvent(entry)
            Level.NORMAL -> !isVerboseOnlyEvent(entry)
            Level.VERBOSE -> true
        }
    }

    private fun isTranscriptEntry(entry: String): Boolean {
        return entry.startsWith("voice_bridge:asr:") ||
            entry.startsWith("voice_bridge:turn_asr:") ||
            entry.startsWith("voice_bridge:asr_cont:") ||
            entry.startsWith("voice_bridge:barge_in_asr:") ||
            entry.startsWith("voice_bridge:transcript_reject:") ||
            entry.startsWith("voice_bridge:transcript_reject_cont:")
    }

    private fun isBasicEvent(entry: String): Boolean {
        if (
            entry.contains(":error:") ||
            entry.contains(":fail") ||
            entry.contains(":timeout") ||
            entry.contains(":err")
        ) {
            return true
        }
        return entry.startsWith("call:state:") ||
            entry.startsWith("call:voice_assistant:") ||
            entry.startsWith("voice_bridge:start") ||
            entry.startsWith("voice_bridge:stop") ||
            entry.startsWith("voice_bridge:shift:") ||
            entry.startsWith("voice_bridge:profile:") ||
            entry.startsWith("voice_bridge:silence_timeout:") ||
            entry.startsWith("root:ready:") ||
            entry.startsWith("root:unavailable:")
    }

    private fun isVerboseOnlyEvent(entry: String): Boolean {
        return entry.startsWith("voice_bridge:root_stream_chunk_short:") ||
            entry.startsWith("voice_bridge:post_playback_prearm:") ||
            entry.startsWith("voice_bridge:activity:") ||
            entry.startsWith("voice_bridge:volume:") ||
            entry.startsWith("root:cmd:")
    }
}

class CallCommandExecutor(
    private val controller: TelecomController,
) {
    fun execute(context: Context, command: CallCommand): Result<Unit> {
        val result = when (command.type) {
            "dial" -> controller.dial(context, command.payload["number"]?.toString().orEmpty())
            "answer" -> controller.answer(context)
            "reject" -> controller.reject(context)
            "hangup" -> controller.hangup(context)
            "mute" -> controller.mute(context, command.payload["muted"] == true)
            "speaker" -> controller.speaker(context, command.payload["enabled"] == true)
            "bt_route" -> controller.bluetoothRoute(context, command.payload["enabled"] == true)
            else -> Result.failure(IllegalArgumentException("Unknown command: ${command.type}"))
        }
        val audit = "${command.id}:${command.type}:${if (result.isSuccess) "ok" else "err"}"
        CommandAuditLog.add(audit)
        result.exceptionOrNull()?.let { Log.e("CallCommandExecutor", "command failed", it) }
        return result
    }
}
