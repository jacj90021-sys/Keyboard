package com.example.keyboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single DataStore instance per process. */
val Context.keyboardDataStore: DataStore<Preferences> by preferencesDataStore(name = "keyboard_prefs")

object PrefKeys {
    val theme = stringPreferencesKey("theme")
    val keyHeight = intPreferencesKey("keyHeight")
    val numRow = booleanPreferencesKey("numRow")
    val toolbar = booleanPreferencesKey("toolbar")
    val suggestions = booleanPreferencesKey("suggestions")
    val arrow = booleanPreferencesKey("arrow")
    val startOpen = booleanPreferencesKey("startOpen")
    val special = booleanPreferencesKey("special")
    val fn = booleanPreferencesKey("fn")
    val popups = booleanPreferencesKey("popups")
    val autoCorrect = booleanPreferencesKey("autoCorrect")
    val swipeTyping = booleanPreferencesKey("swipeTyping")
    val sound = booleanPreferencesKey("sound")
    val haptics = booleanPreferencesKey("haptics")
    val onboardingDone = booleanPreferencesKey("onboardingDone")
}

/**
 * The single settings model of the keyboard. Every field maps 1:1 to a DataStore key.
 * The IME observes this flow so changes apply live. We never store what the user types.
 */
data class KeyboardPrefs(
    val theme: ThemeChoice = ThemeChoice.PAPER,
    val keyHeight: Int = 44,
    val numRow: Boolean = true,
    val toolbar: Boolean = true,
    val suggestions: Boolean = true,
    val arrow: Boolean = true,
    val startOpen: Boolean = true,
    val special: Boolean = false,
    val fn: Boolean = false,
    val popups: Boolean = true,
    val autoCorrect: Boolean = true,
    val swipeTyping: Boolean = true,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val onboardingDone: Boolean = false
) {
    val showChevron: Boolean get() = toolbar && suggestions && arrow
}

class KeyboardPrefsRepository(private val context: Context) {

    val prefs: Flow<KeyboardPrefs> = context.keyboardDataStore.data.map { p ->
        KeyboardPrefs(
            theme = ThemeChoice.fromKey(p[PrefKeys.theme]),
            keyHeight = p[PrefKeys.keyHeight] ?: 44,
            numRow = p[PrefKeys.numRow] ?: true,
            toolbar = p[PrefKeys.toolbar] ?: true,
            suggestions = p[PrefKeys.suggestions] ?: true,
            arrow = p[PrefKeys.arrow] ?: true,
            startOpen = p[PrefKeys.startOpen] ?: true,
            special = p[PrefKeys.special] ?: false,
            fn = p[PrefKeys.fn] ?: false,
            popups = p[PrefKeys.popups] ?: true,
            autoCorrect = p[PrefKeys.autoCorrect] ?: true,
            swipeTyping = p[PrefKeys.swipeTyping] ?: true,
            sound = p[PrefKeys.sound] ?: true,
            haptics = p[PrefKeys.haptics] ?: true,
            onboardingDone = p[PrefKeys.onboardingDone] ?: false
        )
    }

    suspend fun setTheme(choice: ThemeChoice) {
        context.keyboardDataStore.edit { it[PrefKeys.theme] = choice.prefKey }
    }

    suspend fun setKeyHeight(dp: Int) {
        context.keyboardDataStore.edit { it[PrefKeys.keyHeight] = dp }
    }

    suspend fun setBool(key: String, value: Boolean) {
        context.keyboardDataStore.edit { prefs ->
            when (key) {
                "numRow" -> prefs[PrefKeys.numRow] = value
                "toolbar" -> prefs[PrefKeys.toolbar] = value
                "suggestions" -> prefs[PrefKeys.suggestions] = value
                "arrow" -> prefs[PrefKeys.arrow] = value
                "startOpen" -> prefs[PrefKeys.startOpen] = value
                "special" -> prefs[PrefKeys.special] = value
                "fn" -> prefs[PrefKeys.fn] = value
                "popups" -> prefs[PrefKeys.popups] = value
                "autoCorrect" -> prefs[PrefKeys.autoCorrect] = value
                "swipeTyping" -> prefs[PrefKeys.swipeTyping] = value
                "sound" -> prefs[PrefKeys.sound] = value
                "haptics" -> prefs[PrefKeys.haptics] = value
                "onboardingDone" -> prefs[PrefKeys.onboardingDone] = value
            }
        }
    }

    suspend fun finishOnboarding() {
        context.keyboardDataStore.edit { it[PrefKeys.onboardingDone] = true }
    }

    suspend fun setTopBar(
        suggestions: Boolean,
        toolbar: Boolean,
        arrow: Boolean,
        startOpen: Boolean
    ) {
        context.keyboardDataStore.edit { p ->
            p[PrefKeys.suggestions] = suggestions
            p[PrefKeys.toolbar] = toolbar
            p[PrefKeys.arrow] = arrow
            p[PrefKeys.startOpen] = startOpen
        }
    }
}
