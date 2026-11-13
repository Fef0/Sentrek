package app.sentrek.update

import androidx.core.content.FileProvider

/** Hands the downloaded update to Android's installer; its own class, beside the tracks' provider. */
class UpdateFileProvider : FileProvider()
