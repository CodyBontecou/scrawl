package dev.kitlangton.scrawl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Per-device Scrawl ingest configuration, stored in a private no-backup file (the Android stand-in
 * for iOS's `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` Keychain item `dev.kitlangton.scrawl`).
 */
data class ScrawlConfig private constructor(
    val urls: List<String>,
    val token: String,
    val sessionID: String?,
) {
    val isValid: Boolean get() = urls.isNotEmpty() && token.isNotEmpty()

    sealed class SetupAction {
        data class Configure(val config: ScrawlConfig) : SetupAction()
        data object Reset : SetupAction()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("urls", JSONArray(urls))
        put("token", token)
        sessionID?.let { put("sessionID", it) }
    }

    companion object {
        const val STORE_SERVICE = "dev.kitlangton.scrawl"
        private const val LEGACY_STORE_SERVICE = "dev.kitlangton.tether"
        private val setupHosts = setOf("scrawl", "tether")

        /** Normalizes like the iOS initializer: valid, de-duplicated base URLs; trimmed token and session. */
        fun of(urls: List<String>, token: String, sessionID: String? = null): ScrawlConfig {
            val session = sessionID?.trim()?.takeIf { it.isNotEmpty() }
            return ScrawlConfig(validBaseURLs(urls), token.trim(), session)
        }

        /**
         * Parses `<scheme>://scrawl?url=<base>[&url=<second>]&token=<token>[&session=ses_…]` or
         * `<scheme>://scrawl?reset=1` (also accepts the legacy `tether` host). Only `http://` and
         * `https://` base URLs are accepted.
         */
        fun parseSetupURL(url: String): SetupAction? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme
            if (scheme.isNullOrEmpty()) return null
            val host = uri.host?.lowercase() ?: return null
            if (host !in setupHosts) return null
            val items = queryItems(uri.rawQuery ?: return null)
            if (items.any { (name, value) -> name == "reset" && (value == "1" || value?.lowercase() == "true") }) {
                return SetupAction.Reset
            }
            val rawURLs = items.filter { it.first == "url" }.mapNotNull { it.second }.mapNotNull(::parseHTTPBaseURL)
            val token = items.firstOrNull { it.first == "token" }?.second?.trim() ?: ""
            val session = items.firstOrNull { it.first == "session" }?.second?.trim()
            val config = of(rawURLs, token, session)
            return if (config.isValid) SetupAction.Configure(config) else null
        }

        private fun queryItems(raw: String): List<Pair<String, String?>> =
            raw.split('&').filter { it.isNotEmpty() }.map { part ->
                val eq = part.indexOf('=')
                if (eq < 0) decode(part) to null else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
            }

        /** Percent-decoding as `URLComponents.queryItems` does it (a `+` stays a `+`). */
        internal fun decode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length + 1 && i + 2 <= s.length - 1) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }

        fun parseHTTPBaseURL(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty()) return null
            return trimmed
        }

        fun validBaseURLs(candidates: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return candidates.mapNotNull(::parseHTTPBaseURL).filter { seen.add(it) }
        }

        private fun fromJson(json: JSONObject): ScrawlConfig? = runCatching {
            val urls = json.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
            of(urls, json.optString("token"), json.optString("sessionID").takeIf { json.has("sessionID") })
        }.getOrNull()?.takeIf { it.isValid }

        private fun storeFile(context: Context, service: String) = File(context.noBackupFilesDir, "$service.config.json")

        fun load(context: Context): ScrawlConfig? {
            val file = storeFile(context, STORE_SERVICE)
            load(file)?.let { config ->
                // Stored before encryption: re-save it sealed.
                if (runCatching { !file.readText().startsWith(SealedStore.PREFIX) }.getOrDefault(false)) save(context, config)
                return config
            }
            val legacy = load(storeFile(context, LEGACY_STORE_SERVICE)) ?: return null
            save(context, legacy)
            storeFile(context, LEGACY_STORE_SERVICE).delete()
            return legacy
        }

        private fun load(file: File): ScrawlConfig? = runCatching {
            val text = file.readText()
            val json = if (text.startsWith(SealedStore.PREFIX)) SealedStore.open(text) else text
            fromJson(JSONObject(json))
        }.getOrNull()

        fun save(context: Context, config: ScrawlConfig): Boolean {
            val normalized = of(config.urls, config.token, config.sessionID)
            if (!normalized.isValid) return false
            val file = storeFile(context, STORE_SERVICE)
            return runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                // Sealed with a key in the Android Keystore (iOS keeps it in the Keychain).
                val json = normalized.toJson().toString()
                tmp.writeText(runCatching { SealedStore.seal(json) }.getOrDefault(json))
                tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
            }.getOrDefault(false)
        }

        fun delete(context: Context) {
            storeFile(context, STORE_SERVICE).delete()
            storeFile(context, LEGACY_STORE_SERVICE).delete()
        }

        /**
         * Resolves the active configuration by overlaying launch arguments / environment
         * (`-scrawl-url`, `SCRAWL_URL`, `-scrawl-token`, `SCRAWL_TOKEN`, `-scrawl-session`,
         * `SCRAWL_SESSION`) on the stored configuration. On Android, [Scrawl] builds [arguments]
         * from the launch Intent's `scrawl-url`/`scrawl-token`/`scrawl-session` extras and
         * [environment] from the `debug.scrawl.url`/`.token`/`.session` system properties.
         */
        fun resolved(arguments: List<String>, environment: Map<String, String>, stored: ScrawlConfig?): ScrawlConfig? {
            val overrideURLStrings = mutableListOf<String>()
            var index = 0
            while (index < arguments.size) {
                if (arguments[index] == "-scrawl-url" && index + 1 < arguments.size) {
                    overrideURLStrings += arguments[index + 1].split(',')
                    index += 2
                } else {
                    index += 1
                }
            }
            environment["SCRAWL_URL"]?.takeIf { it.isNotEmpty() }?.let { overrideURLStrings += it.split(',') }
            val overrideURLs = validBaseURLs(overrideURLStrings)
            val urls = overrideURLs.ifEmpty { stored?.urls ?: emptyList() }

            fun argument(name: String): String? {
                val i = arguments.indexOf(name)
                return if (i >= 0 && i + 1 < arguments.size) arguments[i + 1].trim() else null
            }

            val overrideToken = argument("-scrawl-token")
                ?: environment["SCRAWL_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
            val token = overrideToken?.takeIf { it.isNotEmpty() } ?: stored?.token ?: ""
            val overrideSession = argument("-scrawl-session")
                ?: environment["SCRAWL_SESSION"]?.trim()?.takeIf { it.isNotEmpty() }
            val session = overrideSession?.takeIf { it.isNotEmpty() } ?: stored?.sessionID
            return of(urls, token, session).takeIf { it.isValid }
        }
    }
}

/**
 * Seals the stored configuration (it holds the bearer token) with an AES-256-GCM key that lives in
 * the Android Keystore and never leaves it: the counterpart of iOS keeping it in the Keychain. The
 * file is in `noBackupFilesDir`, so it isn't in backups either.
 */
internal object SealedStore {
    const val PREFIX = "scrawl-sealed-1:"
    private const val ALIAS = "dev.kitlangton.scrawl.config"

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)
    }

    fun open(text: String): String {
        val bytes = android.util.Base64.decode(text.removePrefix(PREFIX), android.util.Base64.NO_WRAP)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}
