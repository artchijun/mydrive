package kr.artchijun.mydrive

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 새 판 확인: GitHub 공개 저장소의 릴리스 중 이름이 android-v 로 시작하는 것 (PC 앱 릴리스와 섞이지 않게)
object Update {
    private const val REPO = "artchijun/mydrive"

    data class Info(val version: String, val url: String, val size: Long, val notes: String)

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** a 가 b 보다 새 판인가 ("0.1.10" > "0.1.9") */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    /** 가장 새 안드로이드 판 (없으면 null) */
    fun latest(): Info? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        var best: Info? = null
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || !tag.startsWith("android-v")) continue
            val v = tag.removePrefix("android-v")
            val assets = r.optJSONArray("assets") ?: continue
            for (k in 0 until assets.length()) {
                val s = assets.getJSONObject(k)
                if (!s.optString("name").endsWith(".apk")) continue
                if (best == null || newer(v, best.version)) best = Info(v, s.optString("browser_download_url"), s.optLong("size"), r.optString("body"))
            }
        }
        return best
    }

    /** 설치 파일 받기 → 앱 캐시의 update 폴더 */
    fun download(ctx: Context, info: Info, progress: (Long, Long) -> Unit, canceled: () -> Boolean): File {
        val dir = File(ctx.cacheDir, "update"); dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "Mydrive-android-${info.version}.apk")
        var url = URL(info.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {   // GitHub 는 다른 주소로 넘겨줌
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 60000
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) throw java.io.IOException("새 판을 받지 못함 ($code)")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        c.inputStream.use { inp -> f.outputStream().use { out ->
            val buf = ByteArray(128 * 1024); var done = 0L
            while (true) { if (canceled()) throw Canceled(); val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, total) }
        } }
        return f
    }
}
