package kr.artchijun.mydrive

// 클라우드 저장소 (Google Drive · OneDrive): 휴대폰에서 각 회사 서버로 바로 주고받음 (개발자 서버 없음).
//   계정을 여러 개 붙일 수 있음 (구글 계정 둘 등). 계정마다 id: "gd:이메일", "od:이메일"
//   구글 로그인 (파일관리자+ 같은 앱과 같은 방식): 휴대폰에 로그인된 구글 계정을 고르고 → 안드로이드 계정 관리자가
//     허용 화면을 띄우고 토큰을 줌 (구글 클라우드 프로젝트에 앱 패키지·서명이 등록돼 있음). 브라우저를 거치지 않음.
//   예비 방식 (원드라이브, 또는 계정 관리자로 안 될 때): 브라우저 동의 화면 → 휴대폰 안(127.0.0.1)으로 결과 받기 (PKCE).
//     기다리는 동안 앱이 멈추지 않게 알림(포그라운드 서비스)을 띄워 둠.
//   토큰은 앱 설정(휴대폰 안)에만 둠. 경로는 이름으로 (폴더/파일), 서버의 id 는 앱이 기억해 둠.

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

abstract class CloudDrive(protected val ctx: Context, val id: String, val kind: String, val title: String) {
    protected val pf = Prefs(ctx)
    abstract val configured: Boolean                       // 앱에 등록 값(client id)이 들어 있나
    /** 안드로이드 계정 관리자로 토큰을 받는 계정 (구글) — 비면 브라우저 방식 */
    open val deviceAccount: String get() = state().optString("acct")
    val connected get() = state().optString("refresh").isNotEmpty() || deviceAccount.isNotEmpty()
    val account get() = state().optString("email").ifEmpty { deviceAccount }

    protected fun state(): JSONObject = try { JSONObject(pf.str("cloud_$id", "{}")) } catch (e: Exception) { JSONObject() }
    protected fun save(j: JSONObject) = pf.putStr("cloud_$id", j.toString())
    fun disconnect() {
        if (deviceAccount.isNotEmpty()) state().optString("access").takeIf { it.isNotEmpty() }?.let { try { AccountManager.get(ctx).invalidateAuthToken("com.google", it) } catch (e: Exception) { } }
        pf.putStr("cloud_$id", "{}"); ids.clear(); forgetAccount(ctx, id)
    }

    // ---------- 로그인 ----------
    protected abstract fun authUrl(redirect: String, challenge: String, st: String): String
    /** 받은 code → 토큰 (refresh 포함) JSON */
    protected abstract fun exchange(code: String, redirect: String, verifier: String): JSONObject
    protected abstract fun refreshToken(refresh: String): JSONObject
    protected abstract fun fetchEmail(): String

    /** 브라우저로 로그인 → 끝나면 done(오류 글 또는 null) (UI 줄에서) */
    fun connect(a: Activity, done: (String?) -> Unit) {
        val verifier = rand(48)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val st = rand(16)
        val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5 * 60_000 }
        val redirect = "http://127.0.0.1:${ss.localPort}"
        // 브라우저에 가 있는 동안 안드로이드가 이 앱을 얼리지 않게: 알림을 띄운 채(포그라운드 서비스) 기다림
        Transfers.add(a, Transfers.Job(Transfers.Kind.LOGIN, "", "$title 로그인") { _ ->
            val err = try {
                var code: String? = null; var bad: String? = null
                while (code == null && bad == null) {
                    ss.accept().use { s ->
                        val line = s.getInputStream().bufferedReader().readLine() ?: ""
                        val target = line.split(' ').getOrNull(1) ?: ""
                        if (!target.startsWith("/?") && !target.startsWith("/oauth")) { s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
                        val q = Uri.parse("http://x$target")
                        if (q.getQueryParameter("state") != st) bad = "로그인 확인 값이 맞지 않습니다"
                        else { code = q.getQueryParameter("code"); if (code == null) bad = q.getQueryParameter("error_description") ?: q.getQueryParameter("error") ?: "로그인하지 않았습니다" }
                        val msg = if (code != null) "$title 연결 완료 — Mydrive 로 돌아가세요." else "연결하지 못했습니다: $bad"
                        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:40px;font-size:18px'>$msg</body>"
                        val b = html.toByteArray()
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b)
                    }
                }
                if (bad != null) bad else {
                    val t = exchange(code!!, redirect, verifier)
                    val j = JSONObject().put("refresh", t.optString("refresh_token")).put("access", t.optString("access_token"))
                        .put("exp", System.currentTimeMillis() + t.optLong("expires_in", 3000) * 1000 - 60_000)
                    if (j.optString("refresh").isEmpty()) "새로 고침 토큰을 받지 못했습니다" else {
                        save(j); ids.clear()
                        try { save(state().put("email", fetchEmail())) } catch (e: Exception) { }
                        rememberAccount(ctx, id, kind)
                        null
                    }
                }
            } catch (e: java.net.SocketTimeoutException) { "시간이 지나 연결을 그만두었습니다" }
            catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            finally { try { ss.close() } catch (e: Exception) { } }
            a.runOnUiThread {
                if (!a.isFinishing) a.startActivity(Intent(a, a.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                done(err)
            }
            null
        })
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl(redirect, challenge, st))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { ss.close(); done("브라우저를 열지 못했습니다") }
    }

