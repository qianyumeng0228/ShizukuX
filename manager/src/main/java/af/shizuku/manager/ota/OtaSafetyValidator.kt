package af.shizuku.manager.ota

import android.os.Build
import java.io.File

class OtaSafetyValidator {

    data class ValidationResult(
        val isSafe: Boolean,
        val errorMessage: String? = null,
        val willWipeData: Boolean = false
    )

    fun validatePayload(otaZip: File): ValidationResult {
        // Implementation will:
        // 1. Extract and parse META-INF/com/android/metadata
        // 2. Check 'pre-device' property against Build.DEVICE
        // 3. Extract payload_properties.txt and verify hashes against payload.bin
        // 4. Determine if CSC wipe_data flag is present

        val currentDevice = Build.DEVICE

        // Skeleton logic for now
        val parsedDevice = currentDevice // Assume match for skeleton
        val isHomeCsc = true // Assume safe for skeleton

        if (parsedDevice != currentDevice) {
            return ValidationResult(
                isSafe = false,
                errorMessage = "Device mismatch! OTA is for \$parsedDevice but current device is \$currentDevice."
            )
        }

        if (!isHomeCsc) {
            return ValidationResult(
                isSafe = true,
                willWipeData = true,
                errorMessage = "Warning: This OTA payload (CSC) will WIPE user data. A HOME_CSC payload is required to preserve data."
            )
        }

        return ValidationResult(isSafe = true)
    }
}
