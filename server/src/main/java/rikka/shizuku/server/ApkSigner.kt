package rikka.shizuku.server

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Base64

/**
 * Signs an APK with V1 (JAR) + V2 signatures using an ephemeral RSA-2048 key.
 * No external tools required — pure JVM crypto from ART's built-in providers.
 *
 * Streamed: the input APK (e.g. a 2 GB game split) is copied entry-by-entry off disk,
 * and the V2 signing block is spliced into the file with a RandomAccessFile tail rewrite.
 * The whole APK is NEVER materialized in a ByteArray — a 2 GB readBytes() on
 * com.tencent.tmgp.pubgmhd's arm64 split crashed the server with OOM.
 *
 * Usage:
 *   ApkSigner.signFile(srcApk, outApk, patchManifest = true)
 */
internal object ApkSigner {

    // ── Key pair (generated once per process lifetime) ─────────────────────

    private val keyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private val privateKey: PrivateKey get() = keyPair.private
    private val publicKey: PublicKey  get() = keyPair.public

    // ── DER encoding primitives ─────────────────────────────────────────────

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), (n and 0xFF).toByte())
    }

    private fun tlv(tag: Int, data: ByteArray): ByteArray {
        val len = derLen(data.size)
        val out = ByteArray(1 + len.size + data.size)
        out[0] = tag.toByte()
        System.arraycopy(len, 0, out, 1, len.size)
        System.arraycopy(data, 0, out, 1 + len.size, data.size)
        return out
    }

    private fun seq(vararg items: ByteArray)  = tlv(0x30, concat(*items))
    private fun set(vararg items: ByteArray)  = tlv(0x31, concat(*items))
    private fun oid(vararg arcs: Int): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            val v = arcs[i]
            if (v < 0x80) { body.write(v) }
            else {
                val bits = mutableListOf<Int>()
                var rem = v
                while (rem > 0) { bits.add(0, rem and 0x7F); rem = rem ushr 7 }
                for (j in 0 until bits.size - 1) body.write(bits[j] or 0x80)
                body.write(bits.last())
            }
        }
        return tlv(0x06, body.toByteArray())
    }
    private fun int(data: ByteArray)   = tlv(0x02, data)
    private fun oct(data: ByteArray)   = tlv(0x04, data)
    private fun bits(data: ByteArray)  = tlv(0x03, byteArrayOf(0) + data)
    private fun utf8(s: String)        = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun ctx(n: Int, d: ByteArray) = tlv(0xA0 or n, d)
    private fun utcTime(s: String)     = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    private fun nullDer()              = byteArrayOf(0x05, 0x00)
    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }

    // Encode a positive BigInteger-style byte array with a leading 0x00 if high bit set
    private fun posInt(b: ByteArray) = int(if (b[0].toInt() and 0x80 != 0) byteArrayOf(0) + b else b)

    // ── X.509 self-signed certificate (DER) ─────────────────────────────────

    private val certDer: ByteArray by lazy { buildCert() }

    private fun buildCert(): ByteArray {
        val rsaPub = publicKey.encoded  // SubjectPublicKeyInfo DER from JVM

        // OIDs
        val oidSha256WithRsa = oid(1, 2, 840, 113549, 1, 1, 11)
        val oidCn            = oid(2, 5, 4, 3)
        val sigAlg = seq(oidSha256WithRsa, nullDer())

        val name = seq(set(seq(oidCn, utf8("ShizukuX"))))
        val validity = seq(utcTime("200101000000Z"), utcTime("491231235959Z"))
        val spki = ByteArray(rsaPub.size).also { System.arraycopy(rsaPub, 0, it, 0, rsaPub.size) }

        // Serial number: 1
        val serial = posInt(byteArrayOf(0x01))

        val tbsCert = seq(
            ctx(0, byteArrayOf(0x02)),  // version = v3
            serial,
            sigAlg,
            name,           // issuer
            validity,
            name,           // subject
            spki
        )

        // Sign tbsCert
        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbsCert)
        }.sign()

        return seq(tbsCert, sigAlg, bits(rawSig))
    }

    // ── V1 Signing (JAR / MANIFEST.MF + CERT.SF + CERT.RSA), streaming ──────

    private fun sha256b64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data))

    /**
     * Streams the whole APK from [src] to [dst] as a freshly deflated ZIP, strips old
     * META-INF signature entries, accumulates per-entry SHA-256 digests while streaming,
     * then writes new MANIFEST.MF / CERT.SF / CERT.RSA.
     * If [patchManifest] is true, the small AndroidManifest.xml entry (<100 KB) is buffered,
     * passed through [ApkBinaryXmlPatcher.patch] (debuggable=true) and written back patched.
     */
    private fun signV1Stream(src: File, dst: File, patchManifest: Boolean) {
        val manifest = StringBuilder()
        manifest.append("Manifest-Version: 1.0\r\nCreated-By: 1.0 (ShizukuX)\r\n\r\n")
        // (entry name, raw manifest-section bytes) pairs, kept for CERT.SF building.
        val sections = mutableListOf<Pair<String, ByteArray>>()
        var entries = 0

        ZipInputStream(src.inputStream().buffered()).use { zis ->
            ZipOutputStream(dst.outputStream().buffered()).use { zos ->
                zos.setMethod(ZipOutputStream.DEFLATED)
                val buf = ByteArray(64 * 1024)
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val upper = name.uppercase()
                    val isOldSig = name.startsWith("META-INF/") && (
                        upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                        upper.endsWith(".DSA") || upper.endsWith(".EC") ||
                        name == "META-INF/MANIFEST.MF"
                    )
                    if (!isOldSig) {
                        val md = MessageDigest.getInstance("SHA-256")
                        zos.putNextEntry(ZipEntry(name))
                        if (patchManifest && name == "AndroidManifest.xml") {
                            // Manifest is tiny: buffer it, patch, digest the patched bytes.
                            val raw = zis.readBytes()
                            val patched = try {
                                ApkBinaryXmlPatcher.patch(raw)
                            } catch (e: Exception) {
                                Log.e("ShizukuX-Sign", "manifest patch failed name=$name", e)
                                throw e
                            }
                            md.update(patched)
                            zos.write(patched)
                        } else {
                            var n: Int
                            while (zis.read(buf).also { n = it } > 0) {
                                md.update(buf, 0, n)
                                zos.write(buf, 0, n)
                            }
                        }
                        zos.closeEntry()
                        val section = "Name: $name\r\nSHA-256-Digest: ${sha256b64(md.digest())}\r\n\r\n"
                        manifest.append(section)
                        sections.add(name to section.toByteArray(Charsets.UTF_8))
                        entries++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                // CERT.SF
                val mfBytes = manifest.toString().toByteArray(Charsets.UTF_8)
                val sf = StringBuilder()
                sf.append("Signature-Version: 1.0\r\n")
                sf.append("SHA-256-Digest-Manifest: ${sha256b64(mfBytes)}\r\n")
                sf.append("Created-By: 1.0 (ShizukuX)\r\n\r\n")
                for ((name, sectionBytes) in sections) {
                    sf.append("Name: $name\r\n")
                    sf.append("SHA-256-Digest: ${sha256b64(sectionBytes)}\r\n\r\n")
                }
                val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)
                val certRsa = buildPkcs7(sfBytes)

                fun addMeta(name: String, data: ByteArray) {
                    val e = ZipEntry(name); e.method = ZipEntry.DEFLATED
                    zos.putNextEntry(e); zos.write(data); zos.closeEntry()
                }
                addMeta("META-INF/MANIFEST.MF", mfBytes)
                addMeta("META-INF/CERT.SF", sfBytes)
                addMeta("META-INF/CERT.RSA", certRsa)
                zos.finish()
            }
        }
        Log.i("ShizukuX-Sign", "V1 done entries=$entries")
    }

    // PKCS#7 SignedData (detached, no content, SHA-256, RSA)
    private fun buildPkcs7(toBeSigned: ByteArray): ByteArray {
        Log.i("ShizukuX-Sign", "buildPkcs7 size=${toBeSigned.size}")
        val oidData           = oid(1, 2, 840, 113549, 1, 7, 1)
        val oidSignedData     = oid(1, 2, 840, 113549, 1, 7, 2)
        val oidSha256         = oid(2, 16, 840, 1, 101, 3, 4, 2, 1)
        val oidRsa            = oid(1, 2, 840, 113549, 1, 1, 1)
        val oidSha256WithRsa  = oid(1, 2, 840, 113549, 1, 1, 11)

        val certBytes = certDer
        val digest = MessageDigest.getInstance("SHA-256").digest(toBeSigned)

        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(toBeSigned)
        }.sign()

        // IssuerAndSerialNumber
        val issuer = seq(set(seq(oid(2, 5, 4, 3), utf8("ShizukuX"))))
        val serial = posInt(byteArrayOf(0x01))
        val issuerAndSerial = seq(issuer, serial)

        val digestAlgId    = seq(oidSha256, nullDer())
        val encryptAlgId   = seq(oidRsa,  nullDer())

        val signerInfo = seq(
            int(byteArrayOf(0x01)),   // version
            issuerAndSerial,
            digestAlgId,
            encryptAlgId,
            oct(rawSig)
        )

        val digestAlgorithms = set(digestAlgId)

        val encapContentInfo = seq(oidData)  // detached: no content

        val signedData = seq(
            int(byteArrayOf(0x01)),            // version
            digestAlgorithms,
            encapContentInfo,
            tlv(0xA0, certBytes),              // [0] certificates
            set(signerInfo)                    // signerInfos
        )

        return seq(oidSignedData, ctx(0, signedData))
    }

    // ── V2 APK Signature Block (file-based, chunked digests) ─────────────────

    private fun Int.le(): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
    private fun Long.le(): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(this).array()

    private fun lengthPrefixed(data: ByteArray): ByteArray = data.size.le() + data
    private fun lengthPrefixed64(data: ByteArray): ByteArray = data.size.toLong().le() + data

    private const val CHUNK_SIZE = 1024 * 1024  // 1 MB

    /** Stream [file][offset, offset+length) and return one SHA-256 chunk digest per 1 MB chunk. */
    private fun digestSectionFile(file: File, offset: Long, length: Long): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        if (length <= 0) return chunks
        file.inputStream().use { fi ->
            var skipped = 0L
            while (skipped < offset) {
                val s = fi.skip(offset - skipped)
                if (s <= 0) break
                skipped += s
            }
            val buf = ByteArray(CHUNK_SIZE)
            var remaining = length
            while (remaining > 0) {
                val want = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                var read = 0
                while (read < want) {
                    val n = fi.read(buf, read, want - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) break
                val md = MessageDigest.getInstance("SHA-256")
                md.update(0xa5.toByte())
                md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(read).array())
                md.update(buf, 0, read)
                chunks.add(md.digest())
                remaining -= read.toLong()
            }
        }
        return chunks
    }

    private fun digestSectionBytes(data: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < data.size) {
            val chunkLen = minOf(CHUNK_SIZE, data.size - pos)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(0xa5.toByte())
            md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkLen).array())
            md.update(data, pos, chunkLen)
            chunks.add(md.digest())
            pos += chunkLen
        }
        return chunks
    }

    private fun topLevelDigest(chunkDigests: List<ByteArray>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x5a.toByte())
        md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkDigests.size).array())
        for (chunk in chunkDigests) md.update(chunk)
        return md.digest()
    }

    /** Locates the EOCD record in [file] by scanning its tail; returns its offset. */
    private fun locateEocd(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val scanLen = minOf(len, 65557L).toInt()
            raf.seek(len - scanLen)
            val tail = ByteArray(scanLen)
            raf.readFully(tail)
            for (i in (scanLen - 22) downTo 0) {
                if (ByteBuffer.wrap(tail, i, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x06054b50) {
                    return len - scanLen + i
                }
            }
        }
        error("No EOCD found in signed APK")
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    private fun patchEocdOffset(eocd: ByteArray, newCdOffset: Long): ByteArray {
        val result = eocd.copyOf()
        ByteBuffer.wrap(result, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newCdOffset.toInt())
        return result
    }

    private fun buildV2Block(sec1: List<ByteArray>, sec2: List<ByteArray>, sec3: List<ByteArray>): ByteArray {
        val apkDigest = topLevelDigest(sec1 + sec2 + sec3)

        // Signed data structure for V2
        val algoId     = 0x0103.le()   // RSASSA-PKCS1-v1_5 with SHA2-256
        val digestPair = lengthPrefixed(algoId + lengthPrefixed(apkDigest))
        val digests    = lengthPrefixed(digestPair)
        val certs      = lengthPrefixed(lengthPrefixed(certDer))
        val attributes = lengthPrefixed(ByteArray(0))  // empty
        val signedData = lengthPrefixed(digests + certs + attributes)

        // Signature
        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signedData)
        }.sign()
        val sigPair  = lengthPrefixed(algoId + lengthPrefixed(rawSig))
        val sigs     = lengthPrefixed(sigPair)

        // Public key (SubjectPublicKeyInfo DER)
        val pubKeyLp  = lengthPrefixed(publicKey.encoded)

        val signer      = lengthPrefixed(signedData + sigs + pubKeyLp)
        val signerList  = lengthPrefixed(signer)

        // ID-value pair for V2 block (ID = 0x7109871a)
        val idValue  = lengthPrefixed64(0x7109871a.le() + signerList)

        // APK Signing Block: [size64][id-values][size64][magic]
        val blockSize  = (idValue.size + 8 + 16).toLong()
        return blockSize.le() + idValue + blockSize.le() + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Strip old signatures and add V1+V2 signatures, streaming off disk.
     * [src] and [dst] are on-disk APK files; no whole-APK byte array is ever allocated.
     * If [patchManifest] is true, the base APK's AndroidManifest.xml entry is patched
     * to debuggable=true before signing.
     */
    fun signFile(src: File, dst: File, patchManifest: Boolean): Boolean = try {
        signV1Stream(src, dst, patchManifest)

        // V2: locate the central directory, digest the file in 1 MB chunks, splice the block in.
        val eocdOffset = locateEocd(dst)
        Log.i("ShizukuX-Sign", "eocdOffset=$eocdOffset")
        val cdOffset: Long
        val cdBytes: ByteArray
        val eocdBytes: ByteArray
        RandomAccessFile(dst, "r").use { raf ->
            cdOffset = ByteBuffer.wrap(readAt(raf, eocdOffset + 16, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            val cdSize = ByteBuffer.wrap(readAt(raf, eocdOffset + 12, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            Log.i("ShizukuX-Sign", "eocd=$eocdOffset cdOffset=$cdOffset cdSize=$cdSize")
            cdBytes = readAt(raf, cdOffset, cdSize.toInt())
            eocdBytes = readAt(raf, eocdOffset, (dst.length() - eocdOffset).toInt())
        }

        // sec1 covers [0, blockStart): the signing block is spliced immediately BEFORE the
        // central directory, so blockStart == the ORIGINAL cdOffset. The verifier hashes the
        // file prefix the same way regardless of the block being inserted in between.
        val sec1 = digestSectionFile(dst, 0L, cdOffset)

        // Two-pass sec3: the on-disk EOCD's cdOffset field must equal newCdOffset, but
        // newCdOffset depends on the block size, which depends on sec3 itself. One iteration
        // suffices — every length prefix in the block is a fixed-width 4-byte LE value and every
        // embedded digest is a fixed 32-byte SHA-256, so the block size is invariant to its own
        // content:
        //   pass0: size the block against a placeholder EOCD (cdOffset = original)
        val pass0 = buildV2Block(sec1, digestSectionBytes(cdBytes),
            digestSectionBytes(patchEocdOffset(eocdBytes, cdOffset)))
        val newCdOffset = cdOffset + pass0.size
        //   final: digest the EOCD exactly as it will sit on disk (cdOffset = newCdOffset)
        Log.i("ShizukuX-Sign", "buildV2Block begin")
        val sec3 = digestSectionBytes(patchEocdOffset(eocdBytes, newCdOffset))
        val v2Block = buildV2Block(sec1, digestSectionBytes(cdBytes), sec3)
        Log.i("ShizukuX-Sign", "buildV2Block done size=${v2Block.size}")
        Log.i("ShizukuX-Sign", "splice newCd=$newCdOffset block=${v2Block.size}")
        val eocdPatched = patchEocdOffset(eocdBytes, newCdOffset)

        RandomAccessFile(dst, "rw").use { rafw ->
            rafw.setLength(cdOffset)
            rafw.seek(cdOffset)
            rafw.write(v2Block)
            rafw.write(cdBytes)
            rafw.write(eocdPatched)
        }
        true
    } catch (e: Exception) {
        Log.e("ShizukuX-Sign", "signFile failed for ${src.name}", e)
        dst.delete()
        false
    }
}
