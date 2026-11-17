package af.shizuku.manager.utils

/**
 * Redaction for crash reports that come from the server process.
 *
 * The server sends raw exception messages and stack traces (Log.getStackTraceString), which
 * routinely name the package that triggered a denial and the file paths involved. Only the parts
 * that explain the bug are kept: exception classes and frames, with messages and paths masked.
 *
 * Ported from ShizukuPlus b3f41aec.
 */
internal fun redactServerEventMessage(message: String): String {
    val split = message.indexOf(": ")
    val head = if (split >= 0) message.substring(0, split) else message
    val tail = if (split >= 0) message.substring(split + 2) else ""

    fun redact(text: String) =
        text
            .replace(Regex("""(?i)bearer\s+\S+"""), "<token>")
            .replace(Regex("""\b[\w.-]+=\S+"""), "<redacted>")
            .replace(Regex("""(?<![\w])/[^\s,;)]+"""), "<path>")
            .replace(Regex("""\b[a-zA-Z][\w]*(\.[a-zA-Z_][\w]*){2,}\b"""), "<pkg>")

    val details = redact(tail).take(80)
    val redactedHead = redact(head).take(120)
    return if (details.isEmpty()) redactedHead else "$redactedHead: $details"
}

/**
 * A server stack trace for a crash report: frames stay (they name server and framework code
 * only), each exception keeps its class and its message goes through [redactServerEventMessage].
 */
internal fun redactServerStackTrace(trace: String): String =
    trace
        .lineSequence()
        .take(60)
        .map { line ->
            val t = line.trim()
            if (t.startsWith("at ") || t.startsWith("...")) return@map line
            val m = Regex("""^(Caused by: |Suppressed: )?([\w.$]+)(?:: (.*))?$""").find(t) ?: return@map "<line>"
            val (prefix, type, msg) = m.destructured
            if (msg.isEmpty()) "$prefix$type" else "$prefix$type: ${redactServerEventMessage(msg)}"
        }
        .joinToString("\n")
