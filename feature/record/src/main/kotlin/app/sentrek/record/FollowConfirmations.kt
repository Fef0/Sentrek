package app.sentrek.record

import android.content.Context
import app.sentrek.feature.record.R
import app.sentrek.ui.Confirmation

/** "Are you sure?" for the follow actions a stray tap could trigger mid-hike. */
object FollowConfirmations {
    fun stop(context: Context, name: String, stop: () -> Unit) = Confirmation(
        title = context.getString(R.string.confirm_stop_following_title),
        text = context.getString(R.string.confirm_stop_following_text, name),
        confirmLabel = context.getString(R.string.confirm_stop_following),
        onConfirm = stop,
    )

    fun switch(context: Context, current: String, next: String, follow: () -> Unit) = Confirmation(
        title = context.getString(R.string.confirm_switch_title),
        text = context.getString(R.string.confirm_switch_text, current, next),
        confirmLabel = context.getString(R.string.confirm_switch),
        onConfirm = follow,
    )

    fun hideFollowed(context: Context, name: String, hide: () -> Unit) = Confirmation(
        title = context.getString(R.string.confirm_hide_followed_title),
        text = context.getString(R.string.confirm_hide_followed_text, name),
        confirmLabel = context.getString(R.string.confirm_hide),
        onConfirm = hide,
    )
}
