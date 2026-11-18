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
 * 지우기는 세 가지: 모두 한꺼번에 · 폴더별로(고른 폴더에 있는 '지움'만) · 묶음 하나씩(묶음 머리의 [이 묶음 지우기]).
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
    private lateinit var byFolder: android.widget.Button
    private lateinit var summary: TextView
    private val ns = Photos.TEAM
    private var cleaned = false   // 이번에 지운 것이 있음 (다 지우면 '모두 정리했습니다')
    private var showing = false   // 결과 화면인가 (뒤로 가기 → 처음 화면)

    companion object {
        // 마지막으로 찾은 결과 — 화면을 닫았다 열어도 다시 찾지 않고 볼 수 있게 (앱이 살아 있는 동안)
        var lastGroups: List<Dups.Group>? = null
        var lastFoundAt = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("중복 정리", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } },
            ui.iconBtn(Glyph("folder", ui.text), "위치 순위 (중복이 있는 위치를 나열해 순서 정하기)") { editPreferred() },
            ui.iconBtn(Glyph("sort", ui.text), "남길 것 고르는 규칙") { editRules() })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        body = FrameLayout(this).apply { setBackgroundColor(ui.bg) }
        summary = ui.label("", 13.5f, ui.sub)
        go = ui.button("지울 것 없음") { confirmDelete() }
        byFolder = ui.button("폴더별로", primary = false) { pickFolders() }
        val bottom = ui.col(summary, ui.row(byFolder.apply { layoutParams = ui.lp(0, h = ui.dp(50), weight = 0.38f).apply { rightMargin = ui.dp(8) } },
            go.apply { layoutParams = ui.lp(0, h = ui.dp(50), weight = 0.62f) }).apply { layoutParams = ui.lp(top = 8) }).apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)); setBackgroundColor(ui.panel); visibility = View.GONE; tag = "bottom" }
        val root = ui.col(top, body.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, bottom)
        ui.insets(root)
        setContentView(root)
        intro()
    }

    override fun onDestroy() { super.onDestroy(); stop = true; bg.shutdownNow() }
    @Deprecated("") override fun onBackPressed() { if (showing) intro() else super.onBackPressed() }

    private fun bottomBar(): View = (window.decorView as ViewGroup).findViewWithTag("bottom")

    private fun intro() {
        showing = false
        bottomBar().visibility = View.GONE
        val small = CheckBox(this).apply { text = "작게 줄인 사본도 찾기 (찍은 시각과 그림이 같고 해상도만 작은 것)"; setTextColor(ui.text); isChecked = pf.bool("dup_small", true)
            setOnCheckedChangeListener { _, b -> pf.putBool("dup_small", b) } }
        val rules = Dups.rules(this).filter { it.on }.mapIndexed { i, r -> "${i + 1}. " + Dups.ruleNames[r.key] + (if (r.key == "folder") " (" + Dups.preferred(this).filter { it != Dups.REST }.take(3).joinToString(" > ") { it.trimStart('/') }.ifEmpty { "아직 안 정함" } + (if (Dups.preferred(this).count { it != Dups.REST } > 3) " …" else "") + ")" else "") }
        val col = ui.col(
            ui.label("시놀로지 공유 공간에서 중복을 찾습니다", 16f, ui.text, bold = true),
            scopeBox(),
            ui.label("• 정확한 중복: 크기·찍은 시각이 같고 파일 내용(앞·가운데·뒤 일부)까지 같은 것\n• 줄인 사본: 같은 사진을 메신저 등으로 줄여 저장한 것 (작은 미리보기를 견줘 그림이 같은 것만)\n찾기만 하고 바로 지우지 않습니다. 묶음마다 남길 것을 보여 드리고, 바꾼 뒤 [지우기]를 누를 때 지웁니다.", 13.5f, ui.sub).apply { layoutParams = ui.lp(top = 8) },
            small.apply { layoutParams = ui.lp(top = 14) },
            ui.label("남길 것 고르는 규칙 (위에서부터)", 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 18) },
            ui.label(rules.joinToString("\n"), 13.5f, ui.text).apply { layoutParams = ui.lp(top = 6) },
            ui.button("규칙 바꾸기", primary = false) { editRules() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 10) },
            ui.button("위치 순위 정하기", primary = false) { editPreferred() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 8) },
            ui.button("중복 찾기") { scan() }.apply { layoutParams = ui.lp(h = ui.dp(50), top = 20) },
            ui.label("처음에는 사진이 많으면 10분 넘게 걸립니다. 비교한 것은 기억해 두므로 다음부터는 새로 들어온 것만 비교합니다.", 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 6); gravity = Gravity.CENTER },
        ).apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(24)) }
        lastGroups?.takeIf { it.isNotEmpty() }?.let { l ->
            val t = java.text.SimpleDateFormat("H:mm", java.util.Locale.KOREA).format(java.util.Date(lastFoundAt))
            col.addView(ui.button("지난 결과 다시 보기 (${l.size}묶음 · $t 에 찾음)", primary = false) { groups = l; results() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 12) }, col.childCount)
        }
        body.removeAllViews(); body.addView(ScrollView(this).apply { addView(col) })
    }

    // ---------- 찾을 범위: 서버 전체 · 고른 폴더 ----------
    private fun scopeBox(): View {
        val box = ui.col().apply { layoutParams = ui.lp(top = 12); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.round(ui.panel, 12) }
        val folders = pf.str("dup_folders", "").split('\n').filter { it.isNotEmpty() }
        val onFolders = pf.bool("dup_scope_folders", false) && folders.isNotEmpty()
        box.addView(ui.label("찾을 범위", 13f, ui.accent, bold = true))
        val seg = ui.row().apply { layoutParams = ui.lp(top = 8) }
        listOf(false to "서버 전체", true to "고른 폴더").forEach { (v, n) ->
            seg.addView(ui.label(n, 14f, if (v == onFolders) ui.onAccent else ui.text).apply {
                gravity = Gravity.CENTER; background = ui.press(ui.round(if (v == onFolders) ui.accent else ui.panel2, 17), 17)
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply { if (!v) rightMargin = ui.dp(6) }
                setOnClickListener { if (v && folders.isEmpty()) pickServerFolder() else { pf.putBool("dup_scope_folders", v); intro() } }
            })
        }
        box.addView(seg)
        if (onFolders) {
            folders.forEach { f ->
                box.addView(ui.row(ImageView(this).apply { setImageDrawable(Glyph("folder", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(8) } },
                    ui.label(f.trimStart('/') + " (하위 폴더까지)", 13.5f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) },
                    ui.label("빼기", 13f, ui.red).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener {
                        val rest = folders - f; pf.putStr("dup_folders", rest.joinToString("\n")); if (rest.isEmpty()) pf.putBool("dup_scope_folders", false); intro() } }
                ).apply { layoutParams = ui.lp(top = 8); gravity = Gravity.CENTER_VERTICAL })
            }
            box.addView(ui.label("+ 폴더 더하기", 13.5f, ui.accent, bold = true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)); setOnClickListener { pickServerFolder() } })
            box.addView(CheckBox(this).apply {
                text = "고른 폴더의 사진과 같은 것이 서버 다른 곳에 있어도 찾기 (서버 전체를 읽어서 비교 — 더 오래 걸림)"; setTextColor(ui.text); textSize = 13f
                isChecked = pf.bool("dup_outside", false); setOnCheckedChangeListener { _, b -> pf.putBool("dup_outside", b) }; layoutParams = ui.lp(top = 4) })
        }
        return box
    }

    /** 서버 폴더 고르기: 눌러서 들어가고 [이 폴더 고르기] */
    private fun pickServerFolder() {
        var cur = 1L to "/"
        val stack = ArrayList<Pair<Long, String>>()
        val list = ListView(this)
        val head = ui.label("", 13.5f, ui.sub).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
        lateinit var dlg: AlertDialog
        var subs: List<Folder> = emptyList()
        fun load() {
            head.text = "지금: " + (if (cur.second == "/") "맨 위" else cur.second.trimStart('/')) + "  (누르면 들어감)"
            list.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("불러오는 중…"))
            bg.execute {
                val r = try { Photos(this).folders(ns, cur.first) } catch (e: Exception) { emptyList() }
                runOnUiThread { subs = r; list.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    (if (stack.isNotEmpty()) listOf("⬑  위 폴더로") else emptyList()) + r.map { "📁  " + it.name.substringAfterLast('/') }.ifEmpty { listOf("(하위 폴더 없음)") }) }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            var p = pos
            if (stack.isNotEmpty()) { if (p == 0) { cur = stack.removeAt(stack.size - 1); load(); return@setOnItemClickListener }; p-- }
            val f = subs.getOrNull(p) ?: return@setOnItemClickListener
            stack += cur; cur = f.id to f.path; load()
        }
        dlg = AlertDialog.Builder(this).setTitle("중복을 찾을 폴더").setView(ui.col(head, list.apply { layoutParams = ui.lp(h = ui.dp(380)) }))
            .setPositiveButton("이 폴더 고르기") { _, _ ->
                if (cur.second == "/") { Toast.makeText(this, "폴더에 들어간 뒤 고르세요 (맨 위는 서버 전체와 같음)", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val l = pf.str("dup_folders", "").split('\n').filter { it.isNotEmpty() }.toMutableList()
                if (cur.second !in l) l += cur.second
                pf.putStr("dup_folders", l.joinToString("\n")); pf.putBool("dup_scope_folders", true); intro()
            }.setNegativeButton("그만", null).create()
        dlg.show(); load()
    }

    private fun scan() {
        stop = false; cleaned = false
        val stage = ui.label("시작하는 중…", 15f, ui.text)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val col = ui.col(stage, bar.apply { layoutParams = ui.lp(top = 12) }, ui.button("멈추기", primary = false) { stop = true }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 16) },
            ui.label("처음 한 번은 오래 걸립니다. 비교한 사진은 기억해 두므로, 중간에 멈춰도 다음에는 남은 것만 비교합니다.", 13f, ui.sub).apply { layoutParams = ui.lp(top = 16) })
            .apply { setPadding(ui.dp(24), ui.dp(40), ui.dp(24), 0) }
        body.removeAllViews(); body.addView(col)
        var last = 0L
        var stName = ""; var stAt = 0L
        val win = ArrayDeque<Pair<Long, Int>>()   // 최근 30초의 (시각, 한 개수) — 기억해 둔 것을 순식간에 넘긴 부분이 어림을 흐리지 않게
        bg.execute {
            val r = try { Result.success(Dups.scan(this, pf.bool("dup_small", true), { stop }) { p ->
                synchronized(win) {   // 여러 비교 스레드가 함께 부름
                    val now = System.currentTimeMillis()
                    if (p.stage != stName) { stName = p.stage; stAt = now; win.clear() }
                    if (now - last < 250 && p.done < p.total) return@scan; last = now
                    // 남은 시간: 최근 30초의 빠르기로 어림 (단계 시작 10초 뒤부터)
                    win.addLast(now to p.done); while (win.size > 2 && now - win.first().first > 30_000) win.removeFirst()
                    val el = now - win.first().first; val dn = p.done - win.first().second
                    val eta = if (now - stAt > 10_000 && el > 5_000 && dn > 0 && p.total > p.done) ((p.total - p.done).toLong() * el / dn / 1000) else -1L
                    val etaText = when { eta < 0 -> ""; eta < 60 -> "\n남은 시간 1분 안쪽"; else -> "\n남은 시간 약 ${(eta + 59) / 60}분" }
                    runOnUiThread { stage.text = "${p.stage}  ${p.done} / ${p.total}$etaText"; bar.progress = if (p.total > 0) (p.done * 1000L / p.total).toInt() else 0 }
                }
            }) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onFailure { e -> Toast.makeText(this, "찾지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show(); intro() }
                r.onSuccess { g -> if (stop) { intro(); return@onSuccess }; groups = g; lastGroups = g; lastFoundAt = System.currentTimeMillis(); results() }
            }
        }
    }

    // ---------- 결과 ----------
    private val list by lazy { ListView(this).apply { divider = null; setBackgroundColor(ui.bg) } }
    private fun results() {
        showing = true; lastGroups = groups
        body.removeAllViews()
        if (groups.isEmpty()) { bottomBar().visibility = View.GONE
            body.addView(ui.col(ui.label(if (cleaned) "중복을 모두 정리했습니다" else "중복을 찾지 못했습니다", 16f, ui.text), ui.button("처음으로", primary = false) { intro() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 16) }).apply { setPadding(ui.dp(24), ui.dp(40), ui.dp(24), 0) }); return }
        body.addView(list)
        list.adapter = adapter
        bottomBar().visibility = View.VISIBLE
        update()
    }

    private fun update() {
        val drop = groups.flatMap { it.drop }
        val bytes = drop.sumOf { it.it.size }
        summary.text = "${groups.size}묶음 · 정확한 중복 ${groups.count { it.kind == "same" }} · 줄인 사본 ${groups.count { it.kind == "small" }}  — 지울 것: ${StatusBadge.kinds(drop.count { !it.it.video }, drop.count { it.it.video })}  — 누르면 남김/지움이 바뀜"
        go.text = if (drop.isEmpty()) "지울 것 없음" else "모두 ${drop.size}개 지우기 (${Fmt.size(bytes)})"
        go.isEnabled = drop.isNotEmpty(); byFolder.isEnabled = drop.isNotEmpty()
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
            // 묶음 머리: 종류·장수 + [이 묶음 지우기] (하나씩 확인하며 지울 때)
            val one = ui.label("이 묶음 지우기", 13f, ui.red, bold = true).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.press(null, 8)
                contentDescription = "이 묶음에서 '지움' 표시한 것만 지우기"
                setOnClickListener { deleteEntries(g.drop, "이 묶음에서 ${g.drop.size}개를 지울까요?", "이 묶음의 '남김' 사진은 그대로 둡니다.") }
            }
            return ui.col(
                ui.row(ui.label((if (g.kind == "same") "정확한 중복" else "줄인 사본") + (if (g.entries.any { it.it.video }) " · 동영상" else "") + " · ${g.entries.size}개 · 지울 것 ${Fmt.size(g.drop.sumOf { it.it.size })}", 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) },
                    one).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(4), ui.dp(2)) },
                HorizontalScrollView(this@DupActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            )
        }
    }

    private fun card(g: Dups.Group, e: Dups.Entry): View {
        val keep = e.it.id in g.keep
        // 칸 크기: 화면 폭을 묶음의 장수(최대 4)로 나눠 크게 — 좁은 휴대폰에서는 최소 160dp 로 옆으로 밀어 보기, 태블릿에서는 화면을 채움
        val screen = resources.displayMetrics.widthPixels - ui.dp(20)
        val n = minOf(maxOf(g.entries.size, 2), 4)
        val w = (screen / n - ui.dp(8)).coerceIn(ui.dp(160), ui.dp(420))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.panel2) }
        Thumbs.load(iv, ns, e.it.unitId, e.it.cacheKey, if (w > ui.dp(240)) "m" else "sm")
        val badge = ui.label(if (keep) "남김" else "지움", 12f, if (keep) ui.onAccent else 0xFFFFFFFF.toInt(), bold = true).apply {
            background = ui.round(if (keep) ui.accent else ui.red, 10); setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2)) }
        val pic = FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            if (!keep) addView(View(this@DupActivity).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
            if (e.it.video) addView(ui.row(StatusBadge.play(this@DupActivity, ui).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)) },
                ui.label(if (e.it.dur > 0) PhotoGrid.duration(e.it.dur) else "동영상", 11.5f, 0xFFFFFFFF.toInt(), bold = true).apply { setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()); setPadding(ui.dp(4), 0, 0, 0) }),
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(6), 0, 0, ui.dp(6)) })   // 동영상
        }
        return ui.col(
            pic.apply { layoutParams = LinearLayout.LayoutParams(w, w) },
            // 폴더 경로는 두 줄까지 (넘치면 앞을 줄임) — 어느 폴더인지 잘 보이게
            ui.label(e.path.trimStart('/'), 13f, ui.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.START; layoutParams = LinearLayout.LayoutParams(w, -2).apply { topMargin = ui.dp(6) } },
            ui.label(e.it.name, 12.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; layoutParams = LinearLayout.LayoutParams(w, -2).apply { topMargin = ui.dp(2) } },
            ui.label(Fmt.size(e.it.size) + (if (e.it.w > 0) " · ${e.it.w}×${e.it.h}" else ""), 12f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(w, -2) },
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
        deleteEntries(drop, "${drop.size}개를 지울까요?", "묶음마다 '남김' 표시한 사진은 그대로 둡니다.")
    }

    /** 폴더별로 지우기: '지움'이 들어 있는 폴더를 나열 → 고른 폴더에 있는 '지움'만 지움 */
    private fun pickFolders() {
        val drop = groups.flatMap { it.drop }
        if (drop.isEmpty()) return
        val byDir = drop.groupBy { it.path.ifEmpty { "/" } }.entries.sortedWith(compareByDescending<Map.Entry<String, List<Dups.Entry>>> { it.value.size }.thenBy { it.key })
        val names = byDir.map { (d, l) -> d.trimStart('/').ifEmpty { "(맨 위)" } + "\n" + "${l.size}개 · ${Fmt.size(l.sumOf { it.it.size })}" }.toTypedArray()
        val on = BooleanArray(names.size)
        var dlg: AlertDialog? = null
        fun refresh() {
            val n = byDir.indices.filter { on[it] }.sumOf { byDir[it].value.size }
            dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { isEnabled = n > 0; text = if (n > 0) "${n}개 지우기" else "폴더를 고르세요" }
        }
        dlg = AlertDialog.Builder(this).setTitle("어느 폴더에서 지울까요?")
            .setMultiChoiceItems(names, on) { _, w, c -> on[w] = c; refresh() }
            .setPositiveButton("지우기") { _, _ ->
                val pick = byDir.indices.filter { on[it] }
                val sel = pick.flatMap { byDir[it].value }
                deleteEntries(sel, "고른 폴더 ${pick.size}곳에서 ${sel.size}개를 지울까요?",
                    "고른 폴더에 있는 '지움' 표시 사진만 지웁니다:\n" + pick.take(5).joinToString("\n") { "• " + byDir[it].key.trimStart('/') } + (if (pick.size > 5) "\n… 외 ${pick.size - 5}곳" else "") +
                        "\n\n같은 묶음의 '남김' 사진과 다른 폴더의 사진은 그대로 둡니다.")
            }.setNegativeButton("그만", null).create()
        dlg.setOnShowListener { refresh() }
        dlg.show()
    }

    /** 고른 '지움' 사진들을 시놀로지에서 지움 (확인 창을 거침) — 모두 · 폴더별 · 묶음 하나 공통 */
    private fun deleteEntries(drop: List<Dups.Entry>, title: String, note: String) {
        if (drop.isEmpty()) { Toast.makeText(this, "지울 것이 없습니다", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("시놀로지에서 지웁니다 (${Fmt.size(drop.sumOf { it.it.size })}). 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다.\n\n$note")
            .setPositiveButton("지우기") { _, _ -> runDelete(drop) }
.setNegativeButton("그만", null).show()
    }

    /** 실제로 지우기 — 진행 막대와 자세한 상황(몇 개·크기·지금 지우는 폴더/파일·남은 시간)을 창에 보여 줌. [멈추기] 가능 */
    private fun runDelete(drop: List<Dups.Entry>) {
        go.isEnabled = false; byFolder.isEnabled = false; go.text = "지우는 중…"
        val total = drop.size; val totalBytes = drop.sumOf { it.it.size }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = total; progress = 0 }
        val count = ui.label("0 / ${total}개", 22f, ui.text, bold = true)
        val pct = ui.label("0%", 15f, ui.accent, bold = true)
        val size = ui.label("0 B / ${Fmt.size(totalBytes)}", 13.5f, ui.sub)
        val eta = ui.label("남은 시간 계산 중…", 13.5f, ui.sub)
        val nowDir = ui.label("", 13.5f, ui.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.START }
        val nowFile = ui.label("", 12.5f, ui.sub).apply { maxLines = 3 }
        val fail = ui.label("", 13f, ui.red)
        val box = ui.col(
            ui.row(count.apply { layoutParams = ui.lp(0, weight = 1f) }, pct),
            bar.apply { layoutParams = ui.lp(h = ui.dp(10), top = 8) },
            ui.row(size.apply { layoutParams = ui.lp(0, weight = 1f) }, eta).apply { layoutParams = ui.lp(top = 6) },
            ui.label("지금 지우는 곳", 12f, ui.sub, bold = true).apply { layoutParams = ui.lp(top = 16) },
            nowDir.apply { layoutParams = ui.lp(top = 2) }, nowFile.apply { layoutParams = ui.lp(top = 2) }, fail.apply { layoutParams = ui.lp(top = 8) },
        ).apply { setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0) }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this).setTitle("지우는 중").setView(box).setCancelable(false)
            .setNegativeButton("멈추기", null).create()
        dlg.setOnShowListener { dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cancel.set(true); it.isEnabled = false; (it as? android.widget.Button)?.text = "이번 묶음까지 지우고 멈춤…" } }
        dlg.show()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 오래 걸려도 화면이 꺼져 멈추지 않게
        val t0 = android.os.SystemClock.elapsedRealtime()
        bg.execute {
            val ph = Photos(this)
            var done = 0; var bytes = 0L
            val gone = HashSet<Long>()                    // 지운 것
            val failed = LinkedHashMap<Long, String>()    // 못 지운 것 → 까닭
            fun why(e: Exception) = when (e) {
                is java.net.SocketTimeoutException -> "NAS 응답이 늦음"
                is java.io.IOException -> "연결 끊김"
                is SynoError -> "시놀로지 오류 ${e.code}"
                else -> e.message ?: e.javaClass.simpleName
            }
            /** 한 번 지우기 — 실패하면 2초 쉬고 한 번 더 */
            fun tryDelete(ids: List<Long>): Exception? {
                for (k in 0..1) {
                    try { ph.delete(ns, ids); return null } catch (e: Exception) { if (k == 1 || cancel.get() || stop) return e; Thread.sleep(2000) }
                }
                return null
            }
            // 한 번에 10개씩 — 수천 장도 한 요청이 오래 걸리지 않게 (100개씩은 NAS가 1분 안에 못 끝내 끊기곤 했음).
            // 묶음이 실패하면 하나씩 다시 해 보고, 그래도 안 되는 것만 '실패'로 두고 다음으로 넘어감 (멈추지 않음)
            for (c in drop.chunked(10)) {
                if (cancel.get() || stop) break
                runOnUiThread {
                    val dirs = c.map { it.path.trimStart('/').ifEmpty { "(맨 위)" } }.distinct()
                    nowDir.text = dirs.first() + (if (dirs.size > 1) "  외 ${dirs.size - 1}곳" else "")
                    nowFile.text = c.take(3).joinToString(", ") { it.it.name } + (if (c.size > 3) " … (${c.size}개)" else "")
                }
                val e = tryDelete(c.map { it.it.id })
                if (e == null) c.forEach { gone += it.it.id; bytes += it.it.size }
                else for (x in c) {   // 묶음이 안 되면 하나씩
                    if (cancel.get() || stop) break
                    val e1 = tryDelete(listOf(x.it.id))
                    if (e1 == null) { gone += x.it.id; bytes += x.it.size } else failed[x.it.id] = why(e1)
                }
                done = gone.size + failed.size
                val d = done; val b = bytes; val nf = failed.size; val ok = gone.size
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                runOnUiThread {
                    bar.progress = d; count.text = "$ok / ${total}개"; pct.text = "${d * 100 / total}%"
                    size.text = "${Fmt.size(b)} / ${Fmt.size(totalBytes)}"
                    val left = if (d > 0) ms * (total - d) / d / 1000 else -1
                    eta.text = when { left < 0 -> "남은 시간 계산 중…"; left < 60 -> "남은 시간 약 ${left}초"; else -> "남은 시간 약 ${left / 60}분 ${left % 60}초" }
                    if (nf > 0) fail.text = "못 지운 것 ${nf}개 (${failed.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key}) — 끝나고 다시 해 볼 수 있습니다"
                }
            }
            val err = if (failed.isEmpty()) null else failed.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) { }
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (gone.isNotEmpty()) cleaned = true
                val pos = list.firstVisiblePosition; val top = list.getChildAt(0)?.top ?: 0
                groups = groups.map { g -> Dups.Group(g.kind, g.entries.filter { it.it.id !in gone }).also { n -> n.keep += g.keep } }.filter { it.entries.size > 1 }
                val secs = ms / 1000
                val took = if (secs < 60) "${secs}초" else "${secs / 60}분 ${secs % 60}초"
                results()
                if (failed.isNotEmpty() || done < total)   // 못 지운 것이 있으면 창으로 알림 (목록에 그대로 남아 있어 다시 지울 수 있음)
                    AlertDialog.Builder(this).setTitle("${gone.size}개를 지웠습니다")
                        .setMessage((if (failed.isNotEmpty()) "못 지운 것 ${failed.size}개 — 가장 많은 까닭: $err\n" else "") +
                            (if (done < total) "멈춰서 지우지 않은 것 ${total - done}개\n" else "") +
                            "\n남은 것은 목록에 그대로 있으니 [지우기]로 다시 해 보세요. 계속 실패하면 NAS가 바쁜지(색인·백업 중) 확인해 주세요.\n(걸린 시간 $took)")
                        .setPositiveButton("확인", null).show()
                else Toast.makeText(this, "${gone.size}개를 지웠습니다 (${Fmt.size(bytes)} · $took)", Toast.LENGTH_LONG).show()
                if (groups.isNotEmpty()) list.setSelectionFromTop(pos.coerceAtMost(groups.size - 1), top)   // 보던 자리 그대로
            }
        }
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
            box.addView(ui.button("위치 순위 정하기 (중복이 있는 위치를 모두 나열)", primary = false) { editPreferred() }.apply { layoutParams = ui.lp(h = ui.dp(42), top = 10) })
        }
        draw()
        AlertDialog.Builder(this).setTitle("남길 것 고르는 규칙").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> Dups.saveRules(this, rs); if (groups.isNotEmpty()) { Dups.decide(this, groups); update() } else intro() }
            .setNegativeButton("그만", null).show()
    }

    /**
     * 위치 순위 정하기: 중복이 있는 위치(폴더)를 모두 나열하고 그 안에서 순서를 정함.
     * 위에 있을수록 그곳의 것을 남김. 폴더 단계(맨 위 · 2단계 · 3단계 · 끝까지)로 묶어 볼 수 있고,
     * 고치는 동안 위치마다 '남김 · 지움' 개수를 바로 다시 셈. '순위를 정하지 않은 위치' 줄도 옮길 수 있음.
     */
    private fun editPreferred() {
        bg.execute {
            // 찾은 중복이 없으면 맨 위 폴더들로
            val tops = if (groups.isEmpty()) try { Photos(this).folders(ns, 1).map { it.path } } catch (e: Exception) { emptyList() } else emptyList()
            runOnUiThread { showRanking(tops) }
        }
    }

    private fun showRanking(tops: List<String>) {
        val all = groups.flatMap { it.entries }
        var depth = pf.int("dup_depth", 0)   // 0 = 끝까지, 1·2·3 = 그 단계까지
        fun cut(path: String): String { if (depth <= 0) return path; val parts = path.trim('/').split('/'); return "/" + parts.take(depth).joinToString("/") }
        // 위치마다 중복 장수
        fun places(): Map<String, Int> = if (all.isNotEmpty()) all.groupingBy { cut(it.path) }.eachCount() else tops.associateWith { 0 }
        var counts = places()
        val saved = Dups.preferred(this)
        // 순서: 저장해 둔 순서 → 나머지 줄 → 중복 많은 곳부터
        var order: MutableList<String> = ArrayList()
        /**
         * 단계를 바꿀 때 지금 정한 순서를 이어 감: 맨 위에서 정한 순서대로 그 안의 하위 폴더들이 줄 섬
         * (하위 폴더끼리의 순서는 정해 둔 것이 있으면 그대로, 없으면 중복 많은 것부터). 위로 묶을 때는 하위 중 가장 앞선 자리로.
         * 하위 단계에서 따로 순서를 안 바꾸면 위 단계의 순서만으로 충분 — 아래까지 정할 필요 없음
         */
        fun regroup(cur: List<String>): MutableList<String> {
            val r = cur.indexOf(Dups.REST).let { if (it < 0) cur.size else it }
            val savedPos = Dups.preferred(this).withIndex().associate { it.value to it.index }
            fun anchor(k: String): Double {
                var best = -1; var len = -1   // 이 위치를 품는(더 얕은) 것 중 가장 자세한 것
                cur.forEachIndexed { i, p -> if (p != Dups.REST && (k == p || k.startsWith("$p/")) && p.length > len) { best = i; len = p.length } }
                if (best >= 0) return best.toDouble()
                val under = cur.withIndex().filter { (_, p) -> p != Dups.REST && p.startsWith("$k/") }.minOfOrNull { it.index }   // 이 위치 아래에 있던 것들
                return under?.toDouble() ?: (r + 0.5)   // 순서를 모르는 위치는 '정하지 않은 위치' 줄 바로 아래
            }
            val keys = counts.keys.sortedWith(compareBy<String>({ anchor(it) }, { savedPos[it] ?: Int.MAX_VALUE }, { -(counts[it] ?: 0) }))
            val out = ArrayList<String>()
            var restPut = false
            for (k in keys) { if (!restPut && anchor(k) > r) { out += Dups.REST; restPut = true }; out += k }
            if (!restPut) out += Dups.REST
            return out
        }
        // 처음 열 때: 저장해 둔 순서(어느 단계에서 정했든)를 지금 단계에 맞춰 이어 붙임
        order = regroup(saved.let { if (Dups.REST in it) it else it + Dups.REST })
        val box = ui.col().apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(14), 0) }
        // 고친 순서로 미리 셈: 위치마다 남김/지움
        fun preview(): Map<String, Pair<Int, Int>> {
            if (groups.isEmpty()) return emptyMap()
            val tmp = groups.map { g -> Dups.Group(g.kind, g.entries) }
            Dups.decide(this, tmp, order)
            val m = HashMap<String, IntArray>()
            for (g in tmp) for (e in g.entries) { val k = cut(e.path); val a = m.getOrPut(k) { IntArray(2) }; if (e.it.id in g.keep) a[0]++ else a[1]++ }
            return m.mapValues { it.value[0] to it.value[1] }
        }
        fun draw() {
            box.removeAllViews()
            box.addView(ui.label(if (groups.isEmpty()) "맨 위 폴더들입니다. 중복을 찾은 뒤 열면 중복이 있는 위치만 모두 나옵니다." else
                "중복이 있는 위치 ${counts.size}곳. 위에 있을수록 그곳의 것을 남깁니다.\n맨 위 단계에서 정하면 그 안의 하위 폴더 모두에 적용됩니다. 하위 폴더끼리 순서가 필요할 때만 2·3단계로 들어가 고치세요 (위 단계 순서는 그대로 이어집니다).", 12.5f, ui.sub))
            // 묶어 보기 단계
            val seg = ui.row().apply { layoutParams = ui.lp(top = 8) }
            listOf(1 to "맨 위", 2 to "2단계", 3 to "3단계", 0 to "끝까지").forEach { (d, n) ->
                seg.addView(ui.label(n, 12.5f, if (d == depth) ui.onAccent else ui.text).apply {
                    gravity = Gravity.CENTER; background = ui.press(ui.round(if (d == depth) ui.accent else ui.panel2, 14), 14)
                    layoutParams = LinearLayout.LayoutParams(0, ui.dp(30), 1f).apply { rightMargin = ui.dp(4) }
                    setOnClickListener { if (depth != d) { depth = d; pf.putInt("dup_depth", d); counts = places(); order = regroup(order); draw() } }
                })
            }
            if (groups.isNotEmpty()) box.addView(seg)   // 묶어 보기는 찾은 중복이 있을 때만
            val pv = preview()
            order.forEachIndexed { i, f ->
                val isRest = f == Dups.REST
                val title = if (isRest) "— 순위를 정하지 않은 위치 (새로 생긴 위치도 여기) —" else f.trimStart('/').ifEmpty { "/" }
                val sub = when {
                    isRest -> "이 줄보다 아래 위치는 정하지 않은 위치보다도 나중 (먼저 지움)"
                    pv[f] != null -> "중복 ${counts[f] ?: 0}장 · 남김 ${pv[f]!!.first} · 지움 ${pv[f]!!.second}"
                    (counts[f] ?: 0) > 0 -> "중복 ${counts[f]}장"
                    else -> ""
                }
                val rank = ui.label(if (isRest) "" else "${order.take(i + 1).count { it != Dups.REST }}", 13f, ui.accent, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(26), -2); gravity = Gravity.CENTER }
                val text = ui.col(ui.label(title, 13.5f, if (isRest) ui.sub else ui.text).apply { if (!isRest) { maxLines = 2; ellipsize = TextUtils.TruncateAt.START } },
                    ui.label(sub, 11.5f, if (pv[f]?.second ?: 0 > 0) ui.red else ui.sub).apply { visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }).apply { layoutParams = ui.lp(0, weight = 1f) }
                fun move(to: Int) { val t = to.coerceIn(0, order.size - 1); if (t != i) { order.add(t, order.removeAt(i)); draw() } }
                box.addView(ui.row(rank, text,
                    ui.label("맨 위", 12f, if (i == 0) ui.sub else ui.accent, bold = true).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(6), ui.dp(8)); background = ui.press(null, 8); setOnClickListener { move(0) } },
                    ui.iconBtn(Glyph("up", ui.text), "위로", 34) { move(i - 1) },
                    ui.iconBtn(Glyph("down", ui.text), "아래로", 34) { move(i + 1) }
                ).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)); gravity = Gravity.CENTER_VERTICAL; if (isRest) setBackgroundColor(ui.panel2) })
            }
        }
        draw()
        AlertDialog.Builder(this).setTitle("위치 순위").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                Dups.savePreferred(this, order)
                // 순위를 정했으면 규칙에서 위치 순위를 켬
                val rs = Dups.rules(this).map { if (it.key == "folder") it.copy(on = true) else it }; Dups.saveRules(this, rs)
                if (groups.isNotEmpty()) { Dups.decide(this, groups); update() } else intro()
            }
            .setNegativeButton("그만", null).show()
    }
}
