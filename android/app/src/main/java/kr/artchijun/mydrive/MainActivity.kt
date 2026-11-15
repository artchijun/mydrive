package kr.artchijun.mydrive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

// 둘러보기: 동기화 폴더 → 폴더 → 파일 (누르면 열기 / 휴대폰에 저장), [올리기], 다른 앱의 '공유'로 받은 파일 올리기
class MainActivity : Activity(), Transfers.Listener {
    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var api: Api
    private val net = Executors.newSingleThreadExecutor()

    private var root: String? = null          // null = 동기화 폴더 목록
    private var path = ""                     // 그 안의 폴더 ("" = 맨 위)
    private var roots: List<RootInfo> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var loadSeq = 0
    private var pending: List<Uri> = emptyList()   // '공유'로 받아 올릴 곳을 기다리는 파일
    private var upd: Update.Info? = null

    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerBtn: Button
    private lateinit var xfer: LinearLayout
    private lateinit var xferText: TextView
    private lateinit var xferBar: ProgressBar
    private lateinit var upBtn: Button
    private val adapter = Adapter()
    private val coll = Collator.getInstance(Locale.KOREAN)

    private val PICK = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pf = Prefs(this)
        if (!pf.paired) {   // 아직 연결 안 함 → 연결 화면 (공유로 들어온 것도 넘겨줌)
            startActivity(Intent(this, SetupActivity::class.java).apply { intent?.let { action = it.action; type = it.type; putExtras(it); clipData = it.clipData; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } })
            finish(); return
        }
        ui = Ui(this); api = pf.api()
        build()
        savedInstanceState?.let { root = it.getString("root"); path = it.getString("path", "") }
        takeShare(intent)
        load()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("root", root); out.putString("path", path) }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); takeShare(intent) }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        Transfers.listener = this; onTransfers()
        if (System.currentTimeMillis() - pf.lastUpdateCheck > 6 * 3600_000L) checkUpdate(false)
    }

    override fun onPause() { super.onPause(); if (Transfers.listener === this) Transfers.listener = null }

    // ---------- 화면 ----------
    private fun build() {
        back = ui.label("←", 22f, ui.text).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); setOnClickListener { goUp() } }
        title = ui.label("Mydrive", 18f, ui.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
        val refresh = ui.label("⟳", 22f, ui.text).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setOnClickListener { load() } }
        val more = ui.label("⋮", 22f, ui.text).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(16), ui.dp(8)); setOnClickListener { menu() } }
        val top = ui.row(back, title.apply { layoutParams = ui.lp(0, weight = 1f) }, refresh, more).apply {
            setBackgroundColor(ui.card); setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        bannerText = ui.label("", 14f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }
        bannerBtn = ui.button("", true) {}.apply { textSize = 14f }
        banner = ui.row(bannerText, bannerBtn).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(if (ui.dark) 0xFF1E2A44.toInt() else 0xFFE8EEFF.toInt()) }
        }
        status = ui.label("", 14f, ui.sub).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4)); visibility = View.GONE }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter; divider = null; setBackgroundColor(ui.bg)
            setOnItemClickListener { _, _, pos, _ -> tap(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> longTap(pos); true }
        }
        xferText = ui.label("", 13f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        xferBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val xCancel = ui.label("취소", 14f, ui.red).apply { setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener { Transfers.cancelAll() } }
        xfer = ui.col(ui.row(xferText.apply { layoutParams = ui.lp(0, weight = 1f) }, xCancel), xferBar).apply {
            setBackgroundColor(ui.card); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10)); visibility = View.GONE
        }
        upBtn = ui.button("＋ 올리기") { pickFiles() }.apply { elevation = ui.dp(4).toFloat(); setPadding(ui.dp(20), 0, ui.dp(20), 0) }
        val listBox = FrameLayout(this).apply {
            addView(list)
            addView(upBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(18), ui.dp(18)) })
        }
        val rootView = ui.col(top, banner, status, listBox.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, xfer).apply { setBackgroundColor(ui.bg) }
        ui.insets(rootView)
        setContentView(rootView)
    }

    private fun showStatus(s: String?) { status.text = s ?: ""; status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }

    private fun refreshChrome() {
        title.text = if (root == null) "Mydrive" else if (path.isEmpty()) root else "$root/$path"
        back.visibility = if (root == null) View.INVISIBLE else View.VISIBLE
        upBtn.visibility = if (root != null && pending.isEmpty()) View.VISIBLE else View.GONE
        when {
            pending.isNotEmpty() -> {
                banner.visibility = View.VISIBLE
                bannerText.text = if (root == null) "올릴 파일 ${pending.size}개 — 올릴 폴더로 들어가세요" else "올릴 파일 ${pending.size}개 — 이 폴더에 올릴까요?"
                bannerBtn.text = if (root == null) "취소" else "여기에 올리기"
                bannerBtn.setOnClickListener { if (root == null) { pending = emptyList(); refreshChrome() } else { val u = pending; pending = emptyList(); upload(u); refreshChrome() } }
            }
            upd != null -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "새 판 ${upd!!.version}이 있습니다"
                bannerBtn.text = "설치"; bannerBtn.setOnClickListener { installUpdate(upd!!) }
            }
            else -> banner.visibility = View.GONE
        }
    }

    // ---------- 목록 ----------
    private fun load() {
        val r = root; val p = path; val seq = ++loadSeq
        refreshChrome(); showStatus("불러오는 중…")
        net.execute {
            try {
                if (r == null) { val rs = api.roots(); runOnUiThread { if (seq == loadSeq) { roots = rs; entries = emptyList(); adapter.notifyDataSetChanged(); showStatus(if (rs.isEmpty()) "동기화 폴더가 없습니다" else null) } } }
                else {
                    val es = api.list(r, p).sortedWith { a, b -> if (a.dir != b.dir) (if (a.dir) -1 else 1) else coll.compare(a.name, b.name) }
                    runOnUiThread { if (seq == loadSeq) { entries = es; adapter.notifyDataSetChanged(); list.setSelection(0); showStatus(if (es.isEmpty()) "빈 폴더" else null) } }
                }
            } catch (e: ApiException) {
                runOnUiThread {
                    if (seq != loadSeq) return@runOnUiThread
                    if (e.status == 401) { showStatus("이 휴대폰의 연결이 끊겼습니다 (관리 화면에서 연결을 끊었거나 토큰이 바뀜). ⋮ → 연결 다시 하기"); return@runOnUiThread }
                    if (e.status == 404 && r != null) { showStatus("폴더가 없어졌습니다"); return@runOnUiThread }
                    showStatus(e.message)
                }
            } catch (e: Exception) { runOnUiThread { if (seq == loadSeq) showStatus(e.message ?: "불러오지 못함") } }
        }
    }

    private fun goUp() {
        if (root == null) return
        if (path.isEmpty()) root = null else path = path.substringBeforeLast('/', "")
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (root != null) goUp() else super.onBackPressed() }

    private fun tap(pos: Int) {
        if (root == null) { root = roots[pos].name; path = ""; load(); return }
        val e = entries[pos]
        if (e.dir) { path = e.path; load(); return }
        val cached = Transfers.openFile(this, root!!, e.path)
        if (cached.exists() && cached.length() == e.size && kotlin.math.abs(cached.lastModified() - e.mtimeNs / 1_000_000) < 2000) { open(cached); return }
        fileMenu(e)
    }

    private fun longTap(pos: Int) { if (root != null && !entries[pos].dir) fileMenu(entries[pos]) }

    private fun fileMenu(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(Fmt.size(e.size) + "  ·  " + Fmt.time(e.mtimeNs))
            .setPositiveButton("열기") { _, _ -> download(e, save = false) }
            .setNeutralButton("휴대폰에 저장") { _, _ -> download(e, save = true) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun open(f: File) {
        try { startActivity(Transfers.viewIntent(this, f)) }
        catch (x: Exception) { Toast.makeText(this, "이 파일을 열 앱이 없습니다 — '휴대폰에 저장'으로 받아 보세요", Toast.LENGTH_LONG).show() }
    }

    // ---------- 받기 ----------
    private fun download(e: Entry, save: Boolean) {
        askNotify()
        val r = root!!
        Transfers.add(this, Transfers.Job(if (save) Transfers.Kind.SAVE else Transfers.Kind.OPEN, r, e.name) { job ->
            val f = Transfers.openFile(this, r, e.path); f.parentFile?.mkdirs()
            api.download(r, e, f, { d, t -> Transfers.progress(job, d, t) }, { job.cancel })
            if (save) Transfers.saveToDownloads(this, f, e.name) else f.path
        })
    }

    // ---------- 올리기 ----------
    private fun pickFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri } ?: data.data?.let { uris += it }
        upload(uris)
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
        if (::ui.isInitialized) refreshChrome()
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
        } catch (e: Exception) { }
        if (size < 0) size = try { contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1 } catch (e: Exception) { -1 }
        if (mt <= 0) mt = System.currentTimeMillis() * 1_000_000
        name = name.replace('/', '_').replace('\\', '_')
        return Src(u, name, size, mt)
    }

    private fun upload(uris: List<Uri>) {
        val r = root ?: return
        askNotify()
        val dir = path
        val taken = entries.map { it.name.lowercase() }.toMutableSet()
        for (u in uris) {
            val s = describe(u)
            val name = Fmt.uniqueName(s.name) { taken.contains(it.lowercase()) }
            taken += name.lowercase()
            Transfers.add(this, Transfers.Job(Transfers.Kind.UP, r, name) { job ->
                var n = name
                for (attempt in 0 until 20) {
                    val rel = if (dir.isEmpty()) n else "$dir/$n"
                    try {
                        api.upload(r, rel, s.size, s.mtimeNs, { contentResolver.openInputStream(s.uri) ?: throw java.io.IOException("파일을 열 수 없음") },
                            { d, t -> Transfers.progress(job, d, t) }, { job.cancel })
                        return@Job if (n != s.name) "‘$n’ 이름으로 올렸습니다" else null
                    } catch (e: ApiException) {
                        if (e.status != 409) throw e
                        // 그사이 같은 이름이 생김 → 다음 번호로
                        val now = api.list(r, dir).map { it.name.lowercase() }.toSet()
                        n = Fmt.uniqueName(s.name) { now.contains(it.lowercase()) }
                    }
                }
                throw java.io.IOException("올릴 이름을 정하지 못함")
            })
        }
    }

    // ---------- 옮기기 진행 ----------
    override fun onTransfers() {
        val j = Transfers.current
        if (j == null && Transfers.waiting == 0) { xfer.visibility = View.GONE; return }
        xfer.visibility = View.VISIBLE
        val what = when (j?.kind) { Transfers.Kind.UP -> "올리는 중"; null -> "기다리는 중"; else -> "받는 중" }
        val more = Transfers.waiting.let { if (it > 0) "  (기다리는 것 ${it}개)" else "" }
        xferText.text = if (j == null) what + more else "$what  ${j.title}  ${Fmt.size(j.done)} / ${Fmt.size(j.total)}$more"
        xferBar.isIndeterminate = j == null || j.total <= 0
        if (j != null && j.total > 0) xferBar.progress = (j.done * 1000 / j.total).toInt()
    }

    override fun onFinished(job: Transfers.Job, error: String?, result: String?) {
        if (error != null) { if (error != "취소함") Toast.makeText(this, "${job.title}: $error", Toast.LENGTH_LONG).show(); return }
        when (job.kind) {
            Transfers.Kind.OPEN -> result?.let { if (it.endsWith(".apk")) installApk(File(it)) else open(File(it)) }
            Transfers.Kind.SAVE -> Toast.makeText(this, "저장함: $result", Toast.LENGTH_LONG).show()
            Transfers.Kind.UP -> {
                result?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                if (job.root == root && Transfers.waiting == 0) load()
            }
        }
    }

    private fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    // ---------- 메뉴 · 새 판 ----------
    private fun menu() {
        val items = arrayOf("새로 고침", "새 판 확인", "연결 다시 하기 (주소·계정)", "정보")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> load()
                1 -> checkUpdate(true)
                2 -> { startActivity(Intent(this, SetupActivity::class.java)) }
                3 -> AlertDialog.Builder(this).setTitle("Mydrive ${Update.current(this)}")
                    .setMessage("DSM 사용자: ${pf.user}\n지금 쓰는 주소: ${api.active ?: "(아직 연결 전)"}\n집 안 주소: ${pf.home}\n인터넷 주소: ${pf.outside.ifEmpty { "(없음)" }}")
                    .setPositiveButton("닫기", null).show()
            }
        }.show()
    }

    private fun checkUpdate(manual: Boolean) {
        pf.lastUpdateCheck = System.currentTimeMillis()
        Executors.newSingleThreadExecutor().execute {
            val info = try { Update.latest() } catch (e: Exception) { null }
            runOnUiThread {
                if (info != null && Update.newer(info.version, Update.current(this))) { upd = info; refreshChrome() }
                else if (manual) Toast.makeText(this, if (info == null) "새 판을 확인하지 못했습니다" else "최신 판입니다 (${Update.current(this)})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installUpdate(info: Update.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "‘이 출처의 앱 설치 허용’을 켜고 돌아와 [설치]를 다시 눌러 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Transfers.add(this, Transfers.Job(Transfers.Kind.OPEN, "", "새 판 ${info.version}") { job ->
            Update.download(this, info, { d, t -> Transfers.progress(job, d, t) }, { job.cancel }).path
        })
    }

    private fun installApk(f: File) { try { startActivity(Transfers.viewIntent(this, f)) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }

    // ---------- 목록 칸 ----------
    private inner class Adapter : BaseAdapter() {
        override fun getCount() = if (root == null) roots.size else entries.size
        override fun getItem(p: Int): Any = if (root == null) roots[p] else entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val v = (convert as? LinearLayout) ?: run {
                val icon = ui.label("", 22f).apply { width = ui.dp(40); gravity = Gravity.CENTER }
                val name = ui.label("", 16f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                val sub = ui.label("", 12f, ui.sub).apply { setSingleLine() }
                ui.row(icon, ui.col(name, sub).apply { layoutParams = ui.lp(0, weight = 1f) }).apply {
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(16), ui.dp(10)); minimumHeight = ui.dp(60)
                }
            }
            val icon = v.getChildAt(0) as TextView
            val box = v.getChildAt(1) as LinearLayout
            val name = box.getChildAt(0) as TextView; val sub = box.getChildAt(1) as TextView
            if (root == null) {
                val r = roots[p]
                icon.text = "🗂"; name.text = r.name
                sub.text = (if (r.share.isNotEmpty()) "NAS ${r.share} · " else "") + "%,d개".format(r.items)
            } else {
                val e = entries[p]
                icon.text = if (e.dir) "📁" else iconFor(e.name)
                name.text = e.name
                sub.text = if (e.dir) Fmt.time(e.mtimeNs) else Fmt.size(e.size) + "  ·  " + Fmt.time(e.mtimeNs)
            }
            return v
        }
    }

    private fun iconFor(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "heic", "webp", "bmp", "tif", "tiff" -> "🖼"
        "mp4", "mov", "avi", "mkv", "m4v" -> "🎞"
        "mp3", "m4a", "wav", "flac", "aac" -> "🎵"
        "pdf" -> "📕"
        "zip", "7z", "rar", "gz" -> "🗜"
        "doc", "docx", "hwp", "hwpx", "txt", "md" -> "📝"
        "xls", "xlsx", "csv" -> "📊"
        "ppt", "pptx" -> "📽"
        else -> "📄"
    }
}
