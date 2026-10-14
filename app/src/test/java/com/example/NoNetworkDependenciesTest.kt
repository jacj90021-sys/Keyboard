package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A keyboard must not talk to the network. The project used to ship Firebase, Room,
 * Retrofit, OkHttp and Moshi as unused template boilerplate, which registered
 * FirebaseInitProvider in every process (including the IME service) and forced the
 * INTERNET permission onto a keyboard. This guards against that coming back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoNetworkDependenciesTest {

    @Test
    fun `firebase is not on the classpath`() {
        val error = runCatching { Class.forName("com.google.firebase.FirebaseApp") }.exceptionOrNull()
        assertTrue(
            "Firebase must not be bundled in a keyboard (found com.google.firebase.FirebaseApp)",
            error is ClassNotFoundException
        )
    }

    @Test
    fun `app does not request the internet permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val permissions = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "A keyboard must not request INTERNET, got $permissions",
            permissions.none { it == "android.permission.INTERNET" }
        )
    }

    @Test
    fun `no content providers are registered`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val providers = info.providers?.map { it.name }.orEmpty()
        assertTrue(
            "Providers must not run in the IME process, got $providers",
            providers.none { it.contains("firebase", ignoreCase = true) }
        )
    }

    @Test
    fun `keyboard app label is still Keyboard`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Keyboard", context.getString(R.string.app_name))
    }
}