package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 공간 확보: 서버(시놀로지)에 이미 있는 사진·동영상을 휴대폰에서 지움.
 *   휴대폰의 모든 사진을 서버 사진 전체와 대조함 (이 앱으로 올린 것이 아니어도 — 예전 시놀로지 포토 백업 등).
 *   [서버에 있음] 지울 수 있는 것 · [서버에 없음] 아직 백업 안 된 것. 이름으로 찾기.
 *   사진을 누르면 휴대폰 것과 서버 것을 나란히 보여 줌 (어디에 있는지, 크기·날짜, 무엇으로 같다고 봤는지). 길게 누르면 빼기/넣기.
 *   지우기 직전에 서버에 그 사진이 지금도 있는지 다시 확인한 것만 지움. 마지막에 안드로이드가 확인 창을 띄움.
 */
class FreeUpActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private val bg = Executors.newFixedThreadPool(3)
    private class Row(val p: Library.Phone, val m: Library.Match?)
    private var rows: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private val excluded = HashSet<Long>()
    private var tab = 0   // 0 서버에 있음 · 1 서버에 없음
    private var query = ""
    private lateinit var status: TextView
    private lateinit var again: TextView
    private lateinit var seg: LinearLayout
    private lateinit var filters: LinearLayout
    private lateinit var summary: TextView
    private lateinit var go: android.widget.Button
    private lateinit var grid: GridView
    private lateinit var controls: LinearLayout
    private lateinit var foldText: TextView
    private lateinit var foldIcon: ImageView
    private var folded = false
    private fun setFolded(f: Boolean) {
        folded = f; controls.visibility = if (f) View.GONE else View.VISIBLE
        foldIcon.setImageDrawable(Glyph(if (f) "down" else "up", ui.sub)); updateFold()
    }
    /** 접힌 줄에 지금 설정을 한 줄로 */
    private fun updateFold() {
        if (!::foldText.isInitialized) return
        val f = when { tab == 1 -> null; !custom -> "모든 항목"
            else -> listOfNotNull(ages.firstOrNull { it.first == ageDays }?.second?.takeIf { ageDays > 0 }, sizes.firstOrNull { it.first == minMb }?.second?.takeIf { minMb > 0 }).joinToString(" · ").ifEmpty { "전체" } }
        foldText.text = (if (folded) "" else "설정 접기 — ") + listOfNotNull(if (scope == Library.BACKUP) "백업 폴더" else "서버 전체", if (phoneAll) "휴대폰 모든 폴더" else "고른 폴더",
            if (tab == 0) "서버에 있음" else "서버에 없음", f, query.takeIf { it.isNotEmpty() }?.let { "'$it'" }).joinToString(" · ")
    }
    private lateinit var preview: CheckBox
    private val thumbs = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) { override fun sizeOf(k: Long, v: Bitmap) = v.byteCount }
    private var pending: List<Row> = emptyList()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    @Volatile private var stop = false

    private val ages = listOf(0 to "전체", 30 to "1개월 초과", 90 to "3개월 초과", 180 to "6개월 초과", 365 to "1년 초과", 730 to "2년 초과")
    private val sizes = listOf(0L to "전체", 10L to "10MB 초과", 50L to "50MB 초과", 100L to "100MB 초과", 500L to "500MB 초과", 1024L to "1GB 초과")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("공간 확보", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        // 대조 상태 줄: "서버 사진 127,257장과 대조함 · 11월 16일 20:41   [다시 대조]"
        status = ui.label("", 13f, ui.sub).apply { layoutParams = ui.lp(0, weight = 1f) }
        again = ui.label("다시 대조", 13.5f, ui.accent, bold = true).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.press(null, 8); setOnClickListener { load(force = true) } }
        val statusRow = ui.row(Glyph("nas", ui.sub).let { g -> ImageView(this).apply { setImageDrawable(g); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(8) } } }, status, again)
            .apply { setPadding(ui.dp(18), ui.dp(6), ui.dp(12), ui.dp(2)) }
        seg = ui.row().apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(4)) }
        scopeRow = ui.row().apply { setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(2)) }
        filters = ui.col()
        phoneRow = ui.row().apply { setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2)) }
        val search = ui.input("이름으로 찾기 (예: 20250612, IMG_1234)").apply {
            textSize = 14f; layoutParams = ui.lp(top = 4).apply { leftMargin = ui.dp(14); rightMargin = ui.dp(14) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
            })
        }
        summary = ui.label("", 13.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(8)) }
        grid = GridView(this).apply { numColumns = 4; verticalSpacing = ui.dp(2); horizontalSpacing = ui.dp(2); setBackgroundColor(ui.bg); selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, p, _ -> if (tab == 0) toggleOut(shown[p].p.key) else compare(shown[p]) }   // 누르면 빼기/넣기
            setOnItemLongClickListener { _, _, p, _ -> compare(shown[p]); true } }   // 길게 누르면 서버의 것과 나란히
        preview = CheckBox(this).apply { text = "정리하기 전에 미리 보기"; setTextColor(ui.text); isChecked = pf.bool("fu_preview", true)
            setOnCheckedChangeListener { _, b -> pf.putBool("fu_preview", b); refresh() } }
        go = ui.button("지울 항목 없음") { start() }
        val bottom = ui.col(ui.row(preview).apply { gravity = Gravity.CENTER }, go.apply { layoutParams = ui.lp(h = ui.dp(52), top = 8) })
            .apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12)); setBackgroundColor(ui.panel); tag = "bottom" }
        // 설정들은 접을 수 있게: 접으면 한 줄 요약만 남고 사진 칸이 화면 대부분을 씀 (사진을 내려 보기 시작하면 저절로 접힘)
        controls = ui.col(scopeRow, phoneRow, statusRow, seg, filters, search)
        foldText = ui.label("", 13.5f, ui.text).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = ui.lp(0, weight = 1f) }
        foldIcon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)) }
        val foldBar = ui.row(ImageView(this).apply { setImageDrawable(Glyph("sort", ui.accent)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) } }, foldText, foldIcon).apply {
            setPadding(ui.dp(16), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.press(android.graphics.drawable.ColorDrawable(ui.panel))
            setOnClickListener { setFolded(!folded) }
        }
        grid.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            private var touching = false
            override fun onScrollStateChanged(v: android.widget.AbsListView, st: Int) { touching = st == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL }
            override fun onScroll(v: android.widget.AbsListView, first: Int, vis: Int, total: Int) { if (touching && first > 0 && !folded) setFolded(true) }
        })
        val root = ui.col(top, foldBar, controls, summary, grid.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, bottom).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
        buildScope(); buildPhone(); buildSeg(); buildFilters()
        setFolded(false)
        // 고른 범위의 서버 목록이 1시간보다 오래됐으면(또는 그 뒤 백업으로 올린 것이 있으면) 새로 읽고 대조
        load(force = Library.stale(this, 3_600_000L, scope))
    }
    override fun onDestroy() { super.onDestroy(); stop = true; bg.shutdownNow() }

    // ---------- 대조 범위 ----------
    private lateinit var scopeRow: LinearLayout
    /** Library.BACKUP 백업 폴더(올릴 곳)만 — 기본 · Library.ALL 서버 전체 */
    private val scope get() = pf.int("fu_scope", Library.BACKUP)
    private fun buildScope() {
        scopeRow.removeAllViews()
        val root = Library.backupRoot(this).ifEmpty { "(올릴 곳 안 정함)" }
        listOf(Library.BACKUP to "백업 폴더  $root", Library.ALL to "서버 전체").forEachIndexed { i, (k, name) ->
            val sel = k == scope
            scopeRow.addView(ui.row(ImageView(this).apply { setImageDrawable(Glyph(if (k == Library.ALL) "nas" else "folder", if (sel) ui.accent else ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) } },
                ui.label(name, 13.5f, if (sel) ui.text else ui.sub, bold = sel).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(10), 0, ui.dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(38), if (k == Library.ALL) 0.8f else 1.2f).apply { if (i == 0) rightMargin = ui.dp(8) }
                background = ui.press(ui.round(if (sel) ui.select else ui.bg, 10, stroke = if (sel) ui.accent else ui.line), 10)
                setOnClickListener { setScope(k) }
            })
        }
    }
    private fun setScope(k: Int) {
        if (k == scope) return
        pf.putInt("fu_scope", k); excluded.clear(); buildScope()
        load(force = Library.stale(this, 3_600_000L, k))
    }

    // ---------- 휴대폰 쪽 범위: 모든 폴더 · 고른 폴더 ----------
    private lateinit var phoneRow: LinearLayout
    private val phoneAll get() = pf.bool("fu_phone_all", true)
    private fun phoneSet(): Set<String> = pf.str("fu_phone", "").split('\n').filter { it.isNotEmpty() }.toSet()
    private fun buildPhone() {
        phoneRow.removeAllViews()
        val set = phoneSet()
        listOf(true to "휴대폰 모든 폴더", false to (if (set.isEmpty()) "휴대폰 폴더 고르기" else "휴대폰 " + BucketPickActivity.describe(set))).forEachIndexed { i, (all, name) ->
            val sel = all == phoneAll
            phoneRow.addView(ui.row(ImageView(this).apply { setImageDrawable(Glyph(if (all) "phone" else "folder", if (sel) ui.accent else ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) } },
                ui.label(name, 13.5f, if (sel) ui.text else ui.sub, bold = sel).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(10), 0, ui.dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(38), if (all) 0.8f else 1.2f).apply { if (i == 0) rightMargin = ui.dp(8) }
                background = ui.press(ui.round(if (sel) ui.select else ui.bg, 10, stroke = if (sel) ui.accent else ui.line), 10)
                setOnClickListener {
                    if (all) { if (!phoneAll) { pf.putBool("fu_phone_all", true); buildPhone(); buildSeg(); refresh() } }
                    else {   // 고른 폴더: 나무에서 고르기 (이미 골라져 있고 그쪽이 켜져 있으면 다시 고르기)
                        @Suppress("DEPRECATION") startActivityForResult(Intent(this@FreeUpActivity, BucketPickActivity::class.java)
                            .putExtra("key", "fu_phone").putExtra("title", "대조할 휴대폰 폴더")
                            .putExtra("hint", "공간 확보에서 서버와 대조할 휴대폰 폴더입니다. 폴더를 고르면 하위 폴더도 함께. 왼쪽 ›를 누르면 펼쳐집니다."), 43)
                    }
                }
            })
        }
    }

    // ---------- 대조 ----------
    @Volatile private var loadSeq = 0
    private fun load(force: Boolean) {
        again.isEnabled = false; again.alpha = 0.4f
        summary.text = "휴대폰 사진을 읽는 중…"
        val sc = scope
        val my = ++loadSeq   // 범위를 바꾸면 앞서 하던 대조는 멈추고 화면에 쓰지 않음
        rows = emptyList(); buildSeg(); refresh()
        bg.execute {
            var err: String? = null
            // 백업이 같은 목록을 읽는 중이면 기다렸다가 그 목록을 씀 (백업 도중에도 공간 확보·지우기를 할 수 있게)
            if (Library.isRefreshing(sc)) {
                runOnUiThread { if (my == loadSeq) status.text = "백업이 서버 목록을 읽는 중 — 다 읽으면 그 목록으로 대조합니다" }
                while (Library.isRefreshing(sc) && !stop && my == loadSeq) Thread.sleep(300)
                if (my != loadSeq) return@execute
            }
            else if (force || !Library.hasServer(this, sc)) {
                var last = 0L
                val ok = try { Library.refreshServer(this, sc, { stop || my != loadSeq }) { n, t ->
                    val now = System.currentTimeMillis(); if (now - last < 300) return@refreshServer; last = now
                    runOnUiThread { if (my == loadSeq) status.text = if (t < 0) "백업 폴더 읽는 중  폴더 ${-t}개 · 사진 ${"%,d".format(n)}장" else "서버 전체 읽는 중  ${"%,d".format(n)} / ${"%,d".format(t)}" }
                } } catch (e: Exception) { err = e.message; false }
                if (!ok && err == null && Library.isRefreshing(sc)) err = "다른 곳에서 서버 목록을 읽는 중 — 잠시 뒤 다시"
            }
            if (my != loadSeq) return@execute
            val ph = Library.phone(this, force = true)
            runOnUiThread { if (my == loadSeq) status.text = "휴대폰 ${"%,d".format(ph.size)}개를 대조하는 중…" }
            val out = if (Library.hasServer(this, sc)) ph.map { Row(it, Library.matchOnServer(this, it, sc)) } else emptyList()
            runOnUiThread {
                if (isFinishing || my != loadSeq) return@runOnUiThread
                again.isEnabled = true; again.alpha = 1f
                rows = out
                val at = if (Library.hasServer(this, sc)) Library.serverAt(this, sc) else 0L
                val what = if (sc == Library.BACKUP) "백업 폴더의 사진" else "서버 전체 사진"
                status.text = when {
                    err != null && at == 0L -> "읽지 못했습니다: $err"
                    at == 0L -> "아직 대조하지 않았습니다"
                    else -> "$what ${"%,d".format(Library.serverCount(this, sc))}장과 대조함 · " + SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(at)) + (if (err != null) " (새로 읽지 못함: $err)" else "")
                }
                buildSeg(); buildFilters(); refresh()
            }
        }
    }

    private fun buildSeg() {
        seg.removeAllViews()
        val ps = phoneSet()
        val mine = if (phoneAll) rows else rows.filter { BucketPickActivity.covers(ps, it.p.rel) }   // 고른 휴대폰 폴더만
        val on = mine.count { it.m != null }; val off = mine.size - on
        listOf("서버에 있음  ${"%,d".format(on)}", "서버에 없음  ${"%,d".format(off)}").forEachIndexed { i, s ->
            val sel = i == tab
            seg.addView(ui.label(s, 14f, if (sel) ui.onAccent else ui.text, bold = sel).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(38), 1f).apply { if (i == 0) rightMargin = ui.dp(8) }
                background = ui.press(ui.round(if (sel) ui.accent else ui.panel2, 19), 19)
                setOnClickListener { tab = i; buildSeg(); buildFilters(); refresh() }
            })
        }
    }

    private val custom get() = pf.bool("fu_custom", false)
    private val ageDays get() = pf.int("fu_age", 730)
    private val minMb get() = pf.long("fu_size", 0)
    private fun folders(): Set<String> = pf.str("fu_folders", "").split('\n').filter { it.isNotEmpty() }.toSet()

    private fun row(title: String, sub: String?, right: View?, onClick: () -> Unit): View {
        val col = ui.col(ui.label(title, 15f, ui.text)).apply { layoutParams = ui.lp(0, weight = 1f) }
        if (sub != null) col.addView(ui.label(sub, 13f, ui.sub).apply { layoutParams = ui.lp(top = 2) })
        val r = ui.row(col).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.press(null); setOnClickListener { onClick() } }
        if (right != null) r.addView(right)
        return ui.col(r, View(this).apply { setBackgroundColor(ui.line); layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }

    private fun buildFilters() {
        filters.removeAllViews()
        if (tab == 1) return
        fun radio(on: Boolean) = RadioButton(this).apply { isChecked = on; isClickable = false; buttonTintList = android.content.res.ColorStateList.valueOf(if (on) ui.accent else ui.sub) }
        filters.addView(row("서버에 있는 모든 항목", null, radio(!custom)) { pf.putBool("fu_custom", false); buildFilters(); refresh() })
        filters.addView(row("사용자 지정", null, radio(custom)) { pf.putBool("fu_custom", true); buildFilters(); refresh() })
        if (!custom) return
        filters.addView(row("시간 범위", ages.firstOrNull { it.first == ageDays }?.second ?: "${ageDays}일 초과", null) {
            AlertDialog.Builder(this).setTitle("시간 범위 (찍은 지 얼마나 지난 것)").setSingleChoiceItems(ages.map { it.second }.toTypedArray(), ages.indexOfFirst { it.first == ageDays }) { d, i ->
                pf.putInt("fu_age", ages[i].first); d.dismiss(); buildFilters(); refresh() }.show()
        })
        filters.addView(row("파일 크기", sizes.firstOrNull { it.first == minMb }?.second ?: "${minMb}MB 초과", null) {
            AlertDialog.Builder(this).setTitle("파일 크기").setSingleChoiceItems(sizes.map { it.second }.toTypedArray(), sizes.indexOfFirst { it.first == minMb }) { d, i ->
                pf.putLong("fu_size", sizes[i].first); d.dismiss(); buildFilters(); refresh() }.show()
        })

    }

    private fun pickFolders() {
        val avail = rows.filter { it.m != null }.groupBy { it.p.rel }.mapValues { it.value.size }.toSortedMap()
        val cur = folders().toMutableSet()
        val list = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        avail.forEach { (rel, n) -> list.addView(CheckBox(this).apply { text = "${rel.trimEnd('/').ifEmpty { "(맨 위)" }}  ($n)"; setTextColor(ui.text); isChecked = rel in cur
            setOnCheckedChangeListener { _, b -> if (b) cur += rel else cur -= rel } }) }
        if (avail.isEmpty()) list.addView(ui.label("서버에 있는 사진이 든 폴더가 없습니다", 14f, ui.sub))
        AlertDialog.Builder(this).setTitle("휴대폰 폴더 (아무것도 고르지 않으면 모두)").setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("확인") { _, _ -> pf.putStr("fu_folders", cur.joinToString("\n")); buildFilters(); refresh() }.setNegativeButton("그만", null).show()
    }

    /** 지금 조건에 맞는 것 */
    private fun refresh() {
        updateFold()
        val now = System.currentTimeMillis()
        val q = query.lowercase()
        val ps = phoneSet()
        val inPhone = { r: Row -> phoneAll || BucketPickActivity.covers(ps, r.p.rel) }
        shown = rows.filter { r ->
            inPhone(r) && (if (tab == 0) r.m != null else r.m == null) &&
                (q.isEmpty() || r.p.name.lowercase().contains(q) || (r.m?.server?.name?.lowercase()?.contains(q) == true)) &&
                (tab == 1 || !custom || ((ageDays == 0 || now - r.p.taken > ageDays * 86_400_000L) && (minMb == 0L || r.p.size > minMb * 1024 * 1024)))
        }
        findViewByTag<View>("bottom")?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        if (tab == 0) {
            val pick = shown.filter { it.p.key !in excluded }
            val bytes = pick.sumOf { it.p.size }
            summary.text = if (shown.isEmpty()) (if (rows.isEmpty()) "" else "조건과 일치하는 항목 없음.") else
                "${"%,d".format(shown.size)}개 중 ${"%,d".format(pick.size)}개 (${StatusBadge.kinds(pick.count { !it.p.video }, pick.count { it.p.video })}) · ${Fmt.size(bytes)} 확보" + (if (shown.size > pick.size) " (뺀 것 ${shown.size - pick.size}개)" else "") +
                    (if (preview.isChecked) "\n누르면 빼기/넣기 · 길게 누르면 서버의 것과 나란히 비교" else "")
            go.text = if (pick.isEmpty()) "지울 항목 없음" else "${"%,d".format(pick.size)}개 지우기 (${Fmt.size(bytes)})"
            go.isEnabled = pick.isNotEmpty(); go.alpha = if (pick.isEmpty()) 0.5f else 1f
        } else {
            summary.text = (if (shown.isEmpty()) "서버에 없는 휴대폰 사진이 없습니다." else
                "${"%,d".format(shown.size)}개 (${StatusBadge.kinds(shown.count { !it.p.video }, shown.count { it.p.video })}) · ${Fmt.size(shown.sumOf { it.p.size })} — " + (if (scope == Library.BACKUP) "백업 폴더에서" else "서버 전체에서") + " 같은 것을 찾지 못한 사진 (지우지 않음).") +
                (if (scope == Library.BACKUP && shown.isNotEmpty()) "\n다른 폴더에 있을 수 있으면 위의 [서버 전체]로 넓혀 찾아 보세요." else "")
        }
        val vis = tab == 1 || preview.isChecked
        grid.visibility = if (vis) View.VISIBLE else View.INVISIBLE
        if (vis) { if (grid.adapter == null) grid.adapter = adapter else adapter.notifyDataSetChanged() } else grid.adapter = null
    }
    private fun <T : View> findViewByTag(t: String): T? = window.decorView.findViewWithTag(t)

    private fun toggleOut(k: Long) { if (!excluded.remove(k)) excluded += k; adapter.notifyDataSetChanged(); refresh() }

    private fun phoneThumb(p: Library.Phone, iv: ImageView, size: Int = 256) {
        val hit = thumbs.get(p.key)
        if (hit != null && size <= 256) { iv.setImageBitmap(hit); return }
        iv.setImageDrawable(null); iv.tag = p.key
        bg.execute {
            val b = try { contentResolver.loadThumbnail(p.uri, Size(size, size), null) } catch (e: Exception) { null }
            if (b != null && size <= 256) thumbs.put(p.key, b)
            runOnUiThread { if (iv.tag == p.key && b != null) iv.setImageBitmap(b) }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].p.key
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = shown[p]
            val w = (parent.width - ui.dp(2) * 3) / 4
            val f = (cv as? FrameLayout) ?: FrameLayout(this@FreeUpActivity).apply {
                addView(ImageView(this@FreeUpActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.panel2) }, FrameLayout.LayoutParams(-1, -1))
                addView(View(this@FreeUpActivity).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
                addView(ImageView(this@FreeUpActivity), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(5), ui.dp(5), 0) })
                addView(StatusBadge.view(this@FreeUpActivity, ui), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(5), ui.dp(5)) })
                addView(StatusBadge.play(this@FreeUpActivity, ui), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(5), ui.dp(5), 0, 0) })   // 동영상
            }
            f.layoutParams = android.widget.AbsListView.LayoutParams(w, w)
            phoneThumb(r.p, f.getChildAt(0) as ImageView)
            val out = tab == 0 && r.p.key in excluded
            f.getChildAt(1).visibility = if (out) View.VISIBLE else View.GONE
            (f.getChildAt(2) as ImageView).apply {
                visibility = if (tab == 0) View.VISIBLE else View.GONE
                setImageDrawable(if (out) null else Glyph("check", ui.onAccent))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (!out) setColor(ui.accent) else { setColor(0x33000000); setStroke(ui.dp(2), Color.WHITE) } }
                val pd = ui.dp(3); setPadding(pd, pd, pd, pd)
            }
            StatusBadge.set(f.getChildAt(3) as ImageView, if (r.m != null) Library.BOTH else Library.PHONE_ONLY)
            f.getChildAt(4).visibility = if (r.p.video) View.VISIBLE else View.GONE
            return f
        }
    }

    // ---------- 나란히 비교 ----------
    private fun compare(r: Row) {
        val p = r.p; val m = r.m
        fun side(title: String, img: ImageView, lines: List<String>) = ui.col(
            ui.label(title, 13f, ui.accent, bold = true),
            img.apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(150)).apply { topMargin = ui.dp(6) }; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.panel2) },
            ui.label(lines.joinToString("\n"), 12.5f, ui.text).apply { layoutParams = ui.lp(top = 6) },
        ).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val pi = ImageView(this); phoneThumb(p, pi, 512)
        val left = side("휴대폰", pi, listOf(p.name, p.rel.trimEnd('/').ifEmpty { "(맨 위)" }, Fmt.size(p.size) + " (${"%,d".format(p.size)} B)", df.format(Date(p.taken))))
        val box = ui.col().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        if (m == null) {
            box.addView(ui.label("서버에서 같은 사진을 찾지 못했습니다.\n이름·크기가 같거나, 크기가 같고 찍은 때가 같은(몇 초 안쪽, 시간대 차이는 허용) 것을 찾았습니다.", 13.5f, ui.sub).apply { layoutParams = ui.lp(); setPadding(0, 0, 0, ui.dp(10)) })
            box.addView(ui.row(left))
            AlertDialog.Builder(this).setTitle("서버에 없음").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("닫기", null).show()
            return
        }
        val s = m.server
        val si = ImageView(this)
        val pathLbl = ui.label("폴더 확인 중…", 12.5f, ui.text)
        val right = side("서버", si, listOf(s.name, Fmt.size(s.size) + " (${"%,d".format(s.size)} B)", PhotoGrid.stamp(s.time) + (if (s.w > 0) "  ·  ${s.w}×${s.h}" else "")))
        right.addView(pathLbl.apply { setTextColor(ui.sub); layoutParams = ui.lp(top = 2) })
        bg.execute {
            val b = (try { Thumbs.get(this, Photos.TEAM, s.unitId, s.cacheKey, "m") } catch (e: Exception) { null })
                ?: try { Thumbs.smallFromOriginal(this, Photos.TEAM, s, 512) } catch (e: Exception) { null }   // 서버가 썸네일을 아직 못 만든 것
            val path = Library.folderPath(this, s.folderId)
            runOnUiThread { if (b != null) si.setImageBitmap(b); pathLbl.text = path }
        }
        val same = if (m.how == "이름·크기") "이름과 크기(바이트)가 같아 같은 사진으로 봤습니다." else "이름은 다르지만 크기(바이트)와 찍은 때(몇 초 안쪽, 시간대 차이는 허용)가 같아 같은 사진으로 봤습니다."
        box.addView(ui.label(same, 13.5f, ui.sub).apply { setPadding(0, 0, 0, ui.dp(10)) })
        box.addView(ui.row(left, View(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(12), 1) }, right).apply { gravity = Gravity.TOP })
        val out = p.key in excluded
        AlertDialog.Builder(this).setTitle("휴대폰 ↔ 서버").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (out) "다시 넣기" else "지우지 않기 (빼기)") { _, _ -> toggleOut(p.key) }
            .setNeutralButton("서버 것 크게 보기") { _, _ -> ViewerActivity.open(this, listOf(s), 0, Photos.TEAM) }
            .setNegativeButton("닫기", null).show()
    }

    // ---------- 지우기 ----------
    private fun start() {
        val pick = shown.filter { it.p.key !in excluded && it.m != null }
        if (pick.isEmpty()) return
        val dlg = AlertDialog.Builder(this).setTitle("서버에 지금도 있는지 확인하는 중").setView(ui.col(ProgressBar(this)).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }).setCancelable(false).show()
        bg.execute {
            val r = try { Result.success(verify(pick)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                dlg.dismiss()
                r.onFailure { e -> AlertDialog.Builder(this).setTitle("확인하지 못했습니다").setMessage((e.message ?: "") + "\n\n시놀로지에 닿을 때 다시 해 주세요. 아무것도 지우지 않았습니다.").setPositiveButton("확인", null).show() }
                r.onSuccess { ok ->
                    val missing = pick.size - ok.size
                    if (ok.isEmpty()) { AlertDialog.Builder(this).setTitle("지울 것이 없습니다").setMessage("서버에서 ${missing}개를 찾지 못해 남겨 두었습니다. [다시 대조]를 눌러 보세요.").setPositiveButton("확인", null).show(); return@onSuccess }
                    pending = ok
                    if (missing > 0) Toast.makeText(this, "서버에서 지금 찾지 못한 ${missing}개는 남겨 둡니다", Toast.LENGTH_LONG).show()
                    // 안드로이드가 '이 사진들을 지울까요?' 확인 창을 띄움
                    val req = MediaStore.createDeleteRequest(contentResolver, ok.map { it.p.uri })
                    startIntentSenderForResult(req.intentSender, 41, null, 0, 0, 0)
                }
            }
        }
    }

    /** 지우기 직전: 짝지은 서버 사진이 지금도 있고 크기가 같은 것만 */
    private fun verify(pick: List<Row>): List<Row> {
        val syno = Syno(this)
        val alive = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        // 200개씩 묶어 4묶음을 함께 물어봄 (지우기 전에 서버에 지금도 있는지)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val futures = pick.map { it.m!!.server.id }.filter { it > 0 }.distinct().chunked(200).map { chunk -> pool.submit {
            val d = syno.call("${Photos.TEAM}.Browse.Item", "get", 7, mapOf("id" to JSONArray(chunk)))
            val a = d.optJSONArray("list") ?: return@submit
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); alive[o.optLong("id")] = o.optLong("filesize") }
        } }
        try { futures.forEach { it.get() } } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e } finally { pool.shutdown() }
        return pick.filter { alive[it.m!!.server.id] == it.m.server.size }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, result, data)
        if (code == 43) { if (result == RESULT_OK) { pf.putBool("fu_phone_all", phoneSet().isEmpty()); excluded.clear(); buildPhone(); buildSeg(); refresh() }; return }
        if (code != 41) return
        if (result != RESULT_OK) { Toast.makeText(this, "지우지 않았습니다", Toast.LENGTH_SHORT).show(); return }
        val bytes = pending.sumOf { it.p.size }
        Backup.forget(this, pending.map { it.p.key })
        Backup.log(this, "공간 확보: ${pending.size}개 지움 (${Fmt.size(bytes)})")
        Toast.makeText(this, "${pending.size}개를 지워 ${Fmt.size(bytes)} 확보했습니다", Toast.LENGTH_LONG).show()
        val gone = pending.map { it.p.key }.toSet()
        rows = rows.filter { it.p.key !in gone }; excluded.removeAll(gone); pending = emptyList()
        Library.phone(this, force = true)
        buildSeg(); refresh()
    }
}
