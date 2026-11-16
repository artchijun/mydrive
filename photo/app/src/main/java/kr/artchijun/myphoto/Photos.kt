package kr.artchijun.myphoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 폴더 하나 (시놀로지 포토의 폴더 = 사진 공유 폴더 안의 실제 폴더) */
data class Folder(val id: Long, val path: String, val parent: Long, val cover: Pair<Long, String>?) {
    val name get() = path.substringAfterLast('/').ifEmpty { "사진" }
}
/** 인물 · 장소 · 앨범처럼 사진 묶음 */
data class Group(val id: Long, val name: String, val count: Int, val coverUnit: Long, val coverKey: String, val sub: String = "", val thumbType: String = "unit")

/**
 * 시놀로지 포토에서 무엇을 읽고 바꿀지 (화면과 백업이 같이 씀). 모두 일하는 스레드에서 부름.
 *   ns: 공유 공간 "SYNO.FotoTeam" · 개인 공간 "SYNO.Foto"
 */
class Photos(ctx: Context) {
    val syno = Syno(ctx)

    companion object {
        const val TEAM = "SYNO.FotoTeam"
        const val PERSONAL = "SYNO.Foto"
        private val ADD = listOf("thumbnail", "resolution", "orientation", "video_convert", "video_meta")
    }

    private fun items(d: JSONObject): List<Item> {
        val a = d.optJSONArray("list") ?: return emptyList()
        return List(a.length()) { Item.of(a.getJSONObject(it)) }
    }

    /** 사진 목록 (찍은 때 새것부터). filter: folder_id · person_id · geocoding_id · album_id 등 */
    fun list(ns: String, offset: Int, limit: Int, filter: Map<String, Any> = emptyMap()): List<Item> {
        val p = linkedMapOf<String, Any?>("offset" to offset, "limit" to limit, "sort_by" to "takentime", "sort_direction" to "desc", "additional" to ADD)
        p.putAll(filter)
        return items(syno.call("$ns.Browse.Item", "list", 4, p))
    }

    /**
     * 전체 사진의 날짜별 장수 (찍은 때 새것부터) — 사진 목록(takentime desc)과 같은 순서.
     * 시놀로지는 하루가 크면 여러 구간(section)으로 나눠 같은 날을 여러 번 주므로 날마다 한 번만 셈
     * → [yyyyMMdd, 장수]
     */
    fun timelineDays(ns: String): List<IntArray> {
        val d = syno.call("$ns.Browse.Timeline", "get", 5, mapOf("timeline_group_unit" to "day"))
        val out = ArrayList<IntArray>(); val seen = HashSet<Int>()
        val secs = d.optJSONArray("section") ?: return out
        for (i in 0 until secs.length()) {
            val l = secs.getJSONObject(i).optJSONArray("list") ?: continue
            for (k in 0 until l.length()) {
                val o = l.getJSONObject(k)
                val key = o.optInt("year") * 10000 + o.optInt("month") * 100 + o.optInt("day")
                if (seen.add(key)) out += intArrayOf(key, o.optInt("item_count"))
            }
        }
        return out
    }

    /** 날짜별 장수 (시간 막대용): [(년,월,일,장수)] */
    fun timeline(ns: String): List<IntArray> {
        val d = syno.call("$ns.Browse.Timeline", "get", 5, mapOf("timeline_group_unit" to "day"))
        val out = ArrayList<IntArray>()
        val secs = d.optJSONArray("section") ?: return out
        for (i in 0 until secs.length()) {
            val l = secs.getJSONObject(i).optJSONArray("list") ?: continue
            for (k in 0 until l.length()) { val o = l.getJSONObject(k); out += intArrayOf(o.optInt("year"), o.optInt("month"), o.optInt("day"), o.optInt("item_count")) }
        }
        return out
    }

    fun folder(ns: String, id: Long): Folder {
        val f = syno.call("$ns.Browse.Folder", "get", 2, mapOf("id" to id)).getJSONObject("folder")
        return Folder(f.optLong("id"), f.optString("name"), f.optLong("parent"), null)
    }

