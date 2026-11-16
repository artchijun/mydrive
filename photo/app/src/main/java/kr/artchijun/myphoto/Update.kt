package kr.artchijun.myphoto

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

// 새 판 확인: Mydrive 공개 저장소의 릴리스 중 이름이 photo-v 로 시작하는 것 (PC·휴대폰 Mydrive 릴리스와 섞이지 않게)
object Update {
    private const val REPO = "artchijun/mydrive"
    data class Info(val version: String, val apk: String, val page: String, val notes: String)

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    fun latest(): Info? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=40").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        var best: Info? = null
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || !tag.startsWith("photo-v")) continue
            val v = tag.removePrefix("photo-v")
            val assets = r.optJSONArray("assets") ?: continue
            for (k in 0 until assets.length()) {
                val s = assets.getJSONObject(k)
                if (!s.optString("name").endsWith(".apk")) continue
                if (best == null || newer(v, best.version)) best = Info(v, s.optString("browser_download_url"), r.optString("html_url"), r.optString("body"))
            }
        }
        return best
    }
}
