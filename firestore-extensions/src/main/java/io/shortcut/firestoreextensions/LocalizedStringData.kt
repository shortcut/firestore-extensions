package io.shortcut.firestoreextensions

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.os.LocaleListCompat
import java.util.Locale

data class LocalizedStringData(
    val entries: List<LocalizedStringEntry>
) {
    @RequiresApi(Build.VERSION_CODES.N)
    fun get(context: Context): String? {
        val deviceLocales = context.resources.configuration.locales
        val supportedEntries = mutableListOf<LocalizedStringEntry>()

        for (i in 0 until deviceLocales.size()) {
            val devLocale = deviceLocales.get(i)
            entries.forEach { e ->
                val localeMatch = e.getMatchingLocales().any {
                    val languageTagMatch = it.toLanguageTag().equals(devLocale.toLanguageTag(), true)
                    val localeCountryMatch = it.country.equals(devLocale.country, true)
                    val localeEntryCountryMatch = devLocale.country.equals(e.language, true)
                    val localeLanguageMatch = it.language.equals(devLocale.language, true)
                    val localeEntryLanguageMatch = devLocale.language.equals(e.language, true)
                    languageTagMatch || localeCountryMatch || localeEntryCountryMatch || localeLanguageMatch || localeEntryLanguageMatch
                }
                if (localeMatch) {
                    supportedEntries.add(e)
                }
            }
        }

        val noEntry = entries.firstOrNull { it.language.equals("no", true) }?.value
        val enEntry = entries.firstOrNull { it.language.equals("en", true) }?.value

        return supportedEntries.firstOrNull()?.value ?: noEntry ?: enEntry
    }
}

data class LocalizedStringEntry(
    val language: String,
    val value: String
) {
    fun getMatchingLocaleList(): LocaleListCompat = LocaleListCompat.forLanguageTags(language)
    fun getMatchingLocales(): List<Locale> = getMatchingLocaleList().let { localeList ->
        val entryLocales = mutableListOf<Locale>()
        for (j in 0 until localeList.size()) {
            val entryLocale = localeList.get(j)
            entryLocale?.let { entryLocales.add(it) }
        }

        return entryLocales.toList()
    }
}