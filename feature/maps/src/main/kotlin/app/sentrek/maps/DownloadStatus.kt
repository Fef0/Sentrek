package app.sentrek.maps

import app.sentrek.geo.overlay.MapKind

/**
 * Everything downloading now, said once for the Maps screen, the main map and the
 * notification alike: how many areas were asked for together, how far along the whole lot is,
 * what is being fetched this moment, and what it waits for, if anything.
 */
data class DownloadStatus(
    /** Areas in this batch: those asked for since nothing was downloading. */
    val areas: Int,
    /** Of those, the ones with everything on the phone (or given up on). */
    val done: Int,
    /** The whole batch, 0 to 1. */
    val fraction: Float,
    val step: Step,
    /** How many bytes of the map being downloaded are in so far. */
    val mapBytes: Long = 0,
    val waiting: Waiting? = null,
) {
    enum class Step { Map, Trails, Pistes, Cycling, Queued }

    enum class Waiting {
        /** No connection: it carries on by itself once there is one. */
        Network,
        /** The trail server is busy: asked again shortly. */
        Server,
    }

    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 99)
}

/**
 * How a batch ended: [done] areas downloaded whole, and [failed] that couldn't get all they
 * needed (the trail server didn't answer): new ones removed, the others left as they were.
 */
data class DownloadOutcome(val done: Int, val failed: Int)

/** Map tiles or trail details still to download. */
internal val RegionState.isPending: Boolean
    get() = map !is MapDownload.Ready || details.any { it.inProgress }

/**
 * The map counts for most of an area: it's most of the bytes and of the time on a good day.
 * Each detail file counts for one part.
 */
private const val MAP_PARTS = 4f

/** How far along this area is, 0 to 1: the same parts as the whole batch's. */
internal fun RegionState.progress(): Float {
    val map = when (val m = map) {
        MapDownload.Queued -> 0f
        is MapDownload.Downloading -> m.fraction.coerceIn(0f, 1f)
        is MapDownload.Ready -> 1f
    }
    val details = details.count { it == TrailsDownload.Ready || it == TrailsDownload.Failed }
    return (map * MAP_PARTS + details) / (MAP_PARTS + this.details.size)
}

/**
 * The status of [batch] (states of the areas in it, and [failed] more removed from it), or null
 * when none of them is pending.
 */
fun downloadStatus(batch: Collection<RegionState>, online: Boolean, failed: Int = 0): DownloadStatus? {
    if (batch.none { it.isPending }) return null
    // A removed area is over too: it counts as done for the bar, so the bar doesn't go back.
    val fraction = (batch.sumOf { it.progress().toDouble() }.toFloat() + failed) / (batch.size + failed)
    val done = batch.count { !it.isPending } + failed
    val mapActive = batch.firstNotNullOfOrNull { it.map as? MapDownload.Downloading }
    val detailActive = batch.firstNotNullOfOrNull { s ->
        when {
            s.trails == TrailsDownload.Downloading || s.trails == TrailsDownload.Retrying -> MapKind.Hiking
            else -> s.extras.entries.firstOrNull { it.value == TrailsDownload.Downloading || it.value == TrailsDownload.Retrying }?.key
        }
    }
    val step = when {
        mapActive != null -> DownloadStatus.Step.Map
        detailActive == MapKind.Hiking -> DownloadStatus.Step.Trails
        detailActive == MapKind.Winter -> DownloadStatus.Step.Pistes
        detailActive == MapKind.Cycling -> DownloadStatus.Step.Cycling
        else -> DownloadStatus.Step.Queued
    }
    val waiting = when {
        !online -> DownloadStatus.Waiting.Network
        batch.any { TrailsDownload.Retrying in it.details } && mapActive == null -> DownloadStatus.Waiting.Server
        else -> null
    }
    return DownloadStatus(
        areas = batch.size + failed,
        done = done,
        fraction = fraction,
        step = step,
        mapBytes = mapActive?.bytes ?: 0,
        waiting = waiting,
    )
}
