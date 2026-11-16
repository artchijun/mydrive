package kr.artchijun.mydrive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 탐색기 (PC 의 Myplorer 모양): 위 제목줄 · 탭 줄 · 경로 막대 · 목록 · 아래 상태 줄.
 * 휴대폰 저장소와 NAS 폴더를 탭으로 나란히 열고, 복사 → 다른 탭에서 붙여넣기로 오감 (휴대폰 ↔ NAS 는 올리기·받기).
 */
class MainActivity : Activity(), Transfers.Listener {
    // ---------- 상태 ----------
    private class Tab(var loc: Loc, var pinned: Boolean = false) {
        val back = ArrayDeque<Loc>(); val fwd = ArrayDeque<Loc>()
        var scroll = 0; var scrollTop = 0
    }
    private class Clip(val loc: Loc, val items: List<Item>, val move: Boolean)
    /** 목록 한 칸: 머리글 / 카드(처음 화면) / 파일·폴더 */
    private class Row(val type: Int, val title: String = "", val sub: String = "", val right: String = "", val frac: Int = -1,
                      val icon: Drawable? = null, val go: Loc? = null, val item: Item? = null, val act: (() -> Unit)? = null,
                      val long: (() -> Unit)? = null, val segs: List<Triple<Int, Float, String>> = emptyList())

    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var api: Api
    private lateinit var fs: Fs
    private val net = Executors.newSingleThreadExecutor()
    private val coll = Collator.getInstance(Locale.KOREAN)

    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val tab get() = tabs[cur]
    private var items: List<Item> = emptyList()
    private var rows: List<Row> = emptyList()
    private val sel = linkedSetOf<String>()        // 고른 항목 (path)
    private var clip: Clip? = null
    private var pending: List<Uri> = emptyList()   // 다른 앱의 '공유'로 받은 파일
    private var upd: Update.Info? = null
    private var loadSeq = 0
    private var nasRoots: List<RootInfo> = emptyList()
    private var nasErr: String? = null

    private var sortBy get() = pf.int("sortBy", 0); set(v) = pf.putInt("sortBy", v)       // 0 이름 1 날짜 2 크기 3 종류
    private var sortDesc get() = pf.bool("sortDesc", false); set(v) = pf.putBool("sortDesc", v)
    private var showHidden get() = pf.bool("hidden", false); set(v) = pf.putBool("hidden", v)

    // ---------- 화면 부품 ----------
    private lateinit var title: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView
    private lateinit var upBtn: ImageView
    private lateinit var starBtn: ImageView
    private lateinit var pathBar: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerBtn: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var fab: ImageView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var xfer: LinearLayout
    private lateinit var xferText: TextView
    private lateinit var xferBar: ProgressBar
    private lateinit var drawer: FrameLayout
    private lateinit var drawerBody: LinearLayout
    private val adapter = Adapter()

