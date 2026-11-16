package kr.artchijun.myphoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 시놀로지가 돌려준 오류 (code 는 DSM 오류 번호) */
class SynoError(val code: Int, msg: String) : IOException(msg)

/** 사진·동영상 한 장 */
data class Item(
    val id: Long, val name: String, val size: Long, val time: Long,   // time: 찍은 때 (초)
    val type: String, val folderId: Long, val cacheKey: String, val unitId: Long,
    val w: Int, val h: Int, val orient: Int, val fav: Boolean, val indexed: Long = 0,   // indexed: 시놀로지에 들어온 때 (ms)
    val dur: Long = 0,   // 동영상 길이 (ms, 모르면 0)
    val local: String? = null,   // 휴대폰에만 있는 파일이면 그 content:// 주소 (id 는 음수)
) {
    val video get() = type == "video"
    val live get() = type == "live"
    companion object {
        fun of(o: JSONObject): Item {
            val a = o.optJSONObject("additional")
            val th = a?.optJSONObject("thumbnail")
            val res = a?.optJSONObject("resolution")
            return Item(o.optLong("id"), o.optString("filename"), o.optLong("filesize"), o.optLong("time"), o.optString("type"),
                o.optLong("folder_id"), th?.optString("cache_key") ?: "", th?.optLong("unit_id", o.optLong("id")) ?: o.optLong("id"),
                res?.optInt("width") ?: 0, res?.optInt("height") ?: 0, a?.optInt("orientation", 1) ?: 1, a?.optBoolean("favorite") ?: false, o.optLong("indexed_time"),
                a?.optJSONObject("video_meta")?.optLong("duration") ?: 0)
        }
    }
}

/**
 * 시놀로지 포토 서버와 이야기하는 곳 (DSM 웹 API, entry.cgi). 서버는 손대지 않고 그대로 씀.
 *   공유 공간: SYNO.FotoTeam.* (이 집 사진은 모두 여기),  개인 공간: SYNO.Foto.* (꺼져 있으면 602)
 *   세션(sid)이 끝나면(119·106·107) 저장해 둔 계정으로 조용히 다시 로그인하고 한 번 더 시도
 */
class Syno(ctx: Context) {
    val pf = Prefs(ctx.applicationContext)
    private val deviceName = "Myphoto (" + (android.os.Build.MODEL ?: "Android") + ")"

    companion object {
        @Volatile private var base: String? = null
        @Volatile private var baseAt = 0L
        private val loginLock = Any()
        /** 밖에서 이보다 큰 파일은 나눠 올림 (Cloudflare 터널은 요청 하나에 100MB 까지) */
        const val SPLIT_OVER = 90L * 1024 * 1024

        fun authMessage(code: Int): String = when (code) {
            400 -> "계정이나 비밀번호가 맞지 않습니다"
            401 -> "꺼진 계정입니다"
            402 -> "이 계정은 쓸 수 없습니다"
            403 -> "2단계 인증 코드를 넣어 주세요"
            404 -> "2단계 인증 코드가 맞지 않습니다"
            406 -> "이 계정은 2단계 인증을 먼저 켜야 합니다 (DSM 에서)"
            407 -> "로그인을 너무 많이 실패해 이 주소가 잠시 막혔습니다"
            408, 409, 410 -> "비밀번호가 만료되었습니다 — DSM 에서 바꿔 주세요"
            else -> "로그인하지 못했습니다 (오류 $code)"
        }
        fun message(code: Int): String = when (code) {
            105 -> "권한이 없습니다"
            106, 107, 119 -> "다시 로그인해야 합니다"
            117, 120 -> "요청이 올바르지 않습니다 (오류 $code)"
            602 -> "개인 공간이 꺼져 있습니다"
            641 -> "같은 이름이 이미 있습니다"
            620 -> "시놀로지 포토가 받지 않는 값입니다 (오류 620 — 파일 이름·형식 등)"
            else -> "시놀로지 오류 $code"
        }
    }

    // ---------- 주소 고르기: 집 안 주소가 닿으면 그것, 아니면 밖 주소 ----------
    private fun reachable(s: String): Boolean = try {
        val c = URL("$s/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth").openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 4000
        val ok = c.responseCode == 200; c.disconnect(); ok
    } catch (e: Exception) { false }

    fun server(force: Boolean = false): String {
        val b = base
        if (b != null && !force && System.currentTimeMillis() - baseAt < 5 * 60_000) return b
        for (s in pf.servers) if (reachable(s)) { base = s; baseAt = System.currentTimeMillis(); return s }
        base = null
        throw IOException("시놀로지에 닿지 않습니다 (집 와이파이나 인터넷 연결을 확인하세요)")
    }
    val serverOrNull: String? get() = base

