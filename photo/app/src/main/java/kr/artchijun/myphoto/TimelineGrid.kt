package kr.artchijun.myphoto

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * 전체 사진 (시놀로지 사진 앱처럼):
 *   먼저 서버의 날짜별 장수(Timeline)를 받아 12만 장 전체의 자리를 정해 두고, 사진은 화면에 보이는 곳만 그때그때 받음
 *   → 오른쪽 막대를 끌면 몇 년 전이든 바로 감 (막대에 연도 눈금, 끄는 동안 '2022년 5월')
 *   연도 · 월 · 일 보기: 연도에서 사진을 누르면 그 달로, 월에서 누르면 그날로 (두 손가락 벌리기/오므리기도 같음). 일에서 누르면 크게 보기
 *   아직 백업 안 된 휴대폰 사진은 그날 칸 끝에 함께
 */
class TimelineGrid(
    private val a: Activity, private val ui: Ui, private val net: Executor, private val ns: String,
    private val onOpen: (List<Item>, Int) -> Unit, private val onSelect: (Int) -> Unit, private val onUnit: (String) -> Unit,
    /** 인물·장소·폴더처럼 일부만: 그 사진을 모두 받아 날짜별로 나눔 (전체 사진은 비워 둠 — 날짜별 장수를 먼저 받음) */
    private val filter: Map<String, Any> = emptyMap(),
) : FrameLayout(a), PhotoView {
    companion object {
        private const val PAGE = 200
        private val utc = TimeZone.getTimeZone("UTC")
        private fun fmt(p: String, t: Long) = SimpleDateFormat(p, Locale.KOREA).apply { timeZone = utc }.format(java.util.Date(t * 1000))
        private fun dayKeyOf(t: Long) = fmt("yyyyMMdd", t).toInt()
        val UNITS = listOf("year", "month", "day")
        /** 줌 단계: 아주 크게(1칸) … 일 … 월 … 연도(14칸). 두 손가락으로 한 단계씩, [연도|월|일] 은 그 묶음의 기본 단계로 */
        private class Lv(val unit: String, val cols: Int)
        private val LEVELS = listOf(Lv("day", 1), Lv("day", 2), Lv("day", 3), Lv("day", 4), Lv("day", 5), Lv("month", 6), Lv("month", 8), Lv("year", 10), Lv("year", 14))
        private fun home(unit: String) = when (unit) { "year" -> 7; "month" -> 5; else -> 3 }
    }

    /** 하루: 서버 목록에서의 자리(srvStart~+srvCount)와 그날의 휴대폰에만 있는 사진 */
    private class Day(val key: Int, val srvStart: Int, val srvCount: Int, var locals: List<Item> = emptyList()) {
        val count get() = srvCount + locals.size
        var vStart = 0   // 화면 순서에서의 시작 자리
    }
    /** 목록 한 줄: 머리줄(묶음 제목) 또는 사진 칸 줄 [vFrom, vTo) */
    private class Row(val title: String?, val count: Int, val vFrom: Int, val vTo: Int, val year: Int)

    private val pf = Prefs(a)
    private var days = ArrayList<Day>()
    private var total = 0
    private val loaded = HashMap<Int, Item>()        // 서버 자리 → 사진
    private val pagesBusy = HashSet<Int>()
    private val pagesDone = HashSet<Int>()
    private var phoneOnly: List<Item> = emptyList()
    private var rows: List<Row> = emptyList()
    private var seq = 0
    override val selected = LinkedHashSet<Long>()
    override var onPull: (() -> Unit)? = null
    override val selecting get() = selected.isNotEmpty()
    private var level: Int = pf.int("zoom", home(pf.str("unit", "day"))).coerceIn(0, LEVELS.size - 1)
    private fun setLevel(l: Int) {
        val n = l.coerceIn(0, LEVELS.size - 1); if (n == level) return
        val anchor = firstVisibleIndex(); val old = unit
        level = n; pf.putInt("zoom", n); pf.putStr("unit", unit)
        rebuild(); scrollToIndex(anchor)
        onUnit(unit)
    }
    override var unit: String
        get() = LEVELS[level].unit
        set(v) { if (v != unit) setLevel(home(v)) }
    private val cols get() = LEVELS[level].cols
    /** 칸 크기에 맞는 썸네일: 1칸은 큰 것, 2칸은 중간, 나머지는 작은 것 */
    private val thumbSize get() = when (cols) { 1 -> "xl"; 2 -> "m"; else -> "sm" }
    private val showStatus get() = pf.bool("show_status", true)

    private val list = ListView(a)
    private val track = Track()
    private val bubble = TextView(a)
    /** 맨 위에 붙어 있는 묶음 제목 (지금 어느 해·달·날을 보고 있는지) */
    private val sticky = TextView(a)
    private fun titleAbove(row: Int): Pair<String, Int>? { var i = row; while (i >= 0) { rows[i].title?.let { return it to rows[i].count }; i-- }; return null }
    private val spin = ProgressBar(a)
    private val empty = ui.label("", 14.5f, ui.sub).apply { gravity = Gravity.CENTER; visibility = View.GONE }

    // ---------- 받기 ----------
    override fun reload() {
        val my = ++seq
        spin.visibility = View.VISIBLE; empty.visibility = View.GONE
        net.execute {
            var all: List<Item>? = null
            val r = try {
                if (filter.isEmpty()) Result.success(Photos(a).timelineDays(ns))
                else {
                    // 일부만: 5000장씩 모두 받아 (찍은 때 새것부터) 이어지는 같은 날끼리 묶음
                    val got = ArrayList<Item>(); var off = 0
                    while (true) { val page = Photos(a).list(ns, off, 5000, filter); got += page; off += page.size; if (page.size < 5000) break }
                    all = got
                    val out = ArrayList<IntArray>()
                    for (it in got) { val k = dayKeyOf(it.time); if (out.isNotEmpty() && out.last()[0] == k) out.last()[1]++ else out += intArrayOf(k, 1) }
                    Result.success(out)
                }
            } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (my != seq) return@runOnUiThread
                spin.visibility = View.GONE
                r.onFailure { e -> empty.text = e.message ?: "불러오지 못했습니다"; empty.visibility = View.VISIBLE }
                r.onSuccess { tl ->
                    val anchor = firstVisibleIndex()
                    loaded.clear(); pagesBusy.clear(); pagesDone.clear()
                    all?.let { l -> l.forEachIndexed { i, it -> loaded[i] = it }; for (p in 0..l.size / PAGE) pagesDone += p }
                    var start = 0
                    days = ArrayList(tl.map { (k, n) -> Day(k, start, n).also { start += n } })
                    mergeLocals(); rebuild()
                    if (anchor > 0) scrollToIndex(anchor)
                    if (total == 0) { empty.text = "사진이 없습니다"; empty.visibility = View.VISIBLE }
                }
            }
        }
    }

    override fun phoneChanged(list: List<Item>?) {
        if (list != null) phoneOnly = list
        if (days.isEmpty() && phoneOnly.isEmpty()) { adapter.notifyDataSetChanged(); return }
        val anchor = firstVisibleIndex()
        mergeLocals(); rebuild(); if (anchor > 0) scrollToIndex(anchor)
    }

    /** 휴대폰에만 있는 사진을 그날 끝에 붙임 (서버에 없는 날이면 그날을 새로 만듦) */
    private fun mergeLocals() {
        val byDay = phoneOnly.groupBy { dayKeyOf(it.time) }
        val out = ArrayList<Day>()
        for (d in days) { if (d.srvCount > 0) out += Day(d.key, d.srvStart, d.srvCount, byDay[d.key] ?: emptyList()) }
        val have = out.map { it.key }.toHashSet()
        for ((k, l) in byDay) if (k !in have) out += Day(k, 0, 0, l)
        out.sortByDescending { it.key }
        var v = 0
        for (d in out) { d.vStart = v; v += d.count }
        days = out; total = v
    }

    /** 화면 순서 v 번째 사진 (아직 안 받았으면 null — 받기 시작) */
    private fun itemAt(v: Int): Item? {
        val d = dayOf(v) ?: return null
        val o = v - d.vStart
        if (o >= d.srvCount) return d.locals.getOrNull(o - d.srvCount)
        val s = d.srvStart + o
        return loaded[s] ?: run { want(s / PAGE); null }
    }
    private fun dayOf(v: Int): Day? {
        var lo = 0; var hi = days.size - 1
        while (lo <= hi) { val m = (lo + hi) ushr 1; val d = days[m]; if (v < d.vStart) hi = m - 1 else if (v >= d.vStart + d.count) lo = m + 1 else return d }
        return null
    }
    /** 보이는 곳 앞뒤 한 쪽씩 미리 받아 둠 (넘길 때 빈 칸이 덜 보이게) */
    private fun prefetch() {
        if (days.isEmpty() || visTo == Int.MAX_VALUE) return
        fun srv(v: Int): Int { val d = dayOf(v.coerceIn(0, maxOf(0, total - 1))) ?: return 0; return d.srvStart + minOf(v - d.vStart, maxOf(0, d.srvCount - 1)) }
        val a = srv(visFrom) - PAGE; val b = srv(visTo - 1) + PAGE
        for (p in maxOf(0, a) / PAGE..maxOf(0, b) / PAGE) if (p * PAGE < total) want(p)
    }
    private var notifyPosted = false
    @Volatile private var visFrom = 0
    @Volatile private var visTo = Int.MAX_VALUE
    private fun want(p: Int) {
        if (p in pagesDone || !pagesBusy.add(p)) return
        val my = seq
        net.execute {
            // 막대를 끌며 지나친 곳은 받지 않음 (지금 보이는 곳부터 빨리)
            if (p * PAGE + PAGE < visFrom - 1000 || p * PAGE > visTo + 1000) { a.runOnUiThread { pagesBusy.remove(p) }; return@execute }
            val got = try { Photos(a).list(ns, p * PAGE, PAGE) } catch (e: Exception) { null }
            a.runOnUiThread {
                if (my != seq) return@runOnUiThread
                pagesBusy.remove(p)
                if (got == null) return@runOnUiThread   // 다음에 보일 때 다시
                pagesDone += p
                got.forEachIndexed { i, it -> loaded[p * PAGE + i] = it }
                checkDrift(p, got)
                if (!notifyPosted) { notifyPosted = true; list.postDelayed({ notifyPosted = false; adapter.notifyDataSetChanged() }, 60) }
            }
        }
    }

    /** 날짜별 장수와 실제 목록이 어긋나는지 기록 (adb logcat -s TimelineGrid) */
    private fun checkDrift(p: Int, got: List<Item>) {
        var bad = 0
        got.forEachIndexed { i, it -> val s = p * PAGE + i; val d = days.firstOrNull { d -> s >= d.srvStart && s < d.srvStart + d.srvCount }; if (d != null && d.key != dayKeyOf(it.time)) bad++ }
        if (bad > 0) android.util.Log.w("TimelineGrid", "쪽 $p: ${got.size}장 중 ${bad}장이 다른 날 자리")
    }

    // ---------- 줄 만들기 ----------
    private fun groupKey(d: Day) = when (unit) { "year" -> d.key / 10000; "month" -> d.key / 100; else -> d.key }
    private fun groupTitle(d: Day): String {
        val y = d.key / 10000; val m = d.key / 100 % 100; val dd = d.key % 100
        return when (unit) {
            "year" -> "${y}년"
            "month" -> "${y}년 ${m}월"
            else -> { val c = java.util.Calendar.getInstance(utc).apply { clear(); set(y, m - 1, dd) }; "${y}년 ${m}월 ${dd}일 " + "(${"일월화수목금토"[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]})" }
        }
    }
    private fun rebuild() {
        val out = ArrayList<Row>(); val c = cols
        var i = 0
        while (i < days.size) {
            val k = groupKey(days[i]); var j = i
            while (j < days.size && groupKey(days[j]) == k) j++
            val from = days[i].vStart; val to = days[j - 1].vStart + days[j - 1].count
            val year = days[i].key / 10000
            out += Row(groupTitle(days[i]), to - from, from, from, year)
            var s = from
            while (s < to) { out += Row(null, 0, s, minOf(s + c, to), year); s += c }
            i = j
        }
        rows = out
        adapter.notifyDataSetChanged()
        track.rebuild()
    }

    private fun firstVisibleIndex(): Int = rows.getOrNull(list.firstVisiblePosition)?.vFrom ?: 0
    private fun scrollToIndex(v: Int) {
        val r = rowOf(v)
        list.setSelection(if (r > 0 && rows[r - 1].title != null) r - 1 else r)
    }
    private fun rowOf(v: Int): Int {
        var lo = 0; var hi = rows.size - 1; var ans = 0
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (rows[m].vFrom <= v) { ans = m; lo = m + 1 } else hi = m - 1 }
        // 같은 vFrom 의 머리줄과 칸 줄 중 칸 줄로
        while (ans + 1 < rows.size && rows[ans].title != null && rows[ans + 1].vFrom <= v) ans++
        return ans
    }

    // ---------- 고르기 ----------
    override fun clearSelection() { selected.clear(); adapter.notifyDataSetChanged(); onSelect(0) }
    override fun selectAll() { loaded.values.forEach { selected += it.id }; phoneOnly.forEach { selected += it.id }; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    override fun selectedItems(): List<Item> = (loaded.values + phoneOnly).filter { it.id in selected }
    private fun toggle(it: Item) { if (!selected.remove(it.id)) selected += it.id; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    override fun removeItems(ids: Set<Long>) { selected.removeAll(ids); phoneOnly = phoneOnly.filter { it.id !in ids }; onSelect(selected.size); reload() }
    override fun update(it: Item) { val k = loaded.entries.firstOrNull { e -> e.value.id == it.id }?.key ?: return; loaded[k] = it; adapter.notifyDataSetChanged() }

    /** 크게 보기: 누른 사진 앞뒤로 받아 둔 것을 이어서 넘길 수 있게 */
    private fun open(v: Int) {
        var lo = v; var hi = v
        while (lo > 0 && v - lo < 600 && itemAt(lo - 1) != null) lo--
        while (hi < total - 1 && hi - v < 600 && itemAt(hi + 1) != null) hi++
        val l = (lo..hi).mapNotNull { itemAt(it) }
        onOpen(l, (v - lo).coerceIn(0, maxOf(0, l.size - 1)))
    }

    // ---------- 두 손가락: 연도 ↔ 월 ↔ 일 ----------
    private var pinch = 1f
    private var pinched = false
    private val sg = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinch = 1f; pinched = false; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch *= d.scaleFactor
            // 계속 벌리거나 오므리면 여러 단계를 차례로: 벌리면 크게(칸 수 줄임), 오므리면 작게 → 월 → 연도
            if (pinch > 1.3f) { pinch = 1f; setLevel(level - 1) }
            else if (pinch < 0.77f) { pinch = 1f; setLevel(level + 1) }
            return true
        }
    })
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        if (sg.isInProgress || e.pointerCount > 1) { val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle(); return true }
        return super.dispatchTouchEvent(e)
    }

    // ---------- 칸 ----------
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].title != null) 0 else 1
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r.title != null) {
                val v = (cv as? LinearLayout) ?: ui.row(ui.label("", if (unit == "year") 17f else 15f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) },
                    ui.label("", 12.5f, ui.sub)).apply { setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(8)) }
                (v.getChildAt(0) as TextView).apply { text = r.title; textSize = if (unit == "year") 17f else 15f }
                (v.getChildAt(1) as TextView).text = "%,d장".format(r.count)
                return v
            }
            val c = cols
            val w = (parent.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels)
            val gap = ui.dp(if (unit == "year") 1 else 2); val size = (w - gap * (c - 1)) / c
            val row = (cv as? LinearLayout)?.takeIf { it.childCount == c } ?: LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(c) { k -> addView(cell(), LinearLayout.LayoutParams(size, size).apply { if (k > 0) leftMargin = gap }) }
            }
            row.setPadding(0, 0, 0, gap)
            for (k in 0 until c) {
                val cellV = row.getChildAt(k) as FrameLayout
                (cellV.layoutParams as LinearLayout.LayoutParams).apply { width = size; height = size; leftMargin = if (k > 0) gap else 0 }
                val v = r.vFrom + k
                if (v >= r.vTo) { cellV.visibility = View.INVISIBLE; cellV.setOnClickListener(null); cellV.setOnLongClickListener(null); continue }
                cellV.visibility = View.VISIBLE
                bind(cellV, itemAt(v), v)
            }
            return row
        }
    }

    private fun cell(): FrameLayout = FrameLayout(a).apply {
        setBackgroundColor(ui.panel2)
        addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LayoutParams(-1, -1))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply { textSize = 12.5f; setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()); setPadding(0, 0, ui.dp(4), 0) })
            addView(ImageView(a).apply { setImageDrawable(Glyph("play", 0xFF202020.toInt())); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                val q = ui.dp(3); setPadding(q, q, q, q) }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)))
        }, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(5), ui.dp(5), 0) })
        addView(View(a), LayoutParams(-1, -1))   // 고른 칸 덮개
        addView(ImageView(a), LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
        addView(ImageView(a).apply { setImageDrawable(Glyph("heartOn", Color.WHITE)) }, LayoutParams(ui.dp(16), ui.dp(16), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(5), 0, 0, ui.dp(5)) })
        addView(StatusBadge.view(a, ui), LayoutParams(ui.dp(19), ui.dp(19), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(4), ui.dp(4)) })
    }

    private fun statusOf(it: Item): Int = when {
        it.local != null -> Library.PHONE_ONLY
        !Library.phoneLoaded -> 0
        Library.phoneHas(it) -> Library.BOTH
        else -> Library.SERVER_ONLY
    }

    private fun bind(c: FrameLayout, it: Item?, v: Int) {
        val iv = c.getChildAt(0) as ImageView
        val small = cols >= 10
        if (it == null) {   // 아직 받는 중
            iv.tag = null; iv.setImageDrawable(null)
            for (k in 1 until c.childCount) c.getChildAt(k).visibility = View.GONE
            c.setOnClickListener(null); c.setOnLongClickListener(null)
            return
        }
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        if (it.local != null) Thumbs.loadLocal(iv, it.local) { b -> if (b == null) { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph(if (it.video) "film" else "image", ui.sub)) } }
        else Thumbs.load(iv, ns, it.unitId, it.cacheKey, thumbSize) { b -> if (b == null) Thumbs.loadFromOriginal(iv, ns, it) { o -> if (o == null) { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph(if (it.video) "film" else "image", ui.sub)) } } }
        (c.getChildAt(1) as LinearLayout).apply {
            visibility = if (it.video && !small) View.VISIBLE else View.GONE
            (getChildAt(0) as TextView).apply { text = PhotoGrid.duration(it.dur); visibility = if (it.dur > 0 && cols <= 6) View.VISIBLE else View.GONE }
        }
        val on = it.id in selected
        c.getChildAt(2).apply { visibility = if (on) View.VISIBLE else View.GONE; setBackgroundColor(0x5560CDFF) }
        (c.getChildAt(3) as ImageView).apply {
            visibility = if (selecting) View.VISIBLE else View.GONE
            setImageDrawable(if (on) Glyph("check", ui.onAccent) else null)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (on) setColor(ui.accent) else { setColor(0x33000000); setStroke(ui.dp(2), Color.WHITE) } }
            val q = ui.dp(3); setPadding(q, q, q, q)
        }
        c.getChildAt(4).visibility = if (it.fav && !small) View.VISIBLE else View.GONE
        StatusBadge.set(c.getChildAt(5) as ImageView, if (showStatus && !small && cols <= 6) statusOf(it) else 0)
        c.setOnClickListener { _ ->
            when {
                selecting -> toggle(it)
                unit == "year" -> { unit = "month"; scrollToIndex(v) }   // 연도 → 그 달
                unit == "month" -> { unit = "day"; scrollToIndex(v) }    // 월 → 그날 (일 보기 기본 크기)
                else -> open(v)
            }
        }
        c.setOnLongClickListener { _ -> toggle(it); true }
    }

    // ---------- 오른쪽 막대: 끌어서 어디로든 (연도 눈금 · 날짜 말풍선) ----------
    private inner class Track : View(a) {
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dpf(12f); color = ui.text; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
        private val pThumb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pTag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (ui.dark) 0xE6303030.toInt() else 0xF2FFFFFF.toInt() }
        private var years: List<Pair<Int, Int>> = emptyList()   // (줄 번호, 연도)
        var dragging = false; private set
        private var shown = 0f
        private var thumbY = 0f
        private val thumbH get() = ui.dpf(44f)
        private val thumbW get() = ui.dpf(24f)
        fun rebuild() {
            val out = ArrayList<Pair<Int, Int>>(); var last = -1
            rows.forEachIndexed { i, r -> if (r.year != last) { out += i to r.year; last = r.year } }
            years = out; invalidate()
        }
        private val usable get() = (height - paddingBottom - thumbH).coerceAtLeast(1f)
        fun sync() {   // 목록이 움직이면 손잡이도
            if (dragging || rows.isEmpty()) return
            val first = list.firstVisiblePosition; val vis = maxOf(1, list.lastVisiblePosition - first)
            thumbY = usable * first / maxOf(1, rows.size - vis)
            show(); invalidate()
        }
        private val hide = Runnable { animate().alpha(0f).setDuration(300).start(); bubble.animate().alpha(0f).setDuration(300).start() }
        fun show() { animate().cancel(); alpha = 1f; removeCallbacks(hide); if (!dragging) postDelayed(hide, 1500) }
        override fun onDraw(cv: Canvas) {
            if (rows.isEmpty() || rows.size < 60) return
            val right = width.toFloat()
            // 끄는 동안 연도 눈금
            if (dragging) {
                var lastY = -1000f
                for ((row, y) in years) {
                    val yy = usable * row / rows.size + thumbH / 2
                    if (yy - lastY < ui.dpf(20f)) continue
                    val tx = right - thumbW - ui.dpf(12f); val tw = pText.measureText(y.toString())
                    cv.drawRoundRect(RectF(tx - tw - ui.dpf(7f), yy - ui.dpf(9f), tx + ui.dpf(7f), yy + ui.dpf(9f)), ui.dpf(9f), ui.dpf(9f), pTag)
                    cv.drawText(y.toString(), tx, yy + ui.dpf(4f), pText); lastY = yy
                }
            }
            // 손잡이
            pThumb.color = if (dragging) ui.accent else (if (ui.dark) 0xE6505050.toInt() else 0xE6FFFFFF.toInt())
            val r = RectF(right - thumbW - ui.dpf(2f), thumbY, right - ui.dpf(2f), thumbY + thumbH)
            cv.drawRoundRect(r, thumbW / 2, thumbW / 2, pThumb)
            pThumb.color = if (dragging) ui.onAccent else ui.sub
            val cx = r.centerX(); val cy = r.centerY(); val s = ui.dpf(4f)
            pThumb.strokeWidth = ui.dpf(1.6f); pThumb.style = Paint.Style.STROKE
            cv.drawLine(cx - s, cy - ui.dpf(3f), cx, cy - ui.dpf(7f), pThumb); cv.drawLine(cx, cy - ui.dpf(7f), cx + s, cy - ui.dpf(3f), pThumb)
            cv.drawLine(cx - s, cy + ui.dpf(3f), cx, cy + ui.dpf(7f), pThumb); cv.drawLine(cx, cy + ui.dpf(7f), cx + s, cy + ui.dpf(3f), pThumb)
            pThumb.style = Paint.Style.FILL
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (rows.size < 60) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 손잡이 근처(오른쪽 끝)를 눌렀을 때만 잡음
                    if (e.x < width - thumbW - ui.dpf(14f) || alpha < 0.3f) return false   // 숨어 있을 때는 칸 누르기로
                    dragging = true; parent.requestDisallowInterceptTouchEvent(true); show(); moveTo(e.y); return true
                }
                MotionEvent.ACTION_MOVE -> { if (dragging) moveTo(e.y); return dragging }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; show(); invalidate(); return true }
            }
            return false
        }
        private fun moveTo(y: Float) {
            thumbY = (y - thumbH / 2).coerceIn(0f, usable)
            val row = ((thumbY / usable) * (rows.size - 1)).toInt().coerceIn(0, rows.size - 1)
            list.setSelection(row)
            val r = rows[row]
            bubbleAt(dayOf(r.vFrom)?.let { "${it.key / 10000}년 ${it.key / 100 % 100}월" } ?: "")
            invalidate()
        }
        fun bubbleAt(t: String) {
            bubble.text = t
            bubble.animate().cancel(); bubble.alpha = 1f; bubble.visibility = View.VISIBLE
            bubble.translationY = (thumbY + thumbH / 2 - ui.dpf(20f)).coerceAtLeast(0f)
        }
    }

    private var scrolling = false
    init {
        list.divider = null; list.dividerHeight = 0; list.selector = android.graphics.drawable.ColorDrawable(0)
        list.isVerticalScrollBarEnabled = false; list.setBackgroundColor(ui.bg); list.clipToPadding = false
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) { scrolling = s != AbsListView.OnScrollListener.SCROLL_STATE_IDLE; if (scrolling) track.show() }
            override fun onScroll(v: AbsListView, first: Int, visible: Int, totalCount: Int) {
                track.sync()
                visFrom = rows.getOrNull(first)?.vFrom ?: 0; visTo = rows.getOrNull(first + visible)?.vTo ?: Int.MAX_VALUE
                if (!track.dragging) prefetch()
                val r0 = rows.getOrNull(first)
                if (r0 == null || (r0.title != null && (v.getChildAt(0)?.top ?: 0) >= 0)) sticky.visibility = View.GONE
                else titleAbove(first)?.let { (t, n) -> sticky.text = "$t   ${"%,d".format(n)}장"; sticky.visibility = View.VISIBLE }
                if (!track.dragging && scrolling) rows.getOrNull(first)?.let { r -> dayOf(r.vFrom)?.let { d -> track.bubbleAt("${d.key / 10000}년 ${d.key / 100 % 100}월") } }
            }
        })
        addView(PullRefresh(a, ui, list, { !list.canScrollVertically(-1) }) { onPull?.invoke() ?: reload() }, LayoutParams(-1, -1))
        addView(empty, LayoutParams(-1, -1))
        addView(spin, LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
        addView(track, LayoutParams(ui.dp(140), -1, Gravity.END).apply { })
        track.alpha = 0f
        bubble.apply {
            textSize = 15f; setTextColor(ui.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.round(if (ui.dark) 0xF2333333.toInt() else 0xF7FFFFFF.toInt(), 18, stroke = ui.line); elevation = ui.dpf(6f)
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); alpha = 0f
        }
        addView(bubble, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = ui.dp(40) })
        sticky.apply {
            textSize = 14f; setTextColor(ui.text); typeface = android.graphics.Typeface.DEFAULT_BOLD; visibility = View.GONE
            background = ui.round(if (ui.dark) 0xE6262626.toInt() else 0xF2FFFFFF.toInt(), 16); elevation = ui.dpf(3f)
            setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
        }
        addView(sticky, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(8), 0, 0) })
        reload()
    }

    /** 아래에 떠 있는 것(연도·월·일 고르기)에 가리지 않도록 */
    fun setBottomSpace(px: Int) { list.setPadding(0, 0, 0, px); track.setPadding(0, 0, 0, px) }
}
