package af.shizuku.manager.adb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeAdbClientHandler(
    private val context: Context,
    private val socket: Socket
) {
    companion object {
        private const val TAG = "FakeAdbClient"
        private const val PREFS_NAME = "fake_adb_keys"
        private const val PREF_KEYS = "keys"
        /** Newline-delimited "<uid>\t<pubkey>" entries binding each authorized ADB public key
         *  to the loopback UID that owns it. Keys authorized before this binding existed have
         *  no entry here and are adopted on first valid signature (see loop()). */
        private const val PREF_KEY_UIDS = "key_uids"
        private const val MAX_ADB_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB, well above real adb's ~1MB max payload

        // Multiplexing
        private val localIdCounter = AtomicInteger(1)
    }

    private val inputStream = DataInputStream(socket.getInputStream())
    private val outputStream = DataOutputStream(socket.getOutputStream())

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** UID of the app that dialed us over 127.0.0.1, resolved from /proc/net/tcp{,6}.
     *  Null when it cannot be determined on this OEM; the auth flow then falls back to
     *  requiring cryptographic proof (a valid signature) and rejects no-proof handshakes. */
    private val peerUid: Int? = resolvePeerUid()

    // We only support one active session per connection, but could support multiple
    private val activeProcesses = ConcurrentHashMap<Int, ShizukuRemoteProcess>()

    fun loop() {
        // Wait for CNXN
        var msg = readMessage()
        if (msg.command != AdbProtocol.A_CNXN) {
            Timber.tag(TAG).e("Expected CNXN, got ${msg.command}")
            return
        }

        // Send AUTH(TOKEN)
        val token = ByteArray(20)
        java.security.SecureRandom().nextBytes(token)
        writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token))

        Timber.tag(TAG).i("Accepted loopback connection, peer uid=$peerUid")

        var authenticated = false
        while (!authenticated) {
            msg = readMessage()
            if (msg.command == AdbProtocol.A_AUTH) {
                if (msg.arg0 == AdbProtocol.ADB_AUTH_SIGNATURE) {
                    val signature = msg.data ?: run {
                        Timber.tag(TAG).w("AUTH_SIGNATURE with null data — ignoring")
                        continue
                    }
                    val matchedKey = findVerifiedKey(token, signature)
                    if (matchedKey != null) {
                        authenticated = true
                        // A valid RSA signature proves the peer holds the private key for this
                        // authorized public key. Bind (or re-bind) the key to the connecting app's
                        // loopback UID. Rebinding on every successful signature is safe (malware
                        // cannot sign) and self-heals across reinstalls that re-UID the package.
                        if (peerUid != null) bindKeyToUid(matchedKey, peerUid)
                        writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                    } else {
                        // Send another token, prompt client to try next key or send public key
                        java.security.SecureRandom().nextBytes(token)
                        writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token))
                    }
                } else if (msg.arg0 == AdbProtocol.ADB_AUTH_RSAPUBLICKEY) {
                    val rawKeyData = msg.data ?: run {
                        Timber.tag(TAG).w("AUTH_RSAPUBLICKEY with null data — ignoring")
                        continue
                    }
                    val pubKeyStr = String(rawKeyData).trimEnd('\u0000')
                    Timber.tag(TAG).i("Received public key: $pubKeyStr")

                    if (isKeyAuthorized(pubKeyStr)) {
                        // HARDENED (loopback privilege-escalation fix): presenting an
                        // already-authorized public key proves nothing by itself — no signature,
                        // no proof of holding the matching private key. A well-formed ADB client
                        // whose key we already know would have produced a valid AUTH_SIGNATURE on
                        // the previous token and never reach this branch. Accept this degenerate
                        // case only when we can positively identify the peer as the UID that owns
                        // the key (or our own process for self-tests); otherwise fail closed by
                        // dropping the connection. This stops any local app from replaying a known
                        // pubkey to obtain privileged shell on 127.0.0.1:15555.
                        val boundUid = boundUidFor(pubKeyStr)
                        val selfUid = android.os.Process.myUid()
                        if (peerUid != null && (peerUid == selfUid || peerUid == boundUid)) {
                            authenticated = true
                            writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                        } else {
                            Timber.tag(TAG).w(
                                "Rejecting no-proof AUTH_RSAPUBLICKEY for known key: peer uid=$peerUid, bound uid=$boundUid"
                            )
                            return // closes the socket via socket.use{}; the accept loop is untouched
                        }
                    } else {
                        // Prompt user
                        val allowed = promptUserForPairingBlocking(pubKeyStr)
                        if (allowed) {
                            authorizeKey(pubKeyStr)
                            // Record which app requested pairing so a different (possibly malicious)
                            // local app cannot later reuse this key merely by presenting its pubkey.
                            if (peerUid != null) bindKeyToUid(pubKeyStr, peerUid)
                            authenticated = true
                            writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                        } else {
                            Timber.tag(TAG).w("User denied pairing for key: $pubKeyStr")
                            return
                        }
                    }
                }
            } else {
                Timber.tag(TAG).e("Expected AUTH, got ${msg.command}")
                return
            }
        }

        Timber.tag(TAG).i("Client authenticated. Entering multiplex loop.")

        while (true) {
            msg = readMessage()
            when (msg.command) {
                AdbProtocol.A_OPEN -> {
                    val remoteId = msg.arg0
                    val rawData = msg.data
                    if (rawData == null) {
                        Timber.tag(TAG).w("A_OPEN with null data — closing channel")
                        writeMessage(AdbMessage(AdbProtocol.A_CLSE, 0, remoteId, ByteArray(0)))
                    } else {
                        val destination = String(rawData).trimEnd('\u0000')
                        if (destination.startsWith("shell:")) {
                            val cmd = destination.substring(6)
                            startShellProcess(remoteId, cmd)
                        } else {
                            Timber.tag(TAG).w("Unsupported destination: $destination")
                            writeMessage(AdbMessage(AdbProtocol.A_CLSE, 0, remoteId, ByteArray(0)))
                        }
                    }
                }
                AdbProtocol.A_WRTE -> {
                    val localId = msg.arg0
                    val remoteId = msg.arg1
                    val writeData = msg.data
                    val process = activeProcesses[localId]
                    if (process != null && writeData != null) {
                        try {
                            process.outputStream.write(writeData)
                            process.outputStream.flush()
                            writeMessage(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error writing to process stdin")
                        }
                    }
                }
                AdbProtocol.A_CLSE -> {
                    val localId = msg.arg0
                    val process = activeProcesses.remove(localId)
                    process?.destroy()
                }
            }
        }
    }

    private fun startShellProcess(remoteId: Int, cmd: String) {
        val localId = localIdCounter.getAndIncrement()
        try {
            // Acknowledge the OPEN
            writeMessage(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))

            val commandArray = if (cmd.isEmpty()) arrayOf("sh") else arrayOf("sh", "-c", cmd)
            val process = Shizuku.newProcess(commandArray, null, null)
            activeProcesses[localId] = process

            // Read stdout
            Thread {
                val buf = ByteArray(AdbProtocol.A_MAXDATA)
                try {
                    while (true) {
                        val r = process.inputStream.read(buf)
                        if (r <= 0) break
                        writeMessage(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, buf.copyOf(r)))
                        // Wait for OKAY from client (we ignore it for simplicity, but strictly we should wait)
                        // readMessage() is blocking on main loop, so we can't read OKAY here!
                        // Actually, ADB requires us to wait for OKAY before sending another WRTE.
                        // We will just blast WRTEs for now (fake adb clients might not care).
                    }
                } catch (e: Exception) {}

                // Read stderr (optional, usually multiplexed in ADB but we can just blast it)
                try {
                    while (true) {
                        val r = process.errorStream.read(buf)
                        if (r <= 0) break
                        writeMessage(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, buf.copyOf(r)))
                    }
                } catch (e: Exception) {}

                // waitFor() on a ShizukuProcess can throw (OEM builds where exitValue() raises
                // IllegalArgumentException, or a binder death mid-command). Never let that kill
                // this thread: the client is still owed the A_CLSE close signal and the process
                // handle must be released, otherwise the fake-adb shell session hangs.
                try { process.waitFor() } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "waitFor failed for shell process; closing anyway")
                }
                process.destroy()
                activeProcesses.remove(localId)
                writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)))
            }.start()

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start shell process")
            writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)))
        }
    }

    /** Returns the authorized public key whose private key produced [signature] over [token],
     *  or null when no stored key verifies it. A non-null result is proof the peer holds the
     *  corresponding private key. */
    private fun findVerifiedKey(token: ByteArray, signature: ByteArray): String? {
        val allKeys = prefs.getStringSet(PREF_KEYS, emptySet()) ?: emptySet()
        for (keyStr in allKeys) {
            try {
                val pubKey = parseAdbPublicKey(keyStr)

                // ADB's signature is a raw RSA encryption of a PKCS#1 padded token.
                // It does NOT hash the token before signing. It treats the token as the hash.
                val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pubKey)
                val decrypted = cipher.doFinal(signature)

                // Check if the decrypted payload ends with our exact token
                var match = true
                val offset = decrypted.size - token.size
                if (offset < 0) continue

                for (i in token.indices) {
                    if (decrypted[offset + i] != token[i]) {
                        match = false
                        break
                    }
                }
                if (match) return keyStr
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error verifying signature")
            }
        }
        return null
    }

    private fun isKeyAuthorized(pubKeyStr: String): Boolean {
        val allKeys = prefs.getStringSet(PREF_KEYS, emptySet()) ?: emptySet()
        return allKeys.contains(pubKeyStr)
    }

    private fun authorizeKey(pubKeyStr: String) {
        val allKeys = prefs.getStringSet(PREF_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        allKeys.add(pubKeyStr)
        prefs.edit().putStringSet(PREF_KEYS, allKeys).apply()
    }

    /** Look up the loopback UID bound to [pubKeyStr], or null when the key predates binding. */
    private fun boundUidFor(pubKeyStr: String): Int? {
        val raw = prefs.getString(PREF_KEY_UIDS, null) ?: return null
        raw.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0 && line.substring(tab + 1) == pubKeyStr) {
                return line.substring(0, tab).toIntOrNull()
            }
        }
        return null
    }

    /** Bind [pubKeyStr] to [uid], replacing any previous binding for that key. */
    private fun bindKeyToUid(pubKeyStr: String, uid: Int) {
        val raw = prefs.getString(PREF_KEY_UIDS, null)
        val kept = raw?.lineSequence()?.filter { line ->
            val tab = line.indexOf('\t')
            tab <= 0 || line.substring(tab + 1) != pubKeyStr
        }?.filter { it.isNotBlank() }?.joinToString("\n").orEmpty()
        val updated = if (kept.isEmpty()) "$uid\t$pubKeyStr" else "$kept\n$uid\t$pubKeyStr"
        prefs.edit().putString(PREF_KEY_UIDS, updated).apply()
    }

    /**
     * Resolve the UID of the app that dialed us over the loopback TCP connection.
     *
     * TCP sockets have no SO_PEERCRED (that is AF_UNIX only), so we read the world-readable
     * /proc/net/tcp{,6} tables. The accepted socket's remote port is the client's source
     * port; the client's own socket row has `local = 127.0.0.1:<clientPort>` and
     * `rem = 127.0.0.1:<localPort>`, and that row's `uid` column is the client's euid. Returns
     * null when the table cannot be read or the row is absent (race/OEM variant) — callers
     * then fall back to requiring cryptographic proof rather than trusting the peer.
     */
    private fun resolvePeerUid(): Int? {
        val clientPort = socket.port
        val serverPort = socket.localPort
        if (clientPort <= 0 || serverPort <= 0) return null
        for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val uid = try {
                parsePeerUidFromProcTcp(path, clientPort, serverPort)
            } catch (e: Exception) {
                null
            }
            if (uid != null) return uid
        }
        return null
    }

    private fun parsePeerUidFromProcTcp(path: String, clientPort: Int, serverPort: Int): Int? {
        val file = java.io.File(path)
        if (!file.canRead()) return null
        val lines = file.readLines()
        if (lines.isEmpty()) return null
        val headerCols = lines[0].trim().split(Regex("\\s+"))
        val uidIdx = headerCols.indexOf("uid")
        if (uidIdx < 0) return null
        // Ports in /proc/net/tcp are uppercase hex without leading zeros; compare case-insensitively.
        val wantLocalPort = clientPort.toString(16)
        val wantRemPort = serverPort.toString(16)
        for (i in 1 until lines.size) {
            val cols = lines[i].trim().split(Regex("\\s+"))
            if (cols.size <= uidIdx || cols.size < 3) continue
            val localPort = cols[1].substringAfter(':', "")
            val remPort = cols[2].substringAfter(':', "")
            if (localPort.equals(wantLocalPort, ignoreCase = true) &&
                remPort.equals(wantRemPort, ignoreCase = true)
            ) {
                return cols[uidIdx].toIntOrNull()
            }
        }
        return null
    }

    private fun promptUserForPairingBlocking(pubKeyStr: String): Boolean {
        Timber.tag(TAG).i("Prompting user for pairing...")
        // Start an activity to ask the user. We must block until the result is available.
        // For now, let's implement a simple intent to FakeAdbPairingActivity and use a static lock/callback
        return FakeAdbPairingActivity.requestPairingSync(context, pubKeyStr)
    }

    private fun readMessage(): AdbMessage {
        val header = ByteArray(24)
        inputStream.readFully(header)
        val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val data_length = buf.int
        val data_crc32 = buf.int
        val magic = buf.int

        var data: ByteArray? = null
        if (data_length > 0) {
            // data_length comes straight off the wire before CRC/magic are verified, so a
            // corrupt or hostile peer can claim an arbitrary size here; without a cap this
            // allocates unboundedly and OOMs the process (Sentry SHIZUKUPLUS-8F, 825MB alloc).
            if (data_length > MAX_ADB_PAYLOAD_SIZE) {
                throw java.io.IOException("ADB message data_length $data_length exceeds max $MAX_ADB_PAYLOAD_SIZE")
            }
            data = ByteArray(data_length)
            inputStream.readFully(data)
        }
        return AdbMessage(command, arg0, arg1, data_length, data_crc32, magic, data)
    }

    private fun writeMessage(msg: AdbMessage) {
        synchronized(outputStream) {
            outputStream.write(msg.toByteArray())
            outputStream.flush()
        }
    }
}
