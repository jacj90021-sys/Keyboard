package com.example

import android.app.Application
import com.example.keyboard.native.NativeEngineBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Preloads the suggestion dictionary before the IME is ever shown, so the first
 * keyboard open has suggestions instead of waiting for the asset copy.
 */
class KeyboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                NativeEngineBridge.tryLoad()
                NativeEngineBridge.loadSampleDictionary(this@KeyboardApplication)
            }
        }
    }
}
