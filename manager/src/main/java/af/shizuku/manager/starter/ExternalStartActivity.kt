package af.shizuku.manager.starter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import af.shizuku.manager.ShizukuSettings

/**
 * The single exported entry point other apps use to ask ShizukuX to start (or stop) the
 * service (ShizukuPlus 3c6605a6).
 *
 * StarterActivity itself is not exported: it used to read EXTRA_IS_ROOT / EXTRA_IS_SYSTEM /
 * EXTRA_PORT / EXTRA_AUTO_PAIRING out of the incoming intent, so any app could steer how the
 * service starts — point it at a port the caller listens on and grab the trusted ADB key, or
 * force a launch mode the user never chose. This trampoline forwards to StarterActivity with
 * NO extras: the launch mode comes from [ShizukuSettings.getLastLaunchMode] (the manager's own
 * choice), and StarterActivity can still be launched with extras from inside the app.
 */
class ExternalStartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, StarterActivity::class.java)
                .putExtra(StarterActivity.EXTRA_IS_ROOT,
                    ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT)
        )
        finish()
    }
}
