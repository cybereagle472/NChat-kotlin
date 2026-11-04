package com.nchat.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nchat.app.data.ServiceLocator

/**
 * Application entry point.
 *
 * The only job here is registering the app's Coil loader *before* the first composition,
 * so every `AsyncImage` sends the session token when it reads a private photo from
 * `/media/file/...` (see [com.nchat.app.network.MediaImages]). Coil asks this class for the
 * loader the first time it needs one, which is exactly the "set it once, everywhere" hook
 * the library documents.
 */
class NChatApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }

    override fun newImageLoader(): ImageLoader = ServiceLocator.images
}
