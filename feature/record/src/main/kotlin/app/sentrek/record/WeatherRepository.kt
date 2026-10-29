package app.sentrek.record

import android.content.Context
import android.util.Log
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.weather.Forecast
import app.sentrek.map.MapEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A forecast and when it was fetched; [offline] when the network failed and it is the saved copy. */
data class Weather(val forecast: Forecast, val fetchedAt: Long, val offline: Boolean = false)

sealed interface WeatherResult {
    data class Shown(val weather: Weather) : WeatherResult

    /** No connection, and no forecast saved for here. */
    data object Offline : WeatherResult

    /** The service answered, but without a forecast. */
    data object Unavailable : WeatherResult
}

/**
 * Forecasts from Open-Meteo, only when the user asks for one. Each one is kept in the app's
 * files, so a forecast looked up at home is still there on the mountain without signal.
 */
@Singleton
class WeatherRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir by lazy { File(context.filesDir, "weather").apply { mkdirs() } }

    /** The forecast at [at] (for its height too, when known): fresh if possible, else the saved one. */
    suspend fun forecast(at: LatLon): WeatherResult = withContext(Dispatchers.IO) {
        val url = Forecast.url(at.lat, at.lon, at.ele)
        val file = File(dir, name(at))
        val offline = try {
            fetch(url, file)?.let { return@withContext WeatherResult.Shown(it) }
            false
        } catch (e: IOException) {
            Log.d(TAG, "No forecast: ${e.message}")
            true
        }
        // Offline, a forecast saved for somewhere close by (a summit up the same trail) still helps.
        val saved = (file.takeIf { it.exists() } ?: if (offline) nearest(at) else null)
            ?.let { f -> runCatching { f.reader().use(Forecast::parse) }.getOrNull()?.let { Weather(it, f.lastModified(), offline) } }
        when {
            saved != null -> WeatherResult.Shown(saved)
            offline -> WeatherResult.Offline
            else -> WeatherResult.Unavailable
        }
    }

    /** The forecast from the network, saved to [file]; null when the service had none. */
    private fun fetch(url: String, file: File): Weather? {
        val body = MapEngine.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val forecast = Forecast.parse(body.reader()) ?: return null
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(body)
        tmp.renameTo(file)
        forgetOld()
        return Weather(forecast, file.lastModified())
    }

    /** "37.74_14.94_1880.json": where it is for, as rounded in the request. */
    private fun name(at: LatLon) =
        String.format(Locale.ROOT, "%.2f_%.2f_%.0f.json", at.lat, at.lon, at.ele?.let { Math.round(it / 10.0) * 10.0 } ?: -1.0)

    private fun nearest(at: LatLon): File? = dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
        val (lat, lon) = f.name.split('_').takeIf { it.size == 3 }?.let { it[0].toDoubleOrNull() to it[1].toDoubleOrNull() } ?: return@mapNotNull null
        if (lat == null || lon == null) return@mapNotNull null
        f to Geo.distance(at, LatLon(lat, lon))
    }?.filter { it.second <= NEAR_M }?.minByOrNull { it.second }?.first

    /** Saved forecasts past their last day are of no use. */
    private fun forgetOld() {
        val before = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < before }?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "WeatherRepository"
        const val NEAR_M = 5_000.0
        const val KEEP_MS = (Forecast.DAYS + 1) * 24 * 60 * 60 * 1000L
    }
}
