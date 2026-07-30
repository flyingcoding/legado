package io.legado.app.api

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.legado.app.R
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity

object ShortCuts {

    private const val SHORTCUT_BOOKSHELF = "bookshelf"
    private const val SHORTCUT_LAST_READ = "lastRead"
    private const val SHORTCUT_READ_ALOUD = "readAloud"
    private val shortcutIds = setOf(SHORTCUT_BOOKSHELF, SHORTCUT_LAST_READ, SHORTCUT_READ_ALOUD)

    private inline fun <reified T> buildIntent(
        context: Context,
        shortcutId: String? = null
    ): Intent {
        val intent = Intent(context, T::class.java)
        intent.action = Intent.ACTION_VIEW
        shortcutId?.let {
            intent.putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, it)
        }
        return intent
    }

    private fun buildBookShelfShortCutInfo(context: Context): ShortcutInfoCompat {
        val bookShelfIntent = buildIntent<MainActivity>(context, SHORTCUT_BOOKSHELF)
        return ShortcutInfoCompat.Builder(context, SHORTCUT_BOOKSHELF)
            .setShortLabel(context.getString(R.string.bookshelf))
            .setLongLabel(context.getString(R.string.bookshelf))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntent(bookShelfIntent)
            .build()
    }

    private fun buildReadBookShortCutInfo(context: Context): ShortcutInfoCompat {
        val bookShelfIntent = buildIntent<MainActivity>(context)
        val readBookIntent = buildIntent<ReadBookActivity>(context, SHORTCUT_LAST_READ)
        return ShortcutInfoCompat.Builder(context, SHORTCUT_LAST_READ)
            .setShortLabel(context.getString(R.string.last_read))
            .setLongLabel(context.getString(R.string.last_read))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntents(arrayOf(bookShelfIntent, readBookIntent))
            .build()
    }

    private fun buildReadAloudShortCutInfo(context: Context): ShortcutInfoCompat {
        val readAloudIntent = buildIntent<SharedReceiverActivity>(context, SHORTCUT_READ_ALOUD)
        readAloudIntent.putExtra("action", "readAloud")
        return ShortcutInfoCompat.Builder(context, SHORTCUT_READ_ALOUD)
            .setShortLabel(context.getString(R.string.read_aloud))
            .setLongLabel(context.getString(R.string.read_aloud))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntent(readAloudIntent)
            .build()
    }

    fun buildShortCuts(context: Context) {
        ShortcutManagerCompat.setDynamicShortcuts(
            context, listOf(
                buildReadBookShortCutInfo(context),
                buildReadAloudShortCutInfo(context),
                buildBookShelfShortCutInfo(context)
            )
        )
    }

    /**
     * 在快捷方式目标功能真实打开时上报一次使用记录。
     */
    fun reportUsage(context: Context, intent: Intent) {
        val shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID) ?: return
        if (shortcutId !in shortcutIds) return
        intent.removeExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
    }

}
