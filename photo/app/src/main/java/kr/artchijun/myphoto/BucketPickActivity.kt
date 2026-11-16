package kr.artchijun.myphoto

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 백업할 휴대폰 폴더 고르기 — 폴더 나무.
 *   폴더를 고르면 그 아래 하위 폴더까지 함께 (나중에 생기는 하위 폴더도). 일부만 고른 폴더는 반쯤 찬 표시.
 *   저장 형식 (Backup.Settings.buckets): "DCIM/" = 그 폴더와 아래 전부,  "=DCIM/" = 그 폴더에 바로 있는 파일만
 */
class BucketPickActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var st: Backup.Settings
    private val bg = Executors.newSingleThreadExecutor()

    /** 폴더 하나. path 는 "DCIM/Camera/" 꼴 (맨 위는 "") */
    class Node(val name: String, val path: String, val parent: Node?) {
        val kids = ArrayList<Node>()
        var own = 0      // 이 폴더에 바로 있는 사진·동영상 수
        var total = 0    // 아래까지 합친 수
        val depth: Int get() = if (parent == null || parent.parent == null) 0 else parent.depth + 1
    }
    private val root = Node("", "", null)
    private val sel = LinkedHashSet<String>()
    private val open = HashSet<String>()
    private var rows = listOf<Node>()
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var ok: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); st = Backup.Settings(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "그만") { finish() },
            ui.label(intent.getStringExtra("title") ?: "백업할 휴대폰 폴더", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)); minimumHeight = ui.dp(56) }
        val hint = ui.label(intent.getStringExtra("hint") ?: "폴더를 고르면 그 안의 하위 폴더도 함께 백업합니다. 왼쪽 ›를 누르면 펼쳐집니다.", 13f, ui.sub)
            .apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(6)) }
        list = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); setBackgroundColor(ui.bg); adapter = this@BucketPickActivity.adapter }
        summary = ui.label("", 13.5f, ui.sub)
        ok = ui.button("확인") { save(sel.toSet()); setResult(RESULT_OK); finish() }
        val bottom = ui.col(summary, ok.apply { layoutParams = ui.lp(h = ui.dp(50), top = 8) })
            .apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)); setBackgroundColor(ui.panel) }
        val rootView = ui.col(top, hint.apply { setBackgroundColor(ui.bg) }, list.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, bottom)
        ui.insets(rootView)
        setContentView(rootView)
        summary.text = "폴더를 읽는 중…"
        bg.execute {
            val all = try { Backup.deviceBuckets(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { load(all) }
        }
    }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    // 어디에 적을지: 기본은 백업할 폴더, intent 의 "key" 가 있으면 그 설정 (예: 공간 확보의 휴대폰 폴더)
    private val key get() = intent.getStringExtra("key")
    private fun saved(): Set<String> = key?.let { k -> Prefs(this).str(k, "").split('\n').filter { it.isNotEmpty() }.toSet() } ?: st.buckets
    private fun save(v: Set<String>) { val k = key; if (k == null) st.buckets = v else Prefs(this).putStr(k, v.joinToString("\n")) }

    // ---------- 나무 만들기 ----------
    private fun load(all: List<Pair<String, Int>>) {
        for ((rel, n) in all) {
            var cur = root
            for (seg in rel.split('/').filter { it.isNotEmpty() }) {
                cur = cur.kids.firstOrNull { it.name == seg } ?: Node(seg, cur.path + seg + "/", cur).also { cur.kids += it }
            }
            cur.own += n
        }
        fun fin(x: Node): Int { x.kids.sortWith(compareBy({ !(x === root && it.name == "DCIM") }, { it.name.lowercase() })); x.total = x.own + x.kids.sumOf { fin(it) }; return x.total }
        fin(root)
        sel += saved()
        // 처음 펼침: DCIM, 그리고 고른 것이 있는 곳까지
        open += "DCIM/"
        for (e in sel) { var p = e.removePrefix("=").trimEnd('/'); while (p.contains('/')) { p = p.substringBeforeLast('/'); open += "$p/" }; if (e.startsWith("=")) open += e.removePrefix("=") }
        refresh()
    }

    private fun refresh() {
        val out = ArrayList<Node>()
        if (root.own > 0) out += root   // 맨 위에 바로 있는 파일
        fun walk(x: Node) { for (k in x.kids) { out += k; if (k.path in open) walk(k) } }
        walk(root)
        rows = out
        adapter.notifyDataSetChanged()
        // 아래 요약: 고른 폴더 수와 백업할 장수
        val n = countSelected(root)
        summary.text = if (sel.isEmpty()) "고른 폴더가 없습니다" else "고른 폴더 ${sel.size}곳 · 사진·동영상 ${"%,d".format(n)}개"
        ok.isEnabled = true
    }

    // ---------- 고르기 상태 ----------
    private val NONE = 0; private val PART = 1; private val FULL = 2
    private fun covered(x: Node): Boolean { var p: Node? = x; while (p != null && p !== root) { if (p.path in sel) return true; p = p.parent }; return false }
    private fun state(x: Node): Int {
        if (x === root) return if ("=" in sel) FULL else NONE
        if (covered(x)) return FULL
        return if (sel.any { e -> val p = e.removePrefix("="); p.startsWith(x.path) }) PART else NONE
    }
    private fun countSelected(x: Node): Int {
        if (x !== root && x.path in sel) return x.total
        var n = if (("=" + x.path) in sel) x.own else 0
        for (k in x.kids) n += countSelected(k)
        return n
    }

    private fun toggle(x: Node) {
        if (x === root) { if (!sel.remove("=")) sel += "="; refresh(); return }
        if (state(x) == FULL) {
            // 끄기: 덮고 있던 위 폴더 A 를 풀고, A 에서 x 까지 내려오는 길 옆의 것들은 그대로 남김
            var a: Node = x; while (a.path !in sel) a = a.parent!!
            sel -= a.path
            val chain = ArrayList<Node>(); var p: Node = x; while (p !== a) { chain += p; p = p.parent!! }; chain += a
            chain.reverse()   // a … x
            for (i in 0 until chain.size - 1) {
                val cur = chain[i]; val next = chain[i + 1]
                if (cur.own > 0) sel += "=" + cur.path
                for (k in cur.kids) if (k !== next) sel += k.path
            }
        } else {
            // 켜기: 이 폴더 아래의 낱낱 고른 것들은 이 하나로 합침
            sel.removeAll { e -> e.removePrefix("=").startsWith(x.path) }
            sel += x.path
            collapse(x.parent)
        }
        refresh()
    }
    /** 아래가 모두 골라졌으면 위 폴더 하나로 합침 (나중에 생길 하위 폴더도 함께 백업되도록) */
    private fun collapse(x: Node?) {
        if (x == null || x === root) return
        val ownOk = x.own == 0 || ("=" + x.path) in sel
        if (ownOk && x.kids.isNotEmpty() && x.kids.all { it.path in sel }) {
            sel -= "=" + x.path; x.kids.forEach { sel -= it.path }; sel += x.path
            collapse(x.parent)
        }
    }

    // ---------- 줄 그리기 ----------
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val x = rows[p]
            val v = (cv as? LinearLayout) ?: newRow()
            val chev = v.getChildAt(0) as ImageView
            val icon = v.getChildAt(1) as ImageView
            val name = v.getChildAt(2) as TextView
            val count = v.getChildAt(3) as TextView
            val box = v.getChildAt(4) as ImageView
            val s = state(x)
            v.setPadding(ui.dp(6) + ui.dp(22) * x.depth, 0, ui.dp(16), 0)
            val hasKids = x.kids.isNotEmpty() && x !== root   // 맨 위 줄은 그 자리 파일만이라 펼치지 않음
            val isOpen = x.path in open
            chev.setImageDrawable(if (hasKids) Glyph("chev", ui.sub) else null)
            chev.rotation = if (isOpen) 90f else 0f
            chev.setOnClickListener(if (hasKids) View.OnClickListener { if (!open.remove(x.path)) open += x.path; refresh() } else null)
            chev.isClickable = hasKids
            icon.setImageDrawable(Glyph(if (x === root) "phone" else "folder", if (s != NONE) ui.accent else ui.sub))
            name.text = if (x === root) "맨 위 폴더에 바로 있는 파일" else x.name
            count.text = "%,d".format(if (x === root) x.own else x.total)
            box.setImageDrawable(TriBox(s))
            v.setOnClickListener { toggle(x) }
            return v
        }
    }

    private fun newRow(): LinearLayout = ui.row(
        ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(52)); scaleType = ImageView.ScaleType.CENTER; setPadding(ui.dp(10), ui.dp(14), ui.dp(10), ui.dp(14)); background = ui.press(null, 20) },
        ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { rightMargin = ui.dp(12) } },
        ui.label("", 15.5f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
        ui.label("", 13f, ui.sub).apply { setPadding(ui.dp(8), 0, ui.dp(14), 0) },
        ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) },
    ).apply { minimumHeight = ui.dp(52); gravity = Gravity.CENTER_VERTICAL; background = ui.press(null) }

    /** 세 가지 상태 고르기 칸: 빈 칸 · 반쯤(일부) · 다 고름 */
    private inner class TriBox(private val s: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds; val d = resources.displayMetrics.density
            val r = RectF(b.left + 1.5f * d, b.top + 1.5f * d, b.right - 1.5f * d, b.bottom - 1.5f * d)
            if (s == NONE) { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = ui.sub; c.drawRoundRect(r, 4 * d, 4 * d, p); return }
            p.style = Paint.Style.FILL; p.color = ui.accent; c.drawRoundRect(r, 4 * d, 4 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.color = ui.onAccent; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            val w = r.width(); val h = r.height()
            if (s == FULL) { val path = Path(); path.moveTo(r.left + w * 0.22f, r.top + h * 0.52f); path.lineTo(r.left + w * 0.43f, r.top + h * 0.72f); path.lineTo(r.left + w * 0.78f, r.top + h * 0.30f); c.drawPath(path, p) }
            else c.drawLine(r.left + w * 0.25f, r.centerY(), r.right - w * 0.25f, r.centerY(), p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** 이 휴대폰 폴더(RELATIVE_PATH, "DCIM/Camera/")가 고른 것에 드나 — 하위 폴더 포함("X/") · 그 폴더만("=X/") · 맨 위("=") */
        fun covers(set: Set<String>, rel: String): Boolean {
            val r = rel.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return set.any { e -> when { e == "=" -> r.isEmpty(); e.startsWith("=") -> r == e.removePrefix("="); else -> r.startsWith(e) } }
        }
        /** 고른 것을 사람이 읽기 좋게 ("DCIM/Camera", "DCIM (이 폴더만)") */
        fun describe(set: Set<String>): String = set.sorted().joinToString(", ") { e ->
            when {
                e == "=" -> "맨 위 파일"
                e.startsWith("=") -> e.removePrefix("=").trimEnd('/') + " (이 폴더만)"
                else -> e.trimEnd('/')
            }
        }
    }
}
