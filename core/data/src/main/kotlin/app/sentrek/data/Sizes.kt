package app.sentrek.data

/** Text size in the app, on top of the phone's own font size (Android settings). */
enum class TextSize(val scale: Float) {
    Small(0.9f),
    Default(1f),
    Large(1.15f),
    Largest(1.3f),
}

/** How big buttons are; [Gloves] is the biggest, with long presses on the map that reach further too. */
enum class ButtonSize {
    Standard,
    Large,
    Gloves,
}
