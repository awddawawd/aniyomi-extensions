package eu.kanade.tachiyomi.lib.filemoonextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(private val client: OkHttpClient) {

    private val playlistUtils = PlaylistUtils(client)
    private val json = Json { ignoreUnknownKeys = true }

    fun videosFromUrl(
        url: String,
        prefix: String = "Filemoon - ",
        headers: Headers? = null
    ): List<Video> {
        return try {
            val embedPattern = Regex("^(https?://[^/]+)/e/([^/]+)$")
            val (baseUrl, videoCode) = embedPattern.find(url)?.destructured
                ?: return emptyList()

            val baseHeadersBuilder = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0")
                .set("Accept", "*/*")
                .set("Content-Type", "application/json")
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/e/$videoCode")
            if (headers != null) {
                for ((name, value) in headers) {
                    baseHeadersBuilder.set(name, value)
                }
            }
            val reqHeaders = baseHeadersBuilder.build()

            // 1. Load embed page and locate bundle URLs
            val embedPage = client.newCall(GET(url, reqHeaders)).execute().body?.string() ?: return emptyList()
            val mainJsPath = Regex("""src="(/assets/.*?\.js)"""").find(embedPage)?.groupValues?.get(1)
                ?: return emptyList()
            val assetUrl = url.toHttpUrl().resolve(mainJsPath)?.toString() ?: return emptyList()

            val mainJs = client.newCall(GET(assetUrl, reqHeaders)).execute().body?.string() ?: return emptyList()
            val bundleName = Regex("""videoPagesBundle-[a-zA-Z0-9_\-]+\.js""").find(mainJs)?.value
                ?: return emptyList()
            val bundleUrl = assetUrl.toHttpUrl().resolve(bundleName)?.toString() ?: return emptyList()

            // 2. Challenge
            val challengeRes = client.newCall(
                POST(
                    "$baseUrl/api/videos/access/challenge",
                    reqHeaders,
                    "{}".toRequestBody("application/json".toMediaType())
                )
            ).execute()
            val challengeJson = json.parseToJsonElement(challengeRes.body?.string() ?: return emptyList()).jsonObject
            val challengeId = challengeJson["challenge_id"]?.jsonPrimitive?.content ?: return emptyList()
            val nonce = challengeJson["nonce"]?.jsonPrimitive?.content ?: return emptyList()

            // 3. Generate ECDSA key and sign nonce
            val keyPairGen = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val keyPair = keyPairGen.generateKeyPair()
            val privateKey = keyPair.private
            val publicKey = keyPair.public as ECPublicKey

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(nonce.toByteArray())
            val derSig = signature.sign()

            // Convert DER to raw r||s (IEEE P1363, 64 bytes)
            val rLen = derSig[3].toInt() and 0xFF
            val r = derSig.copyOfRange(4, 4 + rLen)
            val sLen = derSig[4 + rLen + 1].toInt() and 0xFF
            val s = derSig.copyOfRange(4 + rLen + 2, 4 + rLen + 2 + sLen)
            val rawSig = r + s
            val signatureB64 = Base64.encodeToString(rawSig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            // Build JWK
            val (x, y) = publicKey.w.affineX to publicKey.w.affineY
            fun to32Bytes(bi: java.math.BigInteger): ByteArray {
                val bytes = bi.toByteArray()
                return if (bytes.size < 32) ByteArray(32 - bytes.size) + bytes
                else bytes.takeLast(32).toByteArray()
            }
            val xB64 = Base64.encodeToString(to32Bytes(x), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val yB64 = Base64.encodeToString(to32Bytes(y), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            val attestPayload = mapOf(
                "viewer_id" to "",
                "device_id" to "",
                "challenge_id" to challengeId,
                "nonce" to nonce,
                "signature" to signatureB64,
                "public_key" to mapOf(
                    "alg" to "ES256",
                    "crv" to "P-256",
                    "ext" to true,
                    "key_ops" to listOf("verify"),
                    "kty" to "EC",
                    "x" to xB64,
                    "y" to yB64
                ),
                "client" to MOCK_FINGERPRINT,
                "storage" to emptyMap<String, String>(),
                "attributes" to mapOf("entropy" to "low")
            )
            val attestRes = client.newCall(
                POST("$baseUrl/api/videos/access/attest", reqHeaders, attestPayload.toJsonRequestBody())
            ).execute()
            val attestJson = json.parseToJsonElement(attestRes.body?.string() ?: return emptyList()).jsonObject
            val attestToken = attestJson["token"]?.jsonPrimitive?.content ?: return emptyList()
            val confidence = attestJson["confidence"]?.jsonPrimitive?.double ?: 0.95
            val viewerId = attestRes.header("Set-Cookie")?.let { extractCookie(it, "byse_viewer_id") } ?: ""
            val deviceId = attestRes.header("Set-Cookie")?.let { extractCookie(it, "byse_device_id") } ?: ""

            // 4. CAPTCHA / PoW challenge
            val captchaHeaders = reqHeaders.newBuilder()
                .set("X-Embed-Origin", "voir-anime.to")
                .set("X-Embed-Referer", "https://voir-anime.to/")
                .set("X-Embed-Parent", "$baseUrl/e/$videoCode")
                .build()

            val fingerprint = mapOf(
                "token" to attestToken,
                "viewer_id" to viewerId,
                "device_id" to deviceId,
                "confidence" to confidence
            )
            val captchaPayload = mapOf("fingerprint" to fingerprint)
            val captchaRes = client.newCall(
                POST("$baseUrl/api/videos/$videoCode/embed/captcha", captchaHeaders, captchaPayload.toJsonRequestBody())
            ).execute()
            val captchaJson = json.parseToJsonElement(captchaRes.body?.string() ?: return emptyList()).jsonObject
            val powNonce = captchaJson["pow_nonce"]?.jsonPrimitive?.content ?: return emptyList()
            val powDifficulty = captchaJson["pow_difficulty"]?.jsonPrimitive?.int ?: return emptyList()
            val powToken = captchaJson["pow_token"]?.jsonPrimitive?.content ?: return emptyList()

            // 5. Solve proof-of-work
            val solution = solvePow(powNonce, powDifficulty)

            // 6. Verify captcha
            val verifyPayload = mapOf(
                "pow_token" to powToken,
                "solution" to solution.toString(),
                "fingerprint" to fingerprint
            )
            val verifyRes = client.newCall(
                POST("$baseUrl/api/videos/$videoCode/embed/captcha/verify", captchaHeaders, verifyPayload.toJsonRequestBody())
            ).execute()
            val verifyJson = json.parseToJsonElement(verifyRes.body?.string() ?: return emptyList()).jsonObject
            if (verifyJson["status"]?.jsonPrimitive?.content != "ok") return emptyList()
            val finalToken = verifyJson["token"]?.jsonPrimitive?.content ?: return emptyList()

            // 7. Fetch encrypted playback
            val playbackHeaders = captchaHeaders.newBuilder()
                .set("X-Captcha-Token", finalToken)
                .build()
            val playbackRes = client.newCall(
                POST(
                    "$baseUrl/api/videos/$videoCode/embed/playback",
                    playbackHeaders,
                    captchaPayload.toJsonRequestBody()
                )
            ).execute()
            val encryptedContainer = json.parseToJsonElement(playbackRes.body?.string() ?: return emptyList()).jsonObject

            // 8. Decrypt and extract master URL
            val masterUrl = decryptAndExtractMaster(encryptedContainer, bundleUrl, reqHeaders)

            // 9. Extract video qualities from master playlist
            val videos = playlistUtils.extractFromHls(
                masterUrl,
                referer = "$baseUrl/",
                videoNameGen = { "$prefix$it" }
            )

            // Optional: add subtitles if present (tracks array)
            val tracks = mutableListOf<Track>()
            // If the decrypted manifest has a "tracks" array, parse it
            // (In the example output, it's empty, so no need)
            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // --------------- Helper functions ---------------

    private fun extractCookie(setCookieHeader: String, name: String): String? {
        return setCookieHeader.split(";").firstOrNull { it.trim().startsWith("$name=") }
            ?.substringAfter("$name=")
    }

    private fun Any.toJsonRequestBody() =
        json.encodeToString(this).toRequestBody("application/json".toMediaType())

    private fun String.base64UrlDecode(): ByteArray =
        Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    // --------------- PoW solver ---------------

    private fun solvePow(token: String, difficultyBits: Int): Long {
        val buffer = StringBuilder()
        var nonce = 0L
        while (nonce < 0xFFFFFFFFL) {
            buffer.clear()
            buffer.append(token).append(':').append(nonce)
            val msg = buffer.toString()
            val len = msg.length

            // State
            var e0 = 1779033703
            var e1 = 3144134277.toInt()
            var e2 = 1013904242
            var e3 = 2773480762.toInt()

            // Byte mixing
            for (i in 0 until len) {
                e0 += msg[i].code.toInt() and 0xFF
                e0 = rotl32(e0, 7)
                // quarter round macro
                e0 += e1
                e3 = rotl32(e3 xor e0, 16)
                e2 += e3
                e1 = rotl32(e1 xor e2, 12)
                e0 += e1
                e3 = rotl32(e3 xor e0, 8)
                e2 += e3
                e1 = rotl32(e1 xor e2, 7)
            }

            // 8x scramble
            repeat(8) {
                e0 += e1
                e3 = rotl32(e3 xor e0, 16)
                e2 += e3
                e1 = rotl32(e1 xor e2, 12)
                e0 += e1
                e3 = rotl32(e3 xor e0, 8)
                e2 += e3
                e1 = rotl32(e1 xor e2, 7)
            }

            val r = IntArray(512)
            // Initialize memory
            for (i in 0 until 512) {
                e0 += e1
                e3 = rotl32(e3 xor e0, 16)
                e2 += e3
                e1 = rotl32(e1 xor e2, 12)
                e0 += e1
                e3 = rotl32(e3 xor e0, 8)
                e2 += e3
                e1 = rotl32(e1 xor e2, 7)
                r[i] = e0 xor e2
            }

            // Memory hard loop (2 passes)
            repeat(2) {
                for (s in 0 until 512) {
                    val a = r[s] and 511
                    var c = r[s] + r[a]
                    c = rotl32(c, 13)
                    c = c xor ( ( (r[(s + 1) and 511].toLong() and 0xFFFFFFFFL) * 2654435761L).toInt() )
                    r[s] = c
                    e0 = e0 xor c
                    // quarter round
                    e0 += e1
                    e3 = rotl32(e3 xor e0, 16)
                    e2 += e3
                    e1 = rotl32(e1 xor e2, 12)
                    e0 += e1
                    e3 = rotl32(e3 xor e0, 8)
                    e2 += e3
                    e1 = rotl32(e1 xor e2, 7)
                }
            }

            val n = IntArray(8)
            for (i in 0 until 8) {
                e0 += e1
                e3 = rotl32(e3 xor e0, 16)
                e2 += e3
                e1 = rotl32(e1 xor e2, 12)
                e0 += e1
                e3 = rotl32(e3 xor e0, 8)
                e2 += e3
                e1 = rotl32(e1 xor e2, 7)
                var sVal = e0
                val base = i * 64
                for (cIdx in 0 until 64) {
                    val d = r[base + cIdx]
                    sVal += d
                    sVal = rotl32(sVal, 5)
                    sVal = sVal xor ( ( (d.toLong() and 0xFFFFFFFFL) * 2246822519L).toInt() )
                }
                n[i] = sVal xor e2
            }

            var zeros = 0
            for (i in 0 until 8) {
                if (n[i] == 0) {
                    zeros += 32
                } else {
                    zeros += Integer.numberOfLeadingZeros(n[i])
                    break
                }
            }
            if (zeros >= difficultyBits) return nonce
            nonce++
        }
        throw Exception("PoW solution not found")
    }

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    // --------------- Decryption ---------------

    @Serializable
    private data class PlaybackData(
        val algorithm: String,
        val iv: String,
        val payload: String,
        val key_parts: List<String>,
        val version: String
    )

    private fun decryptAndExtractMaster(
        encryptedContainer: kotlinx.serialization.json.JsonObject,
        bundleUrl: String,
        headers: Headers
    ): String {
        val playback = json.decodeFromJsonElement(PlaybackData.serializer(), encryptedContainer["playback"]!!)
        val version = playback.version.toInt()
        val keyParts = playback.key_parts
        val iv = playback.iv.base64UrlDecode()
        val ciphertext = playback.payload.base64UrlDecode()

        // Fetch inversion constant from video bundle
        val bundleJs = client.newCall(GET(bundleUrl, headers)).execute().body?.string()
            ?: throw Exception("Failed to fetch video bundle")
        val magic = Regex("""(\d+)-n(?:\^0)?""").find(bundleJs)?.groupValues?.get(1)?.toInt()
            ?: throw Exception("Could not find inversion constant in bundle")

        val idx1 = version - 1
        val idx2 = (magic - version) - 1
        val keyBytes = keyParts[idx1].base64UrlDecode() + keyParts[idx2].base64UrlDecode()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(ciphertext)
        val decryptedJson = json.parseToJsonElement(String(decrypted)).jsonObject

        // Extract master URL from the "sources" array
        val sources = decryptedJson["sources"]?.jsonArray
            ?: throw Exception("No 'sources' array in decrypted manifest")
        val masterUrl = sources.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: throw Exception("No master URL found in sources")

        return masterUrl
    }

    // --------------- Mock fingerprint ---------------
    companion object {
        private val MOCK_FINGERPRINT = mapOf(
            "user_agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0",
            "pixel_ratio" to 1,
            "screen_width" to 2560,
            "screen_height" to 1440,
            "color_depth" to 24,
            "languages" to listOf("en-US", "en"),
            "timezone" to "Europe/Zurich",
            "hardware_concurrency" to 16,
            "touch_points" to 0,
            "webgl_vendor" to "Google Inc. (NVIDIA)",
            "webgl_renderer" to "ANGLE (NVIDIA, NVIDIA GeForce GTX 980 Direct3D11 vs_5_0 ps_5_0), or similar",
            "canvas_hash" to "f3UUiHOulTOLvZYFkfJ0bVvzKSDVNXmwjiEj83uSA_A",
            "audio_hash" to "_oGTjFqFiMCfUhMTzdEID7gIliFGMmPeNMqniFYvQ7M",
            "webgl_params_hash" to "CJWDmD1D3N8WHk4YpFyoi-b4CSfK8t5a3u3dCtAV3Tc",
            "fonts_hash" to "BpQkHDAqAuYn3hRtvG78Z0M7uIl7lIcD9wwi5jN6Mkc",
            "codecs_hash" to "gAcHkrAdUTpJQMTQz3IUpbxaSfLF8v-qi3--oveUBbQ",
            "media_devices" to "ai0ao0vi0",
            "pointer_type" to "fine,hover",
            "extra" to mapOf("vendor" to "", "appVersion" to "5.0 (Windows)")
        )
    }
}