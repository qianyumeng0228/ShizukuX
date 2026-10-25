package af.shizuku.manager.adb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FakeAdbPairingActivity : Activity() {

    companion object {
        private var currentLatch: CountDownLatch? = null
        private var currentResult = AtomicBoolean(false)

        /** Bound how long the accept-loop thread waits for the user to answer the pairing
         *  dialog. Without a timeout a background-activity-start denial, a cancelled dialog or
         *  a process death would park the thread (and leak the local loopback socket) forever. */
        private const val PAIRING_TIMEOUT_SECONDS = 20L

        fun requestPairingSync(context: Context, pubKeyStr: String): Boolean {
            val latch = CountDownLatch(1)
            // Supersede any still-pending request: release the previous waiter (it returns
            // false) so a second connection can never orphan the first awaiter on a static latch.
            currentLatch?.countDown()
            currentLatch = latch
            currentResult.set(false)

            val intent = Intent(context, FakeAdbPairingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("pubKey", pubKeyStr)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Android 10+ background activity start denial etc. — never leave the latch pending.
                if (currentLatch === latch) currentLatch = null
                latch.countDown()
                return false
            }

            return try {
                if (latch.await(PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    currentResult.get()
                } else {
                    false // timed out: dialog never answered (denied/cancelled/reclaimed)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                if (currentLatch === latch) currentLatch = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pubKeyStr = intent.getStringExtra("pubKey") ?: ""
        val hash = pubKeyStr.hashCode().toString(16).uppercase()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fake_adb_pairing_title)
            .setMessage(getString(R.string.fake_adb_pairing_message, hash))
            .setPositiveButton(R.string.fake_adb_pairing_allow) { _, _ ->
                currentResult.set(true)
                currentLatch?.countDown()
                finish()
            }
            .setNegativeButton(R.string.fake_adb_pairing_deny) { _, _ ->
                currentResult.set(false)
                currentLatch?.countDown()
                finish()
            }
            .setOnCancelListener {
                currentResult.set(false)
                currentLatch?.countDown()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    public override fun onDestroy() {
        // Belt-and-suspenders: if the activity is torn down without the dialog answering
        // (config change, process reclamation), release any waiter so it fails fast instead
        // of waiting out the full timeout.
        currentResult.set(false)
        currentLatch?.countDown()
        currentLatch = null
        super.onDestroy()
    }
}
