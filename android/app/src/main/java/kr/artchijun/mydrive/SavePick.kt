package kr.artchijun.mydrive

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * 받은 파일을 저장할 곳 고르기 — 아래에서 올라오는 판.
 *   처음 화면: 섹션별 장소 (NAS 가 맨 위) → 누르면 그 안 폴더 둘러보기 (주소 조각 · 폴더 줄) → 아래 [새 폴더] [여기에 저장]
 */
class SavePick(
    private val a: Activity, private val ui: Ui, private val fs: Fs, private val net: Executor,
    private val title: String, private val files: List<String>,
    private val sections: List<Pair<String, List<Place>>>,
    private val iconOf: (Loc) -> Drawable, private val textOf: (Loc) -> String,
    private val askName: (done: (String) -> Unit) -> Unit,
    private val onPick: (Loc, List<Item>) -> Unit,
) {
    class Place(val title: String, val sub: String, val loc: Loc)

    private val d = Dialog(a)
    private val body = ui.col()
    private val scroll = ScrollView(a).apply { addView(body); isFillViewport = true }
    private val head = ui.col()
    private val foot = ui.row()
    private var cur: Loc? = null
    private var kids: List<Item> = emptyList()
    private var seq = 0

    fun show() {
        val sheet = ui.col(head, scroll.apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }, foot).apply {
            background = GradientDrawable().apply { setColor(ui.panel); cornerRadii = floatArrayOf(ui.dpf(20f), ui.dpf(20f), ui.dpf(20f), ui.dpf(20f), 0f, 0f, 0f, 0f) }
        }
        d.setContentView(sheet)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.78).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.55f)
            attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        places()
        d.show()
    }

    // ---------- 위쪽: 손잡이 · 제목 · 받은 파일 ----------
    private fun header(sub: String, back: (() -> Unit)?) {
        head.removeAllViews()
        head.addView(View(a).apply {   // 손잡이
            background = ui.round(ui.line, 3); layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(10) }
        })
        val t = ui.label(title, 19f, ui.text, bold = true)
        val s = ui.label(sub, 13f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START; layoutParams = ui.lp(top = 2) }
        val text = ui.col(t, s).apply { layoutParams = ui.lp(0, weight = 1f) }
        val row = ui.row().apply { setPadding(ui.dp(if (back != null) 6 else 20), ui.dp(10), ui.dp(8), ui.dp(10)) }
        if (back != null) row.addView(ui.iconBtn(Glyph("back", ui.text), "뒤로", 44) { back() })
        row.addView(text)
        row.addView(ui.iconBtn(Glyph("close", ui.sub), "닫기", 44) { d.dismiss() })
        head.addView(row)
        head.addView(View(a).apply { setBackgroundColor(ui.line); layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }

    private fun sectionHead(s: String) = ui.label(s, 13f, ui.accent, bold = true).apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(6)) }

    private fun row(icon: Drawable, t: String, sub: String, onClick: () -> Unit): View {
        val iv = ImageView(a).apply { setImageDrawable(icon); val p = ui.dp(8); setPadding(p, p, p, p); background = ui.round(ui.panel2, 12)
            layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)) }
        val tt = ui.label(t, 15.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val ss = ui.label(sub, 12.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
        return ui.row(iv, ui.col(tt, ss).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } },
            ImageView(a).apply { setImageDrawable(Glyph("chev", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)) }).apply {
            setPadding(ui.dp(16), ui.dp(7), ui.dp(14), ui.dp(7)); minimumHeight = ui.dp(58); background = ui.press(null); setOnClickListener { onClick() }
        }
    }

    // ---------- 처음 화면: 장소 ----------
    private fun places() {
        cur = null; seq++
        header(if (files.size == 1) files[0] else "${files[0]} 외 ${files.size - 1}개", null)
        body.removeAllViews()
        for ((name, ps) in sections) {
            if (ps.isEmpty()) continue
            body.addView(sectionHead(name))
            for (p in ps) body.addView(row(iconOf(p.loc), p.title, p.sub) { open(p.loc) })
        }
        body.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(16)) })
        foot.removeAllViews(); foot.visibility = View.GONE
        scroll.scrollTo(0, 0)
    }

    // ---------- 폴더 둘러보기 ----------
    private fun open(l: Loc) {
        cur = l; kids = emptyList(); val my = ++seq
        header(textOf(l), { l.parent()?.let { open(it) } ?: places() })
        body.removeAllViews()
        body.addView(ui.row(ProgressBar(a).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) },
            ui.label("폴더를 읽는 중…", 14f, ui.sub).apply { setPadding(ui.dp(12), 0, 0, 0) }).apply { setPadding(ui.dp(20), ui.dp(20), 0, 0) })
        bottom(l)
        net.execute {
            val r = try { Result.success(fs.list(l, false)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (my != seq || !d.isShowing) return@runOnUiThread
                body.removeAllViews()
                r.onFailure { e -> body.addView(ui.label(e.message ?: "읽지 못했습니다", 14f, ui.red).apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), 0) }) }
                r.onSuccess { list ->
                    kids = list
                    val dirs = list.filter { it.dir }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.KOREAN)) { it.name })
                    val files = list.size - dirs.size
                    body.addView(ui.label("폴더 ${dirs.size}개" + (if (files > 0) " · 파일 ${files}개" else ""), 12.5f, ui.sub).apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4)) })
                    if (dirs.isEmpty()) body.addView(ui.label("하위 폴더가 없습니다. 여기에 저장하거나 새 폴더를 만드세요.", 14f, ui.sub).apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), 0) })
                    for (it in dirs) body.addView(row(FileIcon(true, it.name), it.name, if (it.count >= 0) "${it.count}개" else "") { open(l.copy(path = it.path)) })
                    body.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(16)) })
                }
                scroll.scrollTo(0, 0)
            }
        }
    }

    private fun bottom(l: Loc) {
        foot.removeAllViews(); foot.visibility = View.VISIBLE
        foot.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14))
        foot.background = GradientDrawable().apply { setColor(ui.panel) }
        val mk = ui.button("새 폴더", primary = false) { askName { n -> net.execute {
            val err = try { fs.mkdir(l, n); null } catch (e: Exception) { e.message ?: "만들지 못했습니다" }
            a.runOnUiThread { if (err != null) android.widget.Toast.makeText(a, err, android.widget.Toast.LENGTH_SHORT).show() else open(l.child(n)) }
        } } }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { rightMargin = ui.dp(10) } }
        val ok = ui.button("여기에 저장", primary = true) { d.dismiss(); onPick(l, kids) }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1.6f) }
        foot.addView(mk); foot.addView(ok)
    }
}
