package app.sentrek.maps

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.overlay.OverpassOverlay
import app.sentrek.map.MapEngine
import app.sentrek.map.Server
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Where the hiking overlay (trail difficulty + points) comes from. Swapped for our own static files before public release. */
interface TrailOverlaySource {
    /** Writes the overlay GeoJSON for [box] to [out]. */
    suspend fun fetch(box: BoundingBox, out: File)
}

/**
 * Public Overpass API instances, for development and beta only. Requests are serial
 * (see RegionRepository), identified by our User-Agent, and back off between rounds.
 */
class OverpassTrailSource @Inject constructor() : TrailOverlaySource {
    private val client by lazy {
        // Overpass computes the whole answer before sending, and public instances queue
        // requests: a dense alpine cell can take a couple of minutes.
        MapEngine.httpClient.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()
    }

    override suspend fun fetch(box: BoundingBox, out: File) {
        var lastError: IOException? = null
        repeat(ROUNDS) { round ->
            for (endpoint in ENDPOINTS) {
                try {
                    runInterruptible(Dispatchers.IO) { request(endpoint, box, out) }
                    return
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (round < ROUNDS - 1) delay(BACKOFF_MS)
        }
        throw lastError ?: IOException("Overpass unavailable")
    }

    private fun request(endpoint: String, box: BoundingBox, out: File) {
        val request = Request.Builder()
            .url(endpoint)
            .post(FormBody.Builder().add("data", OverpassOverlay.query(box)).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$endpoint: HTTP ${response.code}")
            val tmp = File(out.path + ".tmp")
            tmp.bufferedWriter().use { writer -> OverpassOverlay.convert(response.body!!.charStream(), writer) }
            if (!tmp.renameTo(out)) throw IOException("Cannot write ${out.name}")
        }
    }

    private companion object {
        // FOSSGIS main instance: answers a cell in under a minute, or fails fast (502) when busy.
        // VK Maps: "no request limits, free to use in any project". Often answers 504 under
        // load, but fails fast, so it's tried before the one that can hang.
        // private.coffee: "No rate limit; notify us before large-scale use". It can hold a
        // request for minutes without answering, so it's only a fallback.
        // A server of the user's own takes the place of all three (see ServerRoutes).
        val ENDPOINTS = Server.Trails.defaults
        const val ROUNDS = 3
        const val BACKOFF_MS = 30_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrailSourceModule {
    @Binds
    abstract fun bindTrailSource(impl: OverpassTrailSource): TrailOverlaySource

    @Binds
    abstract fun bindPoiSource(impl: RegionRepository): OverlayPoiSource
}
