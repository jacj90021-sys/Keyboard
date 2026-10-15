package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.native.NativeEngineBridge

private enum class AppScreen { Onboarding, Personalize, Settings }

private val placeholderTitles = mapOf(
    "languages" to "Languages & layouts",
    "swipe" to "Swipe typing",
    "prediction" to "Text prediction",
    "voice" to "Voice input",
    "clipboard" to "Clipboard",
    "emoji" to "Emoji & media",
    "actions" to "Actions",
    "personalization" to "Personalization",
    "sound" to "Sound & vibration",
    "privacy" to "Privacy",
    "premium" to "Premium",
    "other" to "Other",
    "help" to "Help & feedback",
    "about" to "About"
)

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

    LaunchedEffect(Unit) {
        NativeEngineBridge.tryLoad()
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
