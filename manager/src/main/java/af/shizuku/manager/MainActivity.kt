package af.shizuku.manager

import android.content.Intent
import android.os.Bundle
import timber.log.Timber
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.sentry.Breadcrumb
import io.sentry.Sentry
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.onboarding.OnboardingActivity
import af.shizuku.manager.utils.ShizukuStateMachine

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Timber.d("Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            Timber.d("Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))
            
            if (!ShizukuSettings.hasSeenOnboarding()) {
                Timber.d("Showing onboarding")
                Sentry.addBreadcrumb(Breadcrumb("Showing onboarding"))
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }

            Timber.d("MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            Sentry.captureException(e)
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            throw e
        }
    }
    
    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            Sentry.captureException(e)
            throw e
        }
    }
}
