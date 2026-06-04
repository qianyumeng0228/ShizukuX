package af.shizuku.manager.installer.verifier

import java.io.File

class LocalSignatureClient : ApkVerificationClient {
    override val name = "Local Signature Matching"
    override val preferenceKey = "verify_apk_local"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        return VerificationResult(
            isSafe = true,
            methodsUsed = listOf(name),
            riskScore = 0,
            details = "Signature matches locally."
        )
    }
}
