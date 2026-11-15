package app.nightwatch.store

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LoginRequired : Exception("Riot session expired")

class SessionStore(context: Context, preferencesName: String = "nightwatch_secure") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val key: SecretKey get() = synchronized(SessionStore::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nightwatch-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nightwatch-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit())
    }
    fun get(name: String): String? = runCatching {
        val encoded = preferences.getString(name, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    fun session(): RiotSession? = get("session")?.let { runCatching { Gson().fromJson(it, RiotSession::class.java) }.getOrNull() }
    fun save(session: RiotSession) { put("session", Gson().toJson(session)) }
    fun cached(): LiveStoreAccount? = get("account")?.let { raw ->
        runCatching {
            val envelope = JSONObject(raw)
            if(envelope.optInt("schema") != 1) null else Gson().fromJson(envelope.getJSONObject("data").toString(), LiveStoreAccount::class.java).let { account ->
                if(account.career?.rankSchema == 0) account.copy(career = account.career.copy(rankTier = -1, rankedRating = -1), updatedAt = 0) else account
            }
        }.getOrNull()
    }
    fun cache(account: LiveStoreAccount) { put("account", JSONObject().put("schema", 1).put("data", JSONObject(Gson().toJson(account))).toString()) }
    fun clear() { preferences.edit().clear().commit() }

    suspend fun validSession(forceRefresh: Boolean = false): RiotSession = withContext(Dispatchers.IO) {
        val current = session()
        if (!forceRefresh && current != null && tokenExpiry(current.accessToken) > System.currentTimeMillis() / 1000 + 90) return@withContext current
        // Only Riot receives Riot cookies. Redirects are inspected before following them.
        val state = UUID.randomUUID().toString()
        var url = authorizeUrl("en-us", state, silent = true)
        val cookies = CookieManager.getInstance()
        repeat(5) {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "auth.riotgames.com") throw LoginRequired()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                cookies.getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                val status = connection.responseCode
                connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.forEach { cookies.setCookie(url, it) }
                if (status !in 300..399) throw LoginRequired()
                val next = connection.getHeaderField("Location") ?: throw LoginRequired()
                sessionFromRedirect(next, state)?.let { save(it); cookies.flush(); return@withContext it }
                url = URL(URL(url), next).toString()
            } finally { connection.disconnect() }
        }
        throw LoginRequired()
    }
    companion object {
        fun tokenExpiry(token: String): Long = runCatching { JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP))).optLong("exp") }.getOrDefault(0)
        fun authorizeUrl(locale: String, state: String, silent: Boolean = false) = "https://auth.riotgames.com/authorize?client_id=play-valorant-web-prod&redirect_uri=https%3A%2F%2Fplayvalorant.com%2Fopt_in&response_type=token%20id_token&scope=account%20openid&nonce=$state&state=$state&ui_locales=$locale" + if (silent) "&prompt=none" else ""
        fun sessionFromRedirect(url: String, expectedState: String? = null): RiotSession? {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "playvalorant.com" || uri.path?.trimEnd('/') != "/opt_in") return null
            val values = uri.fragment?.split('&')?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to Uri.decode(it[1]) } }?.toMap() ?: return null
            if (expectedState != null && values["state"] != expectedState) return null
            return RiotSession(values["access_token"] ?: return null, values["id_token"] ?: return null)
        }
    }
}
