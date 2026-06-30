package af.shizuku.manager.installer.verifier

import af.shizuku.manager.ShizukuSettings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VirusTotalClient : ApkVerificationClient {
    override val name = "VirusTotal API"
    override val preferenceKey = "verify_apk_virustotal"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        val apiKey = ShizukuSettings.getVirusTotalApiKey()
        if (apiKey.isBlank()) {
            return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                details = "VirusTotal: no API key configured, skipped.")
        }
        return try {
            val url = URL("https://www.virustotal.com/vtapi/v2/file/report?apikey=$apiKey&resource=$sha256")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == 204) {
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = "VirusTotal: rate limit reached, skipped.")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = "VirusTotal: HTTP $code, skipped.")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val responseCode = json.optInt("response_code", -1)
            if (responseCode == 0) {
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = "VirusTotal: hash not in database.")
            }
            val positives = json.optInt("positives", 0)
            val total = json.optInt("total", 0)
            val isSafe = positives == 0
            VerificationResult(
                isSafe = isSafe,
                methodsUsed = listOf(name),
                riskScore = if (total > 0) (positives * 100 / total) else 0,
                details = "VirusTotal: $positives/$total engines flagged this file."
            )
        } catch (e: Exception) {
            VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                details = "VirusTotal: lookup failed (${e.message}), skipped.")
        }
    }
}
