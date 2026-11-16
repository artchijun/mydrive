package kr.artchijun.mydrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * PC 에서 이 휴대폰 열기: 켜 두는 동안 NAS 에 늘 물어보고(긴 기다림), PC Mydrive 가 보낸 요청(목록·받기·올리기·지우기·만들기·옮기기)을
 *   이 휴대폰 저장소에서 처리해 돌려줌. NAS 가 중간에서 이어 줌 (server/phonerelay.go) → 같은 와이파이든 밖이든 NAS 에만 닿으면 됨.
 *   PC 에서는 웹드라이브(예: P:)로 붙음. 켜져 있는 동안 알림이 떠 있음 (끄기 단추).
 */
class PhoneShareService : Service() {
    companion object {
        private const val CH = "phoneshare"
        private const val ID = 2
        @Volatile var running = false
            private set
        @Volatile var status = ""
            private set
        fun start(ctx: Context) { Prefs(ctx).putBool("phoneShare", true); ctx.startForegroundService(Intent(ctx, PhoneShareService::class.java)) }
        fun stop(ctx: Context) { Prefs(ctx).putBool("phoneShare", false); ctx.stopService(Intent(ctx, PhoneShareService::class.java)) }
        /** NAS 가 받아 주는 휴대폰 이름 (글자·숫자·공백·_·-·.) */
        fun name(ctx: Context): String = Prefs(ctx).str("phoneName", android.os.Build.MODEL ?: "휴대폰").replace(Regex("[^\\p{L}\\p{N}_ .-]"), "_").trim().take(40).ifEmpty { "휴대폰" }
    }

