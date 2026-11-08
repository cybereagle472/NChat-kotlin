package com.nchat.app.data.repository

import com.nchat.app.network.ApiException
import java.io.IOException

/** A shared policy for live sends and outbox retries. */
internal fun isTransientSendFailure(error: Throwable?): Boolean = when (error) {
    null -> true
    is ApiException -> error.status >= 500 || error.status == 408 || error.status == 429
    is IOException -> true
    else -> false
}
