package af.shizuku.manager.installer.verifier

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File

data class VerificationResult(
    val isSafe: Boolean,
    val methodsUsed: List<String>,
    val riskScore: Int,
    val details: String
)

interface ApkVerificationClient {
    val name: String
    val preferenceKey: String
    suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult
}

class ApkVerificationManager(
    private val clients: List<ApkVerificationClient>,
    private val sharedPreferences: SharedPreferences
) {
    suspend fun verify(apkFile: File): VerificationResult {
        // Compute SHA256 of the APK once
        val sha256 = computeSha256(apkFile)
        val activeMethods = mutableListOf<String>()
        var totalRisk = 0
        var isSafe = true
        val detailsBuilder = StringBuilder()

        for (client in clients) {
            // Check if the client is enabled in settings.
            // NOTE: the F-Droid index and Local Signature clients are currently placeholders —
            // they perform NO real check yet still returned isSafe=true with "matches" wording,
            // which fabricated a clean bill of health. They default to OFF until the real index/
            // signature comparison is implemented; users opting in see an honest "not actually
            // verified" detail. VirusTotal/Pithus default false as before.
            val isEnabledByDefault = client.preferenceKey in emptyList<String>()
            if (sharedPreferences.getBoolean(client.preferenceKey, isEnabledByDefault)) {
                val result = client.verifyApk(apkFile, sha256)
                activeMethods.add(client.name)
                totalRisk += result.riskScore
                detailsBuilder.appendLine("${client.name}: ${result.details}")
                if (!result.isSafe) {
                    isSafe = false
                }
            }
        }

        return VerificationResult(
            isSafe = isSafe,
            methodsUsed = activeMethods,
            riskScore = totalRisk,
            details = detailsBuilder.toString().trim()
        )
    }

    private suspend fun computeSha256(apkFile: File): String {
        return withContext(Dispatchers.IO) {
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                apkFile.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e("ApkVerifier", "Failed to compute SHA-256 for APK", e)
                ""
            }
        }
    }
}
