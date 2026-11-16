package kr.artchijun.mydrive

// Mydrive 서버와 주고받기 (안드로이드 화면과 따로 — PC 에서 시험할 수 있게 java.net·org.json 만 씀)
//   서버 주소는 여러 개 (집 안 주소 · 인터넷(터널) 주소). 앞에서부터 짧게 확인해 되는 곳을 씀.

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

class ApiException(val status: Int, val kind: String, message: String, val have: Long = -1) : IOException(message)
class Canceled : IOException("취소함")

data class RootInfo(val name: String, val items: Int, val share: String, val total: Long, val free: Long)

data class Entry(val path: String, val dir: Boolean, val size: Long, val mtimeNs: Long) {
    val name: String get() = path.substringAfterLast('/')
}

class Api(servers: List<String>, var token: String) {
    var servers: List<String> = servers.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        set(v) { field = v.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }; active = null }
    @Volatile var active: String? = null
        private set

    /** 큰 파일은 이만큼씩 나눠 올림 (Cloudflare 터널은 요청 하나에 100MB 까지) */
    var partSize = 32L * 1024 * 1024

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun query(q: Map<String, String>) = if (q.isEmpty()) "" else "?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

        /** 서버 하나가 살아 있나 (집 밖에서 집 주소를 오래 기다리지 않게 짧게) */
        fun ping(server: String, timeoutMs: Int): Boolean = try {
            val c = URL(server.trimEnd('/') + "/v1/ping").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText().startsWith("mydrived")
            c.disconnect(); ok
        } catch (e: Exception) { false }

        /** DSM 계정으로 이 휴대폰을 연결 → 토큰 (비밀번호는 저장하지 않음) */
        fun pair(server: String, account: String, password: String, otp: String, device: String): String {
            val c = URL(server.trimEnd('/') + "/v1/pair").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("Account", account).put("Passwd", password).put("Otp", otp).put("PC", device).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            val j = JSONObject(readBody(c))
            return j.optString("token").ifEmpty { throw ApiException(500, "no_token", "서버가 토큰을 주지 않음") }
        }

        private fun readBody(c: HttpURLConnection): String {
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
            val txt = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(txt) } catch (e: Exception) { null }
            throw ApiException(code, j?.optString("error") ?: "http", j?.optString("msg")?.ifEmpty { null } ?: "서버 오류 $code", j?.optLong("have", -1) ?: -1)
        }

        fun newId(): String { val b = ByteArray(16); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }
    }

    /** 되는 서버를 고름 (앞 것부터). 고른 것은 기억했다가 실패하면 다시 고름 */
    fun pick(): String {
        active?.let { return it }
        for (s in servers) {
            val t = if (s.startsWith("https")) 6000 else 2500
            if (ping(s, t)) { active = s; return s }
        }
        throw IOException("NAS 에 연결할 수 없음 — 와이파이·데이터 연결과 서버 주소를 확인해 주세요")
    }

    private fun open(method: String, ep: String, q: Map<String, String>, readTimeoutMs: Int = 30000): HttpURLConnection {
        val c = URL(pick() + ep + query(q)).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = readTimeoutMs
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** 연결이 끊겨서 실패하면 서버를 다시 골라 한 번 더 (집 ↔ 밖으로 옮겨 다닐 때) */
    private fun <T> retry(f: () -> T): T = try { f() } catch (e: ApiException) { throw e } catch (e: Canceled) { throw e } catch (e: IOException) {
        active = null; f()
    }

    fun roots(): List<RootInfo> = retry {
        val a = JSONArray(readBody(open("GET", "/v1/roots", emptyMap())))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RootInfo(o.getString("name"), o.optInt("items"), o.optString("share"), o.optLong("total"), o.optLong("free"))
        }
    }

    fun list(root: String, path: String): List<Entry> = retry {
        val j = JSONObject(readBody(open("GET", "/v1/list", mapOf("root" to root, "path" to path))))
        val a = j.getJSONArray("entries")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(o.getString("p"), o.optBoolean("d"), o.optLong("s"), o.optLong("m"))
        }
    }

    /** 새 폴더 */
    fun mkdir(root: String, path: String): Entry = retry { entry(JSONObject(readBody(open("POST", "/v1/mkdir", mapOf("root" to root, "path" to path))))) }

    /** 이름 바꾸기·옮기기 (같은 연결 폴더 안). 받는 쪽에 같은 이름이 있으면 409 */
    fun move(root: String, from: String, to: String) { retry { readBody(open("POST", "/v1/move", mapOf("root" to root, "from" to from, "to" to to, "if" to "none"))) } }

    /** 지우기 → NAS 휴지통(#recycle)으로 */
    fun delete(root: String, path: String) { retry { readBody(open("DELETE", "/v1/file", mapOf("root" to root, "path" to path))) } }

    /** 이름 검색 (서버 0.6.11 이상) → (결과, 더 있음) */
    fun search(root: String, under: String, q: String, ext: String, limit: Int = 500): Pair<List<Entry>, Boolean> = retry {
        val j = JSONObject(readBody(open("GET", "/v1/search", mapOf("root" to root, "path" to under, "q" to q, "ext" to ext, "limit" to limit.toString()), 60000)))
        val a = j.getJSONArray("entries")
        (0 until a.length()).map { entry(a.getJSONObject(it)) } to j.optBoolean("more")
    }

    /** 내용 SHA-256 (중복 파일 확인) */
    fun hash(root: String, path: String): String = retry { JSONObject(readBody(open("GET", "/v1/hash", mapOf("root" to root, "path" to path), 120000))).getString("sha256") }

    /** 연결 폴더의 모든 항목 (저장소 분석). 큰 목록이라 한 항목씩 읽어 넘김 */
    fun snapshot(root: String, canceled: () -> Boolean, each: (Entry) -> Unit) {
        val c = open("GET", "/v1/snapshot", mapOf("root" to root), 180000)
        val code = c.responseCode
        if (code !in 200..299) { readBody(c); return }
        c.inputStream.bufferedReader().use { r -> JsonStream(r).entries(canceled, each) }
    }

    /** 파일 하나 정보 (없으면 null) — 폴더 안 목록에서 찾음 */
    fun stat(root: String, path: String): Entry? {
        val dir = path.substringBeforeLast('/', "")
        return try { list(root, dir).firstOrNull { it.path == path } } catch (e: ApiException) { if (e.status == 404) null else throw e }
    }

    /** 받기: dest 로. 끊기면 받은 데까지 이어서 (같은 파일일 때만) */
    fun download(root: String, e: Entry, dest: File, progress: (Long, Long) -> Unit, canceled: () -> Boolean) {
        val part = File(dest.path + ".part")
        val stamp = File(dest.path + ".part.id")
        val sig = "${e.size}:${e.mtimeNs}"
        if (!(part.exists() && stamp.exists() && stamp.readText() == sig)) { part.delete(); stamp.writeText(sig) }
        var tries = 0
        while (true) {
            try {
                val have = part.length()
                if (have < e.size || e.size == 0L) {
                    val c = open("GET", "/v1/file", mapOf("root" to root, "path" to e.path), 60000)
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code != 200 && code != 206) readBody(c)
                    val append = code == 206
                    RandomAccessFile(part, "rw").use { out ->
                        if (append) out.seek(have) else out.setLength(0)
                        var done = if (append) have else 0L
                        c.inputStream.use { inp ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (canceled()) throw Canceled()
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n; progress(done, e.size)
                            }
                        }
                    }
                }
                if (part.length() != e.size) throw IOException("받은 크기가 맞지 않음 (${part.length()} / ${e.size})")
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("저장하지 못함")
                stamp.delete()
                if (e.mtimeNs > 0) dest.setLastModified(e.mtimeNs / 1_000_000)
                return
            } catch (x: ApiException) { throw x } catch (x: Canceled) { throw x } catch (x: IOException) {
                if (++tries >= 4) throw x
                active = null; Thread.sleep(1500L * tries)   // 잠깐 끊김: 다시 골라 이어 받기
            }
        }
    }

    /**
     * 올리기: 그 자리에 같은 이름이 없을 때만 (if=none — NAS 파일을 덮지 않음). 같은 이름이 있으면 ApiException(409).
     * 큰 파일은 나눠서 (끊기면 받은 데부터 이어서).
     */
    fun upload(root: String, path: String, size: Long, mtimeNs: Long, input: () -> InputStream, progress: (Long, Long) -> Unit, canceled: () -> Boolean): Entry {
        val q = mapOf("root" to root, "path" to path, "mtime" to mtimeNs.toString(), "if" to "none")
        if (size in 0 until partSize) return retry {
            val c = open("PUT", "/v1/file", q, 120000)
            c.doOutput = true; c.setFixedLengthStreamingMode(size)
            c.outputStream.use { out -> input().use { inp -> copy(inp, out, 0, size, progress, canceled) } }
            entry(JSONObject(readBody(c)))
        }
        if (size < 0) throw IOException("파일 크기를 알 수 없음")
        val id = newId()
        var off = 0L; var tries = 0
        while (off < size) {
            try {
                val n = minOf(partSize, size - off)
                val c = open("PUT", "/v1/part", mapOf("root" to root, "path" to path, "upload" to id, "offset" to off.toString()), 120000)
                c.doOutput = true; c.setFixedLengthStreamingMode(n)
                c.outputStream.use { out -> input().use { inp -> skipFully(inp, off); copy(inp, out, off, n, progress, canceled, size) } }
                off = JSONObject(readBody(c)).getLong("have")
                tries = 0
            } catch (x: ApiException) {
                if (x.kind == "offset" && x.have >= 0) { off = x.have; continue }   // 서버가 받은 데부터
                throw x
            } catch (x: Canceled) {
                // 취소: 서버에 받아 둔 조각을 버리게 함 (안 되면 서버가 하루 뒤 지움)
                try { open("DELETE", "/v1/part", mapOf("root" to root, "path" to path, "upload" to id)).responseCode } catch (e: IOException) { }
                throw x
            } catch (x: IOException) {
                if (++tries >= 4) throw x
                active = null; Thread.sleep(1500L * tries)
                off = try { JSONObject(readBody(open("GET", "/v1/part", mapOf("root" to root, "path" to path, "upload" to id)))).getLong("have") } catch (e: IOException) { off }
            }
        }
        return retry {
            entry(JSONObject(readBody(open("POST", "/v1/part", q + mapOf("upload" to id, "size" to size.toString()), 120000))))
        }
    }

    private fun entry(o: JSONObject) = Entry(o.getString("p"), o.optBoolean("d"), o.optLong("s"), o.optLong("m"))

    private fun skipFully(inp: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(256 * 1024)
        while (left > 0) {
            val s = inp.skip(left)
            if (s > 0) { left -= s; continue }
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("파일이 예상보다 짧음")
            left -= r
        }
    }

    private fun copy(inp: InputStream, out: java.io.OutputStream, base: Long, n: Long, progress: (Long, Long) -> Unit, canceled: () -> Boolean, total: Long = n) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        while (done < n) {
            if (canceled()) throw Canceled()
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), n - done).toInt())
            if (r < 0) throw IOException("파일이 예상보다 짧음")
            out.write(buf, 0, r); done += r; progress(base + done, total)
        }
    }
}

