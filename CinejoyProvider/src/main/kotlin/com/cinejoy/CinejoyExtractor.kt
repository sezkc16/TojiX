package com.cinejoy

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves Cinejoy streams through the site's own API (api.wing.st).
 *
 * How the site seals requests: the player ships a public WASM module
 * (crush.wasm) which seals a small JSON payload; the sealed body is
 * POSTed to /g and the reply is AES-256-GCM. v1 ran that WASM inside
 * the plugin with Chicory — but Zeus on iOS runs .cs3 plugins in an
 * embedded OpenJDK Zero VM (no JIT), where one WASM seal takes
 * ~100 s, far past loadLinks' 30 s budget, so every title came back
 * "No streams found".
 *
 * v2 ports the seal to pure Kotlin/JCA. The algorithm was recovered
 * from crush.wasm (disassembly + fixed-nonce reproduction, verified
 * byte-for-byte against the WASM on two different nonces):
 *
 *  1. ephemeral private = SHA-256("lumen-gate-v2|ephemeral|" ||
 *     nonce[0..32] || 0x00000000); ephemeral public = priv * G (P-256).
 *  2. shared secret = ECDH(priv, server static public key below)
 *     — the x-coordinate, 32 bytes.
 *  3. responseKey = HKDF-SHA256(salt = ephemeral public,
 *     IKM = shared secret, info = "lumen-gate-v2|s2c", 32 bytes).
 *     bodyKey    = same HKDF with info "lumen-gate-v2|c2s".
 *  4. body = AES-256-GCM(bodyKey, IV = nonce[32..44],
 *     AAD = "lumen-gate-v2" || 0x00 0x01 0x02 || ephemeral public)
 *     over the JSON; sealed body = 0x02 0x02 || ephemeral public ||
 *     IV || ciphertext+tag.
 *
 * The P-256 scalar multiplications are plain BigInteger math on
 * purpose: no EC provider is needed, so the seal works on any JVM,
 * including the Zero VM. No WASM, no Chicory, no third-party enc/dec
 * service. The server's static public key is embedded below (it is
 * public — the site ships it inside crush.wasm). If the site ever
 * rotates that key, the seal breaks and the key has to be
 * re-extracted from a fresh crush.wasm.
 */
object CinejoyExtractor {
    // Layout of the sealed output buffer.
    private const val IW = 44 // random nonce length fed to the sealer
    private const val Y = 32 // response key length
    private const val RW = 1 // key-id length
    private const val VW = 65 // ephemeral public key length
    private const val WW = Y + RW + VW // 98, header size before the body
    private const val CW = 12 // GCM IV length
    private const val NW = 16 // GCM tag length
    private const val HW = "lumen-gate-v2" // AAD header the site expects
    private const val KEY_ID = 2

    private const val DEFAULT_API_HOST = "https://api.wing.st"
    private val CANDIDATE_API_HOSTS = listOf(
        "https://api.wing.st",
        "https://api.shegu.st"
    )
    private var activeApiHost: String = DEFAULT_API_HOST

    // Server's static P-256 public key (uncompressed, 65 bytes),
    // extracted from crush.wasm's data section (address 1052336).
    private const val SERVER_PUBLIC_KEY_HEX =
        "045c88a0ae33c683a4872590b04b22f4774a4fe1c6ecf74785c74a919a71207ca94f9e6b5a7854c3aa3b44ee46bc444fea694b9f23bcd80f864ecbef48c1ef6e14"

