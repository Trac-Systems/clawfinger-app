package com.tracsystems.phonebridge

import android.content.Context

interface TelecomController {
    fun dial(context: Context, number: String): Result<Unit>
    fun answer(context: Context): Result<Unit>
    fun reject(context: Context): Result<Unit>
    fun hangup(context: Context): Result<Unit>
    fun mute(context: Context, enabled: Boolean): Result<Unit>
    fun speaker(context: Context, enabled: Boolean): Result<Unit>
    fun bluetoothRoute(context: Context, enabled: Boolean): Result<Unit>
}

object TelecomControllerRegistry {
    @Volatile
    var controllerFactory: () -> TelecomController = { AndroidTelecomController() }

    fun controller(): TelecomController = controllerFactory()
}
