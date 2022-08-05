package de.mm20.launcher2.ui.launcher.search.shortcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import de.mm20.launcher2.appshortcuts.AppShortcutRepository
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.search.data.AppShortcut
import de.mm20.launcher2.ui.launcher.search.common.SearchableItemVM
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ShortcutItemVM(private val shortcut: AppShortcut) : SearchableItemVM(shortcut), KoinComponent {

    private val shortcutRepository: AppShortcutRepository by inject()

    val canDelete = shortcut.launcherShortcut.isPinned

    fun openAppInfo(context: Context) {
        context.tryStartActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${shortcut.launcherShortcut.`package`}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun deleteShortcut() {
        shortcutRepository.removePinnedShortcut(shortcut)
    }
}