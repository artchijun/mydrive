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
class PhotoGrid(
    private val a: Activity, private val ui: Ui, private val net: Executor, val ns: String,
    private val grouped: Boolean, private val loader: (offset: Int, limit: Int) -> List<Item>,
    private val onOpen: (List<Item>, Int) -> Unit, private val onSelect: (Int) -> Unit,
) : FrameLayout(a) {
    companion object {
        private const val PAGE = 300
        private val utc = TimeZone.getTimeZone("UTC")
        private fun fmt(p: String, t: Long) = SimpleDateFormat(p, Locale.KOREA).apply { timeZone = utc }.format(Date(t * 1000))
        /** 묶음 단위: day · month · year */
        fun groupKey(unit: String, t: Long): String = fmt(when (unit) { "year" -> "yyyy"; "month" -> "yyyyMM"; else -> "yyyyMMdd" }, t)
        fun groupTitle(unit: String, t: Long): String = fmt(when (unit) { "year" -> "yyyy년"; "month" -> "yyyy년 M월"; else -> "yyyy년 M월 d일 (E)" }, t)
        fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { timeZone = utc }.format(Date(t * 1000))
    }

    val items = ArrayList<Item>()
    val selected = LinkedHashSet<Long>()
    val selecting get() = selected.isNotEmpty()
    private class Row(val header: String?, val count: Int, val start: Int, val end: Int)
    private var rows = ArrayList<Row>()
    private val pf = Prefs(a)
    private var cols = pf.int("cols", 4)
    /** 묶음 단위 (일·월·년). 바꾸면 칸 수도 그 단위에 맞게 (일 4 · 월 5 · 년 7 기본, 오므리기로 바꾼 값은 단위별로 기억) */
    var unit: String = pf.str("unit", "day")
        set(v) { field = v; pf.putStr("unit", v); cols = pf.int("cols_$v", when (v) { "year" -> 7; "month" -> 5; else -> pf.int("cols", 4) }); rebuild(); list.setSelection(0) }
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
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) {}
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) { if (total > 0 && first + visible >= total - 8) more() }
        })
        addView(list, LayoutParams(-1, -1))
        addView(empty, LayoutParams(-1, -1))
        addView(spin, LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
        reload()
    }

    // ---------- 받기 ----------
    fun reload() {
        seq++; items.clear(); selected.clear(); done = false; loading = false; rebuild(); onSelect(0)
        spin.visibility = View.VISIBLE; empty.visibility = View.GONE
        more()
    }

    private fun more() {
        if (loading || done) return
        loading = true; val my = seq; val off = items.size
        net.execute {
            val r = try { Result.success(loader(off, PAGE)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (my != seq) return@runOnUiThread
                loading = false; spin.visibility = View.GONE
                r.onSuccess { got -> items.addAll(got); if (got.size < PAGE) done = true; rebuild() }
                r.onFailure { e -> done = true; if (items.isEmpty()) { empty.text = e.message ?: "불러오지 못했습니다"; empty.visibility = View.VISIBLE } }
                if (r.isSuccess && items.isEmpty()) { empty.text = "사진이 없습니다"; empty.visibility = View.VISIBLE }
            }
        }
    }

    private fun rebuild() {
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
        adapter.notifyDataSetChanged()
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
        sg.onTouchEvent(e)
        if (sg.isInProgress || e.pointerCount > 1) { val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle(); return true }
        return super.dispatchTouchEvent(e)
    }

    // ---------- 고르기 ----------
    fun clearSelection() { selected.clear(); adapter.notifyDataSetChanged(); onSelect(0) }
    fun selectAll() { items.forEach { selected += it.id }; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    fun selectedItems(): List<Item> = items.filter { it.id in selected }
    fun toggle(it: Item) { if (!selected.remove(it.id)) selected += it.id; adapter.notifyDataSetChanged(); onSelect(selected.size) }
    /** 한 날짜 묶음 전체 고르기/풀기 */
    private fun toggleDay(r: Row) {
        val ids = items.subList(r.start, r.end).map { it.id }
        if (ids.all { it in selected }) selected.removeAll(ids.toSet()) else selected.addAll(ids)
        adapter.notifyDataSetChanged(); onSelect(selected.size)
    }
    fun removeItems(ids: Set<Long>) { items.removeAll { it.id in ids }; selected.removeAll(ids); rebuild(); onSelect(selected.size) }
    fun update(it: Item) { val i = items.indexOfFirst { x -> x.id == it.id }; if (i >= 0) { items[i] = it; adapter.notifyDataSetChanged() } }

    // ---------- 칸 그리기 ----------
    private val adapter: BaseAdapter = object : BaseAdapter() {
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
        addView(ImageView(a).apply { setImageDrawable(Glyph("play", Color.WHITE)); alpha = 0.95f }, LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(5), ui.dp(5), 0) })
        addView(View(a), LayoutParams(-1, -1))   // 고른 칸 덮개
        addView(ImageView(a), LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
        addView(ImageView(a).apply { setImageDrawable(Glyph("heartOn", Color.WHITE)) }, LayoutParams(ui.dp(16), ui.dp(16), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(5), 0, 0, ui.dp(5)) })
    }

    private fun bind(c: FrameLayout, it: Item, idx: Int) {
        val iv = c.getChildAt(0) as ImageView
        Thumbs.load(iv, ns, it.unitId, it.cacheKey, "sm")
        c.getChildAt(1).visibility = if (it.video) View.VISIBLE else View.GONE
        val on = it.id in selected
        c.getChildAt(2).apply { visibility = if (on) View.VISIBLE else View.GONE; setBackgroundColor(0x5560CDFF) }
        (c.getChildAt(3) as ImageView).apply {
            visibility = if (selecting) View.VISIBLE else View.GONE
            setImageDrawable(if (on) Glyph("check", ui.onAccent) else null)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (on) setColor(ui.accent) else { setColor(0x33000000); setStroke(ui.dp(2), Color.WHITE) } }
            val p = ui.dp(3); setPadding(p, p, p, p)
        }
        c.getChildAt(4).visibility = if (it.fav) View.VISIBLE else View.GONE
        c.setOnClickListener { _ -> if (selecting) toggle(it) else onOpen(items, idx) }
        c.setOnLongClickListener { _ -> toggle(it); true }
    }

    init { setup() }
}
