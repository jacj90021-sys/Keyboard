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
    val swipeSensitivity = intPreferencesKey("swipeSensitivity")
    val sound = booleanPreferencesKey("sound")
    val haptics = booleanPreferencesKey("haptics")
    val clipboardHistory = booleanPreferencesKey("clipboardHistory")
    val onboardingDone = booleanPreferencesKey("onboardingDone")
    // "Long-press keys & spacebar". Stored now; see the note on KeyboardPrefs:
    // the board does not read these yet.
    val backspaceHoldWords = booleanPreferencesKey("backspaceHoldWords")
    val backspaceSwipe = stringPreferencesKey("backspaceSwipe")
    val spacebarSwipe = stringPreferencesKey("spacebarSwipe")
    val spacebarHold = stringPreferencesKey("spacebarHold")
    val longPressMs = intPreferencesKey("longPressMs")
    // FUTO's two switches on the same screen. hideWhenHardKeyboard is honoured by
    // the IME service; inlineAutofill is stored but the chips are not wired yet.
    val inlineAutofill = booleanPreferencesKey("inlineAutofill")
    val hideWhenHardKeyboard = booleanPreferencesKey("hideWhenHardKeyboard")
    // "Auto-capitalization" — read by the IME service (it turns the board's Shift
    // key on when the caret sits at the start of a sentence).
    val autoCap = booleanPreferencesKey("autoCap")
    // "Emoji Suggestions" — read by the board, which puts an emoji in front of the
    // word suggestions for the words it knows one for.
    val suggestEmojis = booleanPreferencesKey("suggestEmojis")
}

/**
 * What sliding a finger off the delete key does — FUTO's "Swipe to delete".
 * Stored as a plain key string so the DataStore stays readable by eye.
 */
enum class BackspaceSwipe(val prefKey: String, val label: String) {
    OFF("off", "Off"),
    CHARACTERS("characters", "Characters"),
    WORDS("words", "Words");

    companion object {
        /** FUTO's own default for this setting. */
        fun fromKey(key: String?): BackspaceSwipe =
            entries.firstOrNull { it.prefKey == key } ?: CHARACTERS
    }
}

/**
 * A job the spacebar can do — FUTO's Spacebar "Swipe" / "Hold" pickers. [OFF] is
 * only offered for the slide; a hold always does something.
 */
enum class SpacebarAction(val prefKey: String, val label: String) {
    OFF("off", "Off"),
    CURSOR("cursor", "Move cursor"),
    LANGUAGE("language", "Switch language");

