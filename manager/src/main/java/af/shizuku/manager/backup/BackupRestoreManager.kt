package af.shizuku.manager.backup

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import af.shizuku.manager.utils.SettingsBackupManager

object BackupRestoreManager {

    fun createBackupPayload(context: Context, cipher: Cipher): String {
        val jsonString = SettingsBackupManager.export(context)
        val encryptedBytes = cipher.doFinal(jsonString.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val backupJson = JSONObject()
        backupJson.put("version", 1)
        backupJson.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        backupJson.put("data", Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))

        return backupJson.toString()
    }

    fun restoreFromPayload(context: Context, payload: String, cipherProvider: (ByteArray) -> Cipher) {
        val backupJson = JSONObject(payload)
        val iv = Base64.decode(backupJson.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(backupJson.getString("data"), Base64.NO_WRAP)

        val cipher = cipherProvider(iv)
        val decryptedBytes = cipher.doFinal(data)
        val jsonString = String(decryptedBytes, Charsets.UTF_8)

        val success = SettingsBackupManager.import(context, jsonString)
        if (!success) {
            throw Exception("Failed to import settings (invalid format)")
        }
    }
}
