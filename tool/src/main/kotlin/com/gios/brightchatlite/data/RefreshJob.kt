package com.gios.brightchatlite.data

import com.gios.brightchatlite.api.ApiException
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

const val REFRESH_JOB_KEY = "refresh-cache"

/**
 * Periodic background refresh of the conversation and message cache, so the list is current
 * when the tool opens. Scheduled every 15 minutes (LightWork's minimum) from the list screen.
 * A transient network failure asks for a retry; a wrong password does not, since retrying
 * cannot fix it.
 */
@LightJob(REFRESH_JOB_KEY)
val refreshCacheJob: LightJobHandler = { ctx, _ ->
    val repo = ChatRepository.get(ctx)
    if (repo.config() == null) {
        LightJobResult.Success(emptyMap(), null, "not configured")
    } else {
        val result = repo.refreshConversations()
        val error = result.exceptionOrNull()
        when {
            error == null -> LightJobResult.Success(emptyMap(), null, "refreshed")
            error is ApiException && error.isAuthError -> LightJobResult.Error("wrong password")
            else -> LightJobResult.Retry
        }
    }
}