    @Synchronized protected open fun token(): String {
        val s = state()
        if (s.optString("access").isNotEmpty() && s.optLong("exp") > System.currentTimeMillis()) return s.optString("access")
        val r = s.optString("refresh").ifEmpty { throw IOException("$title 에 연결되어 있지 않습니다 (설정 → 클라우드)") }
        val t = refreshToken(r)
        s.put("access", t.optString("access_token")).put("exp", System.currentTimeMillis() + t.optLong("expires_in", 3000) * 1000 - 60_000)
        t.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { s.put("refresh", it) }
        save(s); return s.optString("access")
    }

    // ---------- HTTP ----------
    protected fun http(method: String, url: String, body: String? = null, ctype: String = "application/json", auth: Boolean = true,
                       headers: Map<String, String> = emptyMap(), timeout: Int = 30_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = timeout
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        if (method == "PATCH") patchFix(c)
        if (auth) c.setRequestProperty("Authorization", "Bearer " + token())
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", ctype); c.outputStream.use { it.write(body.toByteArray()) } }
        return c
    }
    /** HttpURLConnection 은 PATCH 를 모름 → 가능하면 진짜 PATCH 로 (리플렉션), 안 되면 X-HTTP-Method-Override */
    private fun patchFix(c: HttpURLConnection) {
        try { HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(c, "PATCH") } catch (e: Exception) { }
    }
    protected fun json(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val m = try { JSONObject(txt).let { j -> j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: j.optString("error_description").ifEmpty { j.optString("error") } } } catch (e: Exception) { "" }
            if (code == 401) onUnauthorized()
            throw CloudException(code, "$title: " + m.ifEmpty { "HTTP $code" })
        }
        return if (txt.isBlank()) JSONObject() else JSONObject(txt)
    }
    /** 토큰이 거절됨 → 다음 요청 때 새로 받게 */
    protected open fun onUnauthorized() { save(state().put("exp", 0)) }

    protected fun form(url: String, vararg kv: Pair<String, String>): JSONObject =
        json(http("POST", url, kv.joinToString("&") { Api.enc(it.first) + "=" + Api.enc(it.second) }, "application/x-www-form-urlencoded", auth = false))

    protected fun pump(inp: InputStream, out: File, total: Long, prog: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val part = File(out.path + ".part")
        part.outputStream().use { o ->
            val buf = ByteArray(256 * 1024); var done = 0L
            while (true) { if (cancel()) throw Canceled(); val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; prog(done, maxOf(total, done)) }
        }
        out.delete(); if (!part.renameTo(out)) throw IOException("저장하지 못함")
    }

    // ---------- 파일 일 (path = 이름 경로, 맨 위 = "") ----------
    protected val ids = HashMap<String, String>()          // 경로 → 서버 id (Drive)
    abstract fun list(path: String): List<Item>
    abstract fun mkdir(path: String)
    abstract fun rename(path: String, newName: String)
    abstract fun move(path: String, toDir: String)
    abstract fun trash(path: String)
    abstract fun download(path: String, size: Long, out: File, prog: (Long, Long) -> Unit, cancel: () -> Boolean)
    abstract fun upload(dir: String, name: String, size: Long, open: () -> InputStream, prog: (Long, Long) -> Unit, cancel: () -> Boolean)
    /** (쓴 양, 전체) */
    abstract fun quota(): Pair<Long, Long>?
    /** 목록 줄의 미리보기 그림 (Item.extra 의 주소) */
    fun thumb(url: String): ByteArray? = try {
        val c = http("GET", url, auth = url.contains("googleusercontent.com") || url.contains("googleapis.com"))
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) { null }

    companion object {
        private val rnd = SecureRandom()
        fun rand(n: Int): String { val b = ByteArray(n); rnd.nextBytes(b); return b64(b) }
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun time(s: String): Long = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time ?: 0
        } catch (e: Exception) { 0 }

        /** 붙일 수 있는 종류: (kind, 이름) */
        val kinds = listOf("gd" to "Google Drive", "od" to "OneDrive")

        // 붙인 계정 목록: pref "cloudAccts" = [{"id","kind"}] (계정마다 상태는 "cloud_<id>")
        private val cache = HashMap<String, CloudDrive>()
        private fun ids(ctx: Context): List<Pair<String, String>> {
            val p = Prefs(ctx)
            val a = try { org.json.JSONArray(p.str("cloudAccts", "[]")) } catch (e: Exception) { org.json.JSONArray() }
            val out = (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("id") to o.optString("kind") } }.toMutableList()
            // 0.6.0 에서 붙인 구글 드라이브 (id "gd", 브라우저 방식) 이어 쓰기
            if (out.none { it.first == "gd" } && (try { JSONObject(p.str("cloud_gd", "{}")).optString("refresh") } catch (e: Exception) { "" }).isNotEmpty()) out.add(0, "gd" to "gd")
            return out
        }
        fun rememberAccount(ctx: Context, id: String, kind: String) {
            val p = Prefs(ctx)
            val l = ids(ctx).filter { it.first != id } + (id to kind)
            p.putStr("cloudAccts", org.json.JSONArray(l.map { JSONObject().put("id", it.first).put("kind", it.second) }).toString())
        }
        fun forgetAccount(ctx: Context, id: String) {
            val p = Prefs(ctx)
            p.putStr("cloudAccts", org.json.JSONArray(ids(ctx).filter { it.first != id }.map { JSONObject().put("id", it.first).put("kind", it.second) }).toString())
            cache.remove(id)
        }
        fun make(ctx: Context, id: String, kind: String): CloudDrive = cache.getOrPut(id) {
            if (kind == "gd") GDrive(ctx.applicationContext, id) else OneDrive(ctx.applicationContext, id)
        }
        /** 붙인 계정들 (연결이 살아 있는 것만) */
        fun all(ctx: Context): List<CloudDrive> = ids(ctx).map { (id, k) -> make(ctx, id, k) }.filter { it.connected }
        fun of(ctx: Context, id: String): CloudDrive = make(ctx, id, ids(ctx).firstOrNull { it.first == id }?.second ?: id.substringBefore(':'))
    }
}

