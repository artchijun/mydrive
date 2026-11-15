package kr.artchijun.mydrive

import android.content.Context

// 설정: 서버 주소(집 안 · 인터넷), 연결 토큰, DSM 사용자. 비밀번호는 저장하지 않음
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mydrive", Context.MODE_PRIVATE)

    var home: String
        get() = p.getString("home", "http://192.168.0.6:8642") ?: ""
        set(v) = p.edit().putString("home", v.trim()).apply()
    var outside: String
        get() = p.getString("outside", "") ?: ""
        set(v) = p.edit().putString("outside", v.trim()).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    var user: String
        get() = p.getString("user", "") ?: ""
        set(v) = p.edit().putString("user", v).apply()
    var lastUpdateCheck: Long
        get() = p.getLong("updChk", 0)
        set(v) = p.edit().putLong("updChk", v).apply()

    val servers: List<String> get() = listOf(home, outside).map { it.trim() }.filter { it.isNotEmpty() }
    val paired: Boolean get() = token.isNotEmpty() && servers.isNotEmpty()

    fun api() = Api(servers, token)
}
