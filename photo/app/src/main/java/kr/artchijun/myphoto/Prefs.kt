package kr.artchijun.myphoto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// 설정: 시놀로지 주소(집 안 · 밖 = Mydrive 서버가 터널로 이어 줌), 계정, 세션. 비밀번호는 휴대폰의 열쇠 보관소(안드로이드 키스토어)로 잠가서 저장
//   — 세션이 끝나면 앱이 스스로 다시 로그인해야 백업이 멈추지 않음
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("myphoto", Context.MODE_PRIVATE)

    var home: String
        get() = p.getString("home", "http://192.168.0.6:5000") ?: ""
        set(v) = p.edit().putString("home", v.trim().trimEnd('/')).apply()
    var outside: String
        get() = p.getString("outside", null)?.takeIf { it.isNotEmpty() } ?: "https://drive.cdluos.com"   // 비워 두면 기본 (Mydrive 서버)
        set(v) = p.edit().putString("outside", v.trim().trimEnd('/')).apply()
    var account: String
        get() = p.getString("account", "") ?: ""
        set(v) = p.edit().putString("account", v).apply()
    /** 2단계 인증을 다시 묻지 않게 하는 기기 열쇠 (DSM 이 줌) */
    var deviceId: String
        get() = p.getString("did", "") ?: ""
        set(v) = p.edit().putString("did", v).apply()
    var sid: String
        get() = p.getString("sid", "") ?: ""
        set(v) = p.edit().putString("sid", v).apply()
    var synoToken: String
        get() = p.getString("synotoken", "") ?: ""
        set(v) = p.edit().putString("synotoken", v).apply()

    var password: String
        get() = p.getString("pw", "")?.takeIf { it.isNotEmpty() }?.let { Secret.open(it) } ?: ""
        set(v) = p.edit().putString("pw", if (v.isEmpty()) "" else Secret.seal(v)).apply()

    val loggedIn: Boolean get() = account.isNotEmpty() && (sid.isNotEmpty() || password.isNotEmpty())
    val servers: List<String> get() = listOf(home, outside).map { it.trim() }.filter { it.isNotEmpty() }

    fun str(k: String, def: String = "") = p.getString(k, def) ?: def
    fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()
    fun int(k: String, def: Int) = p.getInt(k, def)
    fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun long(k: String, def: Long) = p.getLong(k, def)
    fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    fun bool(k: String, def: Boolean) = p.getBoolean(k, def)
    fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    fun logout() { p.edit().remove("sid").remove("synotoken").remove("pw").apply() }
}

/** 휴대폰 열쇠 보관소의 AES 열쇠로 잠그기/풀기 (열쇠는 휴대폰 밖으로 나가지 않음) */
object Secret {
    private const val ALIAS = "myphoto-pw"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun seal(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun open(s: String): String? = try {
        val (iv, body) = s.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(body, Base64.NO_WRAP)))
    } catch (e: Exception) { null }
}
