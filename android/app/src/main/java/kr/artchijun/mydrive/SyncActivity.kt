package kr.artchijun.mydrive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 폴더 동기화 화면: 짝 목록 (휴대폰 폴더 ⇄ NAS 폴더 · 방향 · 언제 · 지난 결과) + [지금 동기화] [편집],
 *   위 [+] 로 새 짝, 맨 위에 'NAS 에서 바뀐 것도 바로 받기' 스위치.
 * 다른 화면에서 열 때 local / root·rpath 를 넘기면 그 폴더로 채운 새 짝 편집 창을 바로 엶.
 */
class SyncActivity : Activity() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(Ui.wrap(base)) }

    companion object {
        fun open(a: Activity, local: String? = null, root: String? = null, rpath: String? = null) =
            a.startActivity(Intent(a, SyncActivity::class.java).putExtra("local", local).putExtra("root", root).putExtra("rpath", rpath))
        val DIRS = arrayOf("양방향 (양쪽에서 바뀐 것을 서로 맞춤)", "휴대폰 → NAS (올리기만)", "NAS → 휴대폰 (받기만)")
        val DIR_SHORT = arrayOf("⇅ 양방향", "↑ 휴대폰 → NAS", "↓ NAS → 휴대폰")
        val WHENS = arrayOf("실시간 (바뀌면 바로)", "1시간마다", "3시간마다", "6시간마다", "12시간마다", "하루에 한 번", "원할 때만 ([지금 동기화]를 누를 때)")
        val HOURS = intArrayOf(0, 1, 3, 6, 12, 24, 0)
        fun whenIndex(p: Sync.Link) = when (p.mode) { Sync.REALTIME -> 0; Sync.MANUAL -> 6; else -> HOURS.indexOfFirst { it == p.hours }.takeIf { it in 1..5 } ?: 3 }
        fun whenText(p: Sync.Link) = when (p.mode) { Sync.REALTIME -> "실시간"; Sync.MANUAL -> "원할 때만"; else -> if (p.hours >= 24) "하루에 한 번" else "${p.hours}시간마다" }
    }

    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var body: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private class Card(val id: String, val status: TextView, val bar: ProgressBar, val held: View)
    private val cards = ArrayList<Card>()
    private var shown = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = Ui(this); if (!ui.dark) setTheme(R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() },
            ui.label("폴더 동기화", 18f, ui.text, bold = true).apply { setPadding(ui.dp(8), 0, 0, 0); layoutParams = ui.lp(0, weight = 1f) },
            ui.iconBtn(Glyph("refresh", ui.text), "모두 지금 동기화") { syncAll() },
            ui.iconBtn(Glyph("plus", ui.text), "동기화 폴더 추가") { edit(null) }).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        body = ui.col().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(32)) }
        val root = ui.col(top, ScrollView(this).apply { addView(body); layoutParams = ui.lp(h = 0, weight = 1f) }).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
        if (savedInstanceState == null) intent.getStringExtra("local")?.let { l -> edit(null, Sync.Link(local = l)) }
            ?: intent.getStringExtra("root")?.let { r -> edit(null, Sync.Link(root = r, rpath = intent.getStringExtra("rpath") ?: "")) }
    }

    override fun onResume() { super.onResume(); render(); tick() }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fs() = Fs(this, pf.api())

    /** /storage/emulated/0/DCIM → 내장 저장소/DCIM */
    private fun localText(path: String): String {
        val v = fs().volumes().filter { path == it.root.path || path.startsWith(it.root.path + "/") }.maxByOrNull { it.root.path.length } ?: return path
        return v.title + path.removePrefix(v.root.path)
    }

    // ---------- 목록 ----------
    private fun render() {
        body.removeAllViews(); cards.clear()
        val ps = Sync.pairs(this)
        shown = ps.joinToString { it.id }
        // NAS 변경 바로 받기
        val watch = pf.bool("sync_watch", true)
        val sw = Switch(this).apply { isChecked = watch; isClickable = false; isFocusable = false }
        body.addView(ui.row(ui.col(ui.label("NAS에서 바뀐 것도 바로 받기", 15.5f, ui.text),
            ui.label("켜 두면 알림이 계속 떠 있고, NAS에서 바뀌면 몇 초 안에 휴대폰에 맞춥니다. 끄면 NAS 쪽은 15분마다 확인합니다. 휴대폰에서 바뀐 것은 어느 쪽이든 바로 올립니다 (‘실시간’ 폴더)", 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 3) })
            .apply { layoutParams = ui.lp(0, weight = 1f) }, sw).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.press(ui.round(ui.panel2, 12), 12); layoutParams = ui.lp(top = 10)
            setOnClickListener { pf.putBool("sync_watch", !watch); SyncService.refresh(this@SyncActivity); render() }
        })
        // 한 번에 옮길 파일 수
        val par = Sync.parallel(this)
        body.addView(ui.row(ui.col(ui.label("한 번에 옮길 파일 수", 15.5f, ui.text),
            ui.label("여러 파일을 함께 올리고 받습니다. 작은 파일이 많을 때 빨라집니다. 연결이 느리면 줄이세요", 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 3) })
            .apply { layoutParams = ui.lp(0, weight = 1f) }, ui.label("${par}개", 15f, ui.accent, bold = true).apply { setPadding(ui.dp(14), 0, 0, 0) }).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); background = ui.press(ui.round(ui.panel2, 12), 12); layoutParams = ui.lp(top = 8)
            setOnClickListener {
                val ns = intArrayOf(1, 2, 3, 4, 6, 8)
                AlertDialog.Builder(this@SyncActivity).setTitle("한 번에 옮길 파일 수")
                    .setSingleChoiceItems(ns.map { "${it}개" + if (it == 4) " (기본)" else "" }.toTypedArray(), ns.indexOf(par).coerceAtLeast(0)) { d, w -> d.dismiss(); pf.putInt("sync_par", ns[w]); render() }.show()
            }
        })
        if (ps.isEmpty()) {
            body.addView(ui.label("아직 동기화하는 폴더가 없습니다.\n\n휴대폰 폴더 하나와 NAS 폴더 하나를 짝지어 두면, 한쪽에서 바뀐 것을 다른 쪽에 그대로 맞춥니다. 실시간으로 하거나, 몇 시간마다, 또는 원할 때만 할 수 있습니다.\n\n탐색기에서 폴더를 고르고 더 보기 › ‘동기화 폴더로’ 를 눌러도 됩니다.", 14f, ui.sub).apply {
                setPadding(ui.dp(8), ui.dp(24), ui.dp(8), ui.dp(16)) })
            body.addView(ui.button("+ 동기화 폴더 추가") { edit(null) }.apply { layoutParams = ui.lp(top = 4) })
            return
        }
        for (p in ps) body.addView(card(p))
        update()
    }

    private fun card(p: Sync.Link): View {
        val box = ui.col().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(12)); background = ui.round(ui.panel2, 12); layoutParams = ui.lp(top = 12) }
        fun line(icon: String, label: String, value: String, color: Int = ui.text) = ui.row(
            ImageView(this).apply { setImageDrawable(Glyph(icon, ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) } },
            ui.label(label, 12.5f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(46), -2) },
            ui.label(value, 14f, color).apply { layoutParams = ui.lp(0, weight = 1f); maxLines = 2; ellipsize = TextUtils.TruncateAt.START }
        ).apply { layoutParams = ui.lp(top = 6) }
        box.addView(ui.row(ui.label(p.name, 17f, if (p.on) ui.text else ui.sub, bold = true).apply { layoutParams = ui.lp(0, weight = 1f); setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            ui.label((if (p.on) "" else "꺼 둠 · ") + whenText(p), 12.5f, if (p.on) ui.accent else ui.sub)))
        box.addView(line("phone", "휴대폰", localText(p.local)))
        box.addView(ui.label(DIR_SHORT[p.dir], 13f, ui.accent, bold = true).apply { setPadding(ui.dp(28), ui.dp(4), 0, 0) })
        box.addView(line(if (p.web) "globe" else "nas", "NAS", p.nasText()))
        val status = ui.label("", 13f, ui.sub).apply { layoutParams = ui.lp(top = 10) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE; layoutParams = ui.lp(top = 6) }
        box.addView(status); box.addView(bar)
        val btns = ui.row().apply { layoutParams = ui.lp(top = 10) }
        btns.addView(ui.button("지금 동기화") { now(p) }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { rightMargin = ui.dp(8) } })
        val held = ui.button("지울 것 확인", primary = false) { confirmHeld(p) }.apply { setTextColor(ui.red); layoutParams = LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { rightMargin = ui.dp(8) } }
        btns.addView(held)
        btns.addView(ui.button("편집", primary = false) { edit(p) }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(42)) })
        box.addView(btns)
        cards += Card(p.id, status, bar, held)
        return box
    }

    /** 상태 줄만 고침 (0.7초마다) */
    private fun update() {
        val c = Sync.cur
        val wait = Sync.pendingIds(this)
        for (k in cards) {
            val st = Sync.status(this, k.id)
            k.held.visibility = if (st.held > 0 && c?.id != k.id) View.VISIBLE else View.GONE
            if (c != null && c.id == k.id) {
                k.status.setTextColor(ui.accent)
                val act = c.active.entries.toList().sortedBy { it.key }
                k.status.text = "동기화 중 · " + c.phase + (if (c.total > 0) "  ${c.done}/${c.total}" else "") + (if (act.size > 1) " · ${act.size}개 함께" else "") +
                    (if (act.isEmpty()) (if (c.file.isNotEmpty() && c.total == 0) "\n" + c.file else "")
                     else act.take(8).joinToString("") { (rel, a) -> "\n" + (if (a[2] == 1L) "↑ " else "↓ ") + rel.substringAfterLast('/') + (if (a[1] > 0) "  ${a[0] * 100 / a[1]}%" else "") })
                k.bar.visibility = View.VISIBLE
                if (c.total > 0) { k.bar.isIndeterminate = false; k.bar.progress = c.done * 1000 / c.total }
                else if (c.size > 0) { k.bar.isIndeterminate = false; k.bar.progress = (c.bytes * 1000 / c.size).toInt() }
                else if (c.total > 0) { k.bar.isIndeterminate = false; k.bar.progress = c.done * 1000 / c.total } else k.bar.isIndeterminate = true
                continue
            }
            k.bar.visibility = View.GONE
            val t = if (st.time > 0) SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(st.time)) else ""
            k.status.setTextColor(if (st.err) ui.red else ui.sub)
            k.status.text = when {
                k.id in wait -> "곧 동기화합니다" + (if (st.msg.isNotEmpty()) " · 지난번: ${st.msg}" else "")
                st.msg.isEmpty() -> "아직 동기화하지 않았습니다"
                t.isEmpty() -> st.msg
                else -> "$t · ${st.msg}"
            }
        }
    }

    private fun tick() {
        if (Sync.pairs(this).joinToString { it.id } != shown) render() else update()
        h.postDelayed({ tick() }, 700)
    }

    // ---------- 동작 ----------
    private fun ready(): Boolean {
        if (!pf.paired) { toast("먼저 NAS 에 연결해 주세요 (설정 › 연결)"); return false }
        if (!fs().canReadAll()) {
            toast("휴대폰 폴더를 읽고 쓰려면 ‘모든 파일 접근’을 허용해 주세요")
            if (Build.VERSION.SDK_INT >= 30) try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        return true
    }

    private fun now(p: Sync.Link) { if (ready()) { SyncService.now(this, listOf(p.id)); update() } }
    private fun syncAll() { val ps = Sync.pairs(this); if (ps.isEmpty()) { edit(null); return }; if (ready()) { SyncService.now(this, ps.map { it.id }); update() } }

    private fun confirmHeld(p: Sync.Link) {
        val n = Sync.status(this, p.id).held
        AlertDialog.Builder(this).setTitle("지울 것이 많습니다 (${n}개)")
            .setMessage("지난 동기화에서 한쪽에서 지운 것을 다른 쪽에서도 ${n}개 지우게 되어, 지우지 않고 멈췄습니다.\n\n" +
                "[되살리기] 잘못 지운 것이면 — 남아 있는 쪽에서 다시 가져와 양쪽을 채웁니다.\n\n" +
                "[지우기] 정말 지운 것이면 — 다른 쪽에서도 지웁니다. 휴대폰 것은 앱 휴지통, NAS 것은 NAS 휴지통으로 가서 되살릴 수 있습니다.\n\n" +
                "[나중에] 지금은 아무것도 하지 않습니다.")
            .setPositiveButton("지우기") { _, _ -> if (ready()) { Sync.setStatus(this, p.id) { it.held = 0 }; SyncService.now(this, listOf(p.id), allowDel = true) } }
            .setNeutralButton("되살리기") { _, _ -> if (ready()) { Sync.setStatus(this, p.id) { it.held = 0 }; SyncService.now(this, listOf(p.id), restore = true) } }
            .setNegativeButton("나중에", null).show()
    }

    // ---------- 편집 ----------
    private fun edit(old: Sync.Link?, preset: Sync.Link? = null) {
        if (!pf.paired) { toast("먼저 NAS 에 연결해 주세요 (설정 › 연결)"); return }
        val d = (old ?: preset)?.let { Sync.Link.of(it.json()) } ?: Sync.Link()
        if (old == null) d.id = Sync.Link().id
        val box = ui.col().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        lateinit var dlg: AlertDialog
        fun row(icon: String, title: String, desc: String, value: String, sw: Boolean? = null, f: () -> Unit) {
            val t = ui.col(ui.label(title, 15.5f, ui.text), ui.label(desc, 12f, ui.sub).apply { layoutParams = ui.lp(top = 2) }).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(16) } }
            if (value.isNotEmpty()) t.addView(ui.label(value, 13.5f, ui.accent).apply { layoutParams = ui.lp(top = 3); maxLines = 2; ellipsize = TextUtils.TruncateAt.START })
            val r = ui.row(ImageView(this).apply { setImageDrawable(Glyph(icon, ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) }, t).apply {
                setPadding(ui.dp(20), ui.dp(11), ui.dp(16), ui.dp(11)); background = ui.press(null); setOnClickListener { f() } }
            if (sw != null) r.addView(Switch(this).apply { isChecked = sw; isClickable = false; isFocusable = false })
            box.addView(r)
        }
        fun fill() {
            box.removeAllViews()
            row("phone", "휴대폰 폴더", "이 휴대폰에서 맞출 폴더", if (d.local.isEmpty()) "눌러서 고르기" else localText(d.local)) { pickLocal(d.local) { d.local = it; fill() } }
            row(if (d.web) "globe" else "nas", "NAS 폴더", "맞출 NAS 폴더 (Mydrive 연결 폴더나 웹 폴더 안)", if (d.root.isEmpty()) "눌러서 고르기" else d.nasText()) { pickNas { r, path -> d.root = r; d.rpath = path; fill() } }
            row("refresh", "방향", "한 방향은 지운 것을 따라 지우지 않습니다", DIRS[d.dir]) {
                AlertDialog.Builder(this).setTitle("방향").setSingleChoiceItems(DIRS, d.dir) { x, w -> x.dismiss(); d.dir = w; fill() }.show() }
            row("history", "언제", "실시간: 휴대폰에서 바뀌면 몇 초 안에, NAS에서 바뀐 것은 위 스위치대로", WHENS[whenIndex(d)]) {
                AlertDialog.Builder(this).setTitle("언제 맞출까요").setSingleChoiceItems(WHENS, whenIndex(d)) { x, w ->
                    x.dismiss(); d.mode = when (w) { 0 -> Sync.REALTIME; 6 -> Sync.MANUAL; else -> Sync.TIMED }; if (w in 1..5) d.hours = HOURS[w]; fill() }.show() }
            row("globe", "와이파이에서만", "모바일 데이터일 때는 자동으로 하지 않습니다 ([지금 동기화]는 언제나 함)", "", d.wifiOnly) { d.wifiOnly = !d.wifiOnly; fill() }
            row("check", "켜기", "끄면 자동으로 하지 않고 기억만 해 둡니다", "", d.on) { d.on = !d.on; fill() }
        }
        fill()
        val b = AlertDialog.Builder(this).setTitle(if (old == null) "동기화 폴더 추가" else "동기화 편집").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장", null).setNegativeButton("취소", null)
        if (old != null) b.setNeutralButton("빼기", null)
        dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = check(d)
                if (err != null) { toast(err); return@setOnClickListener }
                val all = Sync.pairs(this).toMutableList()
                val i = all.indexOfFirst { it.id == d.id }
                val moved = old != null && (old.local != d.local || old.root != d.root || old.rpath != d.rpath)
                if (i >= 0) all[i] = d else all += d
                if (moved) Sync.forget(this, d.id)   // 폴더를 바꾸면 처음 맞추는 것처럼 (지우지 않고 합침)
                Sync.savePairs(this, all)
                dlg.dismiss()
                SyncService.refresh(this)
                if ((old == null || moved) && ready()) SyncService.now(this, listOf(d.id))
                render()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                AlertDialog.Builder(this).setTitle("‘${d.name}’ 동기화 빼기").setMessage("동기화만 그만둡니다. 휴대폰과 NAS의 파일은 양쪽에 그대로 남습니다.")
                    .setPositiveButton("빼기") { _, _ ->
                        Sync.savePairs(this, Sync.pairs(this).filter { it.id != d.id }); Sync.forget(this, d.id)
                        dlg.dismiss(); SyncService.refresh(this); render()
                    }.setNegativeButton("취소", null).show()
            }
        }
        dlg.show()
    }

    private fun check(d: Sync.Link): String? {
        if (d.local.isEmpty()) return "휴대폰 폴더를 골라 주세요"
        if (d.root.isEmpty()) return "NAS 폴더를 골라 주세요"
        if (!File(d.local).isDirectory) return "휴대폰 폴더가 없습니다"
        fun nested(a: String, b: String) = a == b || a.startsWith("$b/") || b.startsWith("$a/")
        for (o in Sync.pairs(this)) {
            if (o.id == d.id) continue
            if (nested(o.local, d.local)) return "‘${o.name}’ 동기화와 휴대폰 폴더가 겹칩니다 (같은 폴더이거나 안쪽 폴더)"
            if (o.root == d.root && (o.rpath.isEmpty() || d.rpath.isEmpty() || nested(o.rpath, d.rpath))) return "‘${o.name}’ 동기화와 NAS 폴더가 겹칩니다"
        }
        return null
    }

    // ---------- 폴더 고르기 ----------
    /** 목록 창 하나로 둘러보기: 눌러서 들어가고 [이 폴더 고르기], [새 폴더] */
    private fun browser(title: String, load: (String?, (List<String>?, String?) -> Unit) -> Unit, up: (String) -> String?, enter: (String?, Int) -> String,
                        label: (String?) -> String, mkdir: ((String, String, (String) -> Unit) -> Unit)?, pick: (String) -> Unit, start: String?) {
        var cur: String? = start
        val list = ListView(this)
        val head = ui.label("", 13f, ui.sub).apply { setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(4)); maxLines = 2; ellipsize = TextUtils.TruncateAt.START }
        val wrap = ui.col(head, list.apply { layoutParams = ui.lp(h = ui.dp(380)) })
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setPositiveButton("이 폴더 고르기", null).setNeutralButton("새 폴더", null).setNegativeButton("취소", null).create()
        var names: List<String> = emptyList()
        fun show() {
            head.text = label(cur)
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("불러오는 중…"))
            val at = cur
            load(at) { r, err ->
                runOnUiThread {
                    if (at != cur) return@runOnUiThread
                    names = r ?: emptyList()
                    val rows = (if (at != null) listOf("⬑  위 폴더로") else emptyList()) + names.ifEmpty { listOf(err ?: "(안에 폴더 없음)") }
                    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = at != null
                    dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = at != null && mkdir != null
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = cur
            if (c != null && pos == 0) { cur = up(c); show(); return@setOnItemClickListener }
            val i = if (c != null) pos - 1 else pos
            if (i in names.indices) { cur = enter(c, i).also { }; show() }
        }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { cur?.let { pick(it); dlg.dismiss() } }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val c = cur ?: return@setOnClickListener
                val e = ui.input("새 폴더 이름", "")
                AlertDialog.Builder(this).setTitle("새 폴더").setView(FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(e) })
                    .setPositiveButton("만들기") { _, _ ->
                        val n = e.text.toString().trim()
                        if (n.isEmpty() || n.contains('/')) { toast("이름이 올바르지 않습니다"); return@setPositiveButton }
                        mkdir?.invoke(c, n) { k -> runOnUiThread { cur = k; show() } }
                    }.setNegativeButton("취소", null).show()
            }
            show()
        }
        dlg.show()
    }

    private fun pickLocal(start: String, done: (String) -> Unit) {
        val vols = fs().volumes()
        // 열린 자리: 문자열 = 절대 경로, null = 저장소 목록
        var kids: List<String> = emptyList()
        browser("휴대폰 폴더 고르기",
            load = { at, cb ->
                if (at == null) { kids = vols.map { it.root.path }; cb(vols.map { "📱  ${it.title}" }, null) }
                else thread {
                    val l = File(at).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
                    kids = l.map { it.path }; cb(l.map { "📁  ${it.name}" }, if (File(at).canRead()) null else "읽을 수 없습니다")
                }
            },
            up = { c -> if (vols.any { it.root.path == c }) null else File(c).parent },
            enter = { _, i -> kids[i] },
            label = { c -> if (c == null) "저장소를 고르세요" else localText(c) },
            mkdir = { c, n, ok -> if (File(c, n).mkdirs()) ok(File(c, n).path) else toast("폴더를 만들지 못했습니다") },
            pick = done,
            start = start.takeIf { it.isNotEmpty() && File(it).isDirectory })
    }

    /** NAS: 맨 위 = 연결 폴더·웹 폴더 목록, 그 아래 = "root\u0001path" */
    private fun pickNas(done: (String, String) -> Unit) {
        val api = pf.api()
        var kids: List<String> = emptyList()
        fun split(k: String) = k.substringBefore('\u0001') to k.substringAfter('\u0001', "")
        browser("NAS 폴더 고르기",
            load = { at, cb ->
                thread {
                    try {
                        if (at == null) {
                            val rs = api.roots(); val ws = try { api.webs() } catch (e: Exception) { emptyList() }
                            kids = rs.map { it.name + "\u0001" } + ws.map { "~" + it.name + "\u0001" }
                            cb(rs.map { "🔄  ${it.name}   (Mydrive 연결 폴더)" } + ws.map { "🌐  ${it.name}   (웹 폴더)" }, null)
                        } else {
                            val (r, path) = split(at)
                            val ds = api.list(r, path).filter { it.dir && !it.name.startsWith(".") && !it.name.startsWith("#") && !it.name.startsWith("@") }.sortedBy { it.name.lowercase() }
                            kids = ds.map { r + "\u0001" + it.path }; cb(ds.map { "📁  ${it.name}" }, null)
                        }
                    } catch (e: Exception) { cb(null, "NAS 에 닿지 않습니다: ${e.message}") }
                }
            },
            up = { c -> val (r, path) = split(c); if (path.isEmpty()) null else r + "\u0001" + path.substringBeforeLast('/', "") },
            enter = { _, i -> kids[i] },
            label = { c -> if (c == null) "NAS 연결 폴더를 고르세요" else split(c).let { (r, p) -> (if (r.startsWith("~")) r.drop(1) else r) + (if (p.isEmpty()) "" else "/$p") } },
            mkdir = { c, n, ok -> val (r, p) = split(c); thread { try { val np = if (p.isEmpty()) n else "$p/$n"; api.mkdir(r, np); ok(r + "\u0001" + np) } catch (e: Exception) { runOnUiThread { toast(e.message ?: "만들지 못했습니다") } } } },
            pick = { c -> val (r, p) = split(c); done(r, p) },
            start = null)
    }
}
