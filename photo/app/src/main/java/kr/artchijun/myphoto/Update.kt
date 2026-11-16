package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

// 새 판 확인: Mydrive 공개 저장소의 릴리스 중 이름이 photo-v 로 시작하는 것 (PC·휴대폰 Mydrive 릴리스와 섞이지 않게)
object Update {
    private const val REPO = "artchijun/mydrive"
    /** apk: 받을 주소 · size: 바이트 · sha: GitHub 가 알려 준 SHA-256 (없으면 "") */
    data class Info(val version: String, val apk: String, val page: String, val notes: String, val size: Long = 0, val sha: String = "")

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
                if (best == null || newer(v, best.version)) best = Info(v, s.optString("browser_download_url"), r.optString("html_url"), r.optString("body"),
                    s.optLong("size"), s.optString("digest").removePrefix("sha256:"))
            }
        }
        return best
    }

    // ---------- 앱 안에서 받아 설치 ----------
    /** 새 판 APK 를 앱 캐시에 받음 (SHA-256 확인). progress(받은 바이트, 전체) */
    fun download(ctx: Context, info: Info, progress: (Long, Long) -> Unit, canceled: () -> Boolean): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { if (!it.name.contains(info.version)) it.delete() }   // 예전 판 파일 정리
        val f = File(dir, "Myphoto-${info.version}.apk")
        val tmp = File(dir, f.name + ".part")
        var url = info.apk
        var c: HttpURLConnection
        var hops = 0
        while (true) {   // GitHub 는 받을 곳을 다른 주소로 넘김
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = c.getHeaderField("Location"); c.disconnect(); continue }
            if (code != 200) throw java.io.IOException("받지 못함 (HTTP $code)")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        val md = MessageDigest.getInstance("SHA-256")
        var got = 0L
        c.inputStream.use { inp -> tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (canceled()) throw InterruptedException("그만둠")
                val n = inp.read(buf); if (n < 0) break
                out.write(buf, 0, n); md.update(buf, 0, n); got += n; progress(got, total)
            }
        } }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (info.sha.isNotEmpty() && !hex.equals(info.sha, true)) { tmp.delete(); throw java.io.IOException("받은 파일이 GitHub 의 것과 다릅니다 (SHA-256 불일치)") }
        f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("저장하지 못함")
        return f
    }

    /** 받아서 설치 창 띄우기: 처음이면 '이 출처의 앱 설치 허용'을 켜게 안내 */
    fun install(a: Activity, info: Info) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle("설치 허용이 필요합니다")
                .setMessage("안드로이드 설정에서 Myphoto 의 '이 출처의 앱 설치 허용'을 켠 뒤, 돌아와 [받아서 설치]를 다시 눌러 주세요. (처음 한 번만)")
                .setPositiveButton("설정 열기") { _, _ -> a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                .setNegativeButton("그만", null).show()
            return
        }
        val ui = Ui(a)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val txt = ui.label("받는 중…", 13.5f, ui.sub)
        var cancel = false
        val dlg = AlertDialog.Builder(a).setTitle("새 판 ${info.version} 받는 중")
            .setView(ui.col(bar, txt.apply { layoutParams = ui.lp(top = 8) }).apply { setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0) })
            .setNegativeButton("그만") { _, _ -> cancel = true }.setCancelable(false).show()
        Thread {
            var last = 0L
            val r = try { Result.success(download(a, info, { d, t ->
                val now = System.currentTimeMillis(); if (now - last < 150) return@download; last = now
                a.runOnUiThread { bar.progress = if (t > 0) (d * 1000 / t).toInt() else 0; txt.text = Fmt.size(d) + (if (t > 0) " / " + Fmt.size(t) else "") }
            }, { cancel })) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                r.onFailure { e -> if (!cancel) Toast.makeText(a, "받지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                r.onSuccess { f ->
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(a, f), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { Toast.makeText(a, "설치 창을 열지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }.start()
    }

    /** 앱을 열 때 하루 한 번 조용히 확인 → 새 판이면 묻기 */
    fun checkOnStart(a: Activity) {
        val pf = Prefs(a)
        if (System.currentTimeMillis() - pf.long("upd_at", 0) < 12 * 3_600_000L) return
        pf.putLong("upd_at", System.currentTimeMillis())
        Thread {
            val info = try { latest() } catch (e: Exception) { null } ?: return@Thread
            if (!newer(info.version, current(a)) || pf.str("upd_skip", "") == info.version) return@Thread
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                AlertDialog.Builder(a).setTitle("새 판 ${info.version}").setMessage(info.notes.ifEmpty { "새 판이 있습니다" })
                    .setPositiveButton("받아서 설치") { _, _ -> install(a, info) }
                    .setNeutralButton("이 판 건너뛰기") { _, _ -> pf.putStr("upd_skip", info.version) }
                    .setNegativeButton("나중에", null).show()
            }
        }.start()
    }
}
