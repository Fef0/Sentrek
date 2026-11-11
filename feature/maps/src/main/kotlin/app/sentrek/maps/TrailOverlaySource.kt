package app.sentrek.maps

import app.sentrek.geo.overlay.OverlayLineSource
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.overlay.MapKind
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.overlay.OverpassExtras
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
    /**
     * Writes the details of [kind] for [box] to [out] as GeoJSON: the hiking overlay, or a
     * map's extras ([MapKind.extras]). [onBusy] says when it waits for a busy server, and when
     * it asks again.
     */
    suspend fun fetch(box: BoundingBox, out: File, kind: MapKind = MapKind.Hiking, onBusy: (Boolean) -> Unit = {})
}

/**
 * Public Overpass API instances, for development and beta only. Requests are serial
 * (see RegionRepository), identified by our User-Agent, and back off between rounds.
 *
 * Made for bad connections: without one it waits for it, and a round lost to the connection
 * dropping isn't counted. Busy servers get longer and longer pauses (and the one a server
 * asks for, when it says), so a slow afternoon on Overpass ends in trails, not in "failed".
 */
class OverpassTrailSource @Inject constructor(private val connectivity: Connectivity) : TrailOverlaySource {
    private val client by lazy {
        // Overpass computes the whole answer before sending, and public instances queue
        // requests: a dense alpine cell can take a couple of minutes. The timeout is between
        // bytes, so a slow connection still gets a big answer through.
        MapEngine.httpClient.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()
    }

    override suspend fun fetch(box: BoundingBox, out: File, kind: MapKind, onBusy: (Boolean) -> Unit) {
        var lastError: IOException? = null
        var failedRounds = 0
        while (true) {
            connectivity.awaitOnline()
            var wait = 0L
            for (endpoint in ENDPOINTS) {
                try {
                    runInterruptible(Dispatchers.IO) { request(endpoint, box, out, kind) }
                    return
                } catch (e: Rejected) {
                    throw e // The same question would get the same answer.
                } catch (e: IOException) {
                    lastError = e
                    if (e is Busy) wait = maxOf(wait, e.retryAfterMs)
                    if (!connectivity.online.value) break
                }
            }
            // The connection went, not the servers: once it's back, the round starts over.
            if (!connectivity.online.value) continue
            if (++failedRounds >= ROUNDS) break
            onBusy(true)
            delay(maxOf(wait, BACKOFF_MS shl (failedRounds - 1)).coerceAtMost(MAX_BACKOFF_MS))
            onBusy(false)
        }
        throw lastError ?: IOException("Overpass unavailable")
    }

    private fun request(endpoint: String, box: BoundingBox, out: File, kind: MapKind) {
        val request = Request.Builder()
            .url(endpoint)
            .post(FormBody.Builder().add("data", OverpassExtras.query(kind, box)).build())
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 400 -> throw Rejected("$endpoint: HTTP 400")
                // Too many requests, or a server that's busy or restarting.
                response.code == 429 || response.code == 503 || response.code == 504 -> {
                    val after = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0L
                    throw Busy("$endpoint: HTTP ${response.code}", after)
                }
                !response.isSuccessful -> throw IOException("$endpoint: HTTP ${response.code}")
            }
            // Written aside and moved into place whole: a download cut halfway leaves the
            // details the area had, never half a file.
            val tmp = File(out.path + ".tmp")
            try {
                tmp.bufferedWriter().use { writer ->
                    val input = response.body!!.charStream()
                    if (kind == MapKind.Hiking) OverpassOverlay.convert(input, writer) else OverpassExtras.convert(kind, input, writer)
                }
                if (!tmp.renameTo(out)) throw IOException("Cannot write ${out.name}")
            } finally {
                tmp.delete()
            }
        }
    }

    private class Busy(message: String, val retryAfterMs: Long) : IOException(message)

    private class Rejected(message: String) : IOException(message)

    private companion object {
        // FOSSGIS main instance: answers a cell in under a minute, or fails fast (502) when busy.
        // VK Maps: "no request limits, free to use in any project". Often answers 504 under
        // load, but fails fast, so it's tried before the one that can hang.
        // private.coffee: "No rate limit; notify us before large-scale use". It can hold a
        // request for minutes without answering, so it's only a fallback.
        // A server of the user's own takes the place of all three (see ServerRoutes).
        val ENDPOINTS = Server.Trails.defaults

        /** Rounds of all the servers, while online: about 8 minutes of pauses in all. */
        const val ROUNDS = 5
        const val BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrailSourceModule {
    @Binds
    abstract fun bindTrailSource(impl: OverpassTrailSource): TrailOverlaySource

    @Binds
    abstract fun bindPoiSource(impl: RegionRepository): OverlayPoiSource

    @Binds
    abstract fun bindLineSource(impl: RegionRepository): OverlayLineSource
}
