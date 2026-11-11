package app.sentrek.map

/**
 * The servers Sentrek gets its data from, each replaceable by the user's own (a self-hosted
 * OpenFreeMap, a terrain mirror, an Overpass instance). The app keeps asking for the default
 * addresses, and requests are pointed at the user's server on the way out ([ServerRoutes]):
 * downloaded areas, stored under the default addresses, stay valid.
 */
enum class Server(val defaults: List<String>, private val prefix: Boolean) {
    /** Vector map tiles and the fonts of the labels. */
    Tiles(listOf("https://tiles.openfreemap.org/"), prefix = true),

    /** Elevation tiles (Terrarium PNG): relief, contours, profiles. */
    Terrain(listOf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/"), prefix = true),

    /** Overpass API, for the trail details of downloaded areas; tried in turn. */
    Trails(
        listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        ),
        prefix = false,
    );

    /** [url] on [custom] instead, or null when it isn't one of this server's. */
    internal fun route(url: String, custom: String): String? {
        for (default in defaults) {
            if (prefix && url.startsWith(default)) return custom.trimEnd('/') + "/" + url.removePrefix(default)
            if (!prefix && url == default) return custom
        }
        return null
    }

    companion object {
        /** An address the user may set: https only (Android blocks plain http). */
        fun isValid(address: String): Boolean =
            address.startsWith("https://") && address.length > "https://".length && address.none { it.isWhitespace() }
    }
}

/** The user's servers, by [Server]; set from the settings, read by the HTTP client. */
object ServerRoutes {
    /** Set once the saved addresses are read, at start: until then requests wait (below). */
    private val known = java.util.concurrent.CountDownLatch(1)

    @Volatile
    var custom: Map<Server, String> = emptyMap()
        set(value) {
            field = value
            known.countDown()
        }

    /**
     * Called for each request, off the main thread. The first ones of a start wait briefly
     * for the saved addresses, so none reaches a server the user replaced; the app's start
     * itself doesn't wait for them.
     */
    fun rewrite(url: String): String {
        known.await(KNOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        for ((server, address) in custom) server.route(url, address)?.let { return it }
        return url
    }

    private const val KNOWN_WAIT_MS = 3_000L
}
