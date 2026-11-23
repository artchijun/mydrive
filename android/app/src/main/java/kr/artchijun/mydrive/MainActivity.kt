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
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    // ---------- 상태 ----------
    private class Tab(var loc: Loc, var pinned: Boolean = false) {
        val back = ArrayDeque<Loc>(); val fwd = ArrayDeque<Loc>()
        var scroll = 0; var scrollTop = 0
    }
    private class Clip(val loc: Loc, val items: List<Item>, val move: Boolean)
    /** 목록 한 칸: 머리글 / 카드(처음 화면) / 파일·폴더 */
    private class Row(val type: Int, val title: String = "", val sub: String = "", val right: String = "", val frac: Int = -1,
                      val icon: Drawable? = null, val go: Loc? = null, val item: Item? = null, val act: (() -> Unit)? = null,
                      val long: (() -> Unit)? = null, val segs: List<Triple<Int, Float, String>> = emptyList(), val tiles: List<Row> = emptyList())

    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var api: Api
    private lateinit var fs: Fs
    // 화면에 보일 목록은 여러 줄로 (하나가 오래 걸려도 다른 탭·폴더가 기다리지 않게), 홈 통계는 따로 한 줄로
    private val net = Executors.newFixedThreadPool(3)
    private val stats = Executors.newSingleThreadExecutor()
    /** 최근에 본 폴더 목록 (탭을 바꾸거나 돌아올 때 바로 보여 줌) */
    private val listCache = object : LinkedHashMap<String, List<Item>>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Item>>) = size > 40 }
    private var statsAt = 0L          // 홈 통계(종류별 용량·휴지통)를 마지막으로 센 때 — 30초 안에는 다시 세지 않음 (파일을 바꾸면 0으로)
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
    private var webRoots: List<RootInfo> = emptyList()   // NAS 다른 폴더 (웹 폴더)

    private var sortBy get() = pf.int("sortBy", 0); set(v) = pf.putInt("sortBy", v)       // 0 이름 1 날짜 2 크기 3 종류
    private var sortDesc get() = pf.bool("sortDesc", false); set(v) = pf.putBool("sortDesc", v)
    private var showHidden get() = pf.bool("hidden", false); set(v) = pf.putBool("hidden", v)

    // ---------- 화면 부품 ----------
    private lateinit var title: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
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
    private lateinit var viewBtn: ImageView
    private lateinit var topBar: LinearLayout
    private lateinit var selTop: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var xfer: LinearLayout
    private lateinit var xferText: TextView
    private lateinit var xferFile: TextView
    private lateinit var xferBar: ProgressBar
    private lateinit var drawer: FrameLayout
    private lateinit var drawerBody: LinearLayout
    // 동기화 폴더(또는 그 안)를 볼 때 경로 막대 아래에 뜨는 줄: ⟳ 이름 · 방향 · 언제 / 지난 결과·진행 / [지금 동기화]
    private lateinit var syncBar: LinearLayout
    private lateinit var syncTitle: TextView
    private lateinit var syncSub: TextView
    private lateinit var syncProg: ProgressBar
    private var syncHere: List<Sync.Link> = emptyList()    // 지금 폴더가 들어 있는 짝 (깊은 것부터)
    private var syncLocalRoots: Set<String> = emptySet()   // 짝의 휴대폰 폴더 (목록에서 ⟳ 표시)
    private var syncNasRoots: Set<String> = emptySet()     // 짝의 NAS 폴더 "root/rpath"
    private var syncWasBusy = false
    private val syncH = android.os.Handler(android.os.Looper.getMainLooper())
    private val adapter = Adapter()

    private val PICK = 1
    private var stamp = 0
    companion object { const val ACTION_GO = "kr.artchijun.mydrive.GO" }

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = Ui(this); if (!ui.dark) setTheme(R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        pf = Prefs(this); api = pf.api(); fs = Fs(this, api); stamp = pf.int("stamp", 0)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        build()
        if (Sync.pairs(this).isNotEmpty()) SyncService.refresh(this)   // 동기화 예약·감시 다시 걸기 (앱을 새로 깔았을 때 등)
        restoreTabs(fresh = savedInstanceState == null)
        if (!takeGo(intent)) takeShare(intent)
        renderTabs(); load()
        loadNas(); loadCloud()
        if (pf.bool("phoneShare", false) && pf.paired && !PhoneShareService.running) try { PhoneShareService.start(this) } catch (e: Exception) { }   // 켜 두었던 'PC 에서 이 휴대폰 열기' 이어서
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (!takeGo(intent)) takeShare(intent) }

    /** 홈 화면 바로 가기로 들어옴 */
    private fun takeGo(i: Intent?): Boolean {
        if (i?.action != ACTION_GO) return false
        val l = Loc.parse(i.getStringExtra("loc") ?: return true) ?: return true
        val f = i.getStringExtra("file")
        if (f == null) { openInTab(l); return true }
        openInTab(l)
        val path = if (l.path.isEmpty()) f else "${l.path}/$f"
        if (l.kind == "local") { val x = File(l.root, path); if (x.exists()) openItem(l, Item(f, false, x.length(), x.lastModified(), path)) else toast("파일이 없습니다: $f") }
        else net.execute { val e = try { api.stat(l.root, path) } catch (e: Exception) { null }; runOnUiThread { if (e == null) toast("파일이 없습니다: $f") else openItem(l, Item(f, false, e.size, e.mtimeNs / 1_000_000, path)) } }
        intent = Intent()   // 화면을 돌려도 다시 열지 않게
        return true
    }

    override fun onResume() {
        super.onResume()
        if (pf.int("stamp", 0) != stamp) { saveTabs(); recreate(); return }   // 설정을 바꾸고 돌아옴
        Transfers.listener = this; onTransfers()
        if (findSyncHere()) adapter.notifyDataSetChanged()   // 동기화 화면에서 짝을 더하거나 뺐을 수 있음
        syncTick()
        Transfers.asking?.let { onAsk(it) }
        resumedNow = true
        if (tab.loc.kind == "home") load()      // 권한을 켜고 돌아왔을 수 있음
        else if (tab.loc.browsable && !firstResume) load()   // 다른 앱·PC에서 바꿨을 수 있음 → 보던 폴더 새로 (보던 자리 그대로)
        firstResume = false
        liveStart()
        if (pf.int("cloudStamp", 0) != cloudStamp) { cloudStamp = pf.int("cloudStamp", 0); loadCloud(); refreshHome() }
        if (pf.bool("openHomeEdit", false)) { pf.putBool("openHomeEdit", false); if (tab.loc.kind != "home") navigate(Loc.HOME); editHome() }
        if (System.currentTimeMillis() - pf.lastUpdateCheck > 6 * 3600_000L) checkUpdate(false)
    }

    override fun onPause() { super.onPause(); saveTabs(); if (Transfers.listener === this) Transfers.listener = null; syncH.removeCallbacksAndMessages(null); resumedNow = false; liveStop() }

    // ================= 보고 있는 폴더가 바뀌면 바로 반영 =================
    //   휴대폰 폴더: FileObserver 로 그 폴더를 지켜봄.  NAS 폴더: 서버의 바뀜 알림(/v1/changes)을 기다렸다가 받음.
    //   바뀌면 0.6초 모았다가 목록을 새로 (보던 자리 그대로). 고르는 중이면 다 고른 뒤에.
    private var resumedNow = false
    private var firstResume = true
    private var liveGen = 0
    private var liveKey = ""
    private var localObs: android.os.FileObserver? = null
    private var livePending = false
    private val liveH = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveRun = Runnable { liveReload() }

    private fun liveStart() {
        val l = tab.loc
        if (!resumedNow) return
        if (l.key() == liveKey) return
        liveStop(); liveKey = l.key()
        when (l.kind) {
            "local" -> try {
                val m = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or android.os.FileObserver.MOVED_FROM or
                    android.os.FileObserver.MOVED_TO or android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.DELETE_SELF or android.os.FileObserver.MOVE_SELF
                localObs = object : android.os.FileObserver(fs.file(l), m) {
                    override fun onEvent(e: Int, path: String?) { liveH.post { if (liveKey == l.key()) liveHit() } }
                }.also { it.startWatching() }
            } catch (e: Exception) { }
            "nas" -> if (!l.root.startsWith("~")) { val g = ++liveGen; Thread { nasWatch(l, g) }.start() }
        }
    }

    private fun liveStop() { liveGen++; liveKey = ""; try { localObs?.stopWatching() } catch (e: Exception) { }; localObs = null; liveH.removeCallbacks(liveRun) }
    private fun liveHit() { liveH.removeCallbacks(liveRun); liveH.postDelayed(liveRun, 600) }
    private fun liveReload() {
        if (!resumedNow || isFinishing) return
        if (sel.isNotEmpty()) { livePending = true; return }   // 고르는 중에는 목록이 바뀌지 않게
        load()
    }

    /** NAS 폴더 바뀜 기다리기 (20초씩). 바뀐 경로의 부모 폴더 목록은 기억해 둔 것도 버림.
     *  20초 동안 소식이 없으면 그 폴더를 한 번 조용히 물어봄 → 서버(0.6.22+)가 디스크와 맞춰 보고,
     *  NAS 감시 한도 때문에 놓친 변경(File Station·SMB·시놀로지 포토)이 있으면 바뀜 알림으로 돌아옴 */
    private fun nasWatch(l: Loc, g: Int) {
        var epoch = ""; var seq = -1L; var fails = 0
        while (g == liveGen) {
            try {
                if (seq < 0) { val ri = api.roots().firstOrNull { it.name == l.root } ?: return; epoch = ri.epoch; seq = ri.seq }
                val (reset, s, paths) = api.changes(l.root, epoch, seq, 20)
                if (g != liveGen) return
                fails = 0
                if (reset) { seq = -1; runOnUiThread { if (g == liveGen) liveHit() }; continue }
                seq = s
                if (paths.isEmpty()) { try { api.list(l.root, l.path) } catch (e: Exception) { }; continue }
                val parents = paths.map { it.substringBeforeLast('/', "") }.toSet()
                runOnUiThread {
                    parents.forEach { listCache.remove(Loc("nas", l.root, it).key()) }
                    if (g == liveGen && (l.path in parents || l.path in paths)) liveHit()
                }
            } catch (e: Exception) {
                if (g != liveGen || ++fails > 30) return
                try { Thread.sleep(minOf(30_000L, 2_000L * fails)) } catch (x: InterruptedException) { return }
            }
        }
    }

    // ================= 동기화 줄 =================
    /** 지금 폴더가 어느 짝(의 안)인지 다시 셈. 목록의 ⟳ 표시가 바뀌었으면 true */
    private fun findSyncHere(): Boolean {
        val l = tab.loc
        val ps = Sync.pairs(this)
        fun inside(path: String, top: String) = top.isEmpty() || path == top || path.startsWith("$top/")
        syncHere = when (l.kind) {
            "local" -> { val abs = fs.file(l).path; ps.filter { it.local.isNotEmpty() && (abs == it.local || abs.startsWith(it.local + "/")) }.sortedByDescending { it.local.length } }
            "nas" -> ps.filter { it.root == l.root && inside(l.path, it.rpath) }.sortedByDescending { it.rpath.length }
            else -> emptyList()
        }
        val lr = ps.map { it.local }.toSet(); val nr = ps.map { it.root + "/" + it.rpath }.toSet()
        val changed = lr != syncLocalRoots || nr != syncNasRoots
        syncLocalRoots = lr; syncNasRoots = nr
        updateSyncBar()
        return changed
    }

    /** 목록의 폴더가 동기화 짝의 맨 위 폴더인가 */
    private fun isSyncRoot(l: Loc, it: Item) = it.dir && when (l.kind) {
        "local" -> File(l.root, it.path).path in syncLocalRoots
        "nas" -> (l.root + "/" + it.path) in syncNasRoots
        else -> false
    }

    private fun updateSyncBar() {
        val ps = syncHere
        if (ps.isEmpty()) { syncBar.visibility = View.GONE; syncWasBusy = false; return }
        syncBar.visibility = View.VISIBLE
        val p = ps.first(); val l = tab.loc
        val top = if (l.kind == "local") fs.file(l).path == p.local else l.path == p.rpath
        syncTitle.text = (if (top) "동기화 폴더" else "동기화 폴더 ‘${p.name}’ 안") + " · " + SyncActivity.DIR_SHORT[p.dir] + " · " + (if (p.on) SyncActivity.whenText(p) else "꺼 둠") +
            (if (ps.size > 1) " (외 ${ps.size - 1}개)" else "")
        val c = Sync.cur
        val ids = ps.map { it.id }
        val busy = c != null && c.id in ids
        if (busy) {
            val act = c!!.active.size
            syncSub.setTextColor(ui.accent)
            syncSub.text = "동기화 중 · " + c.phase + (if (c.total > 0) "  ${c.done}/${c.total}" else "") + (if (act > 1) " · ${act}개 함께" else "") +
                (c.active.keys.firstOrNull()?.let { " · " + it.substringAfterLast('/') } ?: "")
            syncProg.visibility = View.VISIBLE
            if (c.total > 0) { syncProg.isIndeterminate = false; syncProg.progress = c.done * 1000 / c.total } else syncProg.isIndeterminate = true
        } else {
            syncProg.visibility = View.GONE
            val st = Sync.status(this, p.id)
            val t = if (st.time > 0) java.text.SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(java.util.Date(st.time)) else ""
            val waiting = ids.any { it in Sync.pendingIds(this) }
            syncSub.setTextColor(if (waiting) ui.accent else if (st.err || st.held > 0) ui.red else ui.sub)
            syncSub.text = when {
                waiting -> "곧 동기화합니다"
                st.held > 0 -> "지울 것이 많아 멈춤 (${st.held}개) — 눌러서 확인"
                st.msg.isEmpty() -> "아직 동기화하지 않았습니다"
                t.isEmpty() -> "지난번: " + st.msg
                else -> "지난번 $t · ${st.msg}"
            }
        }
        val finished = syncWasBusy && !busy
        syncWasBusy = busy
        if (finished) load()   // 방금 끝남 → 이 폴더 목록 새로 고침 (받은·지운 파일이 보이게)
    }

    private fun syncTick() {
        syncH.removeCallbacksAndMessages(null)
        if (syncHere.isNotEmpty()) updateSyncBar()
        syncH.postDelayed({ syncTick() }, 700)
    }

    /** [지금 동기화]: 이 폴더가 들어 있는 짝을 지금 맞춤 */
    private fun syncHereNow() {
        val ps = syncHere; if (ps.isEmpty()) return
        if (!pf.paired) { toast("먼저 NAS 에 연결해 주세요 (설정 › 연결)"); return }
        if (!fs.canReadAll()) { toast("휴대폰 폴더를 읽고 쓰려면 ‘모든 파일 접근’을 허용해 주세요"); askAllFiles(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        SyncService.now(this, ps.map { it.id })
        toast("동기화를 시작합니다 · " + ps.joinToString { it.name })
        updateSyncBar()
    }

    // ================= 화면 만들기 =================
    private fun g(kind: String, color: Int = ui.text) = Glyph(kind, color)

    private fun build() {
        val menuBtn = ui.iconBtn(g("menu"), "메뉴") { openDrawer() }
        title = ui.label("Mydrive", 18f, ui.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val searchBtn = ui.iconBtn(g("search"), "검색") { askSearch() }
        val moreBtn = ui.iconBtn(g("more"), "더보기") { moreMenu() }
        topBar = ui.row(menuBtn, title.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } }, searchBtn, moreBtn).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        // 고르는 중에는 위 줄이 바뀜: ✕  고른 수/전체  [전체 선택] [사이 모두 선택]
        selText = ui.label("", 18f, ui.text, bold = true)
        selTop = ui.row(ui.iconBtn(g("close"), "선택 끝") { clearSel() }, selText.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) } },
            ui.iconBtn(g("selectall"), "전체 선택 (폴더 빼고 파일만)") { val t = allTargets(); if (t.isNotEmpty() && t.all { sel.contains(it.path) }) clearSel() else selectAll() },
            ui.iconBtn(g("range"), "사이 모두 선택") { selectRange() }).apply {
            setBackgroundColor(if (ui.dark) Color.parseColor("#16303D") else Color.parseColor("#E3EEFC")); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)); visibility = View.GONE
        }
        val top = FrameLayout(this).apply { addView(topBar); addView(selTop, FrameLayout.LayoutParams(-1, -1)) }

        // 탭 줄
        tabRow = ui.row()
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        val plus = ui.iconBtn(g("plus", ui.sub), "새 탭", 40) { newTab(Loc.HOME) }
        val tabs = ui.row(tabScroll.apply { layoutParams = ui.lp(0, weight = 1f) }, plus).apply { setBackgroundColor(ui.panel); setPadding(ui.dp(6), 0, ui.dp(4), 0) }

        // 경로 막대: ← → ↑ [경로 › …] ☆
        crumbs = ui.row()
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
        val crumbBox = FrameLayout(this).apply { background = ui.round(ui.panel2, 6); setPadding(ui.dp(4), 0, ui.dp(4), 0); addView(crumbScroll) }
        // 앞·뒤·위·즐겨찾기 단추는 두지 않음 (뒤로는 휴대폰 뒤로 단추, 위로는 경로 조각, 즐겨찾기는 ⋮ 메뉴). 홈 단추만 늘 보임
        val homeBtn = ui.iconBtn(g("home"), "홈", 40) { if (tab.loc.kind != "home") navigate(Loc.HOME) }
        pathBar = ui.row(homeBtn, crumbBox.apply { layoutParams = ui.lp(0, ui.dp(36), 1f).apply { leftMargin = ui.dp(4) } }).apply {
            setBackgroundColor(ui.bg); setPadding(ui.dp(6), ui.dp(6), ui.dp(10), ui.dp(6))
        }

        bannerText = ui.label("", 14f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }
        bannerBtn = ui.label("", 14f, ui.onAccent, bold = true).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.press(ui.round(ui.accent, 6), 6) }
        banner = ui.row(bannerText, bannerBtn).apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8)); visibility = View.GONE
            setBackgroundColor(if (ui.dark) Color.parseColor("#16303D") else Color.parseColor("#E3EEFC"))
        }

        // 동기화 줄
        syncTitle = ui.label("", 14f, ui.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        syncSub = ui.label("", 12f, ui.sub).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp(top = 1) }
        val syncBtn = ui.label("지금 동기화", 13.5f, ui.onAccent, bold = true).apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); background = ui.press(ui.round(ui.accent, 6), 6)
            contentDescription = "이 동기화 폴더를 지금 NAS와 맞춤"; setOnClickListener { syncHereNow() }
        }
        syncProg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.accent); visibility = View.GONE; layoutParams = ui.lp(h = ui.dp(4), top = 4)
        }
        syncBar = ui.col(ui.row(ImageView(this).apply { setImageDrawable(g("refresh", ui.accent)); layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) },
            ui.col(syncTitle, syncSub).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(10); rightMargin = ui.dp(8) } }, syncBtn), syncProg).apply {
            setPadding(ui.dp(14), ui.dp(7), ui.dp(10), ui.dp(7)); visibility = View.GONE
            background = ui.press(android.graphics.drawable.ColorDrawable(if (ui.dark) Color.parseColor("#16303D") else Color.parseColor("#E3EEFC")))
            contentDescription = "동기화 폴더 — 눌러서 동기화 설정 열기"; setOnClickListener { SyncActivity.open(this@MainActivity) }
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
        // 보기 방식: 누르면 목록 ↔ 바둑판, 길게 누르면 고르기
        viewBtn = ui.iconBtn(g(if (viewMode == 0) "grid" else "listview", ui.sub), "보기 방식", 36) { viewMode = if (viewMode == 0) 1 else 0; applyView() }
        viewBtn.setOnLongClickListener { viewMenu(); true }
        status = ui.row(statusText.apply { layoutParams = ui.lp(0, weight = 1f) }, viewBtn, sortBtn).apply { setBackgroundColor(ui.panel); setPadding(ui.dp(14), 0, ui.dp(6), 0) }

        lateinit var moreAct: View
        moreAct = action("more", "더 보기") { selMore(moreAct) }
        selBar = ui.row(
            action("copy", "복사") { startClip(false) }, action("move", "이동") { startClip(true) },
            action("edit", "이름 변경") { renameSel() }, action("trash", "삭제", ui.red) { deleteSel() }, moreAct).apply {
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
        xferFile = ui.label("", 11.5f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val xMore = ui.label("자세히", 13f, ui.accent).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4)) }
        xfer = ui.col(ui.row(xferText.apply { layoutParams = ui.lp(0, weight = 1f) }, xMore, xCancel), xferFile, xferBar).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); visibility = View.GONE
            background = ui.press(android.graphics.drawable.ColorDrawable(ui.panel)); setOnClickListener { showTransfers() }
        }
        xMore.setOnClickListener { showTransfers() }

        val line = { View(this).apply { setBackgroundColor(ui.line); layoutParams = ui.lp(h = 1) } }
        val main = ui.col(top, tabs, line(), pathBar, syncBar, banner, listBox.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, line(), pasteBar, xfer, selBar, status.apply { layoutParams = ui.lp(h = ui.dp(36)) }).apply { setBackgroundColor(ui.bg) }

        // 왼쪽 메뉴 (덮개 + 판)
        drawerBody = ui.col().apply { setPadding(0, ui.dp(8), 0, ui.dp(24)) }
        val drawerPanel = ScrollView(this).apply { setBackgroundColor(ui.panel); addView(drawerBody); elevation = ui.dpf(12f) }
        drawer = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; isClickable = true
            setOnClickListener { closeDrawer() }
            addView(drawerPanel, FrameLayout.LayoutParams(minOf(ui.dp(232), (resources.displayMetrics.widthPixels * 0.62).toInt()), -1, Gravity.START))
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
        "lib" -> Lib.dirOfPath(l.path)?.let { d -> if (d.isEmpty()) volTitle(l.root) else d.substringAfterLast('/') } ?: Lib.cat(Lib.keyOfPath(l.path)).title
        "nas" -> if (l.path.isEmpty()) l.root.removePrefix("~") else l.name
        "cloud" -> if (l.path.isEmpty()) fs.cloud(l).title else l.name
        "zip" -> if (l.path.isEmpty()) File(l.root).name else l.name
        else -> if (l.path.isEmpty()) volTitle(l.root) else l.name
    }
    private fun tabIcon(l: Loc): Drawable = when (l.kind) {
        "home" -> g("home", ui.sub); "trash" -> g("trash", ui.sub); "search" -> g("search", ui.sub); "usage" -> g("chart", ui.accent); "lib" -> Lib.cat(Lib.keyOfPath(l.path)).let { Glyph(it.glyph, it.color) }
        "nas" -> if (l.path.isEmpty()) g(if (l.root.startsWith("~")) "globe" else "nas", ui.accent) else FileIcon(true, l.name)
        "cloud" -> if (l.path.isEmpty()) cloudIcon(l.root) else FileIcon(true, l.name)
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

    /** fresh: 앱을 새로 켬 → 홈 탭 하나로 시작 (고정한 탭만 남김).
     *  아니면(설정을 바꾸고 돌아옴·화면 회전·시스템이 앱을 잠시 내렸다 되살림) 보던 탭 그대로 */
    private fun restoreTabs(fresh: Boolean) {
        try {
            val a = JSONArray(pf.str("tabs", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); Loc.parse(o.getString("loc"))?.let { l -> tabs += Tab(if (l.kind == "search") Loc.HOME else l, o.optBoolean("pin")).apply { scroll = o.optInt("s") } } }
        } catch (e: Exception) { }
        if (fresh) {
            val keep = tabs.filter { it.pinned && it.loc.kind != "home" }.onEach { it.scroll = 0 }
            tabs.clear(); tabs += Tab(Loc.HOME); tabs += keep
            cur = 0; saveTabs0()
            return
        }
        if (tabs.isEmpty()) tabs += Tab(Loc.HOME)
        cur = pf.int("tabCur", 0).coerceIn(0, tabs.size - 1)
    }

    /** 목록을 아직 그리기 전에 탭만 저장 */
    private fun saveTabs0() {
        val a = JSONArray(); tabs.forEach { a.put(JSONObject().put("loc", it.loc.key()).put("pin", it.pinned).put("s", 0)) }
        pf.putStr("tabs", a.toString()); pf.putInt("tabCur", cur)
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
            l.kind == "lib" -> navigate(if (Lib.dirOfPath(l.path) != null) l.copy(path = Lib.keyOfPath(l.path)) else Loc.HOME)
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
        if (l.kind == "home") {
            rows = homeRows(); items = emptyList(); showList()
            if (fs.canReadAll() && System.currentTimeMillis() - statsAt > 30_000) { statsAt = System.currentTimeMillis(); loadLibSums() }
            return
        }
        if (l.kind == "usage") { loadUsage(l, seq); return }
        // 전에 본 적 있는 곳이면 그 목록을 먼저 바로 보여 주고 뒤에서 새로 고침. 처음이면 빈 화면에 '불러오는 중'
        //   (다른 탭·폴더의 목록이 남아 보이지 않게)
        val cached = listCache[l.key()]
        if (cached != null) { items = cached; rows = fileRows(cached); showList(); statusText.text = "새로 고치는 중…" }
        else { items = emptyList(); rows = emptyList(); adapter.notifyDataSetChanged(); empty.visibility = View.VISIBLE; empty.text = "불러오는 중…"; statusText.text = "불러오는 중…" }
        net.execute {
            var err: String? = null; var out: List<Item> = emptyList(); var more = false
            try {
                if (l.kind == "lib") { val k = Lib.keyOfPath(l.path); val d = Lib.dirOfPath(l.path)
                    out = if (Lib.grouped(k) && d == null) Lib.folders(this, l.root, k, volTitle(l.root)) else Lib.list(this, l.root, k, d) }
                else if (l.kind == "search") {
                    val base = Loc.parse(l.root)!!; val q = l.path.substringBefore('\u0001'); val ext = l.path.substringAfter('\u0001', "")
                    val r = fs.search(base, q, ext, showHidden) { seq != loadSeq }; out = r.first; more = r.second
                } else out = fs.list(l, showHidden)
            } catch (e: Exception) { err = if (e is ApiException && e.status == 404) "그런 폴더가 없습니다 (지워졌거나 이름이 바뀜)" else e.message ?: e.javaClass.simpleName }
            val sorted = if (l.kind == "lib") out else sortItems(out)
            runOnUiThread {
                if (seq != loadSeq || isFinishing) return@runOnUiThread
                if (err == null) listCache[l.key()] = sorted else listCache.remove(l.key())
                if (cached != null) rememberScroll()   // 먼저 보여 준 목록에서 내려 본 자리 그대로
                items = sorted
                rows = fileRows(sorted)
                showList(err, more)
                if (l.kind == "local" && sorted.any { it.dir }) countLater(l, seq, sorted.filter { it.dir }.take(300).map { it.path })
            }
        }
    }

    /** 휴대폰 폴더 안 항목 수는 목록을 먼저 보여 준 뒤 뒤에서 셈 (폴더가 많으면 하나씩 여는 데 오래 걸림) */
    private fun countLater(l: Loc, seq: Int, dirs: List<String>) {
        val hid = showHidden
        stats.execute {
            val counts = HashMap<String, Int>()
            for (p in dirs) {
                if (seq != loadSeq) return@execute
                counts[p] = fs.childCount(File(l.root, p), hid)
            }
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread
                items = items.map { x -> counts[x.path]?.let { c -> x.copy(count = c) } ?: x }
                rows = fileRows(items)
                adapter.notifyDataSetChanged()
            }
        }
    }

    // ---------- 보기 방식: 0 목록 · 1 바둑판(작은 그림) · 2 큰 그림 ----------
    //   폴더마다 기억 (pref "viewModes" = {위치 키: 0·1·2}). 정한 적 없으면: 사진·동영상 모음이나 사진 폴더는 바둑판, 나머지는 기본(목록)
    private var viewMode: Int
        get() = if (tabs.isEmpty()) pf.int("viewMode", 0) else viewFor(tab.loc)   // 탭을 불러오기 전(화면 만들 때)에는 기본
        set(v) {
            val m = viewModes(); m.put(tab.loc.key(), v)
            if (m.length() > 400) m.remove(m.keys().next())   // 너무 많이 쌓이지 않게 오래된 것부터
            pf.putStr("viewModes", m.toString())
        }
    private fun viewModes() = try { org.json.JSONObject(pf.str("viewModes", "{}")) } catch (e: Exception) { org.json.JSONObject() }
    private fun viewFor(l: Loc): Int {
        viewModes().optInt(l.key(), -1).takeIf { it >= 0 }?.let { return it }
        val photo = Regex("(^|/)(DCIM|Pictures|Camera|Screenshots|PHOTO|사진)(/|$)", RegexOption.IGNORE_CASE)
        return when {
            l.kind == "lib" && Lib.keyOfPath(l.path) in setOf("image", "video") -> 1
            (l.kind == "local" || l.kind == "nas" || l.kind == "cloud") && (photo.containsMatchIn(l.path) || photo.containsMatchIn(l.root.removePrefix("~"))) -> 1
            else -> pf.int("viewMode", 0)   // 기본 보기 (설정)
        }
    }
    private fun gridCols() = maxOf(2, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / (if (viewMode == 2) 170 else 112)).toInt())
    private fun fileRows(l: List<Item>): List<Row> =
        if (viewMode == 0 || tab.loc.kind == "trash") l.map { Row(2, item = it) }
        else l.chunked(gridCols()).map { ch -> Row(5, tiles = ch.map { Row(2, item = it) }) }

    private fun viewMenu() {
        val names = arrayOf("목록 (자세히)", "바둑판 (작은 그림)", "큰 그림")
        AlertDialog.Builder(this).setTitle("보기 방식 — 이 폴더").setSingleChoiceItems(names, viewMode) { d, w -> d.dismiss(); if (w != viewMode) { viewMode = w; applyView() } }.show()
    }
    private fun applyView() {
        viewBtn.setImageDrawable(g(if (viewMode == 0) "grid" else "listview", ui.sub))
        if (items.isNotEmpty() && rows.none { it.type == 1 || it.type == 0 }) { rows = fileRows(items); adapter.notifyDataSetChanged() } else load()
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
        statusText.text = (if (l.kind == "search") "찾음 · " else if (l.kind == "lib" && d == 0) "새것부터 · " else "") + (if (l.kind == "lib" && d > 0) "폴더 ${d}개 · 파일 ${items.sumOf { maxOf(0, it.count) }}개 (${Fmt.size(items.sumOf { it.size })})" else (if (d > 0 || l.kind != "lib") "폴더 ${d}개, " else "") + "파일 ${f}개 (${Fmt.size(items.filter { !it.dir }.sumOf { it.size })})") + (if (more) " · 더 있음 (검색어를 좁혀 주세요)" else "")
    }

    // ---------- 처음 화면 ----------
    /** 처음 화면: 큰 아이콘 바둑판 (저장소 · 모아 보기 · NAS · 즐겨찾기 · 도구) */
    private var libSums: Map<String, Lib.Sum>? = null
    private fun loadLibSums() {
        val prim = fs.volumes().first().root.path
        stats.execute {
            val m = try { Lib.sums(this, prim) } catch (e: Exception) { null }
            val t = try { fs.trashSize() } catch (e: Exception) { null }
            runOnUiThread { if (m != null) libSums = m; trashInfo = t; refreshHome() }
        }
    }
    // 홈 한 줄 아이콘 수 (설정: 0 = 아이콘 크기에 맞춰 자동, 3·4·5) — 칸이 좁으면 아이콘을 칸에 맞게 줄임
    private fun homeCols(): Int { val n = pf.int("homeCols", 4); return if (n in 3..6) n else maxOf(3, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / (tileWant + 46)).toInt()) }
    private val tileWant by lazy { intArrayOf(58, 72, 88)[pf.int("tileSize", 1).coerceIn(0, 2)] }
    private val tileBox get() = minOf(tileWant, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / homeCols()).toInt() - 18)

    /** 홈 섹션들 (키, 제목, 아이콘 줄) — 순서·숨김은 홈 편집에서 (HomeEdit) */
    private fun homeSections(): List<Triple<String, String, List<Row>>> {
        fun sz(b: Long) = Fmt.size(b).replace(" ", "")
        val phone = mutableListOf<Row>()
        for (v in fs.volumes()) phone += Row(1, v.title, "${sz(v.total - v.free)} / ${sz(v.total)}", icon = g(if (v.removable) "sd" else "phone", ui.accent), go = Loc("local", v.root.path))
        val prim = fs.volumes().first().root.path
        phone += Row(1, "다운로드", File(prim, "Download").list()?.let { "${it.size}개" } ?: "", icon = FileIcon(true, "", "download"), go = Loc("local", prim, "Download"))
        val sums = libSums
        for (c in Lib.cats.filter { it.key != "apk" }) phone += Row(1, c.title, sums?.get(c.key)?.let { "${sz(it.size)} (${it.count})" } ?: "…", icon = Glyph(c.glyph, c.color), go = Loc("lib", prim, c.key))
        val nas = mutableListOf<Row>()
        when {
            !pf.paired -> nas += Row(1, "NAS 연결", "눌러서 연결", icon = g("nas", ui.sub), act = { startActivity(Intent(this, SetupActivity::class.java)) })
            nasErr != null && nasRoots.isEmpty() -> nas += Row(1, "NAS", "닿지 않음 · 다시", icon = g("nas", ui.red), act = { toast(nasErr ?: ""); loadNas() })
            nasRoots.isEmpty() -> nas += Row(1, "NAS", "불러오는 중", icon = g("nas", ui.sub))
            else -> {
                for (r in nasRoots.filter { it.name !in nasHidden() }) nas += Row(1, r.name, "%,d개".format(r.items), icon = g("nas", ui.accent), go = Loc("nas", r.name))
                for (w in webRoots) nas += Row(1, w.name, "웹 폴더", icon = g("globe", ui.accent), go = Loc("nas", "~" + w.name))
                nas += Row(1, "폴더 추가", "NAS 다른 폴더", icon = g("plus", ui.sub), act = { WebPick.start(this, ui, api) { loadNas() } })
            }
        }
        // 붙인 계정마다 한 칸 (같은 종류가 여럿이면 이메일로 구별) + [클라우드 추가]
        val accts = CloudDrive.all(this)
        val cloud = accts.map { c ->
            val many = accts.count { it.kind == c.kind } > 1
            Row(1, if (many) c.account.substringBefore('@') else c.title, cloudSub[c.id] ?: c.account.substringBefore('@'), icon = cloudIcon(c.id), go = Loc("cloud", c.id))
        } + Row(1, "클라우드 추가", "구글 드라이브 · 원드라이브", icon = g("plus", ui.sub), act = { addCloud() })
        val tr = trashInfo
        return listOf(Triple("phone", "휴대폰", phone), Triple("nas", "NAS", nas), Triple("cloud", "클라우드", cloud),
            Triple("fav", "즐겨찾기", favList().map { l -> Row(1, tabTitle(l), placeText(l).substringBeforeLast('/').substringAfterLast('/'), icon = tabIcon(l), go = l) }),
            Triple("tools", "도구", listOf(Row(1, "저장소 분석", "큰 파일 · 중복", icon = g("chart", ui.accent), act = { pickAnalyze() }),
                Row(1, "폴더 동기화", Sync.summary(this), icon = g("refresh", ui.accent), act = { SyncActivity.open(this) }),
                Row(1, "휴지통", tr?.let { if (it.first == 0) "비어 있음" else sz(it.second) } ?: "", icon = g("trash", ui.sub), go = Loc.TRASH),
                Row(1, "설정", "화면 · 연결", icon = g("settings", ui.sub), act = { openSettings() }))))
    }
    private fun tileKey(r: Row) = r.go?.key() ?: ("t:" + r.title)

    /** 처음 화면: 큰 아이콘 바둑판 (휴대폰 · NAS · 클라우드 · 즐겨찾기 · 도구), 순서는 홈 편집대로 */
    private fun homeRows(): List<Row> {
        val out = mutableListOf<Row>()
        if (!fs.canReadAll()) out += Row(1, "휴대폰 파일을 보려면 권한이 필요합니다", "눌러서 ‘모든 파일 접근’을 허용해 주세요", icon = g("info", ui.accent), act = { askAllFiles() })
        val cols = homeCols()
        val lay = HomeEdit.load(pf)
        var first = true
        for ((key, head, ts0) in lay.order(homeSections(), lay.secs.ifEmpty { null }) { it.first }) {
            if ("sec:$key" in lay.hide) continue
            val ts = lay.order(ts0, lay.tiles[key]) { tileKey(it) }.filter { tileKey(it) !in lay.hide }
            if (ts.isEmpty()) continue
            // 휴대폰 섹션이 맨 위면 제목 없이 (참고 앱처럼)
            if (!(first && key == "phone")) out += Row(0, head, long = { editHome() })
            first = false
            ts.chunked(cols).forEach { out += Row(4, tiles = it) }
        }
        return out
    }

    private fun editHome() = HomeEdit.show(this, ui, pf, homeSections().map { (k, t, rs) -> HomeEdit.Sec(k, t, rs.map { r -> HomeEdit.Tile(tileKey(r), r.title, r.icon) }) }) { refreshHome() }

    // ---------- 클라우드 ----------
    private val cloudSub = HashMap<String, String>()
    private var cloudStamp = -1
    private fun cloudIcon(id: String, on: Boolean = true) = Glyph("cloud", if (!on) ui.sub else if (id.startsWith("gd")) 0xFF34A853.toInt() else 0xFF2F8FE8.toInt())
    /** 클라우드 추가: 구글 드라이브(휴대폰 계정) · 구글 드라이브(브라우저) · 원드라이브 */
    private fun addCloud() {
        val od = CloudDrive.make(this, "od", "od")
        val names = arrayOf("Google Drive — 휴대폰의 구글 계정으로 (권장)", "Google Drive — 브라우저로 로그인", "OneDrive" + (if (od.configured) "" else " (준비 중)"))
        AlertDialog.Builder(this).setTitle("클라우드 추가").setItems(names) { _, w ->
            when (w) {
                0 -> CloudConnectActivity.start(this)
                1 -> connectCloud(CloudDrive.make(this, "gd", "gd"))
                2 -> connectCloud(od)
            }
        }.show()
    }
    private fun connectCloud(c: CloudDrive) {
        if (!c.configured) { toast("${c.title} 연결은 다음 판에서 열립니다 (앱 등록 중)"); return }
        AlertDialog.Builder(this).setTitle("${c.title} 연결")
            .setMessage("브라우저에서 계정을 고르고 허용을 누르세요. 끝나면 이 앱으로 돌아옵니다.\n\n파일은 휴대폰과 ${c.title} 사이에서만 오가고, 로그인 정보는 이 휴대폰에만 둡니다." +
                (if (c.kind == "gd") "\n\n‘확인되지 않은 앱’ 경고가 나오면 [고급] → [Mydrive(으)로 이동] 을 누르세요 (개인용 앱이라 구글 심사를 받지 않았습니다)." else ""))
            .setPositiveButton("연결") { _, _ -> c.connect(this) { err -> if (err != null) toast(err) else { toast("${c.title} 연결됨 · ${c.account}"); loadCloud(); refreshHome() } } }
            .setNegativeButton("취소", null).show()
    }
    private fun loadCloud() {
        for (c in CloudDrive.all(this).filter { it.connected }) net.execute {
            val q = c.quota()
            runOnUiThread { if (q != null) { cloudSub[c.id] = Fmt.size(q.first).replace(" ", "") + (if (q.second > 0) " / " + Fmt.size(q.second).replace(" ", "") else ""); refreshHome() } }
        }
    }
    private var trashInfo: Pair<Int, Long>? = null

    private fun loadNas() {
        if (!pf.paired) return
        nasErr = null
        net.execute {
            val r = try { api.roots() } catch (e: Exception) { nasErr = e.message; null }
            val w = if (r == null) null else try { api.webs() } catch (e: Exception) { emptyList() }   // 0.6.11 서버는 웹 폴더 없음
            runOnUiThread { if (r != null) nasRoots = r; if (w != null) webRoots = w; refreshHome() }
        }
    }

    private fun placeText(l: Loc) = when (l.kind) {
        "nas" -> "NAS " + l.root.removePrefix("~") + (if (l.path.isEmpty()) "" else "/" + l.path)
        "cloud" -> fs.cloud(l).title + (if (l.path.isEmpty()) "" else "/" + l.path)
        "local" -> volTitle(l.root) + (if (l.path.isEmpty()) "" else "/" + l.path)
        "zip" -> File(l.root).name + (if (l.path.isEmpty()) "" else "/" + l.path)
        else -> tabTitle(l)
    }

    // ---------- 위·아래 줄 갱신 ----------
    private fun refreshChrome() {
        val l = tab.loc
        liveStart()   // 다른 폴더로 옮겼으면 지켜보는 곳도 바꿈
        title.text = tabTitle(l)
        if (::viewBtn.isInitialized) viewBtn.setImageDrawable(g(if (viewMode == 0) "grid" else "listview", ui.sub))
        pathBar.visibility = if (l.kind == "home") View.GONE else View.VISIBLE   // 홈에서는 주소 줄 없음
        // 경로 조각
        crumbs.removeAllViews()
        val parts = mutableListOf<Pair<String, Loc>>()
        when (l.kind) {
            "local", "nas", "zip", "cloud" -> {
                parts += (if (l.kind == "nas") "NAS " + l.root.removePrefix("~") else if (l.kind == "cloud") fs.cloud(l).title else if (l.kind == "zip") File(l.root).name else volTitle(l.root)) to l.copy(path = "")
                var acc = ""; if (l.path.isNotEmpty()) for (s in l.path.split('/')) { acc = if (acc.isEmpty()) s else "$acc/$s"; parts += s to l.copy(path = acc) }
            }
            "search" -> { Loc.parse(l.root)?.let { parts += placeText(it) to it }; parts += tabTitle(l) to l }
            "lib" -> { parts += "홈" to Loc.HOME; val k = Lib.keyOfPath(l.path); parts += Lib.cat(k).title to l.copy(path = k)
                Lib.dirOfPath(l.path)?.let { parts += tabTitle(l) to l } }
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
        findSyncHere()
        // 알림 띠: 공유로 받은 파일 > 새 판
        when {
            pending.isNotEmpty() -> {
                banner.visibility = View.VISIBLE
                if (l.writable) { bannerText.text = "받은 파일 ${pending.size}개 — 이 폴더에 저장할까요?"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "여기에 저장"; bannerBtn.setOnClickListener { val u = pending; pending = emptyList(); importUris(u); refreshChrome() } }
                else { bannerText.text = "받은 파일 ${pending.size}개 — 저장할 곳을 고르세요"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "저장할 곳"; bannerBtn.setOnClickListener { pickSaveDest() } }
            }
            upd != null -> { banner.visibility = View.VISIBLE; bannerText.text = "새 판 ${upd!!.version} 이 나왔습니다"; bannerBtn.visibility = View.VISIBLE; bannerBtn.text = "설치"; bannerBtn.setOnClickListener { installUpdate(upd!!) } }
            else -> banner.visibility = View.GONE
        }
        // 아래 줄
        val selecting = sel.isNotEmpty()
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        status.visibility = if (selecting) View.GONE else View.VISIBLE
        topBar.visibility = if (selecting) View.INVISIBLE else View.VISIBLE; selTop.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) { val its = selItems(); selText.text = "${its.size}/${items.size}" + its.filter { !it.dir }.sumOf { it.size }.let { if (it > 0) "  ·  " + Fmt.size(it) else "" } }
        val c = clip
        pasteBar.visibility = if (c != null && !selecting) View.VISIBLE else View.GONE
        if (c != null) pasteText.text = (if (c.move) "이동" else "복사") + " 대기: " + (if (c.items.size == 1) c.items[0].name else "${c.items.size}개") + (if (l.writable) "" else " — 붙여 넣을 폴더로 가세요")
        fab.visibility = if (l.writable && !selecting) View.VISIBLE else View.GONE
    }

    // ================= 누르기 =================
    private fun tap(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.type == 0) return
        if (r.type == 1) { cardTap(r); return }
        tapItem(r.item!!)
    }

    private fun tapItem(it: Item) {
        if (sel.isNotEmpty()) { toggle(it); return }
        val l = tab.loc
        when {
            l.kind == "trash" -> trashMenu(it)
            l.kind == "lib" && it.dir && Lib.dirOfPath(l.path) == null -> navigate(l.copy(path = l.path + "/" + it.path))
            l.kind == "search" || l.kind == "usage" || l.kind == "lib" -> { val base = baseOf(l); if (it.dir) navigate(base.copy(path = it.path)) else openItem(base, it) }
            it.dir -> navigate(l.copy(path = it.path))
            else -> openItem(l, it)
        }
    }

    private fun longTap(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.type == 0) { r.long?.invoke(); return }
        if (r.type == 1) { cardMenu(r); return }
        if (r.type == 2) toggle(r.item!!)
    }

    private fun cardTap(r: Row) { r.act?.invoke() ?: r.go?.let { navigate(it) } }
    private fun cardMenu(r: Row) {
        r.long?.let { it(); return }
        val home = tab.loc.kind == "home"
        val g = r.go ?: run { if (home) AlertDialog.Builder(this).setTitle(r.title).setItems(arrayOf("홈 편집 (순서·숨기기)")) { _, _ -> editHome() }.show(); return }
        val opts = mutableListOf<Pair<String, () -> Unit>>("새 탭에서 열기" to { newTab(g) }, (if (favList().contains(g)) "즐겨찾기에서 빼기" else "즐겨찾기에 넣기") to { toggleFav(g) })
        if (g.kind == "cloud" && g.path.isEmpty()) { val c = fs.cloud(g)
            opts += "연결 끊기" to { AlertDialog.Builder(this).setMessage("${c.title} (${c.account}) 연결을 끊습니다. 이 휴대폰의 로그인 정보만 지우고, 클라우드의 파일은 그대로 있습니다.")
                .setPositiveButton("끊기") { _, _ -> c.disconnect(); cloudSub.remove(c.id); refreshHome() }.setNegativeButton("취소", null).show() } }
        if (g.kind == "local") opts += "저장소 분석" to { analyze(g) }
        if (g.kind == "nas" && g.path.isEmpty() && g.root.startsWith("~")) {
            val n = g.root.removePrefix("~")
            opts += "웹 폴더에서 빼기" to { AlertDialog.Builder(this).setMessage("‘$n’ 을 목록에서 뺍니다. NAS 의 파일은 그대로 있습니다.")
                .setPositiveButton("빼기") { _, _ -> bg({ api.removeWeb(n) }) { webRoots = it; refreshHome() } }.setNegativeButton("취소", null).show() }
        }
        if (g.kind == "nas" && g.path.isEmpty() && !g.root.startsWith("~") && tab.loc.kind == "home") {
            opts += "홈에서 숨기기" to { pf.putStr("nasHide", JSONArray(nasHidden() + g.root).toString()); refreshHome() }
            opts += "보일 NAS 폴더 고르기" to { pickNasShown() }
        }
        if (home) opts += "홈 편집 (순서·숨기기)" to { editHome() }
        AlertDialog.Builder(this).setTitle(r.title).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    // ---------- 홈에 보일 NAS 폴더 ----------
    private fun nasHidden(): Set<String> = try { JSONArray(pf.str("nasHide", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (e: Exception) { emptySet() }
    private fun refreshHome() { if (tab.loc.kind == "home") { rows = homeRows(); adapter.notifyDataSetChanged() } }
    private fun pickNasShown() {
        if (nasRoots.isEmpty()) { toast("NAS 폴더를 아직 불러오지 못했습니다"); return }
        val names = nasRoots.map { it.name }; val hid = nasHidden()
        val on = BooleanArray(names.size) { names[it] !in hid }
        AlertDialog.Builder(this).setTitle("홈에 보일 NAS 폴더")
            .setMultiChoiceItems(names.map { n -> nasRoots.first { it.name == n }.let { r -> if (r.share.isNotEmpty()) "$n  (NAS ${r.share})" else n } }.toTypedArray(), on) { _, i, c -> on[i] = c }
            .setPositiveButton("확인") { _, _ -> pf.putStr("nasHide", JSONArray(names.filterIndexed { i, _ -> !on[i] }).toString()); refreshHome() }
            .setNegativeButton("취소", null).show()
    }

    private fun toggle(it: Item) { if (it.dir && it.path.isEmpty()) { toast("저장소 맨 위는 고를 수 없습니다"); return }; if (!sel.remove(it.path)) sel.add(it.path); adapter.notifyDataSetChanged(); refreshChrome() }
    private fun selItems() = items.filter { sel.contains(it.path) && !(it.dir && it.path.isEmpty()) }   // 저장소 맨 위(모아 보기 폴더 줄)는 빼고
    private fun clearSel() { sel.clear(); adapter.notifyDataSetChanged(); refreshChrome(); if (livePending) { livePending = false; liveHit() } }
    private fun clearSelSilently() { sel.clear() }
    /** 고른 것 가운데 처음과 끝 사이를 모두 고름 */
    private fun selectRange() {
        val idx = items.indices.filter { sel.contains(items[it].path) }
        if (idx.size < 2) { toast("처음과 끝 두 개를 먼저 고르세요"); return }
        for (i in idx.first()..idx.last()) sel.add(items[i].path)
        adapter.notifyDataSetChanged(); refreshChrome()
    }
    /** 전체 선택이 고를 것: 파일만 (폴더는 빼고). 파일이 하나도 없는 곳이면 폴더를 모두 */
    private fun allTargets(): List<Item> = items.filter { !it.dir }.ifEmpty { items.filter { !(it.dir && it.path.isEmpty()) } }
    private fun selectAll() { allTargets().forEach { sel.add(it.path) }; adapter.notifyDataSetChanged(); refreshChrome() }

    // ---------- 열기 ----------
    private var builtinViewer get() = pf.bool("viewer", true); set(v) = pf.putBool("viewer", v)

    private fun openItem(l: Loc, it: Item) {
        val ext = it.name.substringAfterLast('.', "").lowercase()
        val vk = ViewerActivity.kindOf(it.name)
        if (builtinViewer && vk != null && (l.kind == "local" || l.kind == "nas")) {
            // 같은 폴더의 같은 종류(사진이면 사진들)를 넘겨 볼 수 있게
            val same = if (vk == ViewerActivity.IMG) items.filter { x -> !x.dir && ViewerActivity.kindOf(x.name) == vk } else listOf(it)
            if (tab.loc.browsable) {
                val dir = it.path.substringBeforeLast('/', "")
                ViewerActivity.start(this, l.copy(path = dir), same.map { x -> x.name }, same.map { x -> x.size }, same.map { x -> x.mtimeMs }, same.indexOf(it).coerceAtLeast(0), vk)
            } else {   // 모아 보기·검색·분석: 여러 폴더의 사진을 이어서 넘김 (저장소 기준 경로)
                ViewerActivity.start(this, l.copy(path = ""), same.map { x -> x.path }, same.map { x -> x.size }, same.map { x -> x.mtimeMs }, same.indexOf(it).coerceAtLeast(0), vk)
            }
            return
        }
        when (l.kind) {
            "local" -> {
                val f = File(l.root, it.path)
                if (ext == "zip") navigate(Loc("zip", f.path)) else openFile(f)
            }
            "nas", "cloud" -> fetch(l, it) { f -> if (ext == "zip") navigate(Loc("zip", f.path)) else openFile(f) }
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
            job.startFile(it.path, it.size, "열려고 받는 중"); job.files = 1
            f.parentFile?.mkdirs()
            if (l.kind == "cloud") fs.cloud(l).download(it.path, it.size, f, { d, t -> Transfers.progress(job, d, t) }) { job.cancel }
            else api.download(l.root, Entry(it.path, false, it.size, it.mtimeMs * 1_000_000), f, { d, t -> Transfers.progress(job, d, t) }, { job.cancel })
            job.filesDone = 1
            f.path
        }.apply { from = place(l); to = "휴대폰 (앱 안에 받아 둠)" })
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
        // 받는 폴더에 같은 이름이 있으면 어떻게 할지 먼저 묻기
        // 겹치는 파일은 옮기는 동안 하나씩 물음 (설정에서 정해 두었으면 그대로)
        val saved = dupPf.getInt("paste_policy", -1)
        val savedSame = dupPf.getInt("paste_same", -1)
        startPaste(c, dst, saved, savedSame)
    }

    private var askDlg: AlertDialog? = null
    /** 받는 폴더에 같은 이름이 있을 때: 건너뛰기 · 덮어쓰기 · 이름 변경 (+ 다른 모든 항목에 적용) */
    override fun onAsk(q: Transfers.Ask) {
        if (askDlg?.isShowing == true || q.answer >= 0 || isFinishing) return
        fun info(t: String, it: Item) = ui.row(ui.label(t, 13f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(64), -2) },
            ui.label("${Fmt.size(it.size)} · ${Fmt.time(it.mtimeMs * 1_000_000)}", 13.5f, ui.text)).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) }
        val all = android.widget.CheckBox(this).apply { text = "다른 모든 항목에 적용"; setTextColor(ui.text); textSize = 15f }
        val body = ui.col(
            ui.label((if (q.move) "옮길" else "붙여 넣을") + " 폴더에 이름(${q.src.name})이 같은 파일이 있습니다.", 14.5f, ui.text).apply { setPadding(0, 0, 0, ui.dp(10)) },
            info(if (q.move) "옮길 것" else "넣을 것", q.src), info("있는 것", q.dst),
            ui.label(if (q.same) "크기와 바뀐 때까지 똑같은 파일입니다." else "내용이 다른 파일입니다.", 13f, if (q.same) ui.accent else ui.sub).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) },
            all,
            ui.label("작업 전체 취소", 13.5f, ui.red).apply { setPadding(0, ui.dp(12), 0, 0); setOnClickListener { q.job.cancel = true; askDlg?.dismiss() } }
        ).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0) }
        fun answer(a: Int) { q.all = all.isChecked; q.answer = a }
        askDlg = AlertDialog.Builder(this).setTitle("파일 이름을 바꾸거나 기존 파일 덮어쓰기").setView(body).setCancelable(false)
            .setNeutralButton("건너뛰기") { _, _ -> answer(Fs.SKIP) }      // 순서: 건너뛰기 | 덮어쓰기 | 이름 변경
            .setNegativeButton("덮어쓰기") { _, _ -> answer(Fs.OVERWRITE) }
            .setPositiveButton("이름 변경") { _, _ -> answer(Fs.RENAME) }
            .show()
    }
    private fun startPaste(c: Clip, dst: Loc, policy: Int, samePolicy: Int) {
        clip = null; refreshChrome(); askNotify()
        val title = if (c.items.size == 1) c.items[0].name else "${c.items.size}개"
        val kind = when { c.move -> Transfers.Kind.MOVE; (dst.kind == "nas" || dst.kind == "cloud") && c.loc.kind != dst.kind -> Transfers.Kind.UP; dst.kind == "local" && (c.loc.kind == "nas" || c.loc.kind == "cloud") -> Transfers.Kind.OPEN; else -> Transfers.Kind.COPY }
        Transfers.add(this, Transfers.Job(kind, "paste", title) { job -> fs.transfer(job, c.loc, c.items, dst, c.move, policy, samePolicy) ?: (if (c.move) "옮겼습니다" else "붙여 넣었습니다") }
            .apply { from = place(c.loc); to = place(dst) })
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
        val where = when (l.kind) { "local" -> "휴대폰 휴지통으로 옮깁니다 (되살릴 수 있음)"; "nas" -> "NAS 휴지통으로 옮깁니다 (DSM 에서 되살릴 수 있음)"; "cloud" -> fs.cloud(l).title + " 휴지통으로 옮깁니다 (웹에서 되살릴 수 있음)"; "trash" -> "완전히 지웁니다. 되살릴 수 없습니다."; else -> "" }
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

    /** 더 보기: 아이콘이 붙은 작은 메뉴 (아래 오른쪽에서 펼침) */
    private fun selMore(anchor: View) {
        val its = selItems(); if (its.isEmpty()) return
        val l = baseOf(tab.loc); val one = its.singleOrNull()
        val opts = mutableListOf<Triple<String, String, () -> Unit>>()
        if (l.kind == "trash") opts += Triple("restore", "되살리기") { bg({ fs.restore(its) }) { n -> clearSelSilently(); load(); toast("${n}개 되살렸습니다") } }
        if (its.any { !it.dir } && l.kind != "trash") opts += Triple("share", "공유") { shareSel() }
        if (one != null && one.dir && l.browsable) {
            val fl = l.copy(path = one.path)
            opts += Triple("star", if (favList().contains(fl)) "즐겨찾기 빼기" else "즐겨찾기 추가") { toggleFav(fl); clearSel() }
            opts += Triple("pin", "홈 화면에 추가") { pinShortcut(fl, null, one.name) }
            opts += Triple("plus", "새 탭에서 열기") { clearSel(); newTab(fl) }
            if (l.kind == "local") opts += Triple("refresh", "동기화 폴더로 (NAS와 맞춤)") { clearSel(); SyncActivity.open(this, local = File(l.root, one.path).path) }
            if (l.kind == "nas") opts += Triple("refresh", "동기화 폴더로 (휴대폰과 맞춤)") { clearSel(); SyncActivity.open(this, root = l.root, rpath = one.path) }
        }
        if (one != null && !one.dir && (l.kind == "local" || l.kind == "nas")) opts += Triple("pin", "홈 화면에 추가") { pinShortcut(l.copy(path = one.path.substringBeforeLast('/', "")), one.name, one.name) }
        if (one != null && !one.dir && one.name.endsWith(".zip", true) && l.kind == "local") opts += Triple("zip", "압축 풀기") { clearSel(); extractZip(File(l.root, one.path)) }
        if (l.kind == "local" && !(one != null && one.name.endsWith(".zip", true))) opts += Triple("zip", "압축 (ZIP)") { zipSel(its) }
        if (l.kind == "zip") opts += Triple("copy", "고른 것 풀기") { startClip(false) }
        if (l.kind == "local" && its.all { it.dir }) {
            val hidden = its.all { File(File(l.root, it.path), ".nomedia").exists() }
            opts += Triple("eye", if (hidden) "갤러리에 다시 보이기" else "갤러리에서 숨기기") { noMedia(l, its, !hidden) }
        }
        if (one != null && !one.dir && (l.kind == "local" || l.kind == "nas" || l.kind == "cloud")) {
            opts += Triple("openwith", "연결 프로그램") { withFile(l, one) { f -> openFile(f) } }
            opts += Triple("openas", "다른 형식으로 열기") { openAs(l, one) }
        }
        opts += Triple("info", "속성") { props(its) }
        opts += Triple("settings", "설정") { openSettings() }
        // 메뉴판
        val box = ui.col().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)); background = ui.round(ui.panel2, 10); elevation = ui.dpf(10f) }
        val pw = android.widget.PopupWindow(box, ui.dp(236), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); elevation = ui.dpf(10f) }
        for ((ic, label, f) in opts) box.addView(ui.row(ImageView(this).apply { setImageDrawable(g(ic)); layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)) },
            ui.label(label, 16f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(18) } }).apply {
            setPadding(ui.dp(18), ui.dp(13), ui.dp(14), ui.dp(13)); background = ui.press(null); setOnClickListener { pw.dismiss(); f() } })
        box.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(236), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        pw.showAsDropDown(anchor, anchor.width - ui.dp(236), -anchor.height - box.measuredHeight)
    }

    /** 갤러리에서 숨기기: 폴더에 빈 .nomedia 파일을 두거나 지움 → 휴대폰 파일 목록(MediaStore)을 다시 읽게 함 */
    private fun noMedia(l: Loc, its: List<Item>, hide: Boolean) {
        clearSel()
        bg({
            var n = 0; val refused = mutableListOf<String>()
            val dirs = its.map { File(l.root, it.path) }
            for (d in dirs) {
                val f = File(d, ".nomedia")
                try { if (hide) { if (!f.exists()) f.createNewFile() } else f.delete() } catch (e: Exception) { }
                // 안드로이드는 DCIM/Camera 같은 기본 사진 폴더에는 .nomedia 를 못 만들게 막음 → 만든 뒤 정말 있는지 확인
                if (f.exists() == hide) n++ else refused += d.name
            }
            android.media.MediaScannerConnection.scanFile(this, dirs.map { it.path }.toTypedArray(), null, null)
            n to refused
        }) { (n, refused) ->
            listCache.clear(); statsAt = 0; load()
            toast(when {
                refused.isNotEmpty() && hide -> (if (n > 0) "폴더 ${n}개는 숨겼지만, " else "") + "‘${refused.joinToString(", ")}’ 은 안드로이드가 숨기기를 막습니다 (카메라 기본 폴더 등)"
                refused.isNotEmpty() -> "‘${refused.joinToString(", ")}’ 의 .nomedia 를 지우지 못했습니다"
                hide -> "폴더 ${n}개를 갤러리에서 숨겼습니다 (.nomedia)"
                else -> "폴더 ${n}개를 갤러리에 다시 보이게 했습니다"
            })
        }
    }

    /** 파일을 손에 넣고 다음 일 (NAS 는 받아서) */
    private fun withFile(l: Loc, it: Item, then: (File) -> Unit) { if (l.kind == "local") then(File(l.root, it.path)) else fetch(l, it, then) }

    private fun openAs(l: Loc, it: Item) {
        val kinds = listOf("텍스트" to "text/plain", "이미지" to "image/*", "동영상" to "video/*", "오디오" to "audio/*", "모든 형식" to "*/*")
        AlertDialog.Builder(this).setTitle("어떤 형식으로 열까요?").setItems(kinds.map { k -> k.first }.toTypedArray()) { _, w ->
            withFile(l, it) { f ->
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(this, f), kinds[w].second).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent.createChooser(i, f.name)) } catch (e: Exception) { toast("이 형식으로 열 앱이 없습니다") }
            }
        }.show()
    }

    private fun extractZip(z: File) {
        val v = vol(z) ?: return
        val into = Loc("local", v.root.path, z.parentFile!!.relativeTo(v.root).path)
        askNotify()
        Transfers.add(this, Transfers.Job(Transfers.Kind.COPY, "", z.name) { job -> "‘" + fs.extractAll(job, z, into) + "’ 폴더에 풀었습니다" })
    }

    /** 휴대폰 홈 화면에 바로 가기 (폴더는 그 자리를 새 탭으로, 파일은 바로 열기) */
    private fun pinShortcut(dir: Loc, file: String?, label: String) {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { toast("이 휴대폰 홈 화면은 바로 가기를 받지 않습니다"); return }
        val i = Intent(this, MainActivity::class.java).setAction(ACTION_GO).putExtra("loc", dir.key()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (file != null) i.putExtra("file", file)
        val bm = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bm)
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#202020") }.let { cv.drawCircle(96f, 96f, 96f, it) }
        FileIcon(file == null, file ?: label).apply { setBounds(36, 36, 156, 156); draw(cv) }
        val info = android.content.pm.ShortcutInfo.Builder(this, "go-" + (dir.key() + (file ?: "")).hashCode())
            .setShortLabel(label.take(24)).setIcon(android.graphics.drawable.Icon.createWithBitmap(bm)).setIntent(i).build()
        try { sm.requestPinShortcut(info, null); clearSel() } catch (e: Exception) { toast(e.message ?: "만들지 못했습니다") }
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
        AlertDialog.Builder(this).setItems(arrayOf("새 폴더", if (l.kind == "nas" || l.kind == "cloud") "휴대폰 파일 올리기" else "다른 앱의 파일 가져오기")) { _, w ->
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
        setIntent(Intent(this, MainActivity::class.java))   // 화면을 다시 만들 때(설정 바꿈 등) 같은 파일을 또 받지 않게
        if (::title.isInitialized) { refreshChrome(); list.postDelayed({ pickSaveDest() }, 700) }   // NAS 목록이 올 틈을 조금 둠
    }

    // ---------- 받은 파일을 저장할 곳 고르기 (SavePick: 아래에서 올라오는 판) ----------
    //   NAS 가 맨 위 → 자주 쓰는 곳(지난번·지금 폴더·즐겨찾기·최근) → 휴대폰 → 클라우드
    private fun pickSaveDest(tries: Int = 0) {
        if (pending.isEmpty() || isFinishing) return
        if (pf.paired && nasRoots.isEmpty() && nasErr == null && tries < 8) { list.postDelayed({ pickSaveDest(tries + 1) }, 400); return }   // NAS 목록을 조금 기다림
        val seen = HashSet<String>()
        fun P(title: String, l: Loc?, sub: String = ""): SavePick.Place? = if (l != null && l.writable && seen.add(l.key())) SavePick.Place(title, sub, l) else null
        val nas = (nasRoots.filter { it.name !in nasHidden() }.map { P(it.name, Loc("nas", it.name), "NAS 동기화 폴더 · %,d개".format(it.items)) } +
            webRoots.map { P(it.name, Loc("nas", "~" + it.name), "NAS 웹 폴더") }).filterNotNull()
        val often = (listOfNotNull(Loc.parse(pf.str("lastSave", ""))?.let { P(tabTitle(it), it, "지난번 저장한 곳 · " + placeText(it)) },
            if (tab.loc.writable) P(tabTitle(tab.loc), tab.loc, "지금 폴더 · " + placeText(tab.loc)) else null) +
            favList().map { P(tabTitle(it), it, "즐겨찾기 · " + placeText(it)) } +
            recentList().filter { it.writable }.take(4).map { P(tabTitle(it), it, "최근 · " + placeText(it)) }).filterNotNull()
        val prim = fs.volumes().first().root.path
        val phone = (listOf(P("다운로드", Loc("local", prim, "Download"), volTitle(prim) + "/Download")) + fs.volumes().map { v -> P(v.title, Loc("local", v.root.path)) }).filterNotNull()
        val cloud = CloudDrive.all(this).mapNotNull { c -> P(c.title, Loc("cloud", c.id), c.account) }
        val names = pending.map { u -> describe(u).name }
        SavePick(this, ui, fs, net, "받은 파일 ${pending.size}개 저장", names,
            listOf("NAS" to nas, "자주 쓰는 곳" to often, "휴대폰" to phone, "클라우드" to cloud),
            iconOf = { tabIcon(it) }, textOf = { placeText(it) },
            askName = { done -> ask("새 폴더", "") { n -> if (n.isNotBlank()) done(n.trim()) } },
            onPick = { l, kids ->
                val u = pending; pending = emptyList()
                pf.putStr("lastSave", l.key()); addRecent(l)
                importUris(u, l, kids); refreshChrome()
                toast("${u.size}개를 ‘${placeText(l)}’ 에 저장합니다")
            }).show()
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
    private fun importUris(uris: List<Uri>, dest: Loc = tab.loc, known: List<Item>? = null) {
        val l = dest
        if (!l.writable) { toast("저장할 폴더로 들어가 주세요"); return }
        askNotify()
        val taken = (known ?: if (dest == tab.loc) items else emptyList()).map { it.name.lowercase() }.toMutableSet()
        for (u in uris) {
            val s = describe(u)
            val name = Fmt.uniqueName(s.name) { taken.contains(it.lowercase()) }
            taken += name.lowercase()
            Transfers.add(this, Transfers.Job(if (l.kind == "nas" || l.kind == "cloud") Transfers.Kind.UP else Transfers.Kind.COPY, l.root, name) { job ->
                job.startFile(name, s.size, if (l.kind == "local") "휴대폰 폴더에 저장하는 중" else "올리는 중"); job.files = 1
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
                        if (l.kind == "cloud") { fs.cloud(l).upload(l.path, name, size, open, { d, t -> Transfers.progress(job, d, t) }) { job.cancel }; return@Job if (name != s.name) "‘$name’ 이름으로 올렸습니다" else null }
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
            }.apply { from = "다른 앱에서 보낸 파일"; to = place(l) })
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
        if (tab.loc.browsable) opts += (if (favList().contains(tab.loc)) "즐겨찾기에서 빼기" else "즐겨찾기에 넣기") to { toggleFav() }
        if (tab.loc.kind == "local" || tab.loc.kind == "nas") {
            opts += "이 폴더에서 중복 파일 찾기 (하위 폴더까지)" to { navigate(Loc("usage", tab.loc.key(), "dup")) }
            opts += "이 폴더 용량 분석" to { analyze(tab.loc) }
            opts += "이 폴더를 동기화 폴더로" to { if (tab.loc.kind == "local") SyncActivity.open(this, local = fs.file(tab.loc).path) else SyncActivity.open(this, root = tab.loc.root, rpath = tab.loc.path) }
        }
        if (tab.loc.kind == "home" && pf.paired) opts += "홈에 보일 NAS 폴더 고르기" to { pickNasShown() }
        if (tab.loc.kind == "usage") usageMenu(opts)
        opts += "보기 방식 (목록·바둑판·큰 그림)" to { viewMenu() }
        opts += "정렬" to { sortMenu() }
        opts += (if (showHidden) "숨김 파일 감추기" else "숨김 파일 보기 (점으로 시작)") to { showHidden = !showHidden; load() }
        if (tab.loc.kind == "trash") opts += "휴지통 비우기" to { emptyTrash() }
        if (tab.loc.kind == "zip") opts += "여기에 전부 풀기" to { extractHere() }
        opts += "설정" to { openSettings() }
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
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
        if (favs.isEmpty()) drawerBody.addView(ui.label("폴더를 고르고 더 보기 › 즐겨찾기 추가", 12.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(8)) })
        favs.forEach { l -> item(tabIcon(l), tabTitle(l), placeText(l), long = { toggleFav(l); openDrawer() }) { openInTab(l) } }
        head("저장소")
        item(g("home", ui.sub), "홈") { openInTab(Loc.HOME) }
        fs.volumes().forEach { v -> item(g(if (v.removable) "sd" else "phone", ui.accent), v.title, "${Fmt.size(v.free)} 남음") { openInTab(Loc("local", v.root.path)) } }
        nasRoots.filter { it.name !in nasHidden() }.forEach { r -> item(g("nas", ui.accent), r.name, "NAS ${r.share}") { openInTab(Loc("nas", r.name)) } }
        webRoots.forEach { r -> item(g("globe", ui.accent), r.name, "웹 폴더 · NAS ${r.share}") { openInTab(Loc("nas", "~" + r.name)) } }
        item(g("trash", ui.sub), "휴지통") { openInTab(Loc.TRASH) }
        item(g("chart", ui.sub), "저장소 분석", "휴대폰 · 큰 파일 · 중복 파일") { pickAnalyze() }
        item(g("refresh", ui.sub), "폴더 동기화", Sync.summary(this)) { SyncActivity.open(this) }
        val rec = recentList().filter { !favs.contains(it) }.take(8)
        if (rec.isNotEmpty()) { head("최근 위치"); rec.forEach { l -> item(g("history", ui.sub), tabTitle(l), placeText(l)) { openInTab(l) } } }
        head("")
        item(g("settings", ui.sub), "설정", "화면 · 아이콘 크기 · 연결") { openSettings() }
        drawer.visibility = View.VISIBLE
        val panel = drawer.getChildAt(0); panel.translationX = -panel.layoutParams.width.toFloat(); panel.animate().translationX(0f).setDuration(180).start()
    }
    /** 왼쪽 메뉴에서 고른 곳은 새 탭으로 (이미 그 자리를 연 탭이 있으면 그 탭으로, 지금 탭이 빈 홈이면 그 자리에서) */
    private fun openInTab(l: Loc) {
        val i = tabs.indexOfFirst { it.loc == l }
        when {
            i >= 0 -> switchTab(i)
            !pf.bool("drawerNewTab", true) -> navigate(l)
            else -> newTab(l)
        }
    }
    private fun openSettings() { saveTabs(); startActivity(Intent(this, SettingsActivity::class.java)) }
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
        xferDetail?.invoke()
        val j = Transfers.current
        if (j == null && Transfers.waiting == 0) { xfer.visibility = View.GONE; return }
        xfer.visibility = View.VISIBLE
        val what = if (j == null) "기다리는 중" else Transfers.verb(j.kind)
        val more = Transfers.waiting.let { if (it > 0) " · 기다리는 것 ${it}개" else "" }
        xferText.text = if (j == null) what + more else buildString {
            append("$what  ${j.title}")
            if (j.files > 1) append(" · ${j.filesDone}/${j.files}개")
            if (j.total > 0) append(" · ${j.done * 100 / j.total}%")
            j.speed().let { if (it > 0) append(" · ${Fmt.speed(it)}") }
            j.eta().let { if (it >= 0) append(" · ${Fmt.dur(it)} 남음") }
            append(more)
        }
        xferFile.text = if (j == null) "" else listOf(j.stage, j.file.substringAfterLast('/')).filter { it.isNotEmpty() }.joinToString(" — ")
        xferFile.visibility = if (xferFile.text.isEmpty()) View.GONE else View.VISIBLE
        xferBar.isIndeterminate = j == null || j.total <= 0
        if (j != null && j.total > 0) xferBar.progress = (j.done * 1000 / j.total).toInt()
    }

    /** 어디인지 사람이 읽을 이름: 'NAS 사진/2024', '내장 저장소/DCIM' */
    private fun place(l: Loc): String = (when (l.kind) {
        "nas" -> "NAS " + l.root.removePrefix("~")
        "cloud" -> fs.cloud(l.copy(path = "")).title
        "zip" -> File(l.root).name
        "local" -> volTitle(l.root)
        else -> tabTitle(l)
    }) + (if (l.path.isNotEmpty() && l.kind != "home") "/" + l.path else "")

    private var xferDetail: (() -> Unit)? = null

    /** 진행 중인 파일 작업 자세히: 지금 파일·어디서→어디로·개수·빠르기·남은 시간, 기다리는 작업(뺄 수 있음), 최근에 끝난 작업 */
    private fun showTransfers() {
        val body = ui.col().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        val sv = ScrollView(this).apply { addView(body) }
        fun kv(k: String, v: String) = ui.row(ui.label(k, 13f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(76), -2) },
            ui.label(v, 13.5f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }).apply { gravity = Gravity.TOP; setPadding(0, ui.dp(3), 0, ui.dp(3)) }
        fun bar(p: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = p; progressTintList = android.content.res.ColorStateList.valueOf(ui.accent) }
        fun head(t: String) = ui.label(t, 14.5f, ui.accent, bold = true).apply { setPadding(0, ui.dp(14), 0, ui.dp(4)) }
        fun kindName(k: Transfers.Kind) = when (k) { Transfers.Kind.UP -> "올리기"; Transfers.Kind.COPY -> "복사"; Transfers.Kind.MOVE -> "옮기기"; Transfers.Kind.ZIP -> "압축"; Transfers.Kind.SHARE -> "공유하려고 받기"; Transfers.Kind.SAVE -> "저장"; Transfers.Kind.LOGIN -> "로그인"; else -> "받기" }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        fun render() {
            body.removeAllViews()
            val j = Transfers.current
            body.addView(head("지금 하는 작업"))
            if (j == null) body.addView(ui.label(if (Transfers.waiting > 0) "곧 시작합니다" else "진행 중인 작업이 없습니다", 13.5f, ui.sub))
            else {
                body.addView(ui.label("${kindName(j.kind)} — ${j.title}", 15f, ui.text, bold = true).apply { setPadding(0, 0, 0, ui.dp(4)) })
                if (j.from.isNotEmpty()) body.addView(kv("어디서", j.from))
                if (j.to.isNotEmpty()) body.addView(kv("어디로", j.to))
                if (j.stage.isNotEmpty()) body.addView(kv("하는 일", j.stage))
                if (j.file.isNotEmpty()) {
                    body.addView(kv("지금 파일", j.file))
                    if (j.fileSize > 0) {
                        val fd = (j.done - j.fileBase).coerceIn(0, j.fileSize)
                        body.addView(kv("", "${Fmt.size(fd)} / ${Fmt.size(j.fileSize)}"))
                        body.addView(bar((fd * 1000 / j.fileSize).toInt()))
                    }
                }
                if (j.files > 0) body.addView(kv("파일", "${"%,d".format(j.filesDone)} / ${"%,d".format(j.files)}개 끝남" + (if (j.skipped > 0) " (건너뜀 ${j.skipped}개)" else "")))
                if (j.total > 0) {
                    body.addView(kv("전체", "${Fmt.size(j.done)} / ${Fmt.size(j.total)} (${j.done * 100 / j.total}%)"))
                    body.addView(bar((j.done * 1000 / j.total).toInt()))
                } else body.addView(kv("받은 양", Fmt.size(j.done)))
                body.addView(kv("빠르기", Fmt.speed(j.speed())))
                body.addView(kv("남은 시간", Fmt.dur(j.eta())))
                body.addView(kv("걸린 시간", Fmt.dur((System.currentTimeMillis() - j.started) / 1000) + " (${clock.format(java.util.Date(j.started))} 시작)"))
                body.addView(ui.label("이 작업 취소", 14f, ui.red, bold = true).apply {
                    setPadding(0, ui.dp(10), 0, ui.dp(4)); setOnClickListener { j.cancel = true; toast("취소하는 중…") }
                })
            }
            val q = Transfers.pending()
            if (q.isNotEmpty()) {
                body.addView(head("기다리는 작업 ${q.size}개 (차례대로)"))
                q.forEachIndexed { i, w ->
                    body.addView(ui.row(ui.col(ui.label("${i + 1}. ${kindName(w.kind)} — ${w.title}", 13.5f, ui.text),
                        ui.label(listOf(w.from, w.to).filter { it.isNotEmpty() }.joinToString(" → "), 12f, ui.sub)).apply { layoutParams = ui.lp(0, weight = 1f) },
                        ui.label("빼기", 13f, ui.red).apply { setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6)); setOnClickListener { Transfers.remove(w) } }).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) })
                }
            }
            val h = synchronized(Transfers.history) { Transfers.history.toList() }
            if (h.isNotEmpty()) {
                body.addView(head("최근에 끝난 작업"))
                h.take(15).forEach { d ->
                    val ok = d.error == null
                    val sub = listOfNotNull(
                        clock.format(java.util.Date(d.ended)) + " 끝 · " + Fmt.dur((d.ended - d.started) / 1000),
                        if (d.files > 0) "파일 ${d.filesDone}/${d.files}개" + (if (d.skipped > 0) " (건너뜀 ${d.skipped})" else "") else null,
                        if (d.total > 0) Fmt.size(d.total) else null).joinToString(" · ")
                    body.addView(ui.col(ui.label((if (ok) "✓ " else "✕ ") + "${kindName(d.kind)} — ${d.title}", 13.5f, if (ok) ui.text else ui.red),
                        ui.label(listOf(d.from, d.to).filter { it.isNotEmpty() }.joinToString(" → "), 12f, ui.sub).apply { visibility = if (d.from.isEmpty() && d.to.isEmpty()) View.GONE else View.VISIBLE },
                        ui.label(sub + ((d.error ?: d.result)?.let { if (it.contains('/') && it.length > 60) "" else "\n$it" } ?: ""), 12f, ui.sub)).apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
                }
            }
        }
        render()
        var last = 0L
        val tick = object : Runnable { override fun run() { render(); sv.postDelayed(this, 1000) } }
        xferDetail = { val now = System.currentTimeMillis(); if (now - last > 400) { last = now; render() } }
        val d = AlertDialog.Builder(this).setTitle("파일 작업").setView(sv)
            .setPositiveButton("닫기", null).setNegativeButton("모두 취소") { _, _ -> Transfers.cancelAll() }.create()
        d.setOnDismissListener { xferDetail = null; sv.removeCallbacks(tick) }
        d.show(); sv.postDelayed(tick, 1000)
    }

    override fun onFinished(job: Transfers.Job, error: String?, result: String?) {
        statsAt = 0
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
    private fun baseOf(l: Loc): Loc = when (l.kind) { "search", "usage" -> Loc.parse(l.root) ?: l; "lib" -> Loc("local", l.root); else -> l }

    private fun analyze(l: Loc) = navigate(Loc("usage", l.key(), ""))

    private fun pickAnalyze() {
        val ls = fs.volumes().map { Loc("local", it.root.path) }
        if (ls.size == 1) { analyze(ls[0]); return }
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
        if (base.kind != "local" && base.kind != "nas") { tab.loc = base; renderTabs(); load(); return }
        val r = Usage.get(base)
        fun wait(title: String, sub: String) { items = emptyList(); rows = listOf(Row(1, title, sub, icon = g("chart", ui.accent))); showList() }
        if (r == null) {
            usageErr[bk]?.let { e -> items = emptyList(); rows = listOf(Row(1, "분석하지 못했습니다", e + " — 눌러서 다시", icon = g("info", ui.red), act = { usageErr.remove(bk); load() })); showList(); return }
            usageJob(bk, bk) { say -> say(if (base.kind == "nas") "NAS 폴더 훑는 중…" else "분석 중…"); Usage.scan(base, { n -> say("훑는 중… 파일 %,d개".format(n)) }, { isFinishing }, if (base.kind == "nas") api else null) }
            wait("분석하는 중…", "파일이 많으면 1~2분 걸립니다. 다른 탭을 써도 계속합니다.")
            return
        }
        if (view == "dup" && r.dups == null) {
            usageErr["$bk|dup"]?.let { e -> items = emptyList(); rows = listOf(Row(1, "중복 파일을 찾지 못했습니다", e + " — 눌러서 다시", icon = g("info", ui.red), act = { usageErr.remove("$bk|dup"); load() })); showList(); return }
            usageJob("$bk|dup", bk) { say -> say("중복 파일 찾는 중…"); Usage.findDups(r, say, { isFinishing }, if (base.kind == "nas") api else null) }
            wait("중복 파일 찾는 중…", "크기가 같은 파일끼리 내용을 비교합니다" + (if (base.kind == "nas") " (NAS 가 직접 계산)" else "") + ". 큰 파일이 많으면 몇 분 걸립니다.")
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
                if (ds.isNotEmpty()) {
                    val rs = Usage.rules(dupPf).filter { it.on }.joinToString(" › ") { Usage.ruleNames[it.key]!!.substringBefore(" (") }
                    out += Row(0, "${ds.size}묶음 · ${Fmt.size(ds.sumOf { it[0].size * (it.size - 1) })} 줄일 수 있음")
                    out += Row(1, "규칙대로 하나씩 남기고 나머지 고르기", "남길 것: $rs", icon = g("check", ui.accent), act = { pickDupByRules() })
                    out += Row(1, "남길 것 고르는 규칙 바꾸기", "순서·켜고 끄기 · 선호 폴더 " + Usage.preferred(dupPf).size + "곳", icon = g("sort", ui.sub), act = { editDupRules() })
                }
                for (gp in ds) { out += Row(0, "같은 파일 ${gp.size}개 · 각 ${Fmt.size(gp[0].size)} · ${Fmt.size(gp[0].size * (gp.size - 1))} 줄일 수 있음"); files(gp) }
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
            mine += "규칙대로 하나씩 남기고 고르기" to { pickDupByRules() }
            mine += "남길 것 고르는 규칙…" to { editDupRules() }
            mine += "중복 다시 찾기" to { Usage.get(base)?.dups = null; load() }
        }
        if (l.path.startsWith("d:")) { val p = l.path.substring(2); mine += "이 폴더 열기" to { navigate(base.copy(path = p)) } }
        mine += "다시 분석 (처음부터 다시 셈)" to { Usage.forget(base); usageErr.remove(l.root); load() }
        opts.addAll(0, mine)
    }

    // ---------- 중복 파일: 남길 것 고르는 규칙 ----------
    private val dupPf by lazy { getSharedPreferences("mydrive", MODE_PRIVATE) }
    private fun pickDupByRules() {
        val base = Loc.parse(tab.loc.root) ?: return
        val ds = Usage.get(base)?.dups ?: emptyList()
        val drop = Usage.pickToDelete(dupPf, ds)
        sel.clear(); drop.forEach { sel.add(it.path) }
        adapter.notifyDataSetChanged(); refreshChrome()
        if (sel.isNotEmpty()) toast("묶음마다 하나씩 남기고 ${sel.size}개(${Fmt.size(drop.sumOf { it.size })})를 골랐습니다 — 확인하고 [삭제]")
    }
    /** 규칙: 켜고 끄기 + 위아래로 순서 + 선호 폴더 */
    private fun editDupRules() {
        val rules = Usage.rules(dupPf).toMutableList()
        val box = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        fun build() {
            box.removeAllViews()
            box.addView(ui.label("위에 있는 규칙부터 비교해 묶음마다 하나를 남깁니다. 끝까지 같으면 경로가 짧은 것.", 12.5f, ui.sub).apply { setPadding(0, 0, 0, ui.dp(8)) })
            rules.forEachIndexed { i, r ->
                val cb = android.widget.CheckBox(this).apply { text = Usage.ruleNames[r.key]; isChecked = r.on; setTextColor(ui.text); layoutParams = ui.lp(0, weight = 1f)
                    setOnCheckedChangeListener { _, b -> rules[i] = r.copy(on = b) } }
                val up = ui.label("▲", 15f, if (i > 0) ui.accent else ui.line).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { if (i > 0) { rules.add(i - 1, rules.removeAt(i)); build() } } }
                val dn = ui.label("▼", 15f, if (i < rules.size - 1) ui.accent else ui.line).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { if (i < rules.size - 1) { rules.add(i + 1, rules.removeAt(i)); build() } } }
                box.addView(ui.row(cb, up, dn))
            }
            val pref = Usage.preferred(dupPf)
            box.addView(ui.label("선호 폴더: " + (if (pref.isEmpty()) "없음" else pref.joinToString(" › ") { "/" + it }), 13f, ui.text).apply { setPadding(0, ui.dp(10), 0, 0) })
            box.addView(ui.label("선호 폴더 고르기", 14f, ui.accent, bold = true).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)); setOnClickListener { pickPreferred { build() } } })
        }
        build()
        AlertDialog.Builder(this).setTitle("남길 것 고르는 규칙").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ -> Usage.saveRules(dupPf, rules); load() }.setNegativeButton("취소", null).show()
    }
    /** 선호 폴더: 중복 결과에 나온 폴더들 중에서 고르고 순서 (앞일수록 먼저 남김) */
    private fun pickPreferred(done: () -> Unit) {
        val base = Loc.parse(tab.loc.root) ?: return
        val dirs = (Usage.get(base)?.dups ?: emptyList()).flatten().map { it.path.substringBeforeLast('/', "") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val cur = Usage.preferred(dupPf).toMutableList()
        val all = (cur + dirs).distinct()
        if (all.isEmpty()) { toast("중복 파일을 먼저 찾아 주세요"); return }
        val names = all.map { "/" + it }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it] in cur }
        AlertDialog.Builder(this).setTitle("선호 폴더 (고른 순서대로 먼저 남김)").setMultiChoiceItems(names, checked) { _, w, b ->
            if (b) { if (all[w] !in cur) cur += all[w] } else cur -= all[w]
        }.setPositiveButton("확인") { _, _ -> Usage.savePreferred(dupPf, cur); done() }.setNegativeButton("취소", null).show()
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
            runOnUiThread { statsAt = 0; r.onSuccess(then).onFailure { e -> AlertDialog.Builder(this).setMessage(if (e is ApiException && e.status == 409) "같은 이름이 있습니다" else e.message ?: e.javaClass.simpleName).setPositiveButton("닫기", null).show() } }
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
    private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(8 shl 20, 64 shl 20)) { override fun sizeOf(k: String, b: android.graphics.Bitmap) = b.byteCount }
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbMiss = HashSet<String>()
    private val showThumbs by lazy { pf.bool("thumbs", true) }

    /** 사진·동영상 미리보기. want = 보일 크기(px). 바둑판은 크게, 목록은 작게 — 크기마다 따로 기억 */
    private fun thumb(iv: ImageView, it0: Item, want: Int = ui.dp(68), square: Boolean = true, onSet: (() -> Unit)? = null) {
        val l = tab.loc
        // 모아 보기의 폴더 줄: 그 폴더의 가장 새 사진·동영상을 미리보기로
        val src = it0.extra.removePrefix("!")
        val it = if (it0.dir && l.kind == "lib" && src.isNotEmpty()) Item(src.substringAfterLast('/'), false, 0, it0.mtimeMs, src) else it0
        val base = baseOf(l).takeIf { b -> (b.kind == "local" || b.kind == "nas" || b.kind == "cloud") && showThumbs } ?: run { iv.tag = null; return }
        val k = FileIcon.kindOf(it.name)
        val ext = it.name.substringAfterLast('.', "").lowercase()
        // NAS: 서버가 만든 작은 그림 (JPEG·PNG·GIF). 휴대폰: 사진·동영상
        if (it.dir || (base.kind == "nas" && ext !in setOf("jpg", "jpeg", "png", "gif")) || (base.kind == "cloud" && !it.extra.startsWith("https://")) || (base.kind != "cloud" && k != "image" && k != "video")) { iv.tag = null; return }
        val px = when { want <= ui.dp(80) -> ui.dp(68); want <= 360 -> 360; else -> 560 }   // 크기 단계 (같은 그림을 여러 번 만들지 않게)
        val key = base.kind + ":" + base.root + ":" + it.path + ":" + it.mtimeMs + ":" + px
        iv.tag = key
        thumbs.get(key)?.let { b -> iv.setImageBitmap(b); onSet?.invoke(); return }
        if (key in thumbMiss) return
        thumbPool.execute {
            val still = iv.tag == key                 // 이미 화면 밖으로 지나갔으면 받지 않음
            val b = if (!still) null else try {
                if (base.kind == "cloud") fs.cloud(base).thumb(it.extra.replace(Regex("=s\\d+$"), "=s" + minOf(640, px)))?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                else if (base.kind == "nas") api.thumb(base.root, it.path, minOf(640, maxOf(160, px * 2 / 3 * 2)))?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                else { val f = File(base.root, it.path); val sz = android.util.Size(px, px)
                    if (k == "image") android.media.ThumbnailUtils.createImageThumbnail(f, sz, null) else android.media.ThumbnailUtils.createVideoThumbnail(f, sz, null) }
            } catch (e: Exception) { null }
            val r = b?.let { if (square) cropRound(it) else it }
            runOnUiThread { if (r == null) { if (still) thumbMiss += key } else { thumbs.put(key, r); if (iv.tag == key) { iv.setImageBitmap(r); onSet?.invoke() } } }
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
        override fun getViewTypeCount() = 6
        override fun getItemViewType(p: Int) = rows[p].type
        override fun isEnabled(p: Int) = rows[p].type == 1 || rows[p].type == 2 || (rows[p].type == 0 && rows[p].long != null)

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[p]
            return when (r.type) {
                0 -> ((convert as? TextView) ?: ui.label("", 12.5f, ui.sub, bold = true)).apply { text = r.title
                    if (tab.loc.kind == "home") setPadding(ui.dp(18), ui.dp(8), ui.dp(16), ui.dp(2)) else setPadding(ui.dp(18), ui.dp(16), ui.dp(16), ui.dp(6)) }
                1 -> card(convert, r)
                3 -> segCard(convert, r)
                4 -> tileRow(convert, r)
                5 -> gridRow(convert, r)
                else -> fileRow(convert, r.item!!)
            }
        }

        // 바둑판 보기 한 줄: 그림(사진은 미리보기, 나머지는 아이콘) + 이름. 고르면 테두리와 ✓
        private fun gridRow(convert: View?, r: Row): View {
            val cols = gridCols()
            val big = viewMode == 2
            val v = (convert as? LinearLayout)?.takeIf { it.childCount == cols && it.tag == big } ?: ui.row().apply {
                tag = big; setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3)); gravity = Gravity.TOP
                val cell = (resources.displayMetrics.widthPixels - ui.dp(12)) / cols - ui.dp(6)
                repeat(cols) {
                    val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = FrameLayout.LayoutParams(-1, -1) }
                    val check = ImageView(this@MainActivity).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(5), ui.dp(5), 0) } }
                    val box = FrameLayout(this@MainActivity).apply { addView(img); addView(check); layoutParams = LinearLayout.LayoutParams(cell, cell); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
                    val name = ui.label("", if (big) 13.5f else 12.5f, ui.text).apply { gravity = Gravity.CENTER_HORIZONTAL; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; layoutParams = ui.lp(cell).apply { topMargin = ui.dp(3) } }
                    addView(ui.col(box, name).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }; setPadding(0, ui.dp(4), 0, ui.dp(6)); background = ui.press(null, 10) })
                }
            }
            for (i in 0 until cols) {
                val cell = v.getChildAt(i) as LinearLayout; val t = r.tiles.getOrNull(i)?.item
                cell.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
                if (t == null) { cell.setOnClickListener(null); cell.setOnLongClickListener(null); continue }
                val box = cell.getChildAt(0) as FrameLayout
                val img = box.getChildAt(0) as ImageView; val check = box.getChildAt(1) as ImageView
                val on = sel.contains(t.path)
                box.background = if (on) ui.round(ui.select, 12, ui.accent) else ui.round(ui.panel2, 12)
                img.setImageDrawable(FileIcon(t.dir, t.name, if (t.dir && t.name == "Download" && tab.loc.kind == "local" && tab.loc.path.isEmpty()) "download" else ""))
                val icPad = box.layoutParams.width / 5
                img.setPadding(icPad, icPad, icPad, icPad)
                thumb(img, t, box.layoutParams.width, square = true) { img.setPadding(0, 0, 0, 0) }
                check.visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE
                check.setImageDrawable(if (on) g("check", ui.onAccent) else null)
                check.background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.accent) } else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55000000); setStroke(ui.dp(2), Color.WHITE) }
                (cell.getChildAt(1) as TextView).text = if (t.dir && tab.loc.kind == "lib") "${t.name} (${t.count})" else t.name
                cell.setOnClickListener { tapItem(t) }
                cell.setOnLongClickListener { toggle(t); true }
            }
            return v
        }

        // 처음 화면 바둑판 한 줄: 테두리 둥근 칸 안 큰 아이콘 + 이름 + 작은 글
        private fun tileRow(convert: View?, r: Row): View {
            val cols = homeCols()
            val v = (convert as? LinearLayout)?.takeIf { it.childCount == cols } ?: ui.row().apply {
                setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1)); gravity = Gravity.TOP
                repeat(cols) {
                    val box = FrameLayout(this@MainActivity).apply {
                        background = GradientDrawable().apply { cornerRadius = ui.dpf(20f); setColor(ui.panel); setStroke(ui.dp(1), if (ui.dark) 0xFF4A4A4A.toInt() else ui.line) }
                        layoutParams = LinearLayout.LayoutParams(ui.dp(tileBox), ui.dp(tileBox)).apply { gravity = Gravity.CENTER_HORIZONTAL }
                        addView(ImageView(this@MainActivity), FrameLayout.LayoutParams(ui.dp(tileBox * 53 / 100), ui.dp(tileBox * 53 / 100), Gravity.CENTER)) }
                    val name = ui.label("", 14f, ui.text).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp().apply { topMargin = ui.dp(8) } }
                    val sub = ui.label("", 11.5f, ui.sub).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp().apply { topMargin = ui.dp(1) } }
                    addView(ui.col(box, name, sub).apply { layoutParams = ui.lp(0, weight = 1f); setPadding(ui.dp(1), ui.dp(5), ui.dp(1), ui.dp(5)); background = ui.press(null, 14) })
                }
            }
            for (i in 0 until cols) {
                val cell = v.getChildAt(i) as LinearLayout; val t = r.tiles.getOrNull(i)
                cell.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
                if (t == null) { cell.setOnClickListener(null); cell.setOnLongClickListener(null); continue }
                ((cell.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setImageDrawable(t.icon)
                (cell.getChildAt(1) as TextView).text = t.title
                (cell.getChildAt(2) as TextView).apply { text = t.sub; visibility = if (t.sub.isEmpty()) View.INVISIBLE else View.VISIBLE }
                cell.setOnClickListener { cardTap(t) }
                cell.setOnLongClickListener { cardMenu(t); true }
            }
            return v
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
                val isz = intArrayOf(28, 34, 44)[pf.int("rowIcon", 1).coerceIn(0, 2)]
                val icon = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(isz + 8), ui.dp(isz + 8)); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); background = ui.press(null, (isz + 8) / 2); contentDescription = "고르기" }
                val name = ui.label("", 15f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                val sub = ui.label("", 12f, ui.sub).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
                val check = ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)) }
                ui.row(icon, ui.col(name, sub).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) } }, check).apply {
                    val gap = intArrayOf(2, 6, 11)[pf.int("rowGap", 0).coerceIn(0, 2)]   // 설정의 ‘목록 줄 간격’
                    setPadding(ui.dp(10), ui.dp(gap), ui.dp(14), ui.dp(gap)); minimumHeight = ui.dp(isz + 8 + gap * 2)
                }
            }
            val on = sel.contains(it.path)
            v.setBackgroundColor(if (on) ui.select else Color.TRANSPARENT)
            val iconV = v.getChildAt(0) as ImageView
            // 아이콘을 누르면 열지 않고 고름 (그다음부터는 줄을 눌러도 고르기)
            iconV.setOnClickListener { _ -> toggle(it) }
            iconV.setImageDrawable(FileIcon(it.dir, it.name, if (it.dir && it.name == "Download" && tab.loc.kind == "local" && tab.loc.path.isEmpty()) "download" else ""))
            thumb(iconV, it)
            val box = v.getChildAt(1) as LinearLayout
            (box.getChildAt(0) as TextView).text = it.name
            val l = tab.loc
            (box.getChildAt(1) as TextView).text = when {
                l.kind == "trash" -> prettyAbs(it.extra.substringBeforeLast('/')) + "  ·  " + Fmt.time(it.mtimeMs * 1_000_000)
                l.kind == "search" -> "/" + it.path.substringBeforeLast('/', "") + "  ·  " + (if (it.dir) "폴더" else Fmt.size(it.size))
                l.kind == "lib" && it.dir -> (if (it.extra.startsWith("!")) "갤러리 숨김  ·  " else "") + "${it.count}개  ·  " + Fmt.size(it.size) + "  ·  /" + it.path
                l.kind == "usage" || l.kind == "lib" -> Fmt.size(it.size) + "  ·  " + Fmt.time(it.mtimeMs * 1_000_000).take(10) + "  ·  /" + it.path.substringBeforeLast('/', "")
                it.dir && isSyncRoot(l, it) -> android.text.SpannableString("⇅ 동기화 폴더  ·  " + Fmt.time(it.mtimeMs * 1_000_000) + (if (it.count >= 0) "  ·  ${it.count}개" else "")).apply {
                    setSpan(android.text.style.ForegroundColorSpan(ui.accent), 0, 8, 0); setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 8, 0) }
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
