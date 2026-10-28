package app.sentrek.data

/** Text size in the app, on top of the phone's own font size (Android settings). */
enum class TextSize(val scale: Float) {
    Small(0.9f),
    Default(1f),
    Large(1.15f),
    Largest(1.3f),
}

/** How big buttons are without gloves; glove mode makes them the biggest whatever this says. */
enum class ButtonSize {
    Standard,
    Large,
    Largest,
}
