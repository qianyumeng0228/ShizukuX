package af.shizuku.manager.installer.verifier

import af.shizuku.manager.ShizukuSettings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PithusClient : ApkVerificationClient {
    override val name = "Pithus Threat Intel"
    override val preferenceKey = "verify_apk_pithus"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        return try {
            val apiKey = ShizukuSettings.getPithusApiKey()
            val url = URL("https://beta.pithus.org/api/beta/search/hash/$sha256/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Token $apiKey")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                conn.disconnect()
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = "Pithus: hash not in database.")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = "Pithus: HTTP $code, skipped.")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val riskScore = json.optInt("risk_score", 0)
            val isSafe = riskScore < 50
            VerificationResult(
                isSafe = isSafe,
                methodsUsed = listOf(name),
                riskScore = riskScore,
                details = "Pithus: risk score $riskScore/100."
            )
        } catch (e: Exception) {
            VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                details = "Pithus: lookup failed (${e.message}), skipped.")
        }
    }
}
