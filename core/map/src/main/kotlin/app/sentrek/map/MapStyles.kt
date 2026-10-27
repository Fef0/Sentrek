package app.sentrek.map

/** Map styles bundled with the app, so a map renders offline from the first launch. */
enum class MapStyle(val uri: String) {
    Hiking("asset://styles/hiking.json"),
}