class CloudException(val status: Int, message: String) : IOException(message)

// ================= Google Drive (API v3) =================
class GDrive(ctx: Context, id: String = "gd") : CloudDrive(ctx, id, "gd", "Google Drive") {
    private val cid = BuildConfig.GD_CLIENT_ID
    private val secret = BuildConfig.GD_CLIENT_SECRET
    override val configured get() = true
    companion object {
        const val SCOPE = "oauth2:https://www.googleapis.com/auth/drive"
        /** 휴대폰의 구글 계정으로 붙임 (CloudConnectActivity 가 허용까지 받은 뒤) */
        fun addDeviceAccount(ctx: Context, email: String): CloudDrive {
            val id = "gd:$email"
            Prefs(ctx).putStr("cloud_$id", JSONObject().put("acct", email).put("email", email).toString())
            rememberAccount(ctx, id, "gd")
            return make(ctx, id, "gd")
        }
    }
    // 휴대폰 구글 계정: 안드로이드 계정 관리자가 토큰을 줌 (만료되면 알아서 새로)
    override fun token(): String {
        val acct = deviceAccount
        if (acct.isEmpty()) return super.token()
        val t = try { AccountManager.get(ctx).blockingGetAuthToken(Account(acct, "com.google"), SCOPE, true) }
            catch (e: Exception) { throw IOException("$title ($acct): 토큰을 받지 못했습니다 — ${e.message ?: e.javaClass.simpleName}") }
            ?: throw IOException("$title ($acct): 허용이 필요합니다 — 홈의 클라우드에서 다시 연결해 주세요")
        synchronized(this) { save(state().put("access", t)) }
        return t
    }
    override fun onUnauthorized() {
        if (deviceAccount.isEmpty()) { super.onUnauthorized(); return }
        state().optString("access").takeIf { it.isNotEmpty() }?.let { AccountManager.get(ctx).invalidateAuthToken("com.google", it) }
    }
    private val api = "https://www.googleapis.com/drive/v3"
    private val FOLDER = "application/vnd.google-apps.folder"
    /** 구글 문서류는 내려받을 때 바꿔서: 확장자 → 형식 */
    private val export = mapOf("application/vnd.google-apps.document" to (".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "application/vnd.google-apps.spreadsheet" to (".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "application/vnd.google-apps.presentation" to (".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        "application/vnd.google-apps.drawing" to (".png" to "image/png"))
    private val kinds = HashMap<String, String>()          // 경로 → mimeType

    override fun authUrl(redirect: String, challenge: String, st: String) = "https://accounts.google.com/o/oauth2/v2/auth?" +
        listOf("client_id" to cid, "redirect_uri" to redirect, "response_type" to "code", "scope" to "https://www.googleapis.com/auth/drive",
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to st, "access_type" to "offline", "prompt" to "consent")
            .joinToString("&") { Api.enc(it.first) + "=" + Api.enc(it.second) }
    override fun exchange(code: String, redirect: String, verifier: String) = form("https://oauth2.googleapis.com/token",
        "code" to code, "client_id" to cid, "client_secret" to secret, "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier)
    override fun refreshToken(refresh: String) = form("https://oauth2.googleapis.com/token",
        "refresh_token" to refresh, "client_id" to cid, "client_secret" to secret, "grant_type" to "refresh_token")
    override fun fetchEmail(): String = json(http("GET", "$api/about?fields=user(emailAddress)")).optJSONObject("user")?.optString("emailAddress") ?: ""

    private fun q(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun idOf(path: String): String {
        if (path.isEmpty()) return "root"
        ids[path]?.let { return it }
        val parent = idOf(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val fs = json(http("GET", "$api/files?" + Api.query(mapOf("q" to "'$parent' in parents and name = '${q(name)}' and trashed = false", "fields" to "files(id,mimeType)", "pageSize" to "10"))))
            .optJSONArray("files")
        val f = fs?.optJSONObject(0)
        if (f == null) {   // 구글 문서는 목록에서 확장자를 붙여 보여 줌 → 붙인 것 떼고 다시
            val strip = export.values.firstOrNull { name.endsWith(it.first) }?.let { name.removeSuffix(it.first) } ?: throw IOException("$name — 없습니다")
            val g = json(http("GET", "$api/files?" + Api.query(mapOf("q" to "'$parent' in parents and name = '${q(strip)}' and trashed = false", "fields" to "files(id,mimeType)"))))
                .optJSONArray("files")?.optJSONObject(0) ?: throw IOException("$name — 없습니다")
            ids[path] = g.getString("id"); kinds[path] = g.getString("mimeType"); return ids[path]!!
        }
        ids[path] = f.getString("id"); kinds[path] = f.getString("mimeType")
        return ids[path]!!
    }

    override fun list(path: String): List<Item> = try { list1(path) } catch (e: CloudException) { if (e.status == 401) list1(path) else throw e }
    private fun list1(path: String): List<Item> {
        val pid = idOf(path)
        val out = ArrayList<Item>(); var page = ""
        do {
            val qs = mutableMapOf("q" to "'$pid' in parents and trashed = false", "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink)")
            if (page.isNotEmpty()) qs["pageToken"] = page
            val j = json(http("GET", "$api/files?" + Api.query(qs)))
            val fs = j.optJSONArray("files")
            for (i in 0 until (fs?.length() ?: 0)) {
                val f = fs!!.getJSONObject(i)
                val mime = f.optString("mimeType"); val dir = mime == FOLDER
                if (!dir && mime.startsWith("application/vnd.google-apps.") && mime !in export) continue   // 양식·지도 등은 내려받을 수 없음
                val name = f.optString("name").replace('/', '_') + (export[mime]?.first ?: "")
                val p = if (path.isEmpty()) name else "$path/$name"
                ids[p] = f.getString("id"); kinds[p] = mime
                out += Item(name, dir, f.optString("size", "0").toLongOrNull() ?: 0, time(f.optString("modifiedTime")), p, extra = f.optString("thumbnailLink"))
            }
            page = j.optString("nextPageToken")
        } while (page.isNotEmpty())
        return out
    }

    override fun mkdir(path: String) {
        val parent = idOf(path.substringBeforeLast('/', "")); val name = path.substringAfterLast('/')
        val have = json(http("GET", "$api/files?" + Api.query(mapOf("q" to "'$parent' in parents and name = '${q(name)}' and mimeType = '$FOLDER' and trashed = false", "fields" to "files(id)"))))
            .optJSONArray("files")?.optJSONObject(0)
        if (have != null) { ids[path] = have.getString("id"); return }
        val j = json(http("POST", "$api/files?fields=id", JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", org.json.JSONArray().put(parent)).toString()))
        ids[path] = j.getString("id"); kinds[path] = FOLDER
    }
    override fun rename(path: String, newName: String) {
        val id = idOf(path)
        val clean = export.values.firstOrNull { newName.endsWith(it.first) && kinds[path] in export }?.let { newName.removeSuffix(it.first) } ?: newName
        json(http("PATCH", "$api/files/$id?fields=id", JSONObject().put("name", clean).toString())); forget(path)
    }
    override fun move(path: String, toDir: String) {
        val id = idOf(path)
        val old = json(http("GET", "$api/files/$id?fields=parents")).optJSONArray("parents")?.let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } } ?: ""
        json(http("PATCH", "$api/files/$id?" + Api.query(mapOf("addParents" to idOf(toDir), "removeParents" to old, "fields" to "id")), "{}")); forget(path)
    }
    override fun trash(path: String) { json(http("PATCH", "$api/files/${idOf(path)}?fields=id", JSONObject().put("trashed", true).toString())); forget(path) }
    private fun forget(path: String) { ids.keys.removeAll { it == path || it.startsWith("$path/") } }

    override fun download(path: String, size: Long, out: File, prog: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val id = idOf(path)
        val ex = export[kinds[path]]
        val c = http("GET", if (ex != null) "$api/files/$id/export?mimeType=" + Api.enc(ex.second) else "$api/files/$id?alt=media", timeout = 60_000)
        if (c.responseCode != 200) { json(c); return }
        c.inputStream.use { pump(it, out, size, prog, cancel) }
    }

    override fun upload(dir: String, name: String, size: Long, open: () -> InputStream, prog: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(idOf(dir))).toString()
        val s = http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", meta, "application/json; charset=UTF-8",
            headers = mapOf("X-Upload-Content-Length" to size.toString()))
        if (s.responseCode != 200) { json(s); return }
        val loc = s.getHeaderField("Location") ?: throw IOException("올리기 주소를 받지 못함"); s.disconnect()
        val c = URL(loc).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.setFixedLengthStreamingMode(size)
        c.outputStream.use { o -> open().use { inp ->
            val buf = ByteArray(256 * 1024); var done = 0L
            while (true) { if (cancel()) { c.disconnect(); throw Canceled() }; val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; prog(done, size) }
        } }
        val j = json(c)
        val p = if (dir.isEmpty()) name else "$dir/$name"
        j.optString("id").takeIf { it.isNotEmpty() }?.let { ids[p] = it }
    }

    override fun quota(): Pair<Long, Long>? = try {
        val q = json(http("GET", "$api/about?fields=storageQuota(limit,usage)")).getJSONObject("storageQuota")
        q.optLong("usage") to q.optLong("limit")
    } catch (e: Exception) { null }
}

// ================= OneDrive (Microsoft Graph) =================
class OneDrive(ctx: Context, id: String = "od") : CloudDrive(ctx, id, "od", "OneDrive") {
    private val cid = BuildConfig.OD_CLIENT_ID
    override val configured get() = cid.isNotEmpty()
    private val g = "https://graph.microsoft.com/v1.0/me/drive"
    private val login = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private val scope = "Files.ReadWrite User.Read offline_access"

    override fun authUrl(redirect: String, challenge: String, st: String) = "$login/authorize?" +
        listOf("client_id" to cid, "redirect_uri" to redirect, "response_type" to "code", "scope" to scope, "response_mode" to "query",
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to st, "prompt" to "select_account")
            .joinToString("&") { Api.enc(it.first) + "=" + Api.enc(it.second) }
    override fun exchange(code: String, redirect: String, verifier: String) = form("$login/token",
        "client_id" to cid, "code" to code, "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier, "scope" to scope)
    override fun refreshToken(refresh: String) = form("$login/token", "client_id" to cid, "refresh_token" to refresh, "grant_type" to "refresh_token", "scope" to scope)
    override fun fetchEmail(): String = json(http("GET", "https://graph.microsoft.com/v1.0/me?\$select=userPrincipalName,mail")).let { it.optString("mail").ifEmpty { it.optString("userPrincipalName") } }

    private fun enc(path: String) = path.split('/').joinToString("/") { Api.enc(it) }
    private fun item(path: String) = if (path.isEmpty()) "$g/root" else "$g/root:/${enc(path)}:"

    override fun list(path: String): List<Item> {
        val out = ArrayList<Item>()
        var url: String? = item(path) + "/children?\$top=1000&\$expand=thumbnails(\$select=medium)"
        while (url != null) {
            val j = json(http("GET", url))
            val vs = j.optJSONArray("value")
            for (i in 0 until (vs?.length() ?: 0)) {
                val f = vs!!.getJSONObject(i)
                val name = f.optString("name"); val dir = f.has("folder") || f.has("package")
                val th = f.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONObject("medium")?.optString("url") ?: ""
                out += Item(name, dir, if (dir) 0 else f.optLong("size"), time(f.optString("lastModifiedDateTime")), if (path.isEmpty()) name else "$path/$name",
                    count = f.optJSONObject("folder")?.optInt("childCount", -1) ?: -1, extra = th)
            }
            url = j.optString("@odata.nextLink").ifEmpty { null }
        }
        return out
    }
    override fun mkdir(path: String) {
        try { json(http("POST", item(path.substringBeforeLast('/', "")) + "/children",
            JSONObject().put("name", path.substringAfterLast('/')).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail").toString())) }
        catch (e: CloudException) { if (e.status != 409) throw e }
    }
    override fun rename(path: String, newName: String) { json(http("PATCH", item(path), JSONObject().put("name", newName).toString())) }
    override fun move(path: String, toDir: String) {
        json(http("PATCH", item(path), JSONObject().put("parentReference", JSONObject().put("path", "/drive/root" + (if (toDir.isEmpty()) "" else ":/" + toDir))).toString()))
    }
    override fun trash(path: String) { val c = http("DELETE", item(path)); if (c.responseCode !in 200..299) json(c) else c.disconnect() }
    override fun download(path: String, size: Long, out: File, prog: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val c = http("GET", item(path) + "/content", timeout = 60_000).apply { instanceFollowRedirects = false }
        var r = c
        if (c.responseCode in 300..399) { val to = c.getHeaderField("Location"); c.disconnect(); r = URL(to).openConnection() as HttpURLConnection; r.readTimeout = 60_000 }
        if (r.responseCode != 200) { json(r); return }
        r.inputStream.use { pump(it, out, size, prog, cancel) }
    }
    override fun upload(dir: String, name: String, size: Long, open: () -> InputStream, prog: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val p = if (dir.isEmpty()) name else "$dir/$name"
        val up = json(http("POST", item(p) + "/createUploadSession", JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "rename")).toString())).getString("uploadUrl")
        val chunk = 320 * 1024 * 32L   // 10MB (320KiB 의 배수)
        open().use { inp ->
            var off = 0L
            if (size == 0L) { json((URL(up).openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; doOutput = true; setRequestProperty("Content-Range", "bytes */0"); setFixedLengthStreamingMode(0) }); return }
            val buf = ByteArray(256 * 1024)
            while (off < size) {
                val n = minOf(chunk, size - off)
                val c = URL(up).openConnection() as HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 120_000
                c.setRequestProperty("Content-Range", "bytes $off-${off + n - 1}/$size"); c.setFixedLengthStreamingMode(n)
                c.outputStream.use { o ->
                    var left = n
                    while (left > 0) { if (cancel()) { c.disconnect(); throw Canceled() }; val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw IOException("파일이 짧아졌습니다"); o.write(buf, 0, r); left -= r; prog(off + n - left, size) }
                }
                json(c); off += n
            }
        }
    }
    override fun quota(): Pair<Long, Long>? = try { json(http("GET", "$g?\$select=quota")).getJSONObject("quota").let { it.optLong("used") to it.optLong("total") } } catch (e: Exception) { null }
}
