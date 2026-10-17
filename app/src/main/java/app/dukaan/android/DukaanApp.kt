package app.dukaan.android

import android.app.Application
import android.content.Context
import app.dukaan.android.core.settings.DevicePrefs
import app.dukaan.android.core.settings.withAppLanguage

/** Owns the [AppContainer]. This is the whole DI story (DECISIONS.md D-23). */
class DukaanApp : Application() {

    lateinit var container: AppContainer
        private set

    // App context (used by ViewModels for messages) speaks the chosen language too.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withAppLanguage(DevicePrefs(base).language))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.account.start()
    }
}