/** 아주 작은 JSON 읽개: {"...": ..., "entries": [ {p,d,s,m}, ... ]} 에서 entries 만 한 개씩 */
internal class JsonStream(private val r: java.io.Reader) {
    private var peeked = -2
    private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }
    private fun next(): Int { val c = peek(); peeked = -2; return c }
    private fun ws(): Int { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return c } }
    private fun expect(ch: Char) { val c = ws(); if (c != ch.code) throw IOException("목록 형식이 이상함"); next() }

    fun entries(canceled: () -> Boolean, each: (Entry) -> Unit) {
        expect('{')
        if (ws() == '}'.code) return
        while (true) {
            val key = str(); expect(':')
            if (key == "entries" && ws() == '['.code) {
                next()
                if (ws() == ']'.code) next() else while (true) {
                    if (canceled()) throw Canceled()
                    @Suppress("UNCHECKED_CAST") val o = value() as Map<String, Any?>
                    each(Entry(o["p"] as String, o["d"] == true, (o["s"] as? Number)?.toLong() ?: 0, (o["m"] as? Number)?.toLong() ?: 0))
                    val c = ws(); next(); if (c == ']'.code) break; if (c != ','.code) throw IOException("목록 형식이 이상함")
                }
            } else value()
            val c = ws(); next(); if (c == '}'.code) return; if (c != ','.code) throw IOException("목록 형식이 이상함")
        }
    }

    private fun value(): Any? = when (val c = ws()) {
        '"'.code -> str()
        '{'.code -> { next(); val m = HashMap<String, Any?>(); if (ws() == '}'.code) next() else while (true) { val k = str(); expect(':'); m[k] = value(); val d = ws(); next(); if (d == '}'.code) break }; m }
        '['.code -> { next(); val l = ArrayList<Any?>(); if (ws() == ']'.code) next() else while (true) { l += value(); val d = ws(); next(); if (d == ']'.code) break }; l }
        't'.code -> { repeat(4) { next() }; true }
        'f'.code -> { repeat(5) { next() }; false }
        'n'.code -> { repeat(4) { next() }; null }
        else -> { val sb = StringBuilder(); while (true) { val d = peek(); if (d == '-'.code || d == '+'.code || d == '.'.code || d == 'e'.code || d == 'E'.code || (d >= '0'.code && d <= '9'.code)) sb.append(next().toChar()) else break }
            if (sb.isEmpty()) throw IOException("목록 형식이 이상함 ($c)"); val t = sb.toString(); t.toLongOrNull() ?: t.toDouble() }
    }

    private fun str(): String {
        if (ws() != '"'.code) throw IOException("목록 형식이 이상함"); next()
        val sb = StringBuilder()
        while (true) {
            val c = next(); if (c < 0) throw IOException("목록이 중간에 끊김")
            if (c == '"'.code) return sb.toString()
            if (c != '\\'.code) { sb.append(c.toChar()); continue }
            when (val e = next()) {
                'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r'); 'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000C')
                'u'.code -> { val h = CharArray(4) { next().toChar() }; sb.append(String(h).toInt(16).toChar()) }
                else -> sb.append(e.toChar())
            }
        }
    }
}