    /** 하위 폴더 (이름순) */
    fun folders(ns: String, id: Long): List<Folder> {
        val out = ArrayList<Folder>()
        var off = 0
        while (true) {
            val d = syno.call("$ns.Browse.Folder", "list", 2, mapOf("id" to id, "offset" to off, "limit" to 500, "sort_by" to "filename", "sort_direction" to "asc", "additional" to listOf("thumbnail")))
            val a = d.optJSONArray("list") ?: break
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val th = o.optJSONObject("additional")?.optJSONArray("thumbnail")?.optJSONObject(0)
                out += Folder(o.optLong("id"), o.optString("name"), o.optLong("parent"), th?.let { it.optLong("unit_id") to it.optString("cache_key") })
            }
            if (a.length() < 500) break
            off += 500
        }
        return out
    }

    /** 경로("/JUN PHONE/DCIM/Camera")로 폴더 찾기. 없으면 make 일 때 만듦 */
    fun folderByPath(ns: String, path: String, make: Boolean): Long? {
        var id = 1L   // 맨 위 폴더
        var cur = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            val hit = folders(ns, id).firstOrNull { it.path == cur }
            id = when {
                hit != null -> hit.id
                make -> createFolder(ns, id, part)
                else -> return null
            }
        }
        return id
    }

    /** 폴더째 지우기 (안의 사진·하위 폴더 모두 — 시놀로지 사진 웹과 같은 Browse.Folder delete). 휴지통이 켜져 있으면 DSM 휴지통으로 */
    fun deleteFolders(ns: String, ids: List<Long>) {
        try { syno.call("$ns.Browse.Folder", "delete", 1, mapOf("id" to org.json.JSONArray(ids))) }
        catch (e: SynoError) { if (e.code == 103 || e.code == 104) syno.call("$ns.Browse.Folder", "delete", 2, mapOf("id" to org.json.JSONArray(ids))) else throw e }
    }
    /** 폴더 안의 사진 수와 하위 폴더 수 (아래까지 모두) — 지우기 전에 알려 주려고 */
    fun folderStats(ns: String, id: Long, stop: () -> Boolean = { false }): Pair<Int, Int> {
        var photos = 0; var subs = 0
        val q = ArrayDeque<Long>(); q += id
        while (q.isNotEmpty() && !stop()) {
            val f = q.removeFirst()
            photos += try { syno.call("$ns.Browse.Item", "count", 7, mapOf("folder_id" to f)).optInt("count") } catch (e: Exception) {
                var n = 0; var off = 0
                while (true) { val pg = list(ns, off, 5000, mapOf("folder_id" to f)); n += pg.size; off += pg.size; if (pg.size < 5000) break }
                n
            }
            for (c in folders(ns, f)) { subs++; q += c.id }
        }
        return photos to subs
    }

    fun createFolder(ns: String, parent: Long, name: String): Long {
        val d = try { syno.call("$ns.Browse.Folder", "create", 1, mapOf("target_id" to parent, "name" to name)) }
                catch (e: SynoError) { if (e.code == 641) { return folders(ns, parent).first { it.name == name }.id }; throw e }
        return d.optJSONObject("folder")?.optLong("id") ?: d.optLong("id")
    }

    /** person = true: 얼굴만 잘린 썸네일 (type=person, id=인물 번호) */
    private fun groups(d: JSONObject, person: Boolean = false, sub: (JSONObject) -> String = { "" }): List<Group> {
        val a = d.optJSONArray("list") ?: return emptyList()
        return List(a.length()) {
            val o = a.getJSONObject(it)
            val th = o.optJSONObject("additional")?.optJSONObject("thumbnail")
            val unit = if (person) o.optLong("id") else th?.optLong("unit_id", o.optLong("cover")) ?: o.optLong("cover")
            Group(o.optLong("id"), o.optString("name"), o.optInt("item_count"), unit, th?.optString("cache_key") ?: "", sub(o), if (person) "person" else "unit")
        }
    }

    fun persons(ns: String): List<Group> {
        val out = ArrayList<Group>(); var off = 0
        while (true) {
            val g = groups(syno.call("$ns.Browse.Person", "list", 1, mapOf("offset" to off, "limit" to 500, "show_more" to true, "show_hidden" to false, "additional" to listOf("thumbnail"))), person = true)
            out += g; if (g.size < 500 || off > 20000) break; off += 500
        }
        return out.distinctBy { it.id }
    }

    fun places(ns: String): List<Group> {
        val out = ArrayList<Group>(); var off = 0
        while (true) {
            val g = groups(syno.call("$ns.Browse.Geocoding", "list", 1, mapOf("offset" to off, "limit" to 500, "additional" to listOf("thumbnail")))) { o -> o.optString("country") }
            out += g; if (g.size < 500 || off > 20000) break; off += 500
        }
        return out.distinctBy { it.id }
    }

    fun setFavorite(ns: String, ids: List<Long>, on: Boolean) {
        syno.call("$ns.Browse.Item", "set_favorite", 7, mapOf("id" to JSONArray(ids), "favorite" to on))
    }

    fun delete(ns: String, ids: List<Long>) {
        syno.call("$ns.Browse.Item", "delete", 7, mapOf("id" to JSONArray(ids)))
    }

    /** 개인 공간이 켜져 있나 (꺼져 있으면 공유 공간만 보여 줌) */
    fun personalEnabled(): Boolean = try { syno.call("SYNO.Foto.Setting.User", "get", 1).optBoolean("enable_home_service") } catch (e: Exception) { false }
}
