package com.example

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.settings.KeyboardApp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Tokens

class MainActivity : ComponentActivity() {

    private val imeEnabled = mutableStateOf(false)
    private val imeSelected = mutableStateOf(false)

    private lateinit var repository: KeyboardPrefsRepository

    private var imeObserver: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(applicationContext)
        showLastCrash()
        enableEdgeToEdge()
        repository = KeyboardPrefsRepository(applicationContext)
        // Watch the secure settings for the WHOLE activity lifetime, not just while
        // resumed, so selecting the keyboard from the system picker is detected.
        registerImeObserver()

        setContent {
            val prefs by repository.prefs.collectAsState(initial = KeyboardPrefs())

            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Tokens.Paper
                ) {
                    KeyboardApp(
                        prefs = prefs,
                        repository = repository,
                        imeEnabled = imeEnabled.value,
                        imeSelected = imeSelected.value,
                        onOpenKeyboardSettings = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            }.onFailure {
                                Log.e(TAG, "Unable to open keyboard settings", it)
                            }
                        },
                        onChooseKeyboard = {
                            // Poll FIRST so step 2 completes even if the secure
                            // setting observer never fires.
                            pollImeSelection()
                            runCatching {
                                val imm =
                                    getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showInputMethodPicker()
                            }.onFailure {
                                Log.e(TAG, "Unable to show input method picker", it)
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                }.onFailure {
                                    Log.e(TAG, "Unable to open input method settings", it)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Registered in onCreate (not here): the system IME picker PAUSES this
        // activity, so an observer bound to the resumed window would never see the
        // DEFAULT_INPUT_METHOD change and step 2 would never complete.
        refreshImeState()
    }

    override fun onStart() {
        super.onStart()
        refreshImeState()
    }

    override fun onPause() {
        super.onPause()
        // Intentionally NOT unregistering here - see onResume().
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from the system IME picker or settings regains window focus here.
        if (hasFocus) refreshImeState()
    }

    private fun registerImeObserver() {
        if (imeObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshImeState()
            }
        }
        imeObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
                false,
                observer
            )
        }.onFailure { Log.e(TAG, "Unable to register IME observer", it) }
    }

    private fun unregisterImeObserver() {
        imeObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        imeObserver = null
    }

    /** Poll for ~15s in case the secure setting observer does not fire. */
    private fun pollImeSelection() {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                refreshImeState()
                attempts++
                if (!imeSelected.value && attempts < 50) {
                    handler.postDelayed(this, 300)
                }
            }
        }
        handler.postDelayed(runnable, 300)
    }

    override fun onDestroy() {
        unregisterImeObserver()
        super.onDestroy()
    }

    /**
     * The keyboard and the settings screen share a process, so an IME crash used to take
     * the app down with no visible cause. Show whatever was captured instead.
     */
    private fun showLastCrash() {
        val text = CrashReporter.consumeLastCrash(this) ?: return
        if (text.contains("MainActivity.onCreate")) return  // never loop on our own crash
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Last crash")
                .setMessage(text)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                }
                .setNegativeButton("Dismiss", null)
                .show()
        }
    }

    private fun refreshImeState() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        imeEnabled.value = try {
            imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        } catch (_: Exception) {
            false
        }

        imeSelected.value = try {
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: ""
            current.substringBefore('/').substringBefore(':') == packageName ||
                current.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