    companion object {
        fun fromKey(key: String?, fallback: SpacebarAction): SpacebarAction =
            entries.firstOrNull { it.prefKey == key } ?: fallback
    }
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
    val swipeSensitivity: Int = 1,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val clipboardHistory: Boolean = true,
    val onboardingDone: Boolean = false,
    // "Long-press keys & spacebar". These are stored and shown now; the board
    // does not act on them yet — the backspace still repeats plain deletes and the
    // spacebar has no slide action, so these three are inert preferences until the
    // keyboard core and the IME service are taught to read them.
    val backspaceHoldWords: Boolean = false,
    val backspaceSwipe: BackspaceSwipe = BackspaceSwipe.CHARACTERS,
    val spacebarSwipe: SpacebarAction = SpacebarAction.CURSOR,
    val spacebarHold: SpacebarAction = SpacebarAction.LANGUAGE,
    val longPressMs: Int = 300,
    // FUTO's defaults: inline autofill on, hide-when-USB off. inlineAutofill is a
    // saved choice only — we never call requestInlineSuggestions yet, so no chip
    // can appear; hideWhenHardKeyboard is read by the IME service's
    // onEvaluateInputViewShown.
    val inlineAutofill: Boolean = true,
    val hideWhenHardKeyboard: Boolean = false,
    // "Capitalize the first word of each sentence", FUTO's default. The board's
    // Shift key is turned on by the IME service when Android says the caret sits
    // at the start of a sentence; with this off nothing ever raises Shift for you.
    val autoCap: Boolean = true,
    // "Emoji Suggestions", FUTO's default. The board knows a small list of words
    // that have an obvious emoji (see EmojiSuggestions) and offers that emoji in
    // the strip in front of the word suggestions; with this off the strip shows
    // words only.
    val suggestEmojis: Boolean = true
) {
    /**
     * The strip above the keys hosts two unrelated things: the engine's word
     * suggestions, and the Fn key's Esc / Tab / Ctrl / ... chips. Word
     * suggestions can be switched off, but Fn still needs a row to draw in, so
     * the strip stays whenever EITHER is on. That is what makes Fn independent
     * of the "Word suggestions" switch: with suggestions off the strip shows as
     * a deliberately empty state until Fn is clicked or held.
     */
    val showStrip: Boolean get() = suggestions || fn

    /** The ^ only makes sense when the strip it sits in is actually there. */
    val showChevron: Boolean get() = toolbar && arrow && showStrip
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
            swipeSensitivity = p[PrefKeys.swipeSensitivity] ?: 1,
            sound = p[PrefKeys.sound] ?: true,
            haptics = p[PrefKeys.haptics] ?: true,
            clipboardHistory = p[PrefKeys.clipboardHistory] ?: true,
            onboardingDone = p[PrefKeys.onboardingDone] ?: false,
            backspaceHoldWords = p[PrefKeys.backspaceHoldWords] ?: false,
            backspaceSwipe = BackspaceSwipe.fromKey(p[PrefKeys.backspaceSwipe]),
            spacebarSwipe = SpacebarAction.fromKey(p[PrefKeys.spacebarSwipe], SpacebarAction.CURSOR),
            spacebarHold = SpacebarAction.fromKey(p[PrefKeys.spacebarHold], SpacebarAction.LANGUAGE),
            longPressMs = p[PrefKeys.longPressMs] ?: 300,
            inlineAutofill = p[PrefKeys.inlineAutofill] ?: true,
            hideWhenHardKeyboard = p[PrefKeys.hideWhenHardKeyboard] ?: false,
            autoCap = p[PrefKeys.autoCap] ?: true,
            suggestEmojis = p[PrefKeys.suggestEmojis] ?: true
        )
    }

    suspend fun setTheme(choice: ThemeChoice) {
        context.keyboardDataStore.edit { it[PrefKeys.theme] = choice.prefKey }
    }

    suspend fun setKeyHeight(dp: Int) {
        context.keyboardDataStore.edit { it[PrefKeys.keyHeight] = dp }
    }

    suspend fun setBackspaceHoldWords(value: Boolean) {
        context.keyboardDataStore.edit { it[PrefKeys.backspaceHoldWords] = value }
    }

    suspend fun setBackspaceSwipe(mode: BackspaceSwipe) {
        context.keyboardDataStore.edit { it[PrefKeys.backspaceSwipe] = mode.prefKey }
    }

    suspend fun setSpacebarSwipe(action: SpacebarAction) {
        context.keyboardDataStore.edit { it[PrefKeys.spacebarSwipe] = action.prefKey }
    }

    suspend fun setSpacebarHold(action: SpacebarAction) {
        context.keyboardDataStore.edit { it[PrefKeys.spacebarHold] = action.prefKey }
    }

    suspend fun setLongPressMs(ms: Int) {
        context.keyboardDataStore.edit { it[PrefKeys.longPressMs] = ms }
    }

    suspend fun setInlineAutofill(value: Boolean) {
        context.keyboardDataStore.edit { it[PrefKeys.inlineAutofill] = value }
    }

    suspend fun setHideWhenHardKeyboard(value: Boolean) {
        context.keyboardDataStore.edit { it[PrefKeys.hideWhenHardKeyboard] = value }
    }

    suspend fun setAutoCap(value: Boolean) {
        context.keyboardDataStore.edit { it[PrefKeys.autoCap] = value }
    }

    suspend fun setSuggestEmojis(value: Boolean) {
        context.keyboardDataStore.edit { it[PrefKeys.suggestEmojis] = value }
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
                "clipboardHistory" -> prefs[PrefKeys.clipboardHistory] = value
                "onboardingDone" -> prefs[PrefKeys.onboardingDone] = value
            }
        }
    }

    suspend fun setInt(key: String, value: Int) {
        context.keyboardDataStore.edit { prefs ->
            when (key) {
                "swipeSensitivity" -> prefs[PrefKeys.swipeSensitivity] = value
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
