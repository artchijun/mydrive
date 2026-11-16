package kr.artchijun.mydrive

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 홈 편집: 섹션과 아이콘의 순서를 손잡이(≡)를 끌어서 바꾸고, 체크를 풀면 홈에서 숨김.
 *   저장: pref "homeLayout" = {"secs":[섹션 키…], "tiles":{섹션 키:[아이콘 키…]}, "hide":[키…]}
 */
object HomeEdit {
    class Tile(val key: String, val title: String, val icon: Drawable?)
    class Sec(val key: String, val title: String, val tiles: List<Tile>)

    class Layout(val secs: List<String>, val tiles: Map<String, List<String>>, val hide: Set<String>) {
        /** 저장된 순서대로 (모르는 것은 원래 순서로 뒤에) */
        fun <T> order(list: List<T>, saved: List<String>?, key: (T) -> String): List<T> {
            if (saved == null) return list
            val idx = saved.withIndex().associate { it.value to it.index }
            return list.withIndex().sortedBy { (i, t) -> idx[key(t)] ?: (10_000 + i) }.map { it.value }
        }
    }

    fun load(pf: Prefs): Layout = try {
        val j = JSONObject(pf.str("homeLayout", "{}"))
        fun arr(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.getString(it) } }
        val t = j.optJSONObject("tiles")
        Layout(arr(j.optJSONArray("secs")) ?: emptyList(), t?.keys()?.asSequence()?.associate { k -> k to arr(t.getJSONArray(k))!! } ?: emptyMap(),
            arr(j.optJSONArray("hide"))?.toSet() ?: emptySet())
    } catch (e: Exception) { Layout(emptyList(), emptyMap(), emptySet()) }

    fun show(a: Activity, ui: Ui, pf: Prefs, secs: List<Sec>, saved: () -> Unit) {
        val lay = load(pf)
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        val scroll = ScrollView(a)
        val blocks = ui.col().apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(24)); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        val checks = HashMap<String, CheckBox>()

        fun handle() = ImageView(a).apply { setImageDrawable(Glyph("menu", ui.sub)); val p = ui.dp(10); setPadding(p, p, p, p); layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); contentDescription = "끌어서 옮기기" }
        fun check(key: String) = CheckBox(a).apply { isChecked = key !in lay.hide; checks[key] = this; contentDescription = "홈에 보이기" }

        for (s in lay.order(secs, lay.secs.ifEmpty { null }) { it.key }) {
            val block = ui.col().apply { background = ui.round(ui.panel2, 12); setPadding(0, ui.dp(2), 0, ui.dp(6)); layoutParams = ui.lp().apply { topMargin = ui.dp(10) }; tag = s.key }
            val h = handle()
            block.addView(ui.row(h, ui.label(s.title, 16f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) }, check("sec:" + s.key)).apply { setPadding(ui.dp(4), 0, ui.dp(8), 0) })
            val inner = ui.col()
            for (t in lay.order(s.tiles, lay.tiles[s.key]) { it.key }) {
                val th = handle()
                inner.addView(ui.row(th, ImageView(a).apply { setImageDrawable(t.icon); layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { rightMargin = ui.dp(12) } },
                    ui.label(t.title, 15f).apply { layoutParams = ui.lp(0, weight = 1f) }, check(t.key)).apply { tag = t.key; setPadding(ui.dp(14), 0, ui.dp(8), 0) })
                drag(th, inner, scroll)
            }
            block.addView(inner)
            drag(h, blocks, scroll)
            blocks.addView(block)
        }

        val save = ui.label("저장", 16f, ui.accent, bold = true).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); background = ui.press(null, 8) }
        val reset = ui.label("처음대로", 14f, ui.sub).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.press(null, 8) }
        val top = ui.row(ui.iconBtn(Glyph("close", ui.text), "닫기") { d.dismiss() },
            ui.label("홈 편집", 18f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) } }, reset, save).apply {
            setPadding(ui.dp(6), ui.dp(6), ui.dp(8), ui.dp(6)); setBackgroundColor(ui.panel) }
        val hint = ui.label("≡ 을 누른 채 위아래로 끌면 순서가 바뀝니다. 체크를 풀면 홈에서 숨깁니다.", 13f, ui.sub).apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0) }
        scroll.addView(blocks)
        val root = ui.col(top, hint, scroll.apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }).apply { setBackgroundColor(ui.bg) }
        save.setOnClickListener {
            val j = JSONObject()
            val sk = JSONArray(); val tl = JSONObject()
            for (i in 0 until blocks.childCount) {
                val b = blocks.getChildAt(i) as LinearLayout; val k = b.tag as String; sk.put(k)
                val inner = b.getChildAt(1) as LinearLayout
                tl.put(k, JSONArray((0 until inner.childCount).map { inner.getChildAt(it).tag as String }))
            }
            j.put("secs", sk).put("tiles", tl).put("hide", JSONArray(checks.filter { !it.value.isChecked }.keys.toList()))
            pf.putStr("homeLayout", j.toString()); d.dismiss(); saved()
        }
        reset.setOnClickListener { pf.putStr("homeLayout", "{}"); d.dismiss(); saved() }
        d.setContentView(root)
        d.show()
    }

    /** 손잡이를 누른 채 끌면 그 줄(손잡이의 부모 중 list 의 자식)이 손가락을 따라가고, 손가락이 놓인 자리로 순서가 바뀜 */
    private fun drag(handle: View, list: LinearLayout, scroll: ScrollView) {
        var row: View? = null; var grab = 0f
        val loc = IntArray(2)
        fun fingerInList(rawY: Float): Float { list.getLocationOnScreen(loc); return rawY - loc[1] }
        fun follow(r: View, fy: Float) { r.translationY = fy - grab - r.top }
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    var r: View = v; while (r.parent !== list) r = r.parent as View
                    row = r; grab = fingerInList(e.rawY) - r.top; scroll.requestDisallowInterceptTouchEvent(true)
                    r.alpha = 0.8f; r.translationZ = 16f
                }
                MotionEvent.ACTION_MOVE -> {
                    val r = row ?: return@setOnTouchListener true
                    val fy = fingerInList(e.rawY)
                    val i = list.indexOfChild(r)
                    // 끌고 있는 줄의 가운데가 이웃의 가운데를 넘으면 자리 바꿈 (위치는 손가락 기준이라 스크롤돼도 맞음)
                    val mid = fy - grab + r.height / 2f
                    var to = i
                    while (to > 0 && list.getChildAt(to - 1).let { mid < it.top + it.height / 2f }) to--
                    if (to == i) while (to < list.childCount - 1 && list.getChildAt(to + 1).let { mid > it.top + it.height / 2f }) to++
                    if (to != i) { list.removeView(r); list.addView(r, to); val y = e.rawY; list.post { row?.let { follow(it, fingerInList(y)) } } }
                    else follow(r, fy)
                    // 화면 끝에 가까우면 스크롤
                    val sl = IntArray(2); scroll.getLocationOnScreen(sl)
                    val edge = scroll.height / 8
                    if (e.rawY < sl[1] + edge) scroll.scrollBy(0, -24) else if (e.rawY > sl[1] + scroll.height - edge) scroll.scrollBy(0, 24)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    row?.let { it.animate().translationY(0f).setDuration(120).start(); it.alpha = 1f; it.translationZ = 0f }
                    row = null; scroll.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }
}
