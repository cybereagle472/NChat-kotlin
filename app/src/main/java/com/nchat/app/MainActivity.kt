package com.nchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.ImageLoader
import coil.SingletonImageLoader
import com.nchat.app.data.ServiceLocator
import com.nchat.app.ui.NChatApp
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        configureMediaLoader()
        setContent {
            NChatApp()
        }
    }

    /**
     * Media is fetched through an OkHttp client that carries the same bearer token as the
     * API, so `/media/file/...` can be protected server-side without the UI knowing: the
     * picture bubbles call `AsyncImage` exactly as before.
     *
     * Today the reference worker serves media publicly and ignores the header (harmless);
     * a server built with private media simply answers 401 without it.
     */
    private fun configureMediaLoader() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val token = ServiceLocator.tokens.accessToken
                            val request = if (token.isNullOrEmpty()) {
                                chain.request()
                            } else {
                                chain.request().newBuilder()
                                    .header("authorization", "Bearer $token")
                                    .build()
                            }
                            chain.proceed(request)
                        }
                        .build()
                }
                .crossfade(150)
                .build()
        }
    }
}