    @Volatile private var stopping = false
    private val workers = ArrayList<Thread>()
    private lateinit var pf: Prefs
    private lateinit var fs: Fs
    private lateinit var root: File
    @Volatile private var server: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pf = Prefs(this); fs = Fs(this, pf.api())
        root = fs.volumes().first().root
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "PC 에서 휴대폰 열기", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stop(this); return START_NOT_STICKY }
        startForeground(ID, note("NAS 에 연결하는 중…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true; stopping = false
            repeat(3) { n -> workers += Thread({ loop(n) }, "phoneshare-$n").apply { start() } }   // 동시에 3가지 요청까지
        }
        return START_STICKY
    }

    override fun onDestroy() { stopping = true; running = false; status = ""; workers.forEach { it.interrupt() }; workers.clear(); super.onDestroy() }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneShareService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setContentIntent(open)
            .setContentTitle("PC 에서 이 휴대폰 열기 · ${name(this)}").setContentText(text)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build()).build()
    }
    private fun setStatus(s: String) { if (s != status) { status = s; getSystemService(NotificationManager::class.java).notify(ID, note(s)) } }

    // ---------- NAS 에 물어보기 ----------
    private fun pickServer(): String? {
        server?.let { return it }
        for (s in pf.servers) if (Api.ping(s, 4000)) { server = s.trimEnd('/'); return server }
        return null
    }
    private fun open(method: String, path: String, timeout: Int = 60_000): HttpURLConnection {
        val s = pickServer() ?: throw IOException("NAS 에 닿지 않음")
        val c = URL(s + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = timeout
        c.setRequestProperty("Authorization", "Bearer " + pf.token)
        return c
    }

    private fun loop(n: Int) {
        var fails = 0
        while (!stopping) {
            try {
                val st = android.os.StatFs(root.path)
                val c = open("GET", "/v1/phone/poll?name=" + Api.enc(name(this)) + "&total=${st.totalBytes}&free=${st.availableBytes}", 70_000)
                val code = c.responseCode
                if (code == 204) { c.disconnect(); fails = 0; setStatus("켜짐 — PC 의 Mydrive 에서 이 휴대폰을 웹드라이브로 붙일 수 있음"); continue }
                if (code != 200) throw IOException("NAS 응답 $code")
                val j = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
                fails = 0
                setStatus("켜짐 — PC 가 쓰는 중")
                handle(j)
            } catch (e: Exception) {
                if (stopping) break
                fails++; server = null
                setStatus(if (pf.paired) "NAS 에 닿지 않음 — 다시 시도 중" else "NAS 연결이 필요합니다 (설정 → 연결)")
                try { Thread.sleep(minOf(30_000L, 3_000L * fails)) } catch (x: InterruptedException) { break }
            }
        }
    }

    // ---------- PC 의 요청 처리 ----------
    private class Reply(val status: Int, val type: String = "application/json", val body: ByteArray? = null,
                        val file: File? = null, val off: Long = 0, val len: Long = 0, val range: String? = null, val entry: String? = null)

    private fun q(query: String): Map<String, String> = query.split('&').filter { it.contains('=') }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun rel(p: String?): String {
        val r = (p ?: "").trim('/')
        if (r.split('/').any { it == ".." }) throw IOException("경로가 올바르지 않음")
        return r
    }
    private fun entry(f: File, p: String) = JSONObject().put("p", p).put("d", f.isDirectory).put("s", if (f.isDirectory) 0 else f.length())
        .put("m", f.lastModified() * 1_000_000).put("i", (f.path.hashCode().toLong() and 0xffffffffL))
    private fun json(o: Any, status: Int = 200) = Reply(status, body = o.toString().toByteArray())
    private fun err(status: Int, msg: String) = json(JSONObject().put("error", "phone").put("msg", msg), status)

    private fun handle(j: JSONObject) {
        val id = j.getString("id")
        val r = try { work(j) } catch (e: Exception) { err(500, e.message ?: e.javaClass.simpleName) }
        // 답 보내기 (본문은 그대로 흘려보냄)
        val c = open("POST", "/v1/phone/reply?id=$id", 30 * 60_000)
        c.doOutput = true
        c.setRequestProperty("X-Status", r.status.toString())
        c.setRequestProperty("X-Type", r.type)
        r.range?.let { c.setRequestProperty("X-Range", it) }
        r.entry?.let { c.setRequestProperty("X-Entry", it) }
        val n = r.body?.size?.toLong() ?: r.len
        c.setRequestProperty("X-Length", n.toString())
        c.setFixedLengthStreamingMode(n)
        c.outputStream.use { o ->
            if (r.body != null) o.write(r.body)
            else if (r.file != null) RandomAccessFile(r.file, "r").use { f ->
                f.seek(r.off); val buf = ByteArray(256 * 1024); var left = r.len
                while (left > 0) { val k = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) break; o.write(buf, 0, k); left -= k }
            }
        }
        c.responseCode; c.disconnect()
    }

    private fun work(j: JSONObject): Reply {
        val qs = q(j.optString("query"))
        val method = j.optString("method")
        when (j.optString("path")) {
            "/v1/list" -> {
                val p = rel(qs["path"]); val d = File(root, p)
                if (!d.isDirectory) return err(404, "그런 폴더가 없음")
                val es = JSONArray()
                for (f in d.listFiles() ?: emptyArray()) {
                    if (f.name == Fs.TRASH_DIR) continue
                    es.put(entry(f, if (p.isEmpty()) f.name else "$p/${f.name}"))
                }
                return json(JSONObject().put("path", p).put("entries", es))
            }
            "/v1/file" -> {
                val p = rel(qs["path"]); val f = File(root, p)
                when (method) {
                    "GET" -> {
                        if (!f.isFile) return err(404, "그런 파일이 없음")
                        val size = f.length()
                        val rg = qs["_range"]?.removePrefix("bytes=")
                        val ent = "$size:${f.lastModified() * 1_000_000}:${f.path.hashCode().toLong() and 0xffffffffL}"
                        if (rg != null && rg.contains('-')) {
                            val a = rg.substringBefore('-').toLongOrNull() ?: 0
                            val b = (rg.substringAfter('-').toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                            if (a >= size) return Reply(416, range = "bytes */$size")
                            return Reply(206, "application/octet-stream", file = f, off = a, len = b - a + 1, range = "bytes $a-$b/$size", entry = ent)
                        }
                        return Reply(200, "application/octet-stream", file = f, off = 0, len = size, entry = ent)
                    }
                    "PUT", "POST" -> {
                        if (p.isEmpty()) return err(400, "경로가 없음")
                        f.parentFile?.mkdirs()
                        val tmp = File(f.parentFile, ".mydrive-up-" + j.getString("id"))
                        val c = open("GET", "/v1/phone/body?id=" + j.getString("id"), 30 * 60_000)
                        try { c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } } }
                        catch (e: Exception) { tmp.delete(); throw e } finally { c.disconnect() }
                        if (f.exists() && !f.delete()) { tmp.delete(); return err(409, "바꿀 수 없음") }
                        if (!tmp.renameTo(f)) { tmp.delete(); return err(500, "저장하지 못함") }
                        qs["mtime"]?.toLongOrNull()?.takeIf { it > 0 }?.let { f.setLastModified(it / 1_000_000) }
                        fs.scan(f)
                        return json(entry(f, p))
                    }
                    "DELETE" -> {
                        if (!f.exists()) return err(404, "없음")
                        fs.delete(Loc("local", root.path), listOf(Item(f.name, f.isDirectory, 0, 0, p)))   // 앱 휴지통으로
                        return json(JSONObject().put("ok", true))
                    }
                }
            }
            "/v1/mkdir" -> {
                val p = rel(qs["path"]); val f = File(root, p)
                if (f.exists()) return if (f.isDirectory) json(entry(f, p)) else err(409, "같은 이름의 파일이 있음")
                if (!f.mkdirs()) return err(500, "폴더를 만들지 못함")
                return json(entry(f, p))
            }
            "/v1/move" -> {
                val from = rel(qs["from"]); val to = rel(qs["to"])
                val a = File(root, from); val b = File(root, to)
                if (!a.exists()) return err(404, "없음")
                if (b.exists() && !b.path.equals(a.path, true)) return err(409, "같은 이름이 있음")
                b.parentFile?.mkdirs()
                if (!a.renameTo(b)) return err(500, "옮기지 못함")
                fs.scan(a); fs.scan(b)
                return json(entry(b, to))
            }
        }
        return err(400, "휴대폰에서 할 수 없는 요청")
    }
}
