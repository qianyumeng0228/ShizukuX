package af.shizuku.manager.utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object IntentCrypto {
    private val key = SecretKeySpec("ShizukuIntentSecureKey123!".toByteArray().copyOf(16), "AES")

    fun encrypt(data: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) {
            data // fallback
        }
    }

    fun decrypt(data: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) {
            data // fallback
        }
    }
}
