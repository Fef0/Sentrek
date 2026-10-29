package app.sentrek.map

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

object MapEngine {
    /**
     * OpenFreeMap requires offline downloads to use at most 4 parallel requests and
     * a unique User-Agent. Applying it globally keeps online browsing polite too.
     */
    private const val MAX_REQUESTS_PER_HOST = 4

    /** Shared HTTP client: same politeness rules for map tiles and other data sources. */
    lateinit var httpClient: OkHttpClient
        private set

    /** Call once from Application.onCreate. */
    fun init(context: Context, appVersion: String) {
        MapLibre.getInstance(context)
        val userAgent = "Sentrek/$appVersion (Android)"
        httpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .addInterceptor(ServerInterceptor)
            .addInterceptor(BundledAssetInterceptor(context.applicationContext))
            .addInterceptor(MapSlopes.Tiles)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
        HttpRequestUtil.setOkHttpClient(httpClient)
    }
}

/** Sends requests for a default server to the user's own, when one is set (see [Server]). */
private object ServerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val routed = ServerRoutes.rewrite(url)
        if (routed == url) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(routed).build())
    }
}

/** Serves `https://[MapStyle.BUNDLED_HOST]/<path>` from the APK assets (see [MapStyle.offlineUrl]). */
private class BundledAssetInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != MapStyle.BUNDLED_HOST) return chain.proceed(request)

        val path = request.url.encodedPath.removePrefix("/")
        val bytes = runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (bytes != null) 200 else 404)
            .message(if (bytes != null) "OK" else "Not Found")
            .body((bytes ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
            .build()
    }
}
