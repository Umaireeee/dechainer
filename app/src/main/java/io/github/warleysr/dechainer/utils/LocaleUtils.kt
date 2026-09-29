package io.github.warleysr.dechainer.utils

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The app is English-only; this only clears a language picked in an older version. */
object LocaleUtils {

    fun hasExplicitLocale(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            !context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty
        } else {
            !AppCompatDelegate.getApplicationLocales().isEmpty
        }

    fun clearLocale(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }
}
