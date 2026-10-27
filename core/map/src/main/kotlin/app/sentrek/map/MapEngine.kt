package app.sentrek.map

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

object MapEngine {
    /**
     * OpenFreeMap requires offline downloads to use at most 4 parallel requests and
     * a unique User-Agent. Applying it globally keeps online browsing polite too.
     */
    private const val MAX_REQUESTS_PER_HOST = 4

    /** Call once from Application.onCreate. */
    fun init(context: Context, appVersion: String) {
        MapLibre.getInstance(context)
        val userAgent = "Sentrek/$appVersion (Android)"
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }
}