    private val PICK = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = Ui(this); if (!ui.dark) setTheme(R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        pf = Prefs(this); api = pf.api(); fs = Fs(this, api)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        build()
        restoreTabs()
        takeShare(intent)
        renderTabs(); load()
        loadNas()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); takeShare(intent) }

    override fun onResume() {
        super.onResume()
        Transfers.listener = this; onTransfers()
        if (tab.loc.kind == "home") load()      // 권한을 켜고 돌아왔을 수 있음
        if (System.currentTimeMillis() - pf.lastUpdateCheck > 6 * 3600_000L) checkUpdate(false)
    }

    override fun onPause() { super.onPause(); saveTabs(); if (Transfers.listener === this) Transfers.listener = null }

    // ================= 화면 만들기 =================
    private fun g(kind: String, color: Int = ui.text) = Glyph(kind, color)

    private fun build() {
        val menuBtn = ui.iconBtn(g("menu"), "메뉴") { openDrawer() }
        title = ui.label("Mydrive", 18f, ui.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val searchBtn = ui.iconBtn(g("search"), "검색") { askSearch() }
        val moreBtn = ui.iconBtn(g("more"), "더보기") { moreMenu() }
        val top = ui.row(menuBtn, title.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } }, searchBtn, moreBtn).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }

        // 탭 줄
        tabRow = ui.row()
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        val plus = ui.iconBtn(g("plus", ui.sub), "새 탭", 40) { newTab(Loc.HOME) }
        val tabs = ui.row(tabScroll.apply { layoutParams = ui.lp(0, weight = 1f) }, plus).apply { setBackgroundColor(ui.panel); setPadding(ui.dp(6), 0, ui.dp(4), 0) }

        // 경로 막대: ← → ↑ [경로 › …] ☆
        backBtn = ui.iconBtn(g("back"), "뒤로", 38) { goBack() }
        fwdBtn = ui.iconBtn(g("fwd"), "앞으로", 38) { goFwd() }
        upBtn = ui.iconBtn(g("up"), "위로", 38) { goUp() }
        crumbs = ui.row()
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
        val crumbBox = FrameLayout(this).apply { background = ui.round(ui.panel2, 6); setPadding(ui.dp(4), 0, ui.dp(4), 0); addView(crumbScroll) }
        starBtn = ui.iconBtn(g("star", ui.sub), "즐겨찾기", 38) { toggleFav() }
        pathBar = ui.row(backBtn, fwdBtn, upBtn, crumbBox.apply { layoutParams = ui.lp(0, ui.dp(36), 1f).apply { leftMargin = ui.dp(4); rightMargin = ui.dp(2) } }, starBtn).apply {
            setBackgroundColor(ui.bg); setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        }

        bannerText = ui.label("", 14f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }
        bannerBtn = ui.label("", 14f, ui.onAccent, bold = true).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.press(ui.round(ui.accent, 6), 6) }
        banner = ui.row(bannerText, bannerBtn).apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8)); visibility = View.GONE
            setBackgroundColor(if (ui.dark) Color.parseColor("#16303D") else Color.parseColor("#E3EEFC"))
        }

        list = ListView(this).apply {
            adapter = this@MainActivity.adapter; divider = null; setBackgroundColor(ui.bg); selector = ui.press(null)
            isFastScrollEnabled = true; clipToPadding = false; setPadding(0, 0, 0, ui.dp(80))
            setOnItemClickListener { _, _, pos, _ -> tap(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> longTap(pos); true }
        }
        empty = ui.label("", 14f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(48), ui.dp(24), 0); visibility = View.GONE }
        fab = ImageView(this).apply {
            setImageDrawable(g("plus", ui.onAccent)); val p = ui.dp(16); setPadding(p, p, p, p)
            background = ui.press(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.accent) }, 28)
            elevation = ui.dpf(6f); contentDescription = "새로 만들기"
            setOnClickListener { createMenu() }
        }
        val listBox = FrameLayout(this).apply {
            addView(list); addView(empty, FrameLayout.LayoutParams(-1, -2))
            addView(fab, FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(18), ui.dp(18)) })
        }

        // 아래: 상태 줄 / 선택했을 때 작업 줄 / 붙여넣기 줄 / 옮기는 중
        statusText = ui.label("", 12.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val sortBtn = ui.iconBtn(g("sort", ui.sub), "정렬", 36) { sortMenu() }
        status = ui.row(statusText.apply { layoutParams = ui.lp(0, weight = 1f) }, sortBtn).apply { setBackgroundColor(ui.panel); setPadding(ui.dp(14), 0, ui.dp(6), 0) }

        selText = ui.label("", 13f, ui.text, bold = true)
        val acts = ui.row(
            action("copy", "복사") { startClip(false) }, action("move", "이동") { startClip(true) },
            action("edit", "이름") { renameSel() }, action("share", "공유") { shareSel() },
            action("trash", "삭제", ui.red) { deleteSel() }, action("more", "더보기") { selMore() })
        selBar = ui.col(ui.row(ui.iconBtn(g("close", ui.sub), "선택 끝", 34) { clearSel() }, selText.apply { layoutParams = ui.lp(0, weight = 1f) },
            ui.iconBtn(g("selectall", ui.sub), "전체 선택", 34) { selectAll() }), acts).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); visibility = View.GONE
        }
        pasteText = ui.label("", 13.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val pasteCancel = ui.label("취소", 14f, ui.sub).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.press(null, 6); setOnClickListener { clip = null; refreshChrome() } }
        val pasteGo = ui.label("붙여넣기", 14f, ui.onAccent, bold = true).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.press(ui.round(ui.accent, 6), 6); setOnClickListener { paste() } }
        pasteBar = ui.row(ImageView(this).apply { setImageDrawable(g("copy", ui.accent)); layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)) },
            pasteText.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(10) } }, pasteCancel, pasteGo).apply {
            setBackgroundColor(ui.panel2); setPadding(ui.dp(14), ui.dp(6), ui.dp(8), ui.dp(6)); visibility = View.GONE
        }
        xferText = ui.label("", 12.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        xferBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.accent) }
        val xCancel = ui.label("취소", 13f, ui.red).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4)); setOnClickListener { Transfers.cancelAll() } }
        xfer = ui.col(ui.row(xferText.apply { layoutParams = ui.lp(0, weight = 1f) }, xCancel), xferBar).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); visibility = View.GONE
        }

        val line = { View(this).apply { setBackgroundColor(ui.line); layoutParams = ui.lp(h = 1) } }
        val main = ui.col(top, tabs, line(), pathBar, banner, listBox.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, line(), pasteBar, xfer, selBar, status.apply { layoutParams = ui.lp(h = ui.dp(36)) }).apply { setBackgroundColor(ui.bg) }

        // 왼쪽 메뉴 (덮개 + 판)
        drawerBody = ui.col().apply { setPadding(0, ui.dp(8), 0, ui.dp(24)) }
        val drawerPanel = ScrollView(this).apply { setBackgroundColor(ui.panel); addView(drawerBody); elevation = ui.dpf(12f) }
        drawer = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; isClickable = true
            setOnClickListener { closeDrawer() }
            addView(drawerPanel, FrameLayout.LayoutParams(minOf(ui.dp(300), (resources.displayMetrics.widthPixels * 0.84).toInt()), -1, Gravity.START))
            drawerPanel.setOnClickListener { }
        }
        val root = FrameLayout(this).apply { addView(main); addView(drawer) }
        ui.insets(root)
        setContentView(root)
    }

    private fun action(icon: String, label: String, color: Int = ui.text, f: () -> Unit) = ui.col(
        ImageView(this).apply { setImageDrawable(g(icon, color)); layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL } },
        ui.label(label, 11f, color).apply { gravity = Gravity.CENTER; layoutParams = ui.lp().apply { topMargin = ui.dp(2) } }
    ).apply { layoutParams = ui.lp(0, weight = 1f); setPadding(0, ui.dp(6), 0, ui.dp(6)); background = ui.press(null, 8); setOnClickListener { f() } }

    // ================= 탭 =================
    private fun tabTitle(l: Loc): String = when (l.kind) {
        "home" -> "홈"
        "trash" -> "휴지통"
        "search" -> "검색: " + l.path.substringBefore('\u0001').ifEmpty { "." + l.path.substringAfter('\u0001') }
        "usage" -> usageTitle(l)
        "nas" -> if (l.path.isEmpty()) l.root else l.name
        "zip" -> if (l.path.isEmpty()) File(l.root).name else l.name
        else -> if (l.path.isEmpty()) volTitle(l.root) else l.name
    }
    private fun tabIcon(l: Loc): Drawable = when (l.kind) {
        "home" -> g("home", ui.sub); "trash" -> g("trash", ui.sub); "search" -> g("search", ui.sub); "usage" -> g("chart", ui.accent)
        "nas" -> if (l.path.isEmpty()) g("nas", ui.accent) else FileIcon(true, l.name)
        "zip" -> FileIcon(false, "a.zip")
        else -> if (l.path.isEmpty()) g(if (l.root == fs.volumes().first().root.path) "phone" else "sd", ui.accent) else if (l.path == "Download") FileIcon(true, "", "download") else FileIcon(true, l.name)
    }
    private fun volTitle(root: String) = fs.volumes().firstOrNull { it.root.path == root }?.title ?: File(root).name

    private fun renderTabs() {
        tabRow.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val active = i == cur
            val icon = ImageView(this).apply { setImageDrawable(tabIcon(t.loc)); layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)) }
            val name = ui.label(tabTitle(t.loc), 13f, if (active) ui.text else ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; maxWidth = ui.dp(150); setPadding(ui.dp(6), 0, ui.dp(2), 0) }
            val box = ui.row(icon, name)
            if (t.pinned) box.addView(ImageView(this).apply { setImageDrawable(g("pin", if (active) ui.accent else ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { leftMargin = ui.dp(4) } })
            if (active && !t.pinned && tabs.size > 1) box.addView(ImageView(this).apply {
                setImageDrawable(g("close", ui.sub)); val p = ui.dp(5); setPadding(p, p, p, p); background = ui.press(null, 12)
                layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { leftMargin = ui.dp(2) }
                setOnClickListener { closeTab(i) }
            })
            val bar = View(this).apply { setBackgroundColor(if (active) ui.accent else Color.TRANSPARENT); layoutParams = ui.lp(h = ui.dp(2)) }
            val cell = ui.col(bar, box.apply { setPadding(ui.dp(10), ui.dp(7), ui.dp(8), ui.dp(9)) }).apply {
                background = if (active) ui.round(ui.bg, 0) else ui.press(null)
                setOnClickListener { switchTab(i) }
                setOnLongClickListener { tabMenu(i); true }
            }
            tabRow.addView(cell, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = ui.dp(1) })
        }
        tabRow.post { tabRow.getChildAt(cur)?.let { tabScroll.smoothScrollTo(maxOf(0, it.left - ui.dp(40)), 0) } }
    }

    private fun switchTab(i: Int) {
        if (i == cur) return
        rememberScroll(); clearSelSilently()
        cur = i; renderTabs(); load()
    }

    private fun newTab(l: Loc) { rememberScroll(); clearSelSilently(); tabs.add(cur + 1, Tab(l)); cur++; renderTabs(); load() }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(i)
        if (cur >= tabs.size || i < cur) cur = maxOf(0, cur - 1)
        renderTabs(); load()
    }

    private fun tabMenu(i: Int) {
        val t = tabs[i]
        val opts = arrayOf(if (t.pinned) "고정 풀기" else "고정 (실수로 닫지 않게)", "이 탭 복제", "닫기", "다른 탭 모두 닫기")
        AlertDialog.Builder(this).setTitle(tabTitle(t.loc)).setItems(opts) { _, w ->
            when (w) {
                0 -> { t.pinned = !t.pinned; renderTabs() }
                1 -> { tabs.add(i + 1, Tab(t.loc)); renderTabs() }
                2 -> if (t.pinned) toast("고정한 탭입니다 — 먼저 고정을 풀어 주세요") else closeTab(i)
                3 -> { val keep = tabs.filterIndexed { k, x -> k == i || x.pinned }; tabs.clear(); tabs.addAll(keep); cur = tabs.indexOf(t); renderTabs(); load() }
            }
        }.show()
    }

    private fun saveTabs() {
        if (tabs.isEmpty()) return
        rememberScroll()
        val a = JSONArray(); tabs.forEach { a.put(JSONObject().put("loc", it.loc.key()).put("pin", it.pinned).put("s", it.scroll)) }
        pf.putStr("tabs", a.toString()); pf.putInt("tabCur", cur)
    }

    private fun restoreTabs() {
        try {
            val a = JSONArray(pf.str("tabs", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); Loc.parse(o.getString("loc"))?.let { l -> tabs += Tab(if (l.kind == "search") Loc.HOME else l, o.optBoolean("pin")).apply { scroll = o.optInt("s") } } }
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs += Tab(Loc.HOME)
        cur = pf.int("tabCur", 0).coerceIn(0, tabs.size - 1)
    }

    private fun rememberScroll() { if (tabs.isEmpty()) return; tab.scroll = list.firstVisiblePosition; tab.scrollTop = list.getChildAt(0)?.top ?: 0 }

    // ================= 이동 =================
    private fun navigate(l: Loc) {
        rememberScroll(); clearSelSilently()
        tab.back.addLast(tab.loc); if (tab.back.size > 50) tab.back.removeFirst()
        tab.fwd.clear(); tab.loc = l; tab.scroll = 0; tab.scrollTop = 0
        addRecent(l); renderTabs(); load()
    }
    private fun goBack() { val p = tab.back.removeLastOrNull() ?: return; clearSelSilently(); tab.fwd.addLast(tab.loc); tab.loc = p; tab.scroll = 0; renderTabs(); load() }
    private fun goFwd() { val n = tab.fwd.removeLastOrNull() ?: return; clearSelSilently(); tab.back.addLast(tab.loc); tab.loc = n; tab.scroll = 0; renderTabs(); load() }
    private fun goUp() {
        val l = tab.loc
        when {
            l.kind == "search" -> Loc.parse(l.root)?.let { navigate(it) }
            l.kind == "usage" -> usageUp(l)
            l.parent() != null -> navigate(l.parent()!!)
            l.kind == "zip" -> { val f = File(l.root); vol(f)?.let { v -> navigate(Loc("local", v.root.path, f.parentFile!!.relativeTo(v.root).path)) } }
            l.kind != "home" -> navigate(Loc.HOME)
        }
    }
    /** /storage/emulated/0/Documents → 내장 저장소/Documents */
    private fun prettyAbs(p: String): String { val v = vol(File(p + "/x")) ?: return p; val rel = File(p).relativeTo(v.root).path; return v.title + (if (rel.isEmpty()) "" else "/$rel") }
    private fun vol(f: File) = fs.volumes().filter { f.path.startsWith(it.root.path + "/") }.maxByOrNull { it.root.path.length }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawer.visibility == View.VISIBLE -> closeDrawer()
            sel.isNotEmpty() -> clearSel()
            tab.back.isNotEmpty() -> goBack()
            tab.loc.kind != "home" -> goUp()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ================= 불러오기 =================
    private fun load() {
        val l = tab.loc; val seq = ++loadSeq
        refreshChrome()
        if (l.kind == "home") { rows = homeRows(); items = emptyList(); showList(); return }
        if (l.kind == "usage") { loadUsage(l, seq); return }
        empty.visibility = View.GONE
        statusText.text = "불러오는 중…"
        net.execute {
            var err: String? = null; var out: List<Item> = emptyList(); var more = false
            try {
                if (l.kind == "search") {
                    val base = Loc.parse(l.root)!!; val q = l.path.substringBefore('\u0001'); val ext = l.path.substringAfter('\u0001', "")
                    val r = fs.search(base, q, ext, showHidden) { seq != loadSeq }; out = r.first; more = r.second
                } else out = fs.list(l, showHidden)
            } catch (e: Exception) { err = if (e is ApiException && e.status == 404) "그런 폴더가 없습니다 (지워졌거나 이름이 바뀜)" else e.message ?: e.javaClass.simpleName }
            val sorted = sortItems(out)
            runOnUiThread {
                if (seq != loadSeq || isFinishing) return@runOnUiThread
                items = sorted
                rows = sorted.map { Row(2, item = it) }
                showList(err, more)
            }
        }
    }

    private fun sortItems(l: List<Item>): List<Item> {
        val byName = Comparator<Item> { a, b -> coll.compare(a.name, b.name) }
        var c: Comparator<Item> = when (sortBy) {
            1 -> compareBy<Item> { it.mtimeMs }.then(byName)
            2 -> compareBy<Item> { it.size }.then(byName)
            3 -> compareBy<Item> { it.name.substringAfterLast('.', "").lowercase() }.then(byName)
            else -> byName
        }
        if (sortDesc) c = c.reversed()
        return if (tab.loc.kind == "trash") l else l.sortedWith(compareBy<Item> { !it.dir }.then(c))
    }

    private fun showList(err: String? = null, more: Boolean = false) {
        adapter.notifyDataSetChanged()
        list.setSelectionFromTop(tab.scroll, tab.scrollTop)
        val l = tab.loc
        empty.visibility = if (err != null || (rows.isEmpty() && l.kind != "home")) View.VISIBLE else View.GONE
        empty.text = err ?: when (l.kind) { "trash" -> "휴지통이 비어 있습니다"; "search" -> "찾은 것이 없습니다"; "usage" -> if (l.path == "dup") "중복 파일이 없습니다 — 모두 하나씩입니다" else "해당하는 파일이 없습니다"; else -> "빈 폴더" }
        if (l.kind == "home") { statusText.text = "Mydrive ${Update.current(this)}"; return }
        if (l.kind == "usage") { statusText.text = usageStatus(l); return }
        val d = items.count { it.dir }; val f = items.size - d
        statusText.text = (if (l.kind == "search") "찾음 · " else "") + "폴더 ${d}개, 파일 ${f}개 (${Fmt.size(items.filter { !it.dir }.sumOf { it.size })})" + (if (more) " · 더 있음 (검색어를 좁혀 주세요)" else "")
    }

    // ---------- 처음 화면 ----------
    private fun homeRows(): List<Row> {
        val out = mutableListOf<Row>()
        if (!fs.canReadAll()) out += Row(1, "휴대폰 파일을 보려면 권한이 필요합니다", "눌러서 ‘모든 파일 접근’을 허용해 주세요", icon = g("info", ui.accent), act = { askAllFiles() })
        out += Row(0, "휴대폰")
        for (v in fs.volumes()) {
            val t = v.total; val f = v.free
            out += Row(1, v.title, "${Fmt.size(f)} 남음 / ${Fmt.size(t)}", if (t > 0) "${((t - f) * 100 / t)}% 사용" else "", if (t > 0) ((t - f) * 1000 / t).toInt() else -1,
                g(if (v.removable) "sd" else "phone", ui.accent), Loc("local", v.root.path))
        }
        val prim = fs.volumes().first().root.path
        out += Row(1, "다운로드", "Download", icon = FileIcon(true, "", "download"), go = Loc("local", prim, "Download"))
        out += Row(0, "NAS")
        when {
            !pf.paired -> out += Row(1, "NAS 연결하기", "DSM 계정으로 이 휴대폰을 연결합니다", icon = g("nas", ui.sub), act = { startActivity(Intent(this, SetupActivity::class.java)) })
            nasErr != null && nasRoots.isEmpty() -> out += Row(1, "NAS 에 닿지 않습니다", nasErr ?: "", icon = g("nas", ui.sub), act = { loadNas() })
            nasRoots.isEmpty() -> out += Row(1, "NAS 연결 폴더를 불러오는 중…", "", icon = g("nas", ui.sub))
            else -> for (r in nasRoots) out += Row(1, r.name, (if (r.share.isNotEmpty()) "NAS ${r.share} · " else "") + "%,d개".format(r.items),
                if (r.total > 0) "${((r.total - r.free) * 100 / r.total)}% 사용" else "", if (r.total > 0) ((r.total - r.free) * 1000 / r.total).toInt() else -1, g("nas", ui.accent), Loc("nas", r.name))
        }
        val favs = favList()
        if (favs.isNotEmpty()) { out += Row(0, "즐겨찾기"); favs.forEach { l -> out += Row(1, tabTitle(l), placeText(l), icon = tabIcon(l), go = l) } }
        out += Row(0, "정리")
        out += Row(1, "저장소 분석", "어디에 용량이 들었는지 · 큰 파일 · 중복 파일", icon = g("chart", ui.accent), act = { pickAnalyze() })
        out += Row(1, "휴지통", "휴대폰에서 지운 파일 (NAS 에서 지운 것은 NAS 휴지통)", icon = g("trash", ui.sub), go = Loc.TRASH)
        return out
    }

    private fun loadNas() {
        if (!pf.paired) return
        nasErr = null
        net.execute {
            val r = try { api.roots() } catch (e: Exception) { nasErr = e.message; null }
            runOnUiThread { if (r != null) nasRoots = r; if (tab.loc.kind == "home") { rows = homeRows(); adapter.notifyDataSetChanged() } }
        }
    }

    private fun placeText(l: Loc) = when (l.kind) {
        "nas" -> "NAS " + l.root + (if (l.path.isEmpty()) "" else "/" + l.path)
        "local" -> volTitle(l.root) + (if (l.path.isEmpty()) "" else "/" + l.path)
        "zip" -> File(l.root).name + (if (l.path.isEmpty()) "" else "/" + l.path)
        else -> tabTitle(l)
    }

    // ---------- 위·아래 줄 갱신 ----------
    private fun refreshChrome() {
        val l = tab.loc
        title.text = tabTitle(l)
        backBtn.isEnabled = tab.back.isNotEmpty(); backBtn.alpha = if (backBtn.isEnabled) 1f else 0.35f
        fwdBtn.isEnabled = tab.fwd.isNotEmpty(); fwdBtn.alpha = if (fwdBtn.isEnabled) 1f else 0.35f
        upBtn.isEnabled = l.kind != "home"; upBtn.alpha = if (upBtn.isEnabled) 1f else 0.35f
        val fav = favList().contains(l)
        starBtn.setImageDrawable(g(if (fav) "starOn" else "star", if (fav) ui.accent else ui.sub)); starBtn.visibility = if (l.browsable) View.VISIBLE else View.INVISIBLE
        // 경로 조각
        crumbs.removeAllViews()
        val parts = mutableListOf<Pair<String, Loc>>()
        when (l.kind) {
            "local", "nas", "zip" -> {
                parts += (if (l.kind == "nas") "NAS " + l.root else if (l.kind == "zip") File(l.root).name else volTitle(l.root)) to l.copy(path = "")
                var acc = ""; if (l.path.isNotEmpty()) for (s in l.path.split('/')) { acc = if (acc.isEmpty()) s else "$acc/$s"; parts += s to l.copy(path = acc) }
            }
            "search" -> { Loc.parse(l.root)?.let { parts += placeText(it) to it }; parts += tabTitle(l) to l }
            "usage" -> { Loc.parse(l.root)?.let { b ->
                parts += placeText(b) to b; parts += "분석" to l.copy(path = "")
                val v = l.path
                if (v.startsWith("d:")) { parts += "폴더별" to l.copy(path = "d:" + b.path)
                    val rest = v.substring(2).removePrefix(b.path).trim('/'); var acc = b.path
                    if (rest.isNotEmpty()) for (seg in rest.split('/')) { acc = if (acc.isEmpty()) seg else "$acc/$seg"; parts += seg to l.copy(path = "d:$acc") } }
                else if (v.isNotEmpty()) parts += tabTitle(l) to l } }
            else -> parts += tabTitle(l) to l
        }
        parts.forEachIndexed { i, (name, to) ->
            if (i > 0) crumbs.addView(ImageView(this).apply { setImageDrawable(g("chev", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)) })
            val last = i == parts.size - 1
            crumbs.addView(ui.label(name, 14f, if (last) ui.text else ui.sub, bold = last).apply {
                setPadding(ui.dp(6), ui.dp(7), ui.dp(6), ui.dp(7)); background = ui.press(null, 4); setSingleLine()
                setOnClickListener { if (!last) navigate(to) }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
        // 알림 띠: 공유로 받은 파일 > 새 판
        when {
            pending.isNotEmpty() -> {
                banner.visibility = View.VISIBLE
                if (l.writable) { bannerText.text = "받은 파일 ${pending.size}개 — 이 폴더에 저장할까요?"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "여기에 저장"; bannerBtn.setOnClickListener { val u = pending; pending = emptyList(); importUris(u); refreshChrome() } }
                else { bannerText.text = "받은 파일 ${pending.size}개 — 저장할 폴더로 들어가세요"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "취소"; bannerBtn.setOnClickListener { pending = emptyList(); refreshChrome() } }
            }
            upd != null -> { banner.visibility = View.VISIBLE; bannerText.text = "새 판 ${upd!!.version} 이 나왔습니다"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "설치"; bannerBtn.setOnClickListener { installUpdate(upd!!) } }
            else -> banner.visibility = View.GONE
        }
        // 아래 줄
        val selecting = sel.isNotEmpty()
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        status.visibility = if (selecting) View.GONE else View.VISIBLE
        if (selecting) { val its = selItems(); selText.text = "${its.size}개 선택됨 · ${Fmt.size(its.filter { !it.dir }.sumOf { it.size })}" }
        val c = clip
        pasteBar.visibility = if (c != null && !selecting) View.VISIBLE else View.GONE
        if (c != null) pasteText.text = (if (c.move) "이동" else "복사") + " 대기: " + (if (c.items.size == 1) c.items[0].name else "${c.items.size}개") + (if (l.writable) "" else " — 붙여 넣을 폴더로 가세요")
        fab.visibility = if (l.writable && !selecting) View.VISIBLE else View.GONE
    }

    // ================= 누르기 =================
    private fun tap(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.type == 0) return
        if (r.type == 1) { r.act?.invoke() ?: r.go?.let { navigate(it) }; return }
        val it = r.item!!
        if (sel.isNotEmpty()) { toggle(it); return }
        val l = tab.loc
        when {
            l.kind == "trash" -> trashMenu(it)
            l.kind == "search" || l.kind == "usage" -> { val base = baseOf(l); if (it.dir) navigate(base.copy(path = it.path)) else openItem(base, it) }
            it.dir -> navigate(l.copy(path = it.path))
            else -> openItem(l, it)
        }
    }

    private fun longTap(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.type == 1 && r.long != null) { r.long.invoke(); return }
        if (r.type == 1) { r.go?.let { g -> val can = g.kind == "local" || g.kind == "nas"
            AlertDialog.Builder(this).setItems(arrayOf("새 탭에서 열기", if (favList().contains(g)) "즐겨찾기에서 빼기" else "즐겨찾기에 넣기") + (if (can) arrayOf("저장소 분석") else emptyArray())) { _, w -> when (w) { 0 -> newTab(g); 1 -> toggleFav(g); else -> analyze(g) } }.show() }; return }
        if (r.type == 2) toggle(r.item!!)
    }

    private fun toggle(it: Item) { if (!sel.remove(it.path)) sel.add(it.path); adapter.notifyDataSetChanged(); refreshChrome() }
    private fun selItems() = items.filter { sel.contains(it.path) }
    private fun clearSel() { sel.clear(); adapter.notifyDataSetChanged(); refreshChrome() }
    private fun clearSelSilently() { sel.clear() }
    private fun selectAll() { items.forEach { sel.add(it.path) }; adapter.notifyDataSetChanged(); refreshChrome() }

    // ---------- 열기 ----------
    private var builtinViewer get() = pf.bool("viewer", true); set(v) = pf.putBool("viewer", v)

    private fun openItem(l: Loc, it: Item) {
        val ext = it.name.substringAfterLast('.', "").lowercase()
        val vk = ViewerActivity.kindOf(it.name)
        if (builtinViewer && vk != null && (l.kind == "local" || l.kind == "nas")) {
            // 같은 폴더의 같은 종류(사진이면 사진들)를 넘겨 볼 수 있게
            val dir = it.path.substringBeforeLast('/', "")
            val same = if (vk == ViewerActivity.IMG && tab.loc.browsable) items.filter { x -> !x.dir && ViewerActivity.kindOf(x.name) == vk } else listOf(it)
            ViewerActivity.start(this, l.copy(path = dir), same.map { x -> x.name }, same.map { x -> x.size }, same.map { x -> x.mtimeMs }, same.indexOf(it).coerceAtLeast(0), vk)
            return
        }
        when (l.kind) {
            "local" -> {
                val f = File(l.root, it.path)
                if (ext == "zip") navigate(Loc("zip", f.path)) else openFile(f)
            }
            "nas" -> fetch(l, it) { f -> if (ext == "zip") navigate(Loc("zip", f.path)) else openFile(f) }
            "zip" -> {
                Transfers.add(this, Transfers.Job(Transfers.Kind.OPEN, "", it.name) { job ->
                    val dst = File(File(cacheDir, "open/zip"), it.name); dst.parentFile?.mkdirs(); dst.delete()
                    fs.transfer(job, l, listOf(it), Loc("local", dst.parentFile!!.path), false); dst.path
                })
            }
        }
    }

    private val afterFetch = mutableMapOf<String, (File) -> Unit>()

    /** NAS 파일을 앱 안에 받아 두고 다음 일 */
    private fun fetch(l: Loc, it: Item, then: (File) -> Unit) {
        askNotify()
        val f = Transfers.openFile(this, l.root, it.path)
        afterFetch[f.path] = then
        Transfers.add(this, Transfers.Job(Transfers.Kind.OPEN, l.root, it.name) { job ->
            f.parentFile?.mkdirs()
            api.download(l.root, Entry(it.path, false, it.size, it.mtimeMs * 1_000_000), f, { d, t -> Transfers.progress(job, d, t) }, { job.cancel })
            f.path
        })
    }

    private fun openFile(f: File) {
        if (f.name.endsWith(".apk", true)) { installApk(f); return }
        try { startActivity(Intent.createChooser(Transfers.viewIntent(this, f), f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (x: Exception) { toast("이 파일을 열 앱이 없습니다") }
    }

    // ================= 선택 작업 =================
    private fun startClip(move: Boolean) {
        val its = selItems(); if (its.isEmpty()) return
        val l = baseOf(tab.loc)
        if (move && !l.writable) { toast("여기서는 옮길 수 없습니다 — 복사를 써 주세요"); return }
        clip = Clip(l, its, move)
        clearSel()
        toast("붙여 넣을 폴더로 가서 [붙여넣기]를 누르세요 — 다른 탭도 됩니다")
    }

    private fun paste() {
        val c = clip ?: return; val dst = tab.loc
        if (!dst.writable) { toast("여기에는 붙여 넣을 수 없습니다"); return }
        clip = null; refreshChrome(); askNotify()
        val title = if (c.items.size == 1) c.items[0].name else "${c.items.size}개"
        val kind = when { c.move -> Transfers.Kind.MOVE; dst.kind == "nas" && c.loc.kind != "nas" -> Transfers.Kind.UP; dst.kind == "local" && c.loc.kind == "nas" -> Transfers.Kind.OPEN; else -> Transfers.Kind.COPY }
        Transfers.add(this, Transfers.Job(kind, "paste", title) { job -> fs.transfer(job, c.loc, c.items, dst, c.move) ?: (if (c.move) "옮겼습니다" else "붙여 넣었습니다") })
    }

    private fun renameSel() {
        val its = selItems()
        if (its.size != 1) { toast("하나만 골라 주세요"); return }
        val l = tab.loc; if (!l.writable) { toast("여기서는 이름을 바꿀 수 없습니다"); return }
        val it = its[0]
        ask("이름 바꾸기", it.name, select = if (it.dir) it.name.length else it.name.lastIndexOf('.').let { d -> if (d > 0) d else it.name.length }) { n ->
            bg({ fs.rename(l, it, n) }) { clearSelSilently(); load() }
        }
    }

    private fun deleteSel() {
        val its = selItems(); if (its.isEmpty()) return
        val view = tab.loc; val l = baseOf(view)
        val where = when (l.kind) { "local" -> "휴대폰 휴지통으로 옮깁니다 (되살릴 수 있음)"; "nas" -> "NAS 휴지통으로 옮깁니다 (DSM 에서 되살릴 수 있음)"; "trash" -> "완전히 지웁니다. 되살릴 수 없습니다."; else -> "" }
        if (where.isEmpty()) { toast("여기서는 지울 수 없습니다"); return }
        // 중복 파일: 한 묶음을 모두 고르면 하나도 남지 않음
        val whole = if (view.kind == "usage" && view.path == "dup") Usage.get(l)?.dups?.count { g -> g.all { sel.contains(it.path) } } ?: 0 else 0
        val warn = if (whole > 0) "\n\n주의: ${whole}묶음은 모두 골라서 하나도 남지 않습니다." else ""
        AlertDialog.Builder(this).setTitle(if (its.size == 1) "‘${its[0].name}’ 지우기" else "${its.size}개 지우기 (${Fmt.size(its.filter { !it.dir }.sumOf { it.size })})").setMessage(where + warn)
            .setPositiveButton("지우기") { _, _ -> bg({ fs.delete(l, its) }) { if (l.kind == "local" || l.kind == "nas") Usage.removed(l, its.map { it.path }); clearSelSilently(); load(); toast(if (l.kind == "trash") "지웠습니다" else "휴지통으로 옮겼습니다") } }
            .setNegativeButton("취소", null).show()
    }

    private fun shareSel() {
        val its = selItems().filter { !it.dir }
        if (its.isEmpty()) { toast("파일을 골라 주세요 (폴더는 공유할 수 없음)"); return }
        val l = baseOf(tab.loc)
        when (l.kind) {
            "local" -> shareFiles(its.map { File(l.root, it.path) })
            "nas", "zip" -> {
                askNotify()
                Transfers.add(this, Transfers.Job(Transfers.Kind.SHARE, "", if (its.size == 1) its[0].name else "${its.size}개") { job ->
                    val dir = File(cacheDir, "share/" + System.currentTimeMillis()).apply { mkdirs() }
                    fs.transfer(job, l, its, Loc("local", dir.path), false)
                    dir.listFiles()!!.joinToString("\n") { it.path }
                })
            }
            else -> toast("여기서는 공유할 수 없습니다")
        }
        clearSel()
    }

    private fun shareFiles(fl: List<File>) {
        val uris = ArrayList(fl.map { FilesProvider.uriFor(this, it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(FilesProvider.mimeOf(fl[0].name))
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        startActivity(Intent.createChooser(i, "공유"))
    }

    private fun selMore() {
        val its = selItems(); val l = baseOf(tab.loc)
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (l.kind == "trash") {
            opts += "되살리기" to { bg({ fs.restore(its) }) { n -> clearSelSilently(); load(); toast("${n}개 되살렸습니다") } }
        }
        if (l.kind == "local") opts += "압축 (ZIP)" to { zipSel(its) }
        if (l.kind == "zip") opts += "고른 것 풀기 (복사 후 붙여넣기)" to { startClip(false) }
        if (its.size == 1 && its[0].dir && l.browsable) opts += "새 탭에서 열기" to { newTab(l.copy(path = its[0].path)); }
        if (its.size == 1 && its[0].dir && l.browsable) opts += "즐겨찾기에 넣기" to { toggleFav(l.copy(path = its[0].path)); clearSel() }
        if (its.size == 1 && !its[0].dir && l.kind == "local") opts += "연결 프로그램 고르기" to { openFile(File(l.root, its[0].path)) }
        opts += "속성" to { props(its) }
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun zipSel(its: List<Item>) {
        val l = baseOf(tab.loc)
        val def = (if (its.size == 1) its[0].name.substringBeforeLast('.').ifEmpty { its[0].name } else tabTitle(l)) + ".zip"
        ask("압축 파일 이름", def, select = def.length - 4) { n ->
            clearSel(); askNotify()
            Transfers.add(this, Transfers.Job(Transfers.Kind.ZIP, "", n) { job -> "만듦: " + fs.compress(job, l, its, if (n.endsWith(".zip", true)) n else "$n.zip").name })
        }
    }

    private fun props(its: List<Item>) {
        val l = baseOf(tab.loc)
        val msg = if (its.size == 1) { val it = its[0]
            "위치: ${placeText(l.copy(path = it.path.substringBeforeLast('/', "")))}\n종류: ${if (it.dir) "폴더" else it.name.substringAfterLast('.', "파일").uppercase()}\n" +
                (if (!it.dir) "크기: ${Fmt.size(it.size)} (%,d 바이트)\n".format(it.size) else if (it.count >= 0) "항목: ${it.count}개\n" else "") +
                "바뀐 때: ${Fmt.time(it.mtimeMs * 1_000_000)}" + (if (it.extra.isNotEmpty()) "\n원래 자리: ${it.extra}" else "")
        } else "${its.size}개 · 파일 합계 ${Fmt.size(its.filter { !it.dir }.sumOf { it.size })}"
        AlertDialog.Builder(this).setTitle(if (its.size == 1) its[0].name else "속성").setMessage(msg).setPositiveButton("닫기", null).show()
    }

    private fun trashMenu(it: Item) {
        AlertDialog.Builder(this).setTitle(it.name).setMessage("원래 자리: ${prettyAbs(it.extra)}\n지운 때: ${Fmt.time(it.mtimeMs * 1_000_000)}")
            .setPositiveButton("되살리기") { _, _ -> bg({ fs.restore(listOf(it)) }) { load(); toast("되살렸습니다") } }
            .setNeutralButton("완전히 지우기") { _, _ -> AlertDialog.Builder(this).setMessage("‘${it.name}’을 완전히 지웁니다. 되살릴 수 없습니다.").setPositiveButton("지우기") { _, _ -> bg({ fs.delete(Loc.TRASH, listOf(it)) }) { load() } }.setNegativeButton("취소", null).show() }
            .setNegativeButton("닫기", null).show()
    }

    // ---------- 새로 만들기 (+) ----------
    private fun createMenu() {
        val l = tab.loc
        AlertDialog.Builder(this).setItems(arrayOf("새 폴더", if (l.kind == "nas") "휴대폰 파일 올리기" else "다른 앱의 파일 가져오기")) { _, w ->
            if (w == 0) ask("새 폴더", "새 폴더") { n -> bg({ fs.mkdir(l, n) }) { load() } }
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, PICK)
        }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri } ?: data.data?.let { uris += it }
        importUris(uris)
    }

    private fun takeShare(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris += it }
        }
        if (uris.isEmpty()) return
        pending = uris
        if (::title.isInitialized) refreshChrome()
    }

    private class Src(val uri: Uri, val name: String, val size: Long, val mtimeNs: Long)

    private fun describe(u: Uri): Src {
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "파일"; var size = -1L; var mt = 0L
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!c.isNull(it)) name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { if (!c.isNull(it)) mt = c.getLong(it) * 1_000_000 }
                    if (mt == 0L) c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 }?.let { if (!c.isNull(it)) mt = c.getLong(it) * 1_000_000_000 }
                }
            }
        } catch (e: Exception) { android.util.Log.w("Mydrive", "파일 정보 못 읽음 $u", e) }
        if (size < 0) size = try { contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1 } catch (e: Exception) { -1 }
        if (mt <= 0) mt = System.currentTimeMillis() * 1_000_000
        name = name.replace('/', '_').replace('\\', '_')
        return Src(u, name, size, mt)
    }

    /** 다른 앱이 준 파일(공유·고르기)을 지금 폴더로: 휴대폰 폴더면 복사, NAS 면 올리기 */
    private fun importUris(uris: List<Uri>) {
        val l = tab.loc
        if (!l.writable) { toast("저장할 폴더로 들어가 주세요"); return }
        askNotify()
        val taken = items.map { it.name.lowercase() }.toMutableSet()
        for (u in uris) {
            val s = describe(u)
            val name = Fmt.uniqueName(s.name) { taken.contains(it.lowercase()) }
            taken += name.lowercase()
            Transfers.add(this, Transfers.Job(if (l.kind == "nas") Transfers.Kind.UP else Transfers.Kind.COPY, l.root, name) { job ->
                try {
                    if (l.kind == "local") {
                        val dir = fs.file(l); val out = File(dir, Fmt.uniqueName(name) { File(dir, it).exists() })
                        try {
                            contentResolver.openInputStream(u)!!.use { inp -> out.outputStream().use { o ->
                                val buf = ByteArray(256 * 1024); var done = 0L
                                while (true) { if (job.cancel) throw Canceled(); val r = inp.read(buf); if (r < 0) break; o.write(buf, 0, r); done += r; Transfers.progress(job, done, s.size) }
                            } }
                        } catch (e: Exception) { out.delete(); throw e }
                        out.setLastModified(s.mtimeNs / 1_000_000); fs.scan(out)
                        return@Job if (out.name != s.name) "‘${out.name}’ 이름으로 저장했습니다" else null
                    }
                    // NAS: 크기를 알려 주지 않는 앱이 보낸 파일은 앱 안에 한 번 받아 두고 올림
                    var tmp: File? = null; var size = s.size
                    var open: () -> java.io.InputStream = { contentResolver.openInputStream(s.uri) ?: throw java.io.IOException("파일을 열 수 없음") }
                    if (size < 0) {
                        val f = File(File(cacheDir, "up").apply { mkdirs() }, Api.newId())
                        open().use { inp -> f.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                        tmp = f; size = f.length(); open = { f.inputStream() }
                    }
                    try {
                        var n = name
                        for (attempt in 0 until 20) {
                            val rel = l.child(n).path
                            try {
                                api.upload(l.root, rel, size, s.mtimeNs, open, { d, t -> Transfers.progress(job, d, t) }, { job.cancel })
                                return@Job if (n != s.name) "‘$n’ 이름으로 올렸습니다" else null
                            } catch (e: ApiException) {
                                if (e.status != 409) throw e
                                val now = api.list(l.root, l.path).map { it.name.lowercase() }.toSet()
                                n = Fmt.uniqueName(s.name) { now.contains(it.lowercase()) }
                            }
                        }
                        throw java.io.IOException("올릴 이름을 정하지 못함")
                    } finally { tmp?.delete() }
                } catch (e: SecurityException) { throw java.io.IOException("이 파일을 읽을 권한이 없습니다 — 보낸 앱에서 다시 공유해 주세요") }
            })
        }
    }

    // ================= 검색 · 정렬 · 메뉴 =================
    private fun askSearch() {
        val l = tab.loc
        val base = when { l.kind == "search" || l.kind == "usage" -> Loc.parse(l.root)!!; l.kind == "local" || l.kind == "nas" -> l; else -> Loc("local", fs.volumes().first().root.path) }
        val q = ui.input("이름 일부 (예: 보고서)")
        val ext = ui.input("확장자 (예: jpg,pdf — 비워도 됨)")
        val box = ui.col(ui.label("찾는 곳: ${placeText(base)} 아래 전체", 13f, ui.sub), q.apply { layoutParams = ui.lp(top = 10) }, ext.apply { layoutParams = ui.lp(top = 8) }).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val d = AlertDialog.Builder(this).setTitle("검색").setView(box)
            .setPositiveButton("찾기") { _, _ ->
                val qs = q.text.toString().trim(); val es = ext.text.toString().trim()
                if (qs.isEmpty() && es.isEmpty()) return@setPositiveButton
                navigate(Loc("search", base.key(), qs + "\u0001" + es))
            }.setNegativeButton("취소", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); q.requestFocus()
    }

    private fun sortMenu() {
        val names = arrayOf("이름", "바뀐 날짜", "크기", "종류")
        val opts = names.mapIndexed { i, n -> (if (sortBy == i) "✓ " else "    ") + n } + listOf((if (sortDesc) "✓ " else "    ") + "거꾸로 (내림차순)")
        AlertDialog.Builder(this).setTitle("정렬").setItems(opts.toTypedArray()) { _, w -> if (w < 4) sortBy = w else sortDesc = !sortDesc; rememberScroll(); load() }.show()
    }

    private fun moreMenu() {
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts += "새로 고침" to { if (tab.loc.kind == "home") loadNas(); load() }
        if (tab.loc.browsable) opts += "이 위치를 새 탭으로" to { newTab(tab.loc) }
        if (tab.loc.writable) opts += "이 폴더 용량 분석" to { analyze(tab.loc) }
        if (tab.loc.kind == "usage") usageMenu(opts)
        opts += "정렬" to { sortMenu() }
        opts += (if (showHidden) "숨김 파일 감추기" else "숨김 파일 보기 (점으로 시작)") to { showHidden = !showHidden; load() }
        if (tab.loc.kind == "trash") opts += "휴지통 비우기" to { emptyTrash() }
        if (tab.loc.kind == "zip") opts += "여기에 전부 풀기" to { extractHere() }
        opts += (if (builtinViewer) "✓ 내장 뷰어로 열기 (사진·동영상·PDF·텍스트)" else "    내장 뷰어로 열기 (사진·동영상·PDF·텍스트)") to { builtinViewer = !builtinViewer; toast(if (builtinViewer) "앱 안에서 엽니다" else "휴대폰의 다른 앱으로 엽니다") }
        opts += "화면 테마" to { themeMenu() }
        opts += "새 판 확인" to { checkUpdate(true) }
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun themeMenu() {
        val names = arrayOf("어두운 화면 (Myplorer 기본)", "밝은 화면", "휴대폰 설정 따름")
        val now = pf.int("theme", 0)
        AlertDialog.Builder(this).setTitle("화면 테마").setSingleChoiceItems(names, now) { d, w -> d.dismiss(); if (w != now) { pf.putInt("theme", w); saveTabs(); recreate() } }.show()
    }

    private fun extractHere() {
        val z = File(tab.loc.root); val v = vol(z)
        if (v == null) { toast("휴대폰 저장소 안의 압축 파일만 그 자리에 풀 수 있습니다 — 고른 것을 복사해 붙여 넣어 주세요"); return }
        val into = Loc("local", v.root.path, z.parentFile!!.relativeTo(v.root).path)
        askNotify()
        Transfers.add(this, Transfers.Job(Transfers.Kind.COPY, "", z.name) { job -> "‘" + fs.extractAll(job, z, into) + "’ 폴더에 풀었습니다" })
    }

    private fun emptyTrash() {
        val (n, size) = fs.trashSize()
        if (n == 0) { toast("휴지통이 비어 있습니다"); return }
        AlertDialog.Builder(this).setTitle("휴지통 비우기").setMessage("${n}개 (${Fmt.size(size)})를 완전히 지웁니다. 되살릴 수 없습니다.")
            .setPositiveButton("비우기") { _, _ -> bg({ fs.emptyTrash() }) { load() } }.setNegativeButton("취소", null).show()
    }

    // ---------- 왼쪽 메뉴 ----------
    private fun openDrawer() {
        drawerBody.removeAllViews()
        fun head(s: String) = drawerBody.addView(ui.label(s, 12.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(4)) })
        fun item(icon: Drawable, s: String, sub: String = "", long: (() -> Unit)? = null, f: () -> Unit) {
            val t = ui.col(ui.label(s, 15f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE })
            if (sub.isNotEmpty()) t.addView(ui.label(sub, 11.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START })
            drawerBody.addView(ui.row(ImageView(this).apply { setImageDrawable(icon); layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) },
                t.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } }).apply {
                setPadding(ui.dp(18), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.press(null)
                setOnClickListener { closeDrawer(); f() }
                if (long != null) setOnLongClickListener { long(); true }
            })
        }
        drawerBody.addView(ui.row(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)) },
            ui.label("Mydrive", 18f, ui.text, bold = true).apply { setPadding(ui.dp(12), 0, 0, 0) }).apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6)) })
        val favs = favList()
        head("즐겨찾기")
        if (favs.isEmpty()) drawerBody.addView(ui.label("폴더에서 ☆ 를 누르면 여기에 모입니다", 12.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(8)) })
        favs.forEach { l -> item(tabIcon(l), tabTitle(l), placeText(l), long = { toggleFav(l); openDrawer() }) { navigate(l) } }
        head("저장소")
        item(g("home", ui.sub), "홈") { navigate(Loc.HOME) }
        fs.volumes().forEach { v -> item(g(if (v.removable) "sd" else "phone", ui.accent), v.title, "${Fmt.size(v.free)} 남음") { navigate(Loc("local", v.root.path)) } }
        nasRoots.forEach { r -> item(g("nas", ui.accent), r.name, "NAS ${r.share}") { navigate(Loc("nas", r.name)) } }
        item(g("trash", ui.sub), "휴지통") { navigate(Loc.TRASH) }
        item(g("chart", ui.sub), "저장소 분석", "큰 파일 · 중복 파일") { pickAnalyze() }
        val rec = recentList().filter { !favs.contains(it) }.take(8)
        if (rec.isNotEmpty()) { head("최근 위치"); rec.forEach { l -> item(g("history", ui.sub), tabTitle(l), placeText(l)) { navigate(l) } } }
        head("설정")
        item(g("nas", ui.sub), if (pf.paired) "NAS 연결 (주소·계정)" else "NAS 연결하기") { startActivity(Intent(this, SetupActivity::class.java)) }
        item(g("download", ui.sub), "새 판 확인", "지금 ${Update.current(this)}") { checkUpdate(true) }
        item(g("info", ui.sub), "정보") {
            AlertDialog.Builder(this).setTitle("Mydrive ${Update.current(this)}")
                .setMessage("휴대폰 파일과 NAS 폴더를 탭으로 오가는 탐색기.\n\nDSM 사용자: ${pf.user.ifEmpty { "(연결 안 함)" }}\n지금 쓰는 주소: ${api.active ?: "(아직 연결 전)"}\n집 안 주소: ${pf.home}\n인터넷 주소: ${pf.outside.ifEmpty { "(없음)" }}")
                .setPositiveButton("닫기", null).show()
        }
        drawer.visibility = View.VISIBLE
        val panel = drawer.getChildAt(0); panel.translationX = -panel.layoutParams.width.toFloat(); panel.animate().translationX(0f).setDuration(180).start()
    }
    private fun closeDrawer() { val panel = drawer.getChildAt(0); panel.animate().translationX(-panel.width.toFloat()).setDuration(150).withEndAction { drawer.visibility = View.GONE }.start() }

    // ---------- 즐겨찾기 · 최근 ----------
    private fun locList(key: String): List<Loc> = try { JSONArray(pf.str(key, "[]")).let { a -> (0 until a.length()).mapNotNull { Loc.parse(a.getString(it)) } } } catch (e: Exception) { emptyList() }
    private fun saveLocList(key: String, l: List<Loc>) = pf.putStr(key, JSONArray().apply { l.forEach { put(it.key()) } }.toString())
    private fun favList() = locList("favs")
    private fun recentList() = locList("recent")
    private fun toggleFav(l: Loc = tab.loc) {
        if (!l.browsable) return
        val f = favList().toMutableList()
        if (!f.remove(l)) { f += l; toast("즐겨찾기에 넣었습니다") }
        saveLocList("favs", f); refreshChrome()
        if (tab.loc.kind == "home") { rows = homeRows(); adapter.notifyDataSetChanged() }
    }
    private fun addRecent(l: Loc) { if (!l.browsable) return; val r = recentList().toMutableList(); r.remove(l); r.add(0, l); saveLocList("recent", r.take(20)) }

    // ================= 옮기기 진행 =================
    override fun onTransfers() {
        val j = Transfers.current
        if (j == null && Transfers.waiting == 0) { xfer.visibility = View.GONE; return }
        xfer.visibility = View.VISIBLE
        val what = if (j == null) "기다리는 중" else Transfers.verb(j.kind)
        val more = Transfers.waiting.let { if (it > 0) "  (기다리는 것 ${it}개)" else "" }
        xferText.text = if (j == null) what + more else "$what  ${j.title}  ${Fmt.size(j.done)}" + (if (j.total > 0) " / ${Fmt.size(j.total)}" else "") + more
        xferBar.isIndeterminate = j == null || j.total <= 0
        if (j != null && j.total > 0) xferBar.progress = (j.done * 1000 / j.total).toInt()
    }

    override fun onFinished(job: Transfers.Job, error: String?, result: String?) {
        if (error != null) { if (error != "취소함") AlertDialog.Builder(this).setTitle(job.title).setMessage(error).setPositiveButton("닫기", null).show(); if (Transfers.waiting == 0) load(); return }
        when (job.kind) {
            Transfers.Kind.OPEN -> if (job.root == "paste") { result?.let { toast(it) }; load() } else result?.let { p ->
                val f = File(p)
                val then = afterFetch.remove(p)
                if (then != null) then(f) else if (p.endsWith(".apk")) installApk(f) else openFile(f)
            }
            Transfers.Kind.SAVE -> toast("저장함: $result")
            Transfers.Kind.SHARE -> result?.let { r -> shareFiles(r.split("\n").map { File(it) }) }
            else -> { result?.let { toast(it) }; if (Transfers.waiting == 0) load() }
        }
    }

    private fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) load()
    }

    // ---------- 새 판 ----------
    private fun checkUpdate(manual: Boolean) {
        pf.lastUpdateCheck = System.currentTimeMillis()
        Executors.newSingleThreadExecutor().execute {
            val info = try { Update.latest() } catch (e: Exception) { null }
            runOnUiThread {
                if (info != null && Update.newer(info.version, Update.current(this))) { upd = info; refreshChrome() }
                else if (manual) toast(if (info == null) "새 판을 확인하지 못했습니다" else "최신 판입니다 (${Update.current(this)})")
            }
        }
    }

    private fun installUpdate(info: Update.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("‘이 출처의 앱 설치 허용’을 켜고 돌아와 [설치]를 다시 눌러 주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Transfers.add(this, Transfers.Job(Transfers.Kind.OPEN, "", "새 판 ${info.version}") { job ->
            Update.download(this, info, { d, t -> Transfers.progress(job, d, t) }, { job.cancel }).path
        })
    }

    private fun installApk(f: File) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(this, f), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast(e.message ?: "설치할 수 없습니다") }
    }

    // ================= 저장소 분석 =================
    /** 검색·분석 화면의 항목은 원래 저장소 기준 경로 → 그 저장소 */
    private fun baseOf(l: Loc): Loc = if (l.kind == "search" || l.kind == "usage") Loc.parse(l.root) ?: l else l

    private fun analyze(l: Loc) = navigate(Loc("usage", l.key(), ""))

    private fun pickAnalyze() {
        val ls = fs.volumes().map { Loc("local", it.root.path) } + nasRoots.map { Loc("nas", it.name) }
        AlertDialog.Builder(this).setTitle("어느 저장소를 분석할까요?").setItems(ls.map { placeText(it) }.toTypedArray()) { _, w -> analyze(ls[w]) }.show()
    }

    private fun usageTitle(l: Loc): String {
        val b = Loc.parse(l.root) ?: return "분석"; val v = l.path
        return when {
            v.isEmpty() -> "분석: " + tabTitle(b)
            v.startsWith("t:") -> Usage.catName(v.substring(2))
            v == "big" -> "큰 파일"; v == "old" -> "오래된 큰 파일"; v == "dup" -> "중복 파일"
            v.startsWith("d:") -> v.substring(2).let { if (it == b.path) "폴더별 용량" else it.substringAfterLast('/') }
            else -> "분석"
        }
    }

    private fun usageUp(l: Loc) {
        val b = Loc.parse(l.root) ?: return; val v = l.path
        if (v.isEmpty()) { navigate(b); return }
        if (v.startsWith("d:")) {
            val d = v.substring(2)
            if (d != b.path) { val p = d.substringBeforeLast('/', ""); navigate(l.copy(path = "d:" + (if (p.length < b.path.length) b.path else p))); return }
        }
        navigate(l.copy(path = ""))
    }

    private val usageRun = Executors.newSingleThreadExecutor()
    private val usageBusy = HashSet<String>()       // 분석 중인 저장소 (dup 은 "|dup")
    private val usageErr = HashMap<String, String>()
    private val usageMsg = HashMap<String, String>()

    private fun usageStatus(l: Loc): String {
        val bk = l.root
        usageMsg[bk]?.takeIf { usageBusy.contains(bk) || usageBusy.contains("$bk|dup") }?.let { return it }
        val r = Loc.parse(bk)?.let { Usage.get(it) } ?: return "분석 전"
        val its = items
        return (if (its.isNotEmpty()) "파일 %,d개 (%s) · ".format(its.size, Fmt.size(its.sumOf { it.size })) else "") + "분석한 때 " + Fmt.time(r.at * 1_000_000).substring(11)
    }

    private fun onUsageTab(bk: String) = !isFinishing && tab.loc.kind == "usage" && tab.loc.root == bk

    /** 오래 걸리는 분석은 따로 (다른 탭을 쓰는 동안에도 계속). 끝나면 그 화면이면 다시 그림 */
    private fun usageJob(key: String, bk: String, work: ((String) -> Unit) -> Unit) {
        if (!usageBusy.add(key)) return
        usageErr.remove(key)
        usageRun.execute {
            val err = try { work { m -> runOnUiThread { usageMsg[bk] = m; if (onUsageTab(bk)) statusText.text = m } }; null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            runOnUiThread { usageBusy.remove(key); usageMsg.remove(bk); if (err != null) usageErr[key] = err; if (onUsageTab(bk)) load() }
        }
    }

    private fun loadUsage(l: Loc, seq: Int) {
        val base = Loc.parse(l.root) ?: return; val bk = l.root; val view = l.path
        val r = Usage.get(base)
        fun wait(title: String, sub: String) { items = emptyList(); rows = listOf(Row(1, title, sub, icon = g("chart", ui.accent))); showList() }
        if (r == null) {
            usageErr[bk]?.let { e -> items = emptyList(); rows = listOf(Row(1, "분석하지 못했습니다", e + " — 눌러서 다시", icon = g("info", ui.red), act = { usageErr.remove(bk); load() })); showList(); return }
            usageJob(bk, bk) { say -> say("분석 중…"); Usage.scan(fs, api, base, { n -> say("분석 중… 파일 %,d개".format(n)) }) { isFinishing } }
            wait("분석하는 중…", if (base.kind == "nas") "NAS 목록 전체를 받아 셉니다. 다른 탭을 써도 계속합니다." else "파일이 많으면 1~2분 걸립니다. 다른 탭을 써도 계속합니다.")
            return
        }
        if (view == "dup" && r.dups == null) {
            usageErr["$bk|dup"]?.let { e -> items = emptyList(); rows = listOf(Row(1, "중복 파일을 찾지 못했습니다", e + " — 눌러서 다시", icon = g("info", ui.red), act = { usageErr.remove("$bk|dup"); load() })); showList(); return }
            usageJob("$bk|dup", bk) { say -> say("중복 파일 찾는 중…"); Usage.findDups(api, r, say) { isFinishing } }
            wait("중복 파일 찾는 중…", "크기가 같은 파일끼리 내용을 비교합니다" + (if (base.kind == "nas") " (NAS 가 계산)" else "") + ". 큰 파일이 많으면 몇 분 걸립니다.")
            return
        }
        statusText.text = "정리하는 중…"
        net.execute {
            val built = try { synchronized(Usage) { usageRows(base, r, view) } } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != loadSeq || isFinishing) return@runOnUiThread
                if (built == null) { items = emptyList(); rows = emptyList(); showList("보여 주지 못했습니다"); return@runOnUiThread }
                items = built.second; rows = built.first; showList()
            }
        }
    }

    private fun usageRows(base: Loc, r: Usage.Result, view: String): Pair<List<Row>, List<Item>> {
        val out = mutableListOf<Row>(); val its = mutableListOf<Item>()
        val bk = base.key()
        fun go(v: String) = Loc("usage", bk, v)
        fun files(l: List<Usage.F>) { for (f in l) { val it = Item(f.name, false, f.size, f.mtimeMs, f.path); its += it; out += Row(2, item = it) } }
        val total = r.total.coerceAtLeast(1)
        when {
            view.isEmpty() -> {
                val cats = Usage.byCat(r)
                val vol = if (base.kind == "local" && base.path.isEmpty()) fs.volumes().firstOrNull { it.root.path == base.root } else null
                val used = vol?.let { it.total - it.free } ?: 0L
                val trash = vol?.let { fs.trashBytes(it.root) } ?: 0L
                val other = if (vol != null) (used - r.total - trash).coerceAtLeast(0) else 0L
                val denom = maxOf(r.total + other + trash, 1L).toFloat()
                val segs = cats.map { (k, sz, _) -> Triple(Usage.catColor(k), sz / denom, Usage.catName(k) + " " + Fmt.size(sz)) } +
                    (if (other > 0) listOf(Triple(ui.sub, other / denom, "앱·시스템 " + Fmt.size(other))) else emptyList()) +
                    (if (trash > 0) listOf(Triple(if (ui.dark) 0xFF6B6B6B.toInt() else 0xFF9AA0AA.toInt(), trash / denom, "휴지통 " + Fmt.size(trash))) else emptyList())
                out += Row(3, Fmt.size(r.total), "파일 %,d개 · 폴더 %,d개".format(r.fileCount, r.dirs.size - 1) +
                    (vol?.let { " · 저장소 ${Fmt.size(used)} 사용 / ${Fmt.size(it.total)}" } ?: "") +
                    (if (r.blocked > 0) "\n읽을 수 없는 폴더 ${r.blocked}개는 빠졌습니다" else ""), segs = segs)
                out += Row(0, "종류별")
                for ((k, sz, n) in cats) out += Row(1, Usage.catName(k), "파일 %,d개".format(n), Fmt.size(sz), (sz * 1000 / total).toInt(),
                    FileIcon(false, Usage.cats.first { it.first == k }.third), act = { navigate(go("t:$k")) })
                if (other > 0) out += Row(1, "앱·시스템", "휴대폰이 보여 주지 않는 앱 자료·시스템 (셀 수 없음)", Fmt.size(other), (other * 1000 / denom).toInt(), g("phone", ui.sub))
                if (trash > 0) out += Row(1, "휴지통", "Mydrive 에서 지운 파일 — 비우면 줄어듦", Fmt.size(trash), (trash * 1000 / denom).toInt(), g("trash", ui.sub), Loc.TRASH)
                val (ds, fl) = Usage.children(r, base.path)
                if (ds.isNotEmpty()) {
                    out += Row(0, "큰 폴더")
                    ds.filter { it.second.size > 0 }.take(6).forEach { (p, d) -> out += dirCard(base, p, d, total) }
                    out += Row(1, "폴더별 용량 모두 보기", "폴더 ${ds.size}개" + (if (fl.isNotEmpty()) " · 이 자리 파일 ${fl.size}개" else "") + " — 눌러서 안으로 들어가며 봅니다", icon = g("grid", ui.sub), act = { navigate(go("d:" + base.path)) })
                }
                out += Row(0, "정리하기")
                val b = Usage.big(r, 200)
                out += Row(1, "큰 파일", "가장 큰 ${b.size}개 · 합계 ${Fmt.size(b.sumOf { it.size })}", icon = g("sort", ui.accent), act = { navigate(go("big")) })
                val o = Usage.old(r)
                out += Row(1, "오래된 큰 파일", "1년 넘게 그대로인 10 MB 이상 · ${o.size}개 · ${Fmt.size(o.sumOf { it.size })}", icon = g("history", ui.accent), act = { navigate(go("old")) })
                val d = r.dups
                out += Row(1, "중복 파일", when { d == null -> "내용이 똑같은 파일 찾기 (눌러서 시작)"; d.isEmpty() -> "찾지 못했습니다"
                    else -> "${d.size}묶음 · 하나씩만 남기면 ${Fmt.size(d.sumOf { it[0].size * (it.size - 1) })} 줄어듦" }, icon = g("copy", ui.accent), act = { navigate(go("dup")) })
            }
            view.startsWith("t:") -> files(Usage.ofCat(r, view.substring(2)))
            view == "big" -> files(Usage.big(r))
            view == "old" -> files(Usage.old(r))
            view.startsWith("d:") -> {
                val dir = view.substring(2); val (ds, fl) = Usage.children(r, dir); val t = (r.dirs[dir]?.size ?: 0L).coerceAtLeast(1)
                if (ds.isNotEmpty()) { out += Row(0, "폴더 (용량 큰 순)"); ds.forEach { (p, d) -> out += dirCard(base, p, d, t) } }
                if (fl.isNotEmpty()) { out += Row(0, "이 폴더의 파일"); files(fl) }
            }
            view == "dup" -> {
                val ds = r.dups ?: emptyList()
                if (ds.isNotEmpty()) out += Row(0, "${ds.size}묶음 — ⋮ › ‘하나씩만 남기고 고르기’ 로 한꺼번에 고를 수 있습니다")
                for (gp in ds) { out += Row(0, "같은 파일 ${gp.size}개 · 각 ${Fmt.size(gp[0].size)} · ${Fmt.size(gp[0].size * (gp.size - 1))} 줄일 수 있음"); files(gp) }
                if (r.dupMore) out += Row(0, "NAS 는 크기가 같은 파일 800개까지만 비교했습니다 — 지운 뒤 ⋮ › ‘중복 다시 찾기’")
            }
        }
        return out to its
    }

    private fun dirCard(base: Loc, p: String, d: Usage.D, total: Long): Row {
        val name = p.substringAfterLast('/'); val loc = base.copy(path = p)
        return Row(1, name, "파일 %,d개".format(d.files), Fmt.size(d.size), (d.size * 1000 / total).toInt(), FileIcon(true, name),
            act = { navigate(Loc("usage", base.key(), "d:$p")) },
            long = { AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("폴더 열기", "새 탭에서 열기")) { _, w -> if (w == 0) navigate(loc) else newTab(loc) }.show() })
    }

    private fun usageMenu(opts: MutableList<Pair<String, () -> Unit>>) {
        val l = tab.loc; val base = Loc.parse(l.root) ?: return
        val mine = mutableListOf<Pair<String, () -> Unit>>()
        if (l.path == "dup") {
            mine += "하나씩만 남기고 고르기 (가장 오래된 것 남김)" to {
                val ds = Usage.get(base)?.dups ?: emptyList()
                sel.clear(); ds.forEach { gp -> gp.drop(1).forEach { sel.add(it.path) } }
                adapter.notifyDataSetChanged(); refreshChrome()
                if (sel.isNotEmpty()) toast("${sel.size}개를 골랐습니다 — 확인하고 [삭제]를 누르세요")
            }
            mine += "중복 다시 찾기" to { Usage.get(base)?.dups = null; load() }
        }
        if (l.path.startsWith("d:")) { val p = l.path.substring(2); mine += "이 폴더 열기" to { navigate(base.copy(path = p)) } }
        mine += "다시 분석 (처음부터 다시 셈)" to { Usage.forget(base); usageErr.remove(l.root); load() }
        opts.addAll(0, mine)
    }

    /** 분석 첫 칸: 큰 글씨 합계 + 종류별 색 막대 + 범례 */
    private fun segCard(convert: View?, r: Row): View {
        val v = (convert as? FrameLayout) ?: run {
            val big = ui.label("", 26f, ui.text, bold = true)
            val sub = ui.label("", 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 2) }
            val bar = SegBar().apply { layoutParams = ui.lp(h = ui.dp(14), top = 12) }
            val legend = ui.col().apply { layoutParams = ui.lp(top = 8) }
            val inner = ui.col(big, sub, bar, legend).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); background = ui.round(ui.panel2, 8) }
            FrameLayout(this).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(4)); addView(inner) }
        }
        val inner = v.getChildAt(0) as LinearLayout
        (inner.getChildAt(0) as TextView).text = r.title
        (inner.getChildAt(1) as TextView).text = r.sub
        (inner.getChildAt(2) as SegBar).apply { segs = r.segs; invalidate() }
        // 범례: 두 칸씩 (글자가 중간에서 꺾이지 않게)
        val lg = inner.getChildAt(3) as LinearLayout; lg.removeAllViews()
        r.segs.chunked(2).forEach { pair ->
            val line = ui.row()
            for (k in 0 until 2) {
                val cell = pair.getOrNull(k)
                val t = ui.label("", 12.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp(0, weight = 1f).apply { topMargin = ui.dp(4) } }
                if (cell != null) { val sb = android.text.SpannableStringBuilder("● " + cell.third); sb.setSpan(android.text.style.ForegroundColorSpan(cell.first), 0, 1, 0); t.text = sb }
                line.addView(t)
            }
            lg.addView(line)
        }
        return v
    }

    private inner class SegBar : View(this@MainActivity) {
        var segs: List<Triple<Int, Float, String>> = emptyList()
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path()
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, h / 2, h / 2, android.graphics.Path.Direction.CW)
            c.save(); c.clipPath(clip)
            p.color = if (ui.dark) 0xFF3A3A3A.toInt() else ui.line; c.drawRect(0f, 0f, w, h, p)
            var x = 0f
            for ((col, f, _) in segs) { val ww = f * w; if (ww <= 0f) continue; p.color = col; c.drawRect(x, 0f, x + ww, h, p); x += ww
                p.color = ui.panel2; c.drawRect(x - ui.dpf(1f), 0f, x, h, p) }
            c.restore()
        }
    }

    // ================= 작은 도구 =================
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** 오래 걸릴 수 있는 일을 뒤에서 → 끝나면 화면에서 다음 일. 실패하면 알림 */
    private fun <T> bg(work: () -> T, then: (T) -> Unit) {
        net.execute {
            val r = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread { r.onSuccess(then).onFailure { e -> AlertDialog.Builder(this).setMessage(if (e is ApiException && e.status == 409) "같은 이름이 있습니다" else e.message ?: e.javaClass.simpleName).setPositiveButton("닫기", null).show() } }
        }
    }

    private fun ask(titleS: String, value: String, select: Int = value.length, ok: (String) -> Unit) {
        val e = ui.input("", value).apply { setSelection(0, select.coerceIn(0, value.length)) }
        val box = FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(e) }
        val d = AlertDialog.Builder(this).setTitle(titleS).setView(box)
            .setPositiveButton("확인") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) ok(n) }.setNegativeButton("취소", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); e.requestFocus()
    }

    // ---------- 사진·동영상 미리보기 (휴대폰 파일) ----------
    private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) { override fun sizeOf(k: String, b: android.graphics.Bitmap) = b.byteCount }
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val thumbMiss = HashSet<String>()

    private fun thumb(iv: ImageView, it: Item) {
        val l = tab.loc
        val base = baseOf(l).takeIf { b -> b.kind == "local" } ?: run { iv.tag = null; return }
        val k = FileIcon.kindOf(it.name)
        if (it.dir || (k != "image" && k != "video")) { iv.tag = null; return }
        val f = File(base.root, it.path); val key = f.path + ":" + it.mtimeMs
        iv.tag = key
        thumbs.get(key)?.let { b -> iv.setImageBitmap(b); return }
        if (key in thumbMiss) return
        thumbPool.execute {
            val b = try {
                val sz = android.util.Size(ui.dp(68), ui.dp(68))
                if (k == "image") android.media.ThumbnailUtils.createImageThumbnail(f, sz, null) else android.media.ThumbnailUtils.createVideoThumbnail(f, sz, null)
            } catch (e: Exception) { null }
            val r = b?.let { cropRound(it) }
            runOnUiThread { if (r == null) thumbMiss += key else { thumbs.put(key, r); if (iv.tag == key) iv.setImageBitmap(r) } }
        }
    }

    /** 가운데를 정사각형으로 자르고 모서리를 둥글게 */
    private fun cropRound(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val s = minOf(b.width, b.height)
        val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(b.width - s) / 2f, -(b.height - s) / 2f) }) }
        c.drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * 0.14f, s * 0.14f, p)
        return out
    }

    // ================= 목록 칸 =================
    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(p: Int) = rows[p].type
        override fun isEnabled(p: Int) = rows[p].type == 1 || rows[p].type == 2

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[p]
            return when (r.type) {
                0 -> ((convert as? TextView) ?: ui.label("", 12.5f, ui.sub, bold = true).apply { setPadding(ui.dp(18), ui.dp(16), ui.dp(16), ui.dp(6)) }).apply { text = r.title }
                1 -> card(convert, r)
                3 -> segCard(convert, r)
                else -> fileRow(convert, r.item!!)
            }
        }

        // 처음 화면 카드 (Myplorer 드라이브 카드: 아이콘 · 이름 · 오른쪽 글 · 사용량 막대 · 남은 용량)
        private fun card(convert: View?, r: Row): View {
            val v = (convert as? FrameLayout) ?: run {
                val icon = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)) }
                val name = ui.label("", 15.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
                val right = ui.label("", 12f, ui.sub)
                val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ui.line); layoutParams = ui.lp(h = ui.dp(6), top = 6)
                }
                val sub = ui.label("", 12f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp(top = 4) }
                val body = ui.col(ui.row(name.apply { layoutParams = ui.lp(0, weight = 1f) }, right), bar, sub).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } }
                val inner = ui.row(icon, body).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.round(ui.panel2, 8) }
                FrameLayout(this@MainActivity).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); addView(inner) }
            }
            val inner = v.getChildAt(0) as LinearLayout
            (inner.getChildAt(0) as ImageView).setImageDrawable(r.icon)
            val body = inner.getChildAt(1) as LinearLayout
            val head = body.getChildAt(0) as LinearLayout
            (head.getChildAt(0) as TextView).text = r.title
            (head.getChildAt(1) as TextView).text = r.right
            val bar = body.getChildAt(1) as ProgressBar
            bar.visibility = if (r.frac >= 0) View.VISIBLE else View.GONE; if (r.frac >= 0) bar.progress = r.frac
            (body.getChildAt(2) as TextView).apply { text = r.sub; visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE }
            return v
        }

        private fun fileRow(convert: View?, it: Item): View {
            val v = (convert as? LinearLayout) ?: run {
                val icon = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)) }
                val name = ui.label("", 15f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                val sub = ui.label("", 12f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
                val check = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) }
                ui.row(icon, ui.col(name, sub).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } }, check).apply {
                    setPadding(ui.dp(16), ui.dp(8), ui.dp(14), ui.dp(8)); minimumHeight = ui.dp(58)
                }
            }
            val on = sel.contains(it.path)
            v.setBackgroundColor(if (on) ui.select else Color.TRANSPARENT)
            val iconV = v.getChildAt(0) as ImageView
            iconV.setImageDrawable(FileIcon(it.dir, it.name, if (it.dir && it.name == "Download" && tab.loc.kind == "local" && tab.loc.path.isEmpty()) "download" else ""))
            thumb(iconV, it)
            val box = v.getChildAt(1) as LinearLayout
            (box.getChildAt(0) as TextView).text = it.name
            val l = tab.loc
            (box.getChildAt(1) as TextView).text = when {
                l.kind == "trash" -> prettyAbs(it.extra.substringBeforeLast('/')) + "  ·  " + Fmt.time(it.mtimeMs * 1_000_000)
                l.kind == "search" -> "/" + it.path.substringBeforeLast('/', "") + "  ·  " + (if (it.dir) "폴더" else Fmt.size(it.size))
                l.kind == "usage" -> Fmt.size(it.size) + "  ·  " + Fmt.time(it.mtimeMs * 1_000_000).take(10) + "  ·  /" + it.path.substringBeforeLast('/', "")
                it.dir -> Fmt.time(it.mtimeMs * 1_000_000) + (if (it.count >= 0) "  ·  ${it.count}개" else "")
                else -> Fmt.time(it.mtimeMs * 1_000_000) + "  ·  " + Fmt.size(it.size)
            }
            (v.getChildAt(2) as ImageView).apply {
                visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE
                setImageDrawable(if (on) g("check", ui.accent) else null)
                background = if (on) null else ui.round(Color.TRANSPARENT, 11, ui.sub)
            }
            return v
        }
    }
}
