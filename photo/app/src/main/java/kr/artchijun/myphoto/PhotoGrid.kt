package kr.artchijun.myphoto

import android.app.Activity
import android.graphics.Color
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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * 사진 격자: 날짜별 머리줄 + 정사각 칸. 끝에 가까워지면 다음 묶음을 더 받음.
 *   두 손가락으로 오므리면/벌리면 한 줄 칸 수가 바뀜 (3~8). 길게 누르면 고르기.
 *   시놀로지의 time 은 '찍은 그 곳의 시각'을 UTC 로 적은 값이라 날짜도 UTC 로 읽음
 */
/** 사진 격자들(PhotoGrid · TimelineGrid)이 화면에 주는 것 — 고르기·지우기·새로 고침 */
interface PhotoView {
    val selected: Set<Long>
    val selecting: Boolean
    var unit: String
    fun clearSelection()
    fun selectAll()
    fun selectedItems(): List<Item>
    fun removeItems(ids: Set<Long>)
    fun update(it: Item)
    fun reload()
    fun phoneChanged(list: List<Item>?)
    /** 위로 당겼다 놓으면 (없으면 그 격자만 다시 읽음) */
    var onPull: (() -> Unit)?
}

class PhotoGrid(
    private val a: Activity, private val ui: Ui, private val net: Executor, val ns: String,
    private val grouped: Boolean, private val loader: (offset: Int, limit: Int) -> List<Item>,
    private val onOpen: (List<Item>, Int) -> Unit, private val onSelect: (Int) -> Unit,
) : FrameLayout(a), PhotoView {
    companion object {
        private const val PAGE = 300
        private val utc = TimeZone.getTimeZone("UTC")
        private fun fmt(p: String, t: Long) = SimpleDateFormat(p, Locale.KOREA).apply { timeZone = utc }.format(Date(t * 1000))
        /** 묶음 단위: day · month · year */
        fun groupKey(unit: String, t: Long): String = fmt(when (unit) { "year" -> "yyyy"; "month" -> "yyyyMM"; else -> "yyyyMMdd" }, t)
        fun groupTitle(unit: String, t: Long): String = fmt(when (unit) { "year" -> "yyyy년"; "month" -> "yyyy년 M월"; else -> "yyyy년 M월 d일 (E)" }, t)
        /** 동영상 길이 "00:19" · "1:02:03" */
        fun duration(ms: Long): String { val t = ms / 1000; return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60) }
        fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { timeZone = utc }.format(Date(t * 1000))
    }

    /** 받은 것 전부 (서버 + 끼워 넣은 휴대폰에만 있는 것) */
    private val all = ArrayList<Item>()
    /** 보이는 것 (상태 거르기를 한 뒤) */
    var items: List<Item> = emptyList(); private set
    private var serverCount = 0
    /** 휴대폰에만 있는 사진(백업 안 됨)도 날짜 사이에 끼워 보여 줄지 (전체 사진 화면) */
    var mergePhone = false
    private var phoneOnly: List<Item> = emptyList()
    private val inserted = HashSet<Long>()
    /** 백업 상태로 거르기: 0 모두 · Library.BOTH · SERVER_ONLY · PHONE_ONLY */
    var statusFilter = 0
        set(v) { field = v; rebuild(); list.setSelection(0) }
    private val showStatus get() = pf.bool("show_status", true)
    fun statusOf(it: Item): Int = when {
        it.local != null -> Library.PHONE_ONLY
        !Library.phoneLoaded -> 0
        Library.phoneHas(it) -> Library.BOTH
        else -> Library.SERVER_ONLY
    }
    /** 휴대폰 목록·서버 대조가 새로 끝났을 때: 표시와 끼워 넣은 것을 다시 */
    override fun phoneChanged(list: List<Item>?) {
        if (list != null) {
            val gone = list.map { it.id }.toSet()
            all.removeAll { it.local != null && it.id !in gone }; inserted.retainAll(gone)
            phoneOnly = list
        }
        mergeLocal(); rebuild()
    }
    /** 서버 목록이 내려온 날짜까지의 휴대폰에만 있는 것을 끼워 넣음 (다 받았으면 전부) */
    private fun mergeLocal() {
        if (!mergePhone || phoneOnly.isEmpty()) return
        val edge = if (done) Long.MIN_VALUE else (all.lastOrNull { it.local == null }?.time ?: Long.MAX_VALUE)
        var add = false
        for (p in phoneOnly) if (p.time >= edge && inserted.add(p.id)) { all += p; add = true }
        if (add) all.sortWith(compareByDescending<Item> { it.time }.thenByDescending { it.id })
    }
    override val selected = LinkedHashSet<Long>()
    override var onPull: (() -> Unit)? = null
    override val selecting get() = selected.isNotEmpty()
    private class Row(val header: String?, val count: Int, val start: Int, val end: Int)
    private var rows = ArrayList<Row>()
    private val pf = Prefs(a)
    private var cols = pf.int("cols", 4)
    /** 묶음 단위 (일·월·년). 바꾸면 칸 수도 그 단위에 맞게 (일 4 · 월 5 · 년 7 기본, 오므리기로 바꾼 값은 단위별로 기억) */
    override var unit: String = pf.str("unit", "day")
        set(v) { field = v; pf.putStr("unit", v); cols = pf.int("cols_$v", when (v) { "year" -> 7; "month" -> 5; else -> pf.int("cols", 4) }); rebuild(); list.setSelection(0) }
    /** 아래에 떠 있는 것(일·월·연도 고르기)에 가리지 않도록 목록 끝에 두는 여백 */
    fun setBottomSpace(px: Int) { list.setPadding(0, 0, 0, px) }
    private var scrolling = false
    private var thumbDrag = false
    private var loading = false
    private var done = false
    private var seq = 0
    private val list = ListView(a)
    private val empty = ui.label("", 14.5f, ui.sub).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val spin = ProgressBar(a)

    /** 처음 만들기 (모든 속성이 준비된 뒤 — 맨 아래 init 에서) */
    private fun setup() {
        if (grouped) cols = pf.int("cols_$unit", when (unit) { "year" -> 7; "month" -> 5; else -> pf.int("cols", 4) })
        list.divider = null; list.dividerHeight = 0; list.selector = android.graphics.drawable.ColorDrawable(0)
        list.isFastScrollEnabled = true; list.setBackgroundColor(ui.bg)
        list.clipToPadding = false
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) { scrolling = s != AbsListView.OnScrollListener.SCROLL_STATE_IDLE }
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                if (total > 0 && first + visible >= total - 8) more()
                if (scrolling && grouped && !thumbDrag) showBubble(first, visible, total)
            }
        })
        addView(PullRefresh(a, ui, list, { !list.canScrollVertically(-1) }) { onPull?.invoke() ?: reload() }, LayoutParams(-1, -1))
        addView(empty, LayoutParams(-1, -1))
        addView(spin, LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
        reload()
    }

    // ---------- 받기 ----------
    override fun reload() {
        seq++; all.clear(); inserted.clear(); serverCount = 0; selected.clear(); done = false; loading = false; rebuild(); onSelect(0)
        spin.visibility = View.VISIBLE; empty.visibility = View.GONE
        more()
    }

    private fun more() {
        if (loading || done) return
        loading = true; val my = seq; val off = serverCount
        net.execute {
            val r = try { Result.success(loader(off, PAGE)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (my != seq) return@runOnUiThread
                loading = false; spin.visibility = View.GONE
                r.onSuccess { got -> all.addAll(got); serverCount += got.size; if (got.size < PAGE) done = true; mergeLocal(); rebuild() }
                r.onFailure { e -> done = true; if (all.isEmpty()) { empty.text = e.message ?: "불러오지 못했습니다"; empty.visibility = View.VISIBLE } }
                if (r.isSuccess && all.isEmpty()) { empty.text = "사진이 없습니다"; empty.visibility = View.VISIBLE }
            }
        }
    }

    private fun rebuild() {
        items = if (statusFilter == 0) ArrayList(all) else all.filter { statusOf(it) == statusFilter }
        if (statusFilter != 0 && done) { empty.text = if (items.isEmpty()) "해당하는 사진이 없습니다" else ""; empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE }
        else if (statusFilter == 0 && all.isNotEmpty()) empty.visibility = View.GONE
        val out = ArrayList<Row>()
        var i = 0
        while (i < items.size) {
            var j = i
            if (grouped) {
                val k = groupKey(unit, items[i].time)
                while (j < items.size && groupKey(unit, items[j].time) == k) j++
                out += Row(groupTitle(unit, items[i].time), j - i, i, j)
            } else j = items.size
            var s = i
            while (s < j) { out += Row(null, 0, s, minOf(s + cols, j)); s += cols }
            i = j
        }
        rows = out
        buildSections()
        adapter.notifyDataSetChanged()
        if (!done && !loading && rows.size < 40 && all.isNotEmpty()) list.post { more() }
    }

    // ---------- 칸 수 바꾸기 (오므리기·벌리기) ----------
    private var pinch = 1f
    private val sg = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinch = 1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch *= d.scaleFactor
            val n = when { pinch > 1.35f -> cols - 1; pinch < 0.75f -> cols + 1; else -> cols }.coerceIn(3, 8)
            if (n != cols) { setCols(n); pinch = 1f }
            return true
        }
    })
    fun setCols(n: Int) {
        val first = list.firstVisiblePosition.coerceAtMost(rows.size - 1)
        val anchor = if (first >= 0) rows[first].start else 0
        cols = n; pf.putInt("cols_$unit", n); if (unit == "day") pf.putInt("cols", n); rebuild()
        val idx = rows.indexOfFirst { it.header == null && anchor >= it.start && anchor < it.end }
        if (idx >= 0) list.setSelection(maxOf(0, idx - 1))
    }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // 오른쪽 끝 막대 손잡이를 끄는 중이면 안드로이드의 날짜 말풍선만 (내 것은 숨김)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { thumbDrag = e.x > width - ui.dp(44); if (thumbDrag) { removeCallbacks(hideBubble); bubble.visibility = View.GONE } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> thumbDrag = false
        }
        sg.onTouchEvent(e)
        if (sg.isInProgress || e.pointerCount > 1) { val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle(); return true }
        return super.dispatchTouchEvent(e)
    }

    // ---------- 고르기 ----------
    override fun clearSelection() { selected.clear(); adapter.notifyDataSetChanged(); onSelect(0) }
    override fun selectAll() { items.forEach { selected += it.id }; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    override fun selectedItems(): List<Item> = items.filter { it.id in selected }
    fun toggle(it: Item) { if (!selected.remove(it.id)) selected += it.id; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    /** 한 날짜 묶음 전체 고르기/풀기 */
    private fun toggleDay(r: Row) {
        val ids = items.subList(r.start, r.end).map { it.id }
        if (ids.all { it in selected }) selected.removeAll(ids.toSet()) else selected.addAll(ids)
        adapter.notifyDataSetChanged(); onSelect(selected.size)
    }
    override fun removeItems(ids: Set<Long>) { all.removeAll { it.id in ids }; phoneOnly = phoneOnly.filter { it.id !in ids }; selected.removeAll(ids); rebuild(); onSelect(selected.size) }
    override fun update(it: Item) { val i = all.indexOfFirst { x -> x.id == it.id }; if (i >= 0) { all[i] = it; rebuild() } }

    // ---------- 칸 그리기 ----------
    // ---------- 스크롤 막대의 날짜 ----------
    // 막대를 잡고 끌 때 안드로이드가 띄우는 말풍선(SectionIndexer)과, 그냥 넘길 때 막대 옆에 잠깐 뜨는 날짜
    private var sectionLabels: Array<String> = emptyArray()
    private var rowSection = IntArray(0)
    private var sectionStart = IntArray(0)
    private fun rowLabel(r: Row): String { val t = items.getOrNull(r.start)?.time ?: return ""; return fmt(if (unit == "year") "yyyy년" else "yyyy년 M월", t) }
    private fun buildSections() {
        val labels = ArrayList<String>(); val starts = ArrayList<Int>(); val rs = IntArray(rows.size)
        for ((i, r) in rows.withIndex()) {
            val l = rowLabel(r)
            if (labels.isEmpty() || labels.last() != l) { labels += l; starts += i }
            rs[i] = labels.size - 1
        }
        sectionLabels = labels.toTypedArray(); sectionStart = starts.toIntArray(); rowSection = rs
    }
    private val bubble by lazy {
        TextView(a).apply {
            textSize = 13.5f; setTextColor(ui.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.round(if (ui.dark) 0xF2333333.toInt() else 0xF7FFFFFF.toInt(), 16, stroke = ui.line); elevation = ui.dpf(4f)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); visibility = View.GONE
            this@PhotoGrid.addView(this, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = ui.dp(30) })
        }
    }
    private val hideBubble = Runnable { bubble.animate().alpha(0f).setDuration(250).withEndAction { bubble.visibility = View.GONE }.start() }
    private fun showBubble(first: Int, visible: Int, total: Int) {
        if (total <= visible || rows.isEmpty()) return
        val r = rows.getOrNull(first) ?: return
        bubble.text = rowLabel(r).ifEmpty { return }
        bubble.animate().cancel(); bubble.alpha = 1f; bubble.visibility = View.VISIBLE
        // 막대 손잡이 높이쯤에 둠
        val h = list.height - list.paddingBottom
        val y = (h * first.toFloat() / maxOf(1, total - visible)).coerceIn(0f, (h - ui.dp(40)).toFloat().coerceAtLeast(0f))
        bubble.translationY = y
        removeCallbacks(hideBubble); postDelayed(hideBubble, 1200)
    }

    private val adapter: BaseAdapter = object : BaseAdapter(), android.widget.SectionIndexer {
        override fun getSections(): Array<Any> = sectionLabels.map { it as Any }.toTypedArray()
        override fun getPositionForSection(sec: Int) = sectionStart.getOrElse(sec.coerceAtLeast(0)) { rows.size - 1 }.coerceAtLeast(0)
        override fun getSectionForPosition(pos: Int) = rowSection.getOrElse(pos) { maxOf(0, sectionLabels.size - 1) }
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].header != null) 0 else 1

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r.header != null) {
                val v = (cv as? LinearLayout) ?: ui.row(ui.label("", 15f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) },
                    ui.label("", 12.5f, ui.sub)).apply { setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(8)) }
                (v.getChildAt(0) as TextView).text = r.header
                (v.getChildAt(1) as TextView).text = if (selecting) (if ((r.start until r.end).all { items[it].id in selected }) "모두 풀기" else "모두 고르기") else "${r.count}장"
                v.setOnClickListener { if (selecting) toggleDay(r) }
                return v
            }
            val w = (parent.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels)
            val gap = ui.dp(2); val size = (w - gap * (cols - 1)) / cols
            val row = (cv as? LinearLayout)?.takeIf { it.childCount == cols } ?: LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(cols) { k -> addView(cell(), LinearLayout.LayoutParams(size, size).apply { if (k > 0) leftMargin = gap }) }
            }
            row.setPadding(0, 0, 0, gap)
            for (k in 0 until cols) {
                val c = row.getChildAt(k) as FrameLayout
                (c.layoutParams as LinearLayout.LayoutParams).apply { width = size; height = size }
                val idx = r.start + k
                if (idx >= r.end) { c.visibility = View.INVISIBLE; c.setOnClickListener(null); c.setOnLongClickListener(null); continue }
                c.visibility = View.VISIBLE
                bind(c, items[idx], idx)
            }
            return row
        }
    }

    private fun cell(): FrameLayout = FrameLayout(a).apply {
        setBackgroundColor(ui.panel2)
        addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LayoutParams(-1, -1))
        // 동영상 표시: 길이 + 흰 동그라미 안의 ▶ (시놀로지 사진 앱처럼 오른쪽 위)
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply { textSize = 12.5f; setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()); setPadding(0, 0, ui.dp(4), 0) })
            addView(ImageView(a).apply { setImageDrawable(Glyph("play", 0xFF202020.toInt())); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                val q = ui.dp(3); setPadding(q, q, q, q) }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)))
        }, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(6), ui.dp(6), 0) })
        addView(View(a), LayoutParams(-1, -1))   // 고른 칸 덮개
        addView(ImageView(a), LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
        addView(ImageView(a).apply { setImageDrawable(Glyph("heartOn", Color.WHITE)) }, LayoutParams(ui.dp(16), ui.dp(16), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(5), 0, 0, ui.dp(5)) })
        addView(StatusBadge.view(a, ui), LayoutParams(ui.dp(19), ui.dp(19), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(4), ui.dp(4)) })   // 백업 상태
    }

    private fun bind(c: FrameLayout, it: Item, idx: Int) {
        val iv = c.getChildAt(0) as ImageView
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        // 시놀로지가 아직 썸네일을 못 만든 것: 작은 사진은 원본을 줄여 보여 주고, 그래도 안 되면 그림 표시
        if (it.local != null) Thumbs.loadLocal(iv, it.local) { b -> if (b == null) { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph(if (it.video) "film" else "image", ui.sub)) } }
        else Thumbs.load(iv, ns, it.unitId, it.cacheKey, "sm") { b -> if (b == null) Thumbs.loadFromOriginal(iv, ns, it) { o -> if (o == null) { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph(if (it.video) "film" else "image", ui.sub)) } } }
        (c.getChildAt(1) as LinearLayout).apply {
            visibility = if (it.video) View.VISIBLE else View.GONE
            // 칸이 좁으면(7칸 이상) 길이는 빼고 ▶ 만
            (getChildAt(0) as TextView).apply { text = duration(it.dur); visibility = if (it.dur > 0 && cols <= 6) View.VISIBLE else View.GONE }
        }
        val on = it.id in selected
        c.getChildAt(2).apply { visibility = if (on) View.VISIBLE else View.GONE; setBackgroundColor(0x5560CDFF) }
        (c.getChildAt(3) as ImageView).apply {
            visibility = if (selecting) View.VISIBLE else View.GONE
            setImageDrawable(if (on) Glyph("check", ui.onAccent) else null)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (on) setColor(ui.accent) else { setColor(0x33000000); setStroke(ui.dp(2), Color.WHITE) } }
            val p = ui.dp(3); setPadding(p, p, p, p)
        }
        c.getChildAt(4).visibility = if (it.fav) View.VISIBLE else View.GONE
        StatusBadge.set(c.getChildAt(5) as ImageView, if (showStatus && cols <= 6) statusOf(it) else 0)
        c.setOnClickListener { _ -> if (selecting) toggle(it) else onOpen(items, idx) }
        c.setOnLongClickListener { _ -> toggle(it); true }
    }

    init { setup() }
}
