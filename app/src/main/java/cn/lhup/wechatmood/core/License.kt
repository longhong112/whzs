package cn.lhup.wechatmood.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LicenseInfo(
    val code: String,
    val device: String,
    val fingerprint: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

enum class LicenseState { NONE, ACTIVE, EXPIRED, INVALID }

/**
 * 激活码授权：服务端用 RSA 私钥签发授权串，App 只内置公钥，
 * 因此授权串无法被伪造；设备指纹与有效期可在本地离线校验。
 */
object License {
    const val ENDPOINT = "https://zb.lhuq.cn/whzs/api.php"

    const val REQUIRED_HINT = "请先在设置页「激活」中填写激活码"

    @Volatile private var cachedToken: String = ""
    @Volatile private var cachedInfo: LicenseInfo? = null

    /** 机型特征；不包含系统版本，避免 OTA 升级后授权失效。 */
    fun fingerprint(): String = sha256(
        "${Build.BRAND}|${Build.MODEL}|${Build.DEVICE}|${Build.BOARD}|${Build.HARDWARE}"
    )

    fun deviceId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return sha256("lh|$androidId")
    }

    /** 校验授权串；expectDevice 非空时同时核对本机设备号。 */
    fun verify(token: String, expectDevice: String? = null): LicenseInfo? {
        if (token.isBlank()) return null
        val parts = token.split('.', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val valid = runCatching {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(publicKeyBytes()))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(parts[0].toByteArray(Charsets.US_ASCII))
                verify(Base64.decode(parts[1], Base64.NO_WRAP))
            }
        }.getOrDefault(false)
        if (!valid) return null
        val json = runCatching {
            JSONObject(String(Base64.decode(parts[0], Base64.NO_WRAP), Charsets.UTF_8))
        }.getOrNull() ?: return null
        val info = LicenseInfo(
            code = json.optString("code"),
            device = json.optString("device"),
            fingerprint = json.optString("fp"),
            issuedAt = json.optLong("issued"),
            expiresAt = json.optLong("expire"),
        )
        if (info.code.isBlank() || info.device.isBlank()) return null
        if (info.fingerprint.isNotEmpty() && info.fingerprint != fingerprint()) return null
        if (expectDevice != null && info.device != expectDevice) return null
        return info
    }

    fun info(config: AppConfig): LicenseInfo? {
        val token = config.license
        if (token.isBlank()) return null
        if (token == cachedToken) return cachedInfo
        val info = verify(token)
        cachedToken = token
        cachedInfo = info
        return info
    }

    fun status(config: AppConfig, context: Context? = null): LicenseState {
        if (config.license.isBlank()) return LicenseState.NONE
        val info = info(config) ?: return LicenseState.INVALID
        if (info.expiresAt * 1000L <= System.currentTimeMillis()) return LicenseState.EXPIRED
        if (context != null && info.device != deviceId(context)) return LicenseState.INVALID
        return LicenseState.ACTIVE
    }

    fun isActive(config: AppConfig): Boolean = status(config) == LicenseState.ACTIVE

    internal fun publicKeyBytes(): ByteArray =
        Base64.decode(LicenseKeys.PUBLIC_KEY_PEM
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }, Base64.NO_WRAP)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 与 PHP 后台约定的加密请求：RSA-OAEP 包裹随机会话密钥 + AES-256-GCM 报文。 */
object LicenseClient {
    private const val TIMEOUT = 12_000

    data class Activation(val license: String, val info: LicenseInfo)

    fun activate(context: Context, code: String): Activation {
        val device = License.deviceId(context)
        val request = JSONObject().apply {
            put("v", 1)
            put("action", "activate")
            put("code", code.trim().uppercase())
            put("device", device)
            put("fp", License.fingerprint())
            put("ts", System.currentTimeMillis() / 1000)
            put("nonce", nonce())
        }
        val response = call(request)
        if (!response.optBoolean("ok")) {
            throw IOException(response.optString("message", "激活失败，请检查激活码"))
        }
        val token = response.optString("license")
        val info = License.verify(token, device)
            ?: throw IOException("授权信息校验失败，请重试")
        return Activation(token, info)
    }

    /** 重新向后台确认设备授权状态；返回 null 表示该设备已无有效授权。 */
    fun refresh(context: Context): Activation? {
        val device = License.deviceId(context)
        val request = JSONObject().apply {
            put("v", 1)
            put("action", "status")
            put("device", device)
            put("fp", License.fingerprint())
            put("ts", System.currentTimeMillis() / 1000)
            put("nonce", nonce())
        }
        val response = call(request)
        if (!response.optBoolean("ok") || !response.optBoolean("licensed")) return null
        val token = response.optString("license")
        val info = License.verify(token, device) ?: return null
        return Activation(token, info)
    }

    private fun call(request: JSONObject): JSONObject {
        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val encryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        }
        val cipherText = encryptor.doFinal(request.toString().toByteArray(Charsets.UTF_8))
        val wrappedKey = rsaEncrypt(aesKey)
        val envelope = JSONObject().apply {
            put("k", Base64.encodeToString(wrappedKey, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("d", Base64.encodeToString(cipherText, Base64.NO_WRAP))
        }

        val connection = (URL(License.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "lh-license/1")
        }
        try {
            connection.outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream
                else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("授权服务器无响应，请稍后重试")
            val body = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IOException("授权服务器返回异常")
            val responseIv = body.optString("iv")
            val responseData = body.optString("d")
            if (responseIv.isBlank() || responseData.isBlank()) {
                throw IOException(body.optString("message", "授权服务器返回异常"))
            }
            val decryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
                    GCMParameterSpec(128, Base64.decode(responseIv, Base64.NO_WRAP)))
            }
            val plain = decryptor.doFinal(Base64.decode(responseData, Base64.NO_WRAP))
            return JSONObject(String(plain, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun rsaEncrypt(key: ByteArray): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(License.publicKeyBytes()))
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, publicKey)
            doFinal(key)
        }
    }

    private fun nonce(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
