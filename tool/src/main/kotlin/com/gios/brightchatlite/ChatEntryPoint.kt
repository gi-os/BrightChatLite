package com.gios.brightchatlite

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Chat Lite takes no push notifications (BlueBubbles pushes over Firebase or its own
 * socket, neither of which a Light tool can receive), so both hooks are empty. The object
 * still has to exist for the SDK's KSP processor to generate a registry.
 */
@EntryPoint
object ChatEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
