package af.shizuku.manager.utils;

import android.text.TextUtils;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for input validation to prevent injection attacks and ensure system stability.
 */
public class InputValidationUtils {

    private static final String TAG = "InputValidationUtils";

    // Whitelist of valid spoof target device identifiers
    private static final List<String> VALID_SPOOF_TARGETS = Arrays.asList(
        "auto",
        "pixel_9_pro_xl",
        "pixel_8_pro",
        "s24_ultra",
        "s23_ultra",
        "s22_ultra",
        "oneplus_12",
        "nothing_phone_2"
    );

    // Valid DNS mode values
    private static final List<String> VALID_DNS_MODES = Arrays.asList(
        "off",
        "opportunistic",
        "hostname"
    );

    // Pattern for validating hostname format
    // Hostname: 1-253 chars, alphanumeric and hyphens, segments separated by dots
    // Each segment: 1-63 chars, cannot start or end with hyphen
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$"
    );

    // Pattern for simple hostname (single label)
    private static final Pattern SIMPLE_HOSTNAME_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$"
    );

    private InputValidationUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Validates a spoof target string against the whitelist of valid device targets.
     *
     * @param target the spoof target string to validate
     * @return true if the target is valid, false otherwise
     */
    public static boolean isValidSpoofTarget(String target) {
        if (TextUtils.isEmpty(target)) {
            return false;
        }
        return VALID_SPOOF_TARGETS.contains(target);
    }

    /**
     * Gets the list of valid spoof targets.
     *
     * @return list of valid spoof target strings
     */
    public static List<String> getValidSpoofTargets() {
        return VALID_SPOOF_TARGETS;
    }

    /**
     * Validates a DNS mode parameter.
     *
     * @param mode the DNS mode to validate
     * @return true if the mode is valid ("off", "opportunistic", or "hostname"), false otherwise
     */
    public static boolean isValidDnsMode(String mode) {
        if (TextUtils.isEmpty(mode)) {
            return false;
        }
        return VALID_DNS_MODES.contains(mode.toLowerCase());
    }

    /**
     * Validates a DNS hostname string.
     *
     * @param hostname the hostname to validate
     * @return true if the hostname is valid, false otherwise
     */
    public static boolean isValidHostname(String hostname) {
        if (TextUtils.isEmpty(hostname)) {
            return false;
        }

        // Check total length (max 253 characters)
        if (hostname.length() > 253) {
            return false;
        }

        // Check if it's a simple hostname (single label)
        if (!hostname.contains(".")) {
            return SIMPLE_HOSTNAME_PATTERN.matcher(hostname).matches();
        }

        // Check if it's a fully qualified domain name
        return HOSTNAME_PATTERN.matcher(hostname).matches();
    }

    /**
     * Sanitizes a hostname string by removing potentially dangerous characters.
     *
     * @param hostname the hostname to sanitize
     * @return sanitized hostname, or null if input is null/empty
     */
    public static String sanitizeHostname(String hostname) {
        if (TextUtils.isEmpty(hostname)) {
            return null;
        }

        // Remove any characters that are not alphanumeric, hyphen, or dot
        return hostname.replaceAll("[^a-zA-Z0-9.\\-]", "");
    }

    /**
     * Validates and sanitizes a hostname.
     *
     * @param hostname the hostname to validate and sanitize
     * @return sanitized hostname if valid, null if invalid
     */
    public static String validateAndSanitizeHostname(String hostname) {
        String sanitized = sanitizeHostname(hostname);
        if (TextUtils.isEmpty(sanitized) || !isValidHostname(sanitized)) {
            return null;
        }
        return sanitized;
    }
}