    // ---------- 로그인 ----------
    /** 로그인해서 세션을 저장. otp: 2단계 인증 코드 (필요할 때만) */
    fun login(account: String, password: String, otp: String? = null) {
        val s = server(true)
        val q = linkedMapOf(
            "api" to "SYNO.API.Auth", "version" to "6", "method" to "login",
            "account" to account, "passwd" to password, "session" to "Myphoto", "format" to "sid",
            "enable_syno_token" to "yes", "enable_device_token" to "yes", "device_name" to deviceName,
        )
        if (!otp.isNullOrBlank()) q["otp_code"] = otp.trim()
        if (pf.deviceId.isNotEmpty() && pf.account == account) q["device_id"] = pf.deviceId
        val c = URL("$s/webapi/entry.cgi").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form(q).toByteArray()) }
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
        if (!j.optBoolean("success")) {
            val code = j.optJSONObject("error")?.optInt("code") ?: 0
            throw SynoError(code, authMessage(code))
        }
        val d = j.getJSONObject("data")
        pf.account = account; pf.sid = d.optString("sid"); pf.synoToken = d.optString("synotoken")
        d.optString("did").takeIf { it.isNotEmpty() }?.let { pf.deviceId = it }
        pf.password = password
    }

    /** 세션이 끝났을 때: 저장한 비밀번호로 다시 (2단계 인증은 기기 열쇠로 건너뜀) */
    private fun relogin(): Boolean = synchronized(loginLock) {
        val pw = pf.password
        if (pf.account.isEmpty() || pw.isEmpty()) return false
        return try { login(pf.account, pw); true } catch (e: SynoError) { false }
    }

    fun logout() {
        try { call("SYNO.API.Auth", "logout", 6, mapOf("session" to "Myphoto"), retry = false) } catch (_: Exception) {}
        pf.logout()
    }

    // ---------- 호출 ----------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun form(m: Map<String, String>) = m.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    /** 값은 시놀로지 웹과 같게 JSON 으로 (글자는 따옴표, 배열은 [..]) */
    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> JSONObject.quote(v)
        is Collection<*> -> JSONArray(v.toList()).toString()
        is Array<*> -> JSONArray(v.toList()).toString()
        is LongArray -> JSONArray(v.toList()).toString()
        is JSONArray, is JSONObject -> v.toString()
        else -> v.toString()
    }

    /** API 하나 부르기 → data (실패하면 SynoError) */
    fun call(api: String, method: String, version: Int, params: Map<String, Any?> = emptyMap(), retry: Boolean = true): JSONObject {
        val s = server()
        val q = linkedMapOf("api" to api, "method" to method, "version" to version.toString())
        for ((k, v) in params) q[k] = jsonValue(v)
        q["_sid"] = pf.sid
        val c = try { URL("$s/webapi/entry.cgi").openConnection() as HttpURLConnection } catch (e: IOException) { base = null; throw e }
        val j = try {
            c.connectTimeout = 10_000; c.readTimeout = 60_000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (pf.synoToken.isNotEmpty()) c.setRequestProperty("X-SYNO-TOKEN", pf.synoToken)
            c.outputStream.use { it.write(form(q).toByteArray()) }
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) { base = null; throw e } finally { c.disconnect() }
        if (j.optBoolean("success")) return j.optJSONObject("data") ?: JSONObject()
        val code = j.optJSONObject("error")?.optInt("code") ?: 0
        if (retry && code in setOf(106, 107, 119) && relogin()) return call(api, method, version, params, false)
        throw SynoError(code, message(code))
    }

    /** GET 주소 (썸네일·내려받기·동영상) — 세션을 주소에 붙임 */
    fun url(api: String, method: String, version: Int, params: Map<String, Any?>): String {
        val q = linkedMapOf("api" to api, "method" to method, "version" to version.toString())
        for ((k, v) in params) q[k] = if (v is String) v else jsonValue(v)
        q["_sid"] = pf.sid
        if (pf.synoToken.isNotEmpty()) q["SynoToken"] = pf.synoToken
        return server() + "/webapi/entry.cgi?" + form(q)
    }

    /** 주소를 열어 내용 받기. 세션이 끝났으면 다시 로그인하고 주소를 새로 만들어 한 번 더 */
    fun open(make: () -> String, timeout: Int = 30_000): HttpURLConnection {
        for (attempt in 0..1) {
            val c = URL(make()).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = timeout
            val code = try { c.responseCode } catch (e: IOException) { base = null; throw e }
            val type = c.contentType ?: ""
            if (code == 200 && !type.startsWith("application/json") && !type.startsWith("text/")) return c
            // 오류는 JSON 으로 옴
            val body = try { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
            c.disconnect()
            val err = try { JSONObject(body).optJSONObject("error")?.optInt("code") ?: 0 } catch (e: Exception) { 0 }
            if (attempt == 0 && err in setOf(106, 107, 119) && relogin()) continue
            if (code == 200 && err == 0 && body.isNotEmpty()) throw IOException("받지 못했습니다")
            throw SynoError(err, if (err != 0) message(err) else "받지 못했습니다 (HTTP $code)")
        }
        throw IOException("받지 못했습니다")
    }

    // ---------- 사진 주소 ----------
    /** 썸네일 크기: sm(240) · m(320) · xl(1280) */
    fun thumbUrl(ns: String, unitId: Long, cacheKey: String, size: String, type: String = "unit") =
        url("$ns.Thumbnail", "get", 2, mapOf("id" to unitId.toString(), "cache_key" to cacheKey, "type" to type, "size" to size))

    fun originalUrl(ns: String, it: Item) =
        url("$ns.Download", "download", 2, mapOf("unit_id" to "[${it.unitId}]", "cache_key" to it.cacheKey, "download_type" to "source"))

    fun streamUrl(ns: String, it: Item, quality: String = "medium") =
        url("$ns.Streaming", "streaming", 2, mapOf("id" to it.id.toString(), "type" to "item", "quality" to quality))

    fun thumbStream(ns: String, unitId: Long, cacheKey: String, size: String, type: String = "unit"): InputStream =
        open({ thumbUrl(ns, unitId, cacheKey, size, type) }).inputStream

    // ---------- 올리기 (백업) ----------
    /**
     * 파일 하나를 공유 공간 폴더에 올림 (시놀로지 웹과 같은 방식: multipart, 값은 JSON).
     * duplicate: "ignore"(같은 것이 있으면 건너뜀) · "rename" · "overwrite"
     * 돌려주는 값: 서버 응답의 data (id 등)
     */
    fun upload(ns: String, folderId: Long, name: String, mtimeSec: Long, size: Long, open: () -> InputStream,
               duplicate: String = "ignore", progress: (Long) -> Unit = {}, canceled: () -> Boolean = { false }): JSONObject {
        // 밖(Mydrive 서버를 거치는 터널)으로 큰 파일: 요청 하나 100MB 제한 → 나눠 올림
        if (size > SPLIT_OVER && server() != pf.home) return uploadInParts(ns, folderId, name, mtimeSec, size, open, duplicate, progress, canceled)
        for (attempt in 0..1) {
            val s = server()
            val boundary = "----Myphoto" + System.nanoTime()
            val fields = linkedMapOf("api" to "$ns.Upload.Item", "method" to "upload_to_folder", "version" to "8",
                "duplicate" to JSONObject.quote(duplicate), "name" to JSONObject.quote(name), "mtime" to mtimeSec.toString(),
                "target_folder_id" to folderId.toString())
            val sb = StringBuilder()
            for ((k, v) in fields) sb.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            val safe = name.replace("\"", "_")
            sb.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            val head = sb.toString().toByteArray()
            val tail = "\r\n--$boundary--\r\n".toByteArray()
            val q = "api=" + enc("$ns.Upload.Item") + "&method=upload_to_folder&version=8&_sid=" + enc(pf.sid) +
                (if (pf.synoToken.isNotEmpty()) "&SynoToken=" + enc(pf.synoToken) else "")
            val c = URL("$s/webapi/entry.cgi?$q").openConnection() as HttpURLConnection
            val j = try {
                c.connectTimeout = 15_000; c.readTimeout = 10 * 60_000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (pf.synoToken.isNotEmpty()) c.setRequestProperty("X-SYNO-TOKEN", pf.synoToken)
                c.setFixedLengthStreamingMode(head.size + size + tail.size)
                c.outputStream.use { o ->
                    o.write(head)
                    var sent = 0L
                    open().use { inp ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (canceled()) throw InterruptedException("멈춤")
                            val n = inp.read(buf); if (n < 0) break
                            if (sent + n > size) throw IOException("파일이 올리는 중에 바뀌었습니다")
                            o.write(buf, 0, n); sent += n; progress(sent)
                        }
                    }
                    if (sent != size) throw IOException("파일이 올리는 중에 바뀌었습니다")
                    o.write(tail)
                }
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: IOException) { base = null; throw e } finally { c.disconnect() }
            if (j.optBoolean("success")) return j.optJSONObject("data") ?: JSONObject()
            val code = j.optJSONObject("error")?.optInt("code") ?: 0
            if (attempt == 0 && code in setOf(106, 107, 119) && relogin()) continue
            throw SynoError(code, message(code))
        }
        throw IOException("올리지 못했습니다")
    }

    /**
     * 나눠 올리기 (밖에서 큰 파일): 32MB 조각을 차례로 Mydrive 서버에 보내고, 서버가 NAS 안에서 시놀로지에 한 번에 올림.
     * 조각 하나가 실패하면 서버가 받은 곳부터 다시 보냄 (처음부터가 아님)
     */
    private fun uploadInParts(ns: String, folderId: Long, name: String, mtimeSec: Long, size: Long, open: () -> InputStream,
                              duplicate: String, progress: (Long) -> Unit, canceled: () -> Boolean): JSONObject {
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        // 먼저 빈 조각으로 세션 확인 (끊긴 세션이면 32MB 를 다 보낸 뒤에야 거절되는 것을 막음). 밖 주소로 바뀌면 DSM 이 세션을 새로 요구함
        fun probe(): Int {
            val c = URL(server() + "/webapi/myphoto/part?id=$id&off=0&" + "_sid=" + enc(pf.sid) + (if (pf.synoToken.isNotEmpty()) "&SynoToken=" + enc(pf.synoToken) else "")).openConnection() as HttpURLConnection
            return try { c.connectTimeout = 15_000; c.readTimeout = 60_000; c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close(); c.responseCode } finally { c.disconnect() }
        }
        if (probe() == 401) { if (!relogin() || probe() == 401) throw SynoError(119, message(119)) }
        val auth = "_sid=" + enc(pf.sid) + (if (pf.synoToken.isNotEmpty()) "&SynoToken=" + enc(pf.synoToken) else "")   // 다시 로그인했으면 새 세션
        val chunk = 32 * 1024 * 1024
        val buf = ByteArray(chunk)
        open().use { inp ->
            var off = 0L
            var fails = 0
            while (off < size) {
                if (canceled()) throw InterruptedException("멈춤")
                // 조각 하나 채우기
                var n = 0
                while (n < chunk) { val k = inp.read(buf, n, chunk - n); if (k < 0) break; n += k }
                if (n == 0) throw IOException("파일이 올리는 중에 바뀌었습니다")
                var sent = false
                while (!sent) {
                    try {
                        val c = URL(server() + "/webapi/myphoto/part?id=$id&off=$off&$auth").openConnection() as HttpURLConnection
                        val j = try {
                            c.connectTimeout = 15_000; c.readTimeout = 5 * 60_000; c.requestMethod = "POST"; c.doOutput = true
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.setFixedLengthStreamingMode(n)
                            c.outputStream.use { o -> var w = 0; while (w < n) { val k = minOf(256 * 1024, n - w); o.write(buf, w, k); w += k; progress(off + w) } }
                            val code = c.responseCode
                            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                            if (code == 401) { if (fails++ < 1 && relogin()) continue; throw SynoError(119, message(119)) }
                            if (code != 200) throw IOException("조각을 보내지 못함 (HTTP $code)")
                            JSONObject(body)
                        } finally { c.disconnect() }
                        if (!j.optBoolean("ok")) throw IOException("서버가 받은 크기가 맞지 않음 (${j.optLong("have")})")
                        sent = true
                    } catch (e: IOException) {
                        if (e is SynoError || ++fails > 4 || canceled()) throw e
                        base = null; Thread.sleep(3000L * fails)   // 잠깐 끊김: 같은 조각을 다시
                    }
                }
                off += n
            }
            if (off != size) throw IOException("파일이 올리는 중에 바뀌었습니다")
        }
        // 마무리: 서버가 시놀로지에 한 번에
        val form = form(linkedMapOf("duplicate" to JSONObject.quote(duplicate), "name" to JSONObject.quote(name), "mtime" to mtimeSec.toString(),
            "target_folder_id" to folderId.toString(), "size" to size.toString()))
        val c = URL(server() + "/webapi/myphoto/finish?id=$id&api=" + enc("$ns.Upload.Item") + "&method=upload_to_folder&version=8&$auth").openConnection() as HttpURLConnection
        val j = try {
            c.connectTimeout = 15_000; c.readTimeout = 30 * 60_000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code != 200) throw IOException("마무리하지 못함 (HTTP $code) " + body.take(120))
            JSONObject(body)
        } finally { c.disconnect() }
        if (j.optBoolean("success")) return j.optJSONObject("data") ?: JSONObject()
        val code = j.optJSONObject("error")?.optInt("code") ?: 0
        throw SynoError(code, message(code))
    }
}
