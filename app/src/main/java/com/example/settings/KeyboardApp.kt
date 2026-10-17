package com.example.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.native.NativeEngineBridge

private enum class AppScreen { Onboarding, Personalize, Settings }

private val placeholderTitles = emptyMap<String, String>()

@Composable
fun KeyboardApp(
    prefs: KeyboardPrefs,
    repository: KeyboardPrefsRepository,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit
) {
    var screen by remember { mutableStateOf(AppScreen.Onboarding) }
    val context = LocalContext.current

    LaunchedEffect(context) {
        // Off the frame: engine setup maps and validates the dictionary.
        withContext(Dispatchers.IO) {
            NativeEngineBridge.tryLoad()
            NativeEngineBridge.loadSampleDictionary(context)
        }
    }
    var page by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Onboarding is shown on first launch and while the keyboard is not enabled.
    LaunchedEffect(prefs.onboardingDone, imeEnabled) {
        screen = if (!imeEnabled || !prefs.onboardingDone) AppScreen.Onboarding else AppScreen.Settings
        page = null
    }

    when (screen) {
        AppScreen.Onboarding -> OnboardingScreen(
            enabled = imeEnabled,
            selected = imeSelected,
            onOpenKeyboardSettings = onOpenKeyboardSettings,
            onChooseKeyboard = onChooseKeyboard,
            onPersonalize = { screen = AppScreen.Personalize }
        )

        AppScreen.Personalize -> PersonalizeScreen(
            prefs = prefs,
            repository = repository,
            isOnboarding = true,
            onBack = { screen = AppScreen.Onboarding },
            onFinish = {
                scope.launch {
                    repository.finishOnboarding()
                    screen = if (imeEnabled) AppScreen.Settings else AppScreen.Onboarding
                    page = null
                }
            }
        )

        AppScreen.Settings -> {
            when (val p = page) {
                null -> SettingsScreen(
                    prefs = prefs,
                    repository = repository,
                    onOpenPage = { page = it }
                )

                "topbar" -> TopBarPage(prefs, repository) { page = null }
                "typing" -> TypingPage(prefs, repository) { page = null }
                "voice" -> VoicePage { page = null }
                "prediction" -> PredictionPage(prefs, repository) { page = null }
                "swipe" -> SwipePage(prefs, repository) { page = null }
                "personalization" -> PersonalizationPage { page = null }
                "sound" -> SoundPage(prefs, repository) { page = null }
                "privacy" -> PrivacyPage { page = null }
                "languages" -> LanguagesLayoutsScreen { page = null }
                "actions" -> ActionsPage { page = null }
                "other" -> OtherPage { page = null }
                "help" -> HelpPage { page = null }
                "about" -> AboutPage { page = null }
                "clipboard" -> ClipboardPage(prefs, repository) { page = null }
                "emoji" -> EmojiPage { page = null }
                "premium" -> PremiumPage { page = null }
                "appearance" -> PersonalizeScreen(
                    prefs = prefs,
                    repository = repository,
                    isOnboarding = false,
                    onBack = { page = null },
                    onFinish = { page = null }
                )

                else -> PlaceholderPage(
                    title = placeholderTitles[p] ?: p,
                    onBack = { page = null }
                )
            }
        }
    }
}
