package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 중복 정리 (서버): 찾기 → 묶음마다 남길 것 하나(규칙대로) · 나머지는 지움 표시 → 사람이 보고 바꿀 수 있음 → 지우기.
 * 남길 것 고르는 규칙은 순서를 바꾸고 켜고 끌 수 있음 (선호 폴더 · 해상도 · 파일 크기 · 이름 · 날짜).
 */
class DupActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private val bg = Executors.newFixedThreadPool(2)
    @Volatile private var stop = false
    private var groups: List<Dups.Group> = emptyList()
    private lateinit var body: FrameLayout
    private lateinit var go: android.widget.Button
    private lateinit var summary: TextView
    private val ns = Photos.TEAM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("중복 정리", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } },
            ui.iconBtn(Glyph("sort", ui.text), "남길 것 고르는 규칙") { editRules() })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        body = FrameLayout(this).apply { setBackgroundColor(ui.bg) }
        summary = ui.label("", 13.5f, ui.sub)
        go = ui.button("지울 것 없음") { confirmDelete() }
        val bottom = ui.col(summary, go.apply { layoutParams = ui.lp(h = ui.dp(50), top = 8) }).apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)); setBackgroundColor(ui.panel); visibility = View.GONE; tag = "bottom" }
        val root = ui.col(top, body.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, bottom)
        ui.insets(root)
        setContentView(root)
        intro()
    }
    override fun onDestroy() { super.onDestroy(); stop = true; bg.shutdownNow() }

    private fun bottomBar(): View = (window.decorView as ViewGroup).findViewWithTag("bottom")

    private fun intro() {
        bottomBar().visibility = View.GONE
        val small = CheckBox(this).apply { text = "작게 줄인 사본도 찾기 (찍은 시각이 같고 해상도만 작은 것)"; setTextColor(ui.text); isChecked = pf.bool("dup_small", true)
            setOnCheckedChangeListener { _, b -> pf.putBool("dup_small", b) } }
        val rules = Dups.rules(this).filter { it.on }.mapIndexed { i, r -> "${i + 1}. " + Dups.ruleNames[r.key] + (if (r.key == "folder") " (" + Dups.preferred(this).joinToString(" > ").ifEmpty { "아직 안 정함" } + ")" else "") }
        val col = ui.col(
            ui.label("시놀로지 공유 공간 전체에서 중복을 찾습니다", 16f, ui.text, bold = true),
            ui.label("• 정확한 중복: 크기·찍은 시각이 같고 파일 앞뒤 내용까지 같은 것\n• 줄인 사본: 같은 사진을 메신저 등으로 줄여 저장한 것\n찾기만 하고 바로 지우지 않습니다. 묶음마다 남길 것을 보여 드리고, 바꾼 뒤 [지우기]를 누를 때 지웁니다.", 13.5f, ui.sub).apply { layoutParams = ui.lp(top = 8) },
            small.apply { layoutParams = ui.lp(top = 14) },
            ui.label("남길 것 고르는 규칙 (위에서부터)", 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 18) },
            ui.label(rules.joinToString("\n"), 13.5f, ui.text).apply { layoutParams = ui.lp(top = 6) },
            ui.button("규칙 바꾸기", primary = false) { editRules() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 10) },
            ui.button("중복 찾기") { scan() }.apply { layoutParams = ui.lp(h = ui.dp(50), top = 20) },
            ui.label("사진이 많으면 몇 분 걸립니다 (목록 읽기 → 후보만 내용 비교)", 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 6); gravity = Gravity.CENTER },
        ).apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(24)) }
        body.removeAllViews(); body.addView(ScrollView(this).apply { addView(col) })
    }

    private fun scan() {
        stop = false
        val stage = ui.label("시작하는 중…", 15f, ui.text)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val col = ui.col(stage, bar.apply { layoutParams = ui.lp(top = 12) }, ui.button("멈추기", primary = false) { stop = true }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 16) },
            ui.label("처음 한 번은 오래 걸립니다. 비교한 사진은 기억해 두므로, 중간에 멈춰도 다음에는 남은 것만 비교합니다.", 13f, ui.sub).apply { layoutParams = ui.lp(top = 16) })
            .apply { setPadding(ui.dp(24), ui.dp(40), ui.dp(24), 0) }
        body.removeAllViews(); body.addView(col)
        var last = 0L
        var stName = ""; var stAt = 0L; var stDone = 0
        bg.execute {
            val r = try { Result.success(Dups.scan(this, pf.bool("dup_small", true), { stop }) { p ->
                val now = System.currentTimeMillis()
                if (p.stage != stName) { stName = p.stage; stAt = now; stDone = p.done }
                if (now - last < 250 && p.done < p.total) return@scan; last = now
                // 남은 시간: 이 단계에서 지금까지의 빠르기로 어림 (10초 지나서부터)
                val el = now - stAt; val dn = p.done - stDone
                val eta = if (el > 10_000 && dn > 0 && p.total > p.done) ((p.total - p.done).toLong() * el / dn / 1000) else -1L
                val etaText = when { eta < 0 -> ""; eta < 60 -> "\n남은 시간 1분 안쪽"; else -> "\n남은 시간 약 ${(eta + 59) / 60}분" }
                runOnUiThread { stage.text = "${p.stage}  ${p.done} / ${p.total}$etaText"; bar.progress = if (p.total > 0) (p.done * 1000L / p.total).toInt() else 0 }
            }) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onFailure { e -> Toast.makeText(this, "찾지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show(); intro() }
                r.onSuccess { g -> if (stop) { intro(); return@onSuccess }; groups = g; results() }
            }
        }
    }

    // ---------- 결과 ----------
    private val list by lazy { ListView(this).apply { divider = null; setBackgroundColor(ui.bg) } }
    private fun results() {
        body.removeAllViews()
        if (groups.isEmpty()) { body.addView(ui.col(ui.label("중복을 찾지 못했습니다", 16f, ui.text), ui.button("처음으로", primary = false) { intro() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 16) }).apply { setPadding(ui.dp(24), ui.dp(40), ui.dp(24), 0) }); return }
        body.addView(list)
        list.adapter = adapter
        bottomBar().visibility = View.VISIBLE
        update()
    }

    private fun update() {
        val drop = groups.flatMap { it.drop }
        val bytes = drop.sumOf { it.it.size }
        summary.text = "${groups.size}묶음 · 정확한 중복 ${groups.count { it.kind == "same" }} · 줄인 사본 ${groups.count { it.kind == "small" }}  — 사진을 누르면 남김/지움이 바뀜"
        go.text = if (drop.isEmpty()) "지울 것 없음" else "${drop.size}개 지우기 (${Fmt.size(bytes)})"
        go.isEnabled = drop.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = groups.size
        override fun getItem(p: Int) = groups[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val g = groups[p]
            val row = ui.row().apply { setPadding(ui.dp(10), 0, ui.dp(10), 0) }
            for (e in g.entries) row.addView(card(g, e))
            return ui.col(
                ui.label((if (g.kind == "same") "정확한 중복" else "줄인 사본") + " · ${g.entries.size}장 · 지울 것 ${Fmt.size(g.drop.sumOf { it.it.size })}", 13f, ui.accent, bold = true).apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(6)) },
                HorizontalScrollView(this@DupActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            )
        }
    }

    private fun card(g: Dups.Group, e: Dups.Entry): View {
        val keep = e.it.id in g.keep
        val w = ui.dp(132)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.panel2) }
        Thumbs.load(iv, ns, e.it.unitId, e.it.cacheKey, "sm")
        val badge = ui.label(if (keep) "남김" else "지움", 12f, if (keep) ui.onAccent else 0xFFFFFFFF.toInt(), bold = true).apply {
            background = ui.round(if (keep) ui.accent else ui.red, 10); setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2)) }
        val pic = FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            if (!keep) addView(View(this@DupActivity).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
        }
        return ui.col(
            pic.apply { layoutParams = LinearLayout.LayoutParams(w, w) },
            ui.label(e.path.trimStart('/'), 11.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START; layoutParams = LinearLayout.LayoutParams(w, -2).apply { topMargin = ui.dp(4) } },
            ui.label(e.it.name, 11f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; layoutParams = LinearLayout.LayoutParams(w, -2) },
            ui.label(Fmt.size(e.it.size) + (if (e.it.w > 0) " · ${e.it.w}×${e.it.h}" else ""), 11f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(w, -2) },
        ).apply {
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)); background = ui.press(null)
            setOnClickListener {
                if (keep) { if (g.keep.size > 1) g.keep -= e.it.id else Toast.makeText(this@DupActivity, "묶음마다 하나는 남겨야 합니다 — 남길 다른 사진을 먼저 누르세요", Toast.LENGTH_SHORT).show() }
                else g.keep += e.it.id
                update()
            }
            setOnLongClickListener { ViewerActivity.open(this@DupActivity, g.entries.map { it.it }, g.entries.indexOf(e), ns); true }
        }
    }

    private fun confirmDelete() {
        val drop = groups.flatMap { it.drop }
        if (drop.isEmpty()) return
        AlertDialog.Builder(this).setTitle("${drop.size}개를 지울까요?")
            .setMessage("시놀로지에서 지웁니다 (${Fmt.size(drop.sumOf { it.it.size })}). 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다.\n\n묶음마다 '남김' 표시한 사진은 그대로 둡니다.")
            .setPositiveButton("지우기") { _, _ ->
                go.isEnabled = false; go.text = "지우는 중…"
                bg.execute {
                    val ph = Photos(this)
                    var done = 0; var err: String? = null
                    for (c in drop.chunked(100)) { try { ph.delete(ns, c.map { it.it.id }); done += c.size } catch (e: Exception) { err = e.message; break } }
                    runOnUiThread {
                        val gone = drop.take(done).map { it.it.id }.toSet()
                        groups = groups.map { g -> Dups.Group(g.kind, g.entries.filter { it.it.id !in gone }).also { n -> n.keep += g.keep } }.filter { it.entries.size > 1 }
                        Toast.makeText(this, if (err == null) "${done}개를 지웠습니다" else "${done}개 지우고 멈춤: $err", Toast.LENGTH_LONG).show()
                        results()
                    }
                }
            }.setNegativeButton("그만", null).show()
    }

    // ---------- 규칙 ----------
    private fun editRules() {
        val rs = Dups.rules(this).toMutableList()
        val box = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        fun draw() {
            box.removeAllViews()
            box.addView(ui.label("위에 있을수록 먼저 봅니다. 켠 규칙만 씁니다.", 12.5f, ui.sub))
            rs.forEachIndexed { i, r ->
                val cb = CheckBox(this).apply { text = Dups.ruleNames[r.key]; setTextColor(ui.text); isChecked = r.on; setOnCheckedChangeListener { _, b ->
                    rs[i] = r.copy(on = b); if (b && r.key == "old") rs.indexOfFirst { it.key == "new" }.let { k -> rs[k] = rs[k].copy(on = false) }
                    if (b && r.key == "new") rs.indexOfFirst { it.key == "old" }.let { k -> rs[k] = rs[k].copy(on = false) }; draw() } }
                box.addView(ui.row(cb.apply { layoutParams = ui.lp(0, weight = 1f) },
                    ui.iconBtn(Glyph("up", ui.text), "위로", 36) { if (i > 0) { rs.add(i - 1, rs.removeAt(i)); draw() } },
                    ui.iconBtn(Glyph("down", ui.text), "아래로", 36) { if (i < rs.size - 1) { rs.add(i + 1, rs.removeAt(i)); draw() } }))
            }
            box.addView(ui.button("선호 폴더 순서 정하기", primary = false) { editPreferred() }.apply { layoutParams = ui.lp(h = ui.dp(42), top = 10) })
        }
        draw()
        AlertDialog.Builder(this).setTitle("남길 것 고르는 규칙").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> Dups.saveRules(this, rs); if (groups.isNotEmpty()) { Dups.decide(this, groups); update() } else intro() }
            .setNegativeButton("그만", null).show()
    }

    private fun editPreferred() {
        bg.execute {
            val tops = try { Photos(this).folders(ns, 1).map { it.path } } catch (e: Exception) { emptyList() }
            runOnUiThread {
                val order = (Dups.preferred(this) + tops).distinct().toMutableList()
                val on = Dups.preferred(this).toMutableSet()
                val box = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
                fun draw() {
                    box.removeAllViews()
                    box.addView(ui.label("체크한 폴더가 위에서부터 우선. 중복이 여러 폴더에 있으면 위 폴더의 것을 남김", 12.5f, ui.sub))
                    order.forEachIndexed { i, f ->
                        val cb = CheckBox(this).apply { text = f.trimStart('/'); setTextColor(ui.text); isChecked = f in on; setOnCheckedChangeListener { _, b -> if (b) on += f else on -= f } }
                        box.addView(ui.row(cb.apply { layoutParams = ui.lp(0, weight = 1f) },
                            ui.iconBtn(Glyph("up", ui.text), "위로", 36) { if (i > 0) { order.add(i - 1, order.removeAt(i)); draw() } }))
                    }
                }
                draw()
                AlertDialog.Builder(this).setTitle("선호 폴더").setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton("확인") { _, _ -> Dups.savePreferred(this, order.filter { it in on }); if (groups.isNotEmpty()) { Dups.decide(this, groups); update() } }
                    .setNegativeButton("그만", null).show()
            }
        }
    }
}
