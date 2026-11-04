package com.nchat.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.ServiceLocator

/**
 * Application entry point.
 *
 * Two jobs, both of which must be done before the first frame:
 * 1. install the crash recorder, so "the app keeps stopping" comes with a reason
 *    (see [CrashLog]);
 * 2. hand Coil the app's loader, so every `AsyncImage` sends the session token when it
 *    reads a private photo from `/media/file/...` (see [com.nchat.app.network.MediaImages]).
 *
 * The loader is built defensively: a failure to construct it must never stop the app from
 * starting — pictures are not worth a process death — so it falls back to a plain loader and
 * records what went wrong.
 */
class NChatApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ServiceLocator.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        runCatching { ServiceLocator.images }.getOrElse { error ->
            CrashLog.note("image loader failed to build — using Coil's default", error)
            ImageLoader.Builder(this).build()
        }
}
