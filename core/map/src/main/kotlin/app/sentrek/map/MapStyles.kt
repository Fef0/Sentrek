package app.sentrek.map

/** Map styles bundled with the app, so a map renders offline from the first launch. */
enum class MapStyle(private val asset: String) {
    Hiking("styles/hiking.json");

    /** Used to display the map: read straight from the APK. */
    val uri: String get() = "asset://$asset"

    /**
     * Used for offline region definitions. MapLibre's downloader only fetches http(s) URLs, so
     * this points at a reserved `.invalid` host that [MapEngine]'s HTTP client answers from the
     * APK assets, without touching the network.
     */
    val offlineUrl: String get() = "https://$BUNDLED_HOST/$asset"

    companion object {
        const val BUNDLED_HOST = "bundled.sentrek.invalid"
    }
}