    // P-256 (secp256r1) domain parameters.
    private val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val P256_A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val P256_GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val P256_GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val P256_G = P256_GX to P256_GY

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // HKDF-SHA256 (RFC 5869). Only one expand block is ever needed
    // here because the output is always 32 bytes.
    private fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val t1 = hmacSha256(prk, info + byteArrayOf(1))
        return Arrays.copyOf(t1, length)
    }

    // P-256 point addition (affine coordinates; null = infinity).
    private fun ecAdd(
        p: Pair<BigInteger, BigInteger>?,
        q: Pair<BigInteger, BigInteger>?
    ): Pair<BigInteger, BigInteger>? {
        if (p == null) return q
        if (q == null) return p
        val (x1, y1) = p
        val (x2, y2) = q
        if (x1 == x2) {
            if ((y1 + y2).mod(P256_P) == BigInteger.ZERO) return null
            // Point doubling: m = (3*x1^2 + a) / (2*y1)
            val m = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(P256_A)
                .multiply(y1.multiply(BigInteger.TWO).mod(P256_P).modInverse(P256_P))
                .mod(P256_P)
            val x3 = m.multiply(m).subtract(x1).subtract(x2).mod(P256_P)
            val y3 = m.multiply(x1.subtract(x3)).subtract(y1).mod(P256_P)
            return x3 to y3
        }
        val m = y2.subtract(y1)
            .multiply(x2.subtract(x1).mod(P256_P).modInverse(P256_P))
            .mod(P256_P)
        val x3 = m.multiply(m).subtract(x1).subtract(x2).mod(P256_P)
        val y3 = m.multiply(x1.subtract(x3)).subtract(y1).mod(P256_P)
        return x3 to y3
    }

    // P-256 scalar multiplication (double-and-add).
    private fun ecMultiply(k: BigInteger, point: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        var result: Pair<BigInteger, BigInteger>? = null
        var addend = point
        var scalar = k
        while (scalar != BigInteger.ZERO) {
            if (scalar.testBit(0)) result = ecAdd(result, addend)
            addend = ecAdd(addend, addend)!!
            scalar = scalar.shiftRight(1)
        }
        return result!!
    }

    private fun toFixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b.copyOfRange(b.size - 32, b.size)
        }
    }

    private fun encodeUncompressed(p: Pair<BigInteger, BigInteger>): ByteArray =
        byteArrayOf(0x04) + toFixed32(p.first) + toFixed32(p.second)

    private fun decodeUncompressed(bytes: ByteArray): Pair<BigInteger, BigInteger> {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        return BigInteger(1, bytes.copyOfRange(1, 33)) to
            BigInteger(1, bytes.copyOfRange(33, 65))
    }

    data class SealResult(
        val responseKey: ByteArray,
        val keyId: Int,
        val ephemeralPublic: ByteArray,
        val body: ByteArray
    )

    private fun makeAad(keyId: Int, ephemeralPublic: ByteArray): ByteArray {
        val header = HW.toByteArray(StandardCharsets.UTF_8)
        val aad = ByteArray(header.size + 3 + ephemeralPublic.size)
        System.arraycopy(header, 0, aad, 0, header.size)
        aad[header.size] = 0
        aad[header.size + 1] = 2
        aad[header.size + 2] = keyId.toByte()
        System.arraycopy(ephemeralPublic, 0, aad, header.size + 3, ephemeralPublic.size)
        return aad
    }

    // Seal a request with the pure-Kotlin port of the site's sealer.
    // The layout matches the old WASM output exactly:
    // [responseKey 32][keyId 1][ephemeralPublic 65][body ...].
    fun seal(path: String, payloadJson: String?): SealResult {
        val fullJson = "{\"path\":\"$path\",\"payload\":${payloadJson ?: "null"}}"
        val jsonBytes = fullJson.toByteArray(StandardCharsets.UTF_8)
        val nonce = ByteArray(IW).apply { SecureRandom().nextBytes(this) }
        return sealWithNonce(jsonBytes, nonce)
    }

    // Same seal, but with a caller-supplied nonce. Used by tests to
    // reproduce a seal byte-for-byte against the reference WASM.
    fun sealWithNonce(jsonBytes: ByteArray, nonce: ByteArray): SealResult {
        require(nonce.size == IW) { "nonce must be $IW bytes" }
        val hw = HW.toByteArray(StandardCharsets.UTF_8)

        // 1. Ephemeral key pair, derived from the nonce.
        val ephInput = hw + "|ephemeral|".toByteArray(StandardCharsets.UTF_8) +
            nonce.copyOfRange(0, 32) + byteArrayOf(0, 0, 0, 0)
        val ephPrivate = BigInteger(1, sha256(ephInput))
        val ephemeralPublic = encodeUncompressed(ecMultiply(ephPrivate, P256_G))

        // 2. ECDH shared secret (x-coordinate, 32 bytes).
        val serverPoint = decodeUncompressed(hexToBytes(SERVER_PUBLIC_KEY_HEX))
        val sharedSecret = toFixed32(ecMultiply(ephPrivate, serverPoint).first)

        // 3. HKDF keys: s2c decrypts the /g reply, c2s seals the request.
        val responseKey = hkdfSha256(
            ephemeralPublic, sharedSecret,
            "lumen-gate-v2|s2c".toByteArray(StandardCharsets.UTF_8)
        )
        val bodyKey = hkdfSha256(
            ephemeralPublic, sharedSecret,
            "lumen-gate-v2|c2s".toByteArray(StandardCharsets.UTF_8)
        )

        // 4. AES-256-GCM over the JSON. Request AAD is HW || 0x00 0x01
        // 0x02 (direction c2s) || ephemeral public — the reply AAD in
        // makeAad() uses 0x02 (direction s2c) instead.
        val iv = nonce.copyOfRange(32, 44)
        val aad = hw + byteArrayOf(0, 1, KEY_ID.toByte()) + ephemeralPublic
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(bodyKey, "AES"),
            GCMParameterSpec(NW * 8, iv)
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(jsonBytes)

        val body = byteArrayOf(0x02, KEY_ID.toByte()) + ephemeralPublic + iv + ciphertext
        return SealResult(responseKey, KEY_ID, ephemeralPublic, body)
    }

    fun decrypt(encData: ByteArray, seal: SealResult): String {
        val iv = Arrays.copyOfRange(encData, 0, CW)
        val ciphertextWithTag = Arrays.copyOfRange(encData, CW, encData.size)
        val aad = makeAad(seal.keyId, seal.ephemeralPublic)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(seal.responseKey, "AES")
        val gcmSpec = GCMParameterSpec(NW * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        val decrypted = cipher.doFinal(ciphertextWithTag)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    // Ask the API which servers are up right now (Nebula, Lisbon,
    // Athens, Solara, ...). Falls back to a static list if the call fails.
    suspend fun getActiveServers(domain: String = "https://cinejoy.pk"): List<String> {
        val cleanDomain = domain.removeSuffix("/")
        val apiHosts = listOf(activeApiHost) + CANDIDATE_API_HOSTS.filter { it != activeApiHost }
        for (host in apiHosts) {
            try {
                val res = app.get(
                    "$host/servers",
                    headers = mapOf(
                        "Origin" to cleanDomain,
                        "Referer" to "$cleanDomain/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    ),
                    timeout = 4
                )
                if (res.isSuccessful) {
                    val json = JSONObject(res.text)
                    val arr = json.optJSONArray("servers")
                    if (arr != null && arr.length() > 0) {
                        activeApiHost = host
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val serverObj = arr.optJSONObject(i) ?: continue
                            val serverName = serverObj.optString("name").lowercase()
                            val serverStatus = serverObj.optString("status")
                            if (serverName.isNotBlank() && (serverStatus.isBlank() || serverStatus == "ok")) {
                                list.add(serverName)
                            }
                        }
                        if (list.isNotEmpty()) return list
                    }
                }
            } catch (_: Exception) {}
        }
        return listOf("lisbon", "nebula", "solara", "athens")
    }

    // Resolve one title on one server. Returns the decrypted JSON:
    // {"data": {"stream": [{"type": "hls", "playlist": "...",
    // "captions": [...]}, {"type": "file", "qualities": {...}}]}}.
    suspend fun queryServer(
        server: String,
        isTv: Boolean,
        tmdbId: String,
        season: Int = 1,
        episode: Int = 1,
        domain: String = "https://cinejoy.pk"
    ): JSONObject? {
        return try {
            val endpoint = if (isTv) "series" else "movie"
            val payload = if (isTv) {
                "{\"tmdb\":\"$tmdbId\",\"season\":\"$season\",\"episode\":\"$episode\"}"
            } else {
                "{\"tmdb\":\"$tmdbId\"}"
            }

            val sealed = seal("/$server/$endpoint", payload)
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestBody = sealed.body.toRequestBody(mediaType)

            val cleanDomain = domain.removeSuffix("/")
            val apiHosts = listOf(activeApiHost) + CANDIDATE_API_HOSTS.filter { it != activeApiHost }

            for (host in apiHosts) {
                try {
                    val resp = app.post(
                        "$host/g",
                        requestBody = requestBody,
                        headers = mapOf(
                            "Content-Type" to "application/octet-stream",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            "Origin" to cleanDomain,
                            "Referer" to "$cleanDomain/"
                        ),
                        timeout = 10
                    )

                    if (resp.code == 200) {
                        activeApiHost = host
                        val decrypted = decrypt(resp.body.bytes(), sealed)
                        return JSONObject(decrypted)
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
