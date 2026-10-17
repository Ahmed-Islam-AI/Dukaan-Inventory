package app.dukaan.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.dukaan.android.core.settings.DevicePrefs
import app.dukaan.android.core.settings.withAppLanguage
import app.dukaan.android.core.ui.theme.DukaanTheme

/** FragmentActivity: BiometricPrompt (app lock, M6) needs it. */
class MainActivity : FragmentActivity() {

    /** Android's own base context, whose outer context is this Activity. */
    private var systemBase: Context? = null

    override fun attachBaseContext(newBase: Context) {
        systemBase = newBase
        super.attachBaseContext(newBase.withAppLanguage(DevicePrefs(newBase).language))
    }

    /**
     * PrintManager only prints for an Activity, and the language context isn't one: take the
     * print service from Android's base (found on the emulator: A4 print crashed).
     */
    override fun getSystemService(name: String): Any? =
        if (name == PRINT_SERVICE) systemBase?.getSystemService(name) ?: super.getSystemService(name) else super.getSystemService(name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The saffron launch window (themes.xml) is for start-up only; under Compose use the page colour.
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (night) 0xFF121416.toInt() else 0xFFF4F0EA.toInt()))
        enableEdgeToEdge()
        setContent {
            DukaanTheme { DukaanRoot() }
        }
    }

    /** Single-activity app: leaving it = the app going to the background. Snapshot for backup (D-28). */
    override fun onStop() {
        super.onStop()
        dukaanContainer().snapshotInBackground()
        dukaanContainer().lock.onBackground()
    }

    override fun onStart() {
        super.onStart()
        dukaanContainer().lock.onForeground()
        dukaanContainer().account.onForeground()
    }
}

/** True when the app may talk to a paired Bluetooth printer (always true below Android 12). */
fun dukaanPrinterAllowed(context: Context) = context.dukaanContainer().printer.hasPermission()

/** Walks up to the Application and returns its container. Works under wrapped contexts too. */
fun Context.dukaanContainer(): AppContainer {
    var context = this
    while (context !is DukaanApp) {
        context = when (context) {
            is ContextWrapper -> context.baseContext
            else -> return (applicationContext as DukaanApp).container
        }
    }
    return context.container
}
