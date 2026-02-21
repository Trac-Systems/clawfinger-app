package com.tracsystems.phonebridge

import org.json.JSONObject

data class CallCommand(
    val id: String,
    val type: String,
    val payload: Map<String, Any?>,
)

object CallCommandParser {
    fun parse(raw: String): CallCommand {
        val json = JSONObject(raw)
        val payloadJson = json.optJSONObject("payload") ?: JSONObject()
        val payload = mutableMapOf<String, Any?>()
        payloadJson.keys().forEach { key ->
            payload[key] = payloadJson.opt(key)
        }
        return CallCommand(
            id = json.optString("id", ""),
            type = json.optString("type", ""),
            payload = payload,
        )
    }
}
