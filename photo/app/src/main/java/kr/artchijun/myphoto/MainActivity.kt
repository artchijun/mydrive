package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 첫 화면: 아래 탭 [사진 · 폴더 · 인물 · 장소 · 백업]. 위 줄은 제목 + 설정.
 *   사진: 공유 공간 전체를 날짜별 격자로 (새것부터).  폴더: 시놀로지 포토의 폴더 그대로.
 *   인물·장소: 시놀로지가 묶어 둔 것.  사진을 길게 누르면 고르기 → 즐겨찾기·지우기
 *   사진 탭 위 칩 [♥ 즐겨찾기]: 즐겨찾기한 사진만 모아 봄
 */
class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var ph: Photos
    private val net = Executors.newFixedThreadPool(5)   // 사진 목록 쪽을 여럿 함께 받음
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var tabBar: LinearLayout
    private var tab = 0
    /** 탭마다 화면 쌓기 (폴더 → 하위 폴더 → 사진) */
    private val stacks = Array(4) { ArrayList<Pair<String, View>>() }
    private val ns = Photos.TEAM
    private val tabs = listOf("image" to "사진", "folder" to "폴더", "person" to "인물", "place" to "장소", "backup" to "백업")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); ph = Photos(this)
        if (!Prefs(this).loggedIn) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel

        title = ui.label("사진", 19f, ui.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
        topBar = ui.row(
            ui.iconBtn(Glyph("back", ui.text), "뒤로") { back() }.apply { visibility = View.GONE; tag = "back" },
            title.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(12) } },
            ui.iconBtn(Glyph("refresh", ui.text), "새로 고침") { refresh() },
            ui.iconBtn(Glyph("more", ui.text), "메뉴") {}.also { b -> b.setOnClickListener { menu(b) } },
        ).apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); minimumHeight = ui.dp(56) }
        selCount = ui.label("", 17f, ui.text, bold = true)
        selBar = ui.row(
            ui.iconBtn(Glyph("close", ui.text), "고르기 끝") { grid()?.clearSelection() },
            selCount.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) } },
            ui.iconBtn(Glyph("selectall", ui.text), "모두 고르기") { grid()?.selectAll() },
            ui.iconBtn(Glyph("heart", ui.text), "즐겨찾기") { favSelected() },
            ui.iconBtn(Glyph("trash", ui.text), "지우기") { deleteSelected() },
        ).apply { setBackgroundColor(ui.select); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); minimumHeight = ui.dp(56); visibility = View.GONE }
        content = FrameLayout(this).apply { setBackgroundColor(ui.bg) }
        tabBar = ui.row().apply { setBackgroundColor(ui.panel); setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        tabs.forEachIndexed { i, (g, name) ->
            tabBar.addView(ui.col(ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL } },
                ui.label(name, 11.5f, ui.sub).apply { gravity = Gravity.CENTER }).apply {
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(54), 1f); gravity = Gravity.CENTER; background = ui.press(null)
                setOnClickListener { select(i) }
            })
        }
        val root = ui.col(FrameLayout(this).apply { addView(topBar); addView(selBar, FrameLayout.LayoutParams(-1, -1)) }, content.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, tabBar)
        ui.insets(root)
        setContentView(root)
        select(0)
        BackupSchedule.schedule(this)
        Update.checkOnStart(this)
   // 하루 두 번까지 새 판 확인 → 있으면 앱 안에서 받아 설치
    }

    override fun onResume() {
        super.onResume()
        // 뷰어에서 지우거나 즐겨찾기를 바꾼 것 반영
        val g = grid() ?: return
        if (ViewerActivity.removed.isNotEmpty()) { g.removeItems(ViewerActivity.removed.toSet()); ViewerActivity.removed.clear() }
        if (ViewerActivity.changed.isNotEmpty()) { ViewerActivity.changed.values.forEach { g.update(it) }; ViewerActivity.changed.clear() }
    }
    override fun onDestroy() { super.onDestroy(); net.shutdownNow() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (grid()?.selecting == true) { grid()?.clearSelection(); return }
        if (tab < 4 && stacks[tab].size > 1) { back(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- 탭·화면 쌓기 ----------
    private fun select(i: Int) {
        if (i == 4) { startActivity(Intent(this, BackupActivity::class.java)); return }
        tab = i
        for (k in 0 until tabBar.childCount) {
            val c = tabBar.getChildAt(k) as LinearLayout
            val on = k == i
            (c.getChildAt(0) as ImageView).setImageDrawable(Glyph(tabs[k].first, if (on) ui.accent else ui.sub))
            (c.getChildAt(1) as TextView).setTextColor(if (on) ui.accent else ui.sub)
        }
        if (stacks[i].isEmpty()) push(when (i) {
            0 -> "사진" to timelinePage()
            1 -> "폴더" to folderPage(1, "폴더")
            2 -> "인물" to personPage()
            else -> "장소" to placePage()
        }) else show()
    }

    private fun push(p: Pair<String, View>) { stacks[tab] += p; show() }
    private fun back() { if (stacks[tab].size > 1) { stacks[tab].removeAt(stacks[tab].size - 1); show() } }
    private fun show() {
        val (t, v) = stacks[tab].last()
        content.removeAllViews(); (v.parent as? ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        title.text = t
        topBar.findViewWithTag<View>("back").visibility = if (stacks[tab].size > 1) View.VISIBLE else View.GONE
        onSel(grid()?.selected?.size ?: 0)
    }
    private fun grid(): PhotoView? = if (tab < 4) stacks[tab].lastOrNull()?.second.let { (it as? PhotoView) ?: (it as? GridPage)?.grid } else null
    private fun refresh() {
        val top = stacks[tab].lastOrNull() ?: return
        (top.second as? GridPage)?.onRefresh?.invoke()   // 백업 상태도 다시 대조
        grid()?.reload() ?: run {
            stacks[tab].removeAt(stacks[tab].size - 1)
            select(tab)   // 맨 위 화면이면 다시 만들기
        }
    }

    /** 위 줄 메뉴: 공간 확보 · 중복 정리 · 설정 */
    private fun menu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "공간 확보 (백업된 사진을 휴대폰에서 지우기)")
        pm.menu.add(0, 2, 1, "중복 정리 (서버)")
        pm.menu.add(0, 3, 2, "설정")
        pm.setOnMenuItemClickListener { mi ->
            startActivity(Intent(this, when (mi.itemId) { 1 -> FreeUpActivity::class.java; 2 -> DupActivity::class.java; else -> SettingsActivity::class.java })); true
        }
        pm.show()
    }

    private fun onSel(n: Int) {
        selBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        (stacks.getOrNull(tab)?.lastOrNull()?.second as? GridPage)?.floating?.visibility = if (n > 0) View.GONE else View.VISIBLE
        selCount.text = "${n}장 고름"
    }

    private fun photoGrid(grouped: Boolean, filter: Map<String, Any>, load: ((Int, Int) -> List<Item>)? = null): PhotoGrid =
        PhotoGrid(this, ui, net, ns, grouped, load ?: { off, lim -> ph.list(ns, off, lim, filter) }, { list, i -> ViewerActivity.open(this, list, i, ns) }, { n -> onSel(n) }).apply { onPull = { refresh() } }

    /** 인물·장소·폴더의 사진: 전체 사진과 같은 격자 (오른쪽 막대로 어느 해든 바로 · 연도/월/일) */
    private fun timelineFor(filter: Map<String, Any>): View {
        lateinit var page: GridPage
        val units = listOf("year" to "연도", "month" to "월", "day" to "일")
        val on = if (ui.dark) 0xFF4A4A4A.toInt() else 0xFFDDE3EC.toInt()
        val pill = ui.row().apply {
            background = ui.round(if (ui.dark) 0xF2303030.toInt() else 0xF7FFFFFF.toInt(), 26, stroke = if (ui.dark) 0 else ui.line)
            elevation = ui.dpf(8f); val q = ui.dp(5); setPadding(q, q, q, q)
        }
        fun paint() {
            for (k in 0 until pill.childCount) {
                val sel = units[k].first == page.grid.unit
                (pill.getChildAt(k) as TextView).apply {
                    setTextColor(if (sel) ui.text else ui.sub); typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    background = if (sel) ui.round(on, 21) else ui.press(null, 21)
                }
            }
        }
        val tg = TimelineGrid(this, ui, net, ns, { list, i -> ViewerActivity.open(this, list, i, ns) }, { n -> onSel(n) }, { paint() }, filter)
        tg.setBottomSpace(ui.dp(76)); tg.onPull = { refresh() }
        units.forEach { (u, name) ->
            pill.addView(ui.label(name, 15f, ui.sub).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ui.dp(76), ui.dp(42))
                setOnClickListener { if (page.grid.unit != u) { page.grid.unit = u; paint() } }
            })
        }
        page = GridPage(this, tg, tg, pill)
        paint()
        return page
    }

    // ---------- 사진 (전체) ----------
    /** 전체 사진: 시놀로지 사진 앱처럼 날짜별 장수를 먼저 받아 전체 자리를 잡아 둔 격자(오른쪽 막대로 어느 해든 바로) +
     *  아래에 떠 있는 [연도 | 월 | 일] 고르기. 위의 백업 상태 칩을 고르면 그 상태만 모아 보는 격자로 바뀜 */
    private fun timelinePage(): View {
        val units = listOf("year" to "연도", "month" to "월", "day" to "일")
        val on = if (ui.dark) 0xFF4A4A4A.toInt() else 0xFFDDE3EC.toInt()
        val pill = ui.row().apply {
            background = ui.round(if (ui.dark) 0xF2303030.toInt() else 0xF7FFFFFF.toInt(), 26, stroke = if (ui.dark) 0 else ui.line)
            elevation = ui.dpf(8f); val q = ui.dp(5); setPadding(q, q, q, q)
        }
        lateinit var page: GridPage
        fun paint() {
            for (k in 0 until pill.childCount) {
                val sel = units[k].first == page.grid.unit
                (pill.getChildAt(k) as TextView).apply {
                    setTextColor(if (sel) ui.text else ui.sub); typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    background = if (sel) ui.round(on, 21) else ui.press(null, 21)
                }
            }
        }
        val tg = TimelineGrid(this, ui, net, ns, { list, i -> ViewerActivity.open(this, list, i, ns) }, { n -> onSel(n) }, { paint() })
        tg.setBottomSpace(ui.dp(76))
        tg.onPull = { refresh() }   // 위로 당겼다 놓으면: 사진 목록과 백업 상태를 다시
        var filtered: PhotoGrid? = null   // 백업 상태로 거른 격자 (칩을 고를 때 만듦)
        val holder = FrameLayout(this).apply { addView(tg, FrameLayout.LayoutParams(-1, -1)) }
        units.forEach { (u, name) ->
            pill.addView(ui.label(name, 15f, ui.sub).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ui.dp(76), ui.dp(42))
                setOnClickListener { if (page.grid.unit != u) { page.grid.unit = u; paint() } }
            })
        }
        // 위: 백업 상태로 거르기 [모두 | 백업됨 | 서버에만 | 백업 안 됨] + 대조 진행 줄
        val chips = ui.row().apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
        val note = ui.label("", 12.5f, ui.sub).apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(6)); visibility = View.GONE }
        val FAV = -1   // 즐겨찾기 칩
        val kinds = listOf(0 to "모두", FAV to "즐겨찾기") + StatusBadge.legend.map { (k, _) -> k to when (k) { Library.BOTH -> "백업됨"; Library.SERVER_ONLY -> "서버에만"; else -> "백업 안 됨" } }
        var favGrid: PhotoGrid? = null
        var status = 0
        fun paintChips() {
            for (i in 0 until chips.childCount) {
                val sel = kinds[i].first == status
                val c = chips.getChildAt(i) as LinearLayout
                c.background = ui.press(ui.round(if (sel) ui.accent else ui.panel2, 17), 17)
                (c.getChildAt(c.childCount - 1) as TextView).setTextColor(if (sel) ui.onAccent else ui.text)
            }
        }
        kinds.forEach { (k, name) ->
            val c = ui.row().apply { setPadding(ui.dp(if (k == 0) 14 else 8), 0, ui.dp(14), 0); minimumHeight = ui.dp(34)
                layoutParams = LinearLayout.LayoutParams(-2, ui.dp(34)).apply { rightMargin = ui.dp(6) } }
            if (k == FAV) c.addView(ImageView(this).apply { setImageDrawable(Glyph("heart", 0xFFE5484D.toInt())) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(6) })
            else if (k != 0) c.addView(StatusBadge.view(this, ui).also { StatusBadge.set(it, k) }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { rightMargin = ui.dp(6) })
            c.addView(ui.label(name, 13.5f, ui.text))
            c.setOnClickListener {
                if (status == k) return@setOnClickListener
                page.grid.clearSelection(); status = k; paintChips()
                holder.removeAllViews()
                if (k == 0) { holder.addView(tg, FrameLayout.LayoutParams(-1, -1)); page.grid = tg }
                else if (k == FAV) {   // 즐겨찾기한 사진만 (시놀로지 포토의 즐겨찾기 거르기 그대로)
                    val g = favGrid ?: photoGrid(true, emptyMap()) { off, lim -> ph.favorites(ns, off, lim) }.apply { setBottomSpace(ui.dp(76)) }.also { favGrid = it }
                    g.unit = tg.unit
                    holder.addView(g, FrameLayout.LayoutParams(-1, -1)); page.grid = g
                }
                else {
                    val g = filtered ?: photoGrid(true, emptyMap()).apply { mergePhone = true; setBottomSpace(ui.dp(76))
                        net.execute { val l = Library.phoneOnly(this@MainActivity)?.map { it.toItem() }; runOnUiThread { phoneChanged(l) } } }.also { filtered = it }
                    g.statusFilter = k; g.unit = tg.unit
                    holder.addView(g, FrameLayout.LayoutParams(-1, -1)); page.grid = g
                }
                paint()
            }
            chips.addView(c)
        }
        paintChips()
        val strip = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips); setBackgroundColor(ui.bg) }
        page = GridPage(this, ui.col(strip, note, holder.apply { layoutParams = ui.lp(h = 0, weight = 1f) }), tg, pill)
        paint()
        val views = { listOfNotNull<PhotoView>(tg, filtered, favGrid) }
        loadStatus(views, note)
        page.onRefresh = { loadStatus(views, note) }
        return page
    }

    /** 휴대폰 목록을 읽고, 서버 목록이 없거나 12시간 넘었으면 새로 읽어 대조 → 백업 안 된 사진을 끼워 넣음 */
    private fun loadStatus(views: () -> List<PhotoView>, note: TextView) {
        Thread {
            Library.phone(this, force = true)
            runOnUiThread { views().forEach { it.phoneChanged(null) } }
            // 백업 폴더(올릴 곳)만 읽어 대조 — 빠름. 서버 전체는 공간 확보에서 넓혀 볼 때만 읽음 (읽어 둔 것이 있으면 함께 씀)
            if (Library.backupRoot(this).isNotEmpty() && Library.stale(this, 12 * 3_600_000L, Library.BACKUP)) {
                var last = 0L
                try { Library.refreshServer(this, Library.BACKUP) { n, t ->
                    val now = System.currentTimeMillis(); if (now - last < 500) return@refreshServer; last = now
                    runOnUiThread { note.visibility = View.VISIBLE; note.text = "백업 폴더와 대조하는 중  폴더 ${-t}개 · 사진 ${"%,d".format(n)}장 — 끝나면 '백업 안 됨' 사진이 보입니다" }
                } } catch (e: Exception) { runOnUiThread { note.visibility = View.VISIBLE; note.text = "백업 폴더와 대조하지 못했습니다: ${e.message}" } }
            }
            val only = Library.phoneOnly(this)?.map { it.toItem() }
            runOnUiThread { if (only != null) { if (note.text.startsWith("백업 폴더와 대조하는 중")) note.visibility = View.GONE; views().forEach { it.phoneChanged(only) } } }
        }.start()
    }

    // ---------- 폴더 ----------
    private fun folderPage(id: Long, name: String): View {
        val list = ListView(this).apply { divider = null; setBackgroundColor(ui.bg) }
        val wrap = FrameLayout(this).apply { addView(list); addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER)) }
        net.execute {
            val r = try { Result.success(ph.folders(ns, id)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                wrap.removeViewAt(1)
                r.onFailure { e -> wrap.addView(ui.label(e.message ?: "읽지 못했습니다", 14f, ui.sub).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1)) }
                r.onSuccess { subs ->
                    if (subs.isEmpty() && id != 1L) {   // 하위 폴더가 없으면 바로 사진
                        stacks[tab].removeAt(stacks[tab].size - 1); push(name to timelineFor(mapOf("folder_id" to id))); return@onSuccess
                    }
                    val rows = ArrayList<Folder?>(); if (id != 1L) rows += null; rows.addAll(subs)
                    val ad = object : BaseAdapter() {
                        override fun getCount() = rows.size
                        override fun getItem(p: Int) = rows[p]
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                            val f = rows[p]
                            // 줄을 재활용 (스크롤할 때마다 새로 만들지 않음 → 부드럽게)
                            val row = (cv as? LinearLayout) ?: ui.row(
                                ImageView(this@MainActivity).apply { background = ui.round(ui.panel2, 10); clipToOutline = true; layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)) },
                                ui.label("", 15.5f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } },
                                ImageView(this@MainActivity).apply { setImageDrawable(Glyph("chev", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)) })
                                .apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(12), ui.dp(6)); background = ui.press(null) }
                            val iv = row.getChildAt(0) as ImageView
                            iv.scaleType = ImageView.ScaleType.CENTER_CROP
                            if (f == null) { iv.tag = null; iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph("image", ui.accent)) }
                            else if (f.cover != null) Thumbs.load(iv, ns, f.cover.first, f.cover.second, "sm") else { iv.tag = null; iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph("folder", ui.sub)) }
                            (row.getChildAt(1) as TextView).apply { text = f?.name ?: "이 폴더의 사진"; setTextColor(if (f == null) ui.accent else ui.text) }
                            return row
                        }
                    }
                    list.adapter = ad
                    list.isFastScrollEnabled = rows.size > 40   // 폴더가 많으면 오른쪽 손잡이로 빨리
                    list.setOnItemClickListener { _, _, p, _ ->
                        if (p >= rows.size) return@setOnItemClickListener   // 아래 안내 줄
                        val f = rows[p]
                        if (f == null) push(name to timelineFor(mapOf("folder_id" to id)))
                        else push(f.name to folderPage(f.id, f.name))
                    }
                    // 길게 누르면: 폴더째 지우기
                    list.setOnItemLongClickListener { _, _, p, _ ->
                        val f = rows.getOrNull(p) ?: return@setOnItemLongClickListener false
                        AlertDialog.Builder(this).setTitle(f.name).setItems(arrayOf("폴더째 지우기")) { _, _ ->
                            deleteFolder(f) { rows.remove(f); ad.notifyDataSetChanged() }
                        }.show()
                        true
                    }
                    if (rows.any { it != null }) list.addFooterView(ui.label("폴더를 길게 누르면 폴더째 지울 수 있습니다", 12.5f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, ui.dp(24)) }, null, false)
                }
            }
        }
        return wrap
    }

    /** 폴더째 지우기: 안에 몇 장·하위 폴더 몇 개인지 먼저 세어 보여 주고 확인받은 뒤 */
    private fun deleteFolder(f: Folder, done: () -> Unit) {
        var stop = false
        val wait = AlertDialog.Builder(this).setTitle("폴더 안을 세는 중").setMessage(f.path).setNegativeButton("그만") { _, _ -> stop = true }.show()
        net.execute {
            val r = try { Result.success(ph.folderStats(ns, f.id) { stop }) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (stop || isFinishing) return@runOnUiThread
                wait.dismiss()
                r.onFailure { e -> Toast.makeText(this, "폴더를 읽지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                r.onSuccess { (n, subs) ->
                    AlertDialog.Builder(this).setTitle("폴더째 지울까요?")
                        .setMessage("${f.path}\n\n사진·동영상 ${"%,d".format(n)}개" + (if (subs > 0) " · 하위 폴더 ${"%,d".format(subs)}개" else "") +
                            "\n\n시놀로지에서 폴더와 그 안의 파일을 모두 지웁니다. 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다.")
                        .setPositiveButton("폴더째 지우기") { _, _ ->
                            net.execute {
                                val err = try { ph.deleteFolders(ns, listOf(f.id)); null } catch (e: Exception) { e.message ?: "오류" }
                                runOnUiThread {
                                    if (err != null) Toast.makeText(this, "지우지 못했습니다: $err", Toast.LENGTH_LONG).show()
                                    else { Toast.makeText(this, "${f.name} 폴더를 지웠습니다", Toast.LENGTH_SHORT).show(); done() }
                                }
                            }
                        }.setNegativeButton("그만", null).show()
                }
            }
        }
    }

    // ---------- 인물 · 장소 ----------
    private fun groupPage(load: () -> List<Group>, round: Boolean, open: (Group) -> Unit): View {
        val gv = GridView(this).apply { numColumns = if (round) 3 else 2; verticalSpacing = ui.dp(10); horizontalSpacing = ui.dp(10); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); clipToPadding = false; setBackgroundColor(ui.bg); selector = android.graphics.drawable.ColorDrawable(0) }
        val wrap = FrameLayout(this).apply { addView(gv); addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER)) }
        net.execute {
            val r = try { Result.success(load()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                wrap.removeViewAt(1)
                r.onFailure { e -> wrap.addView(ui.label(e.message ?: "읽지 못했습니다", 14f, ui.sub).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1)) }
                r.onSuccess { gs ->
                    if (gs.isEmpty()) wrap.addView(ui.label("없습니다", 14f, ui.sub).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    gv.adapter = object : BaseAdapter() {
                        override fun getCount() = gs.size
                        override fun getItem(p: Int) = gs[p]
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                            val g = gs[p]
                            val w = (parent.width - ui.dp(24) - ui.dp(10) * (gv.numColumns - 1)) / gv.numColumns
                            // 칸을 재활용 (스크롤할 때마다 새로 만들지 않음 → 부드럽게)
                            val cell = (cv as? LinearLayout) ?: ui.col(ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                                background = if (round) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.panel2) } else ui.round(ui.panel2, 12)
                            }, ui.label("", 14f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp(top = 6); if (round) gravity = Gravity.CENTER },
                                ui.label("", 12f, ui.sub).apply { setSingleLine(); if (round) gravity = Gravity.CENTER })
                            val iv = cell.getChildAt(0) as ImageView
                            iv.layoutParams = LinearLayout.LayoutParams(w, if (round) w else w * 3 / 4)
                            if (g.coverKey.isNotEmpty()) Thumbs.load(iv, ns, g.coverUnit, g.coverKey, "sm", g.thumbType) else { iv.tag = null; iv.setImageDrawable(null) }
                            (cell.getChildAt(1) as TextView).text = g.name.ifEmpty { "이름 없음" }
                            (cell.getChildAt(2) as TextView).text = "${g.count}장" + (if (g.sub.isNotEmpty() && g.sub != g.name) " · ${g.sub}" else "")
                            return cell
                        }
                    }
                    gv.setOnItemClickListener { _, _, p, _ -> open(gs[p]) }
                    gv.isFastScrollEnabled = gs.size > 30   // 많으면 오른쪽 손잡이로 빨리
                }
            }
        }
        return wrap
    }

    private fun personPage(): View = groupPage({ ph.persons(ns) }, true) { g -> push(g.name.ifEmpty { "이름 없음" } to timelineFor(mapOf("person_id" to g.id))) }
    /** 장소: 위에 [목록 | 지도] — 지도에서는 장소마다 동그라미, 눌러서 사진 보기 */
    private fun placePage(): View {
        val openPlace: (Group) -> Unit = { g -> push(g.name to timelineFor(mapOf("geocoding_id" to g.id))) }
        val holder = FrameLayout(this)
        var places: List<Group>? = null
        val seg = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setBackgroundColor(ui.bg) }
        val modes = listOf("목록", "지도")
        val pf = Prefs(this)
        var mode = pf.int("place_mode", 0)
        fun show() {
            for (i in 0 until seg.childCount) (seg.getChildAt(i) as TextView).apply {
                val on = i == mode; setTextColor(if (on) ui.onAccent else ui.text); background = ui.press(ui.round(if (on) ui.accent else ui.panel2, 17), 17)
            }
            holder.removeAllViews()
            if (mode == 0) holder.addView(groupPage({ places ?: ph.places(ns).also { places = it } }, false, openPlace), FrameLayout.LayoutParams(-1, -1))
            else {
                val wait = ProgressBar(this)
                holder.addView(wait, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
                net.execute {
                    val r = try { Result.success(places ?: ph.places(ns).also { places = it }) } catch (e: Exception) { Result.failure(e) }
                    runOnUiThread {
                        if (mode != 1) return@runOnUiThread
                        holder.removeAllViews()
                        r.onSuccess { holder.addView(PlaceMap(this, ui, ns, it, openPlace), FrameLayout.LayoutParams(-1, -1)) }
                        r.onFailure { e -> holder.addView(ui.label(e.message ?: "읽지 못했습니다", 14f, ui.sub).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1)) }
                    }
                }
            }
        }
        modes.forEachIndexed { i, n ->
            seg.addView(ui.label(n, 14f, ui.text).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(34), 1f).apply { if (i == 0) rightMargin = ui.dp(6) }
                setOnClickListener { if (mode != i) { mode = i; pf.putInt("place_mode", i); show() } }
            })
        }
        show()
        return ui.col(seg, holder.apply { layoutParams = ui.lp(h = 0, weight = 1f) })
    }

    // ---------- 고른 사진 ----------
    private fun favSelected() {
        val g = grid() ?: return
        val sel = g.selectedItems().filter { it.local == null }
        if (sel.isEmpty()) { Toast.makeText(this, "휴대폰에만 있는 사진은 즐겨찾기를 할 수 없습니다 (먼저 백업)", Toast.LENGTH_SHORT).show(); return }
        val on = !sel.all { it.fav }
        net.execute {
            val err = try { ph.setFavorite(ns, sel.map { it.id }, on); null } catch (e: Exception) { e.message }
            runOnUiThread {
                if (err != null) { Toast.makeText(this, "즐겨찾기를 바꾸지 못했습니다: $err", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                sel.forEach { g.update(it.copy(fav = on)) }; g.clearSelection()
                Toast.makeText(this, if (on) "즐겨찾기에 넣었습니다" else "즐겨찾기에서 뺐습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 고른 사진 지우기. 휴대폰에도 같은 사진이 있으면 '휴대폰에서도 지우기'를 고를 수 있음 (고른 것은 기억) */
    private fun deleteSelected() {
        val g = grid() ?: return
        val all = g.selectedItems()
        val sel = all.filter { it.local == null }   // 휴대폰에만 있는 것(백업 안 됨)은 여기서 지우지 않음
        if (sel.isEmpty()) { Toast.makeText(this, "고른 것은 모두 휴대폰에만 있는 사진입니다 — 여기서는 지우지 않습니다", Toast.LENGTH_LONG).show(); return }
        net.execute {
            Library.phone(this)   // 휴대폰 목록 (30초 안에 읽었으면 그대로)
            val copies = sel.flatMap { Library.phoneCopies(it) }.distinctBy { it.key }
            runOnUiThread { askDelete(g, sel, all.size - sel.size, copies) }
        }
    }

    private var phoneDel: List<Library.Phone> = emptyList()   // 안드로이드 확인 창이 끝나면 정리할 것

    private fun askDelete(g: PhotoView, sel: List<Item>, skipped: Int, copies: List<Library.Phone>) {
        val pf = Prefs(this)
        val box = android.widget.CheckBox(this).apply {
            text = "휴대폰에서도 지우기 (같은 사진 ${copies.size}개)"; textSize = 15f; setTextColor(ui.text)
            isChecked = pf.bool("del_phone_too", false)
        }
        val hint = ui.label("켜면 시놀로지에서 지운 다음, 안드로이드가 휴대폰 사진을 지울지 한 번 더 묻습니다. 휴대폰에서 지운 사진은 되살릴 수 없습니다 (시놀로지 쪽은 휴지통이 켜져 있으면 되살릴 수 있음).", 12.5f, ui.sub)
            .apply { setPadding(ui.dp(32), 0, 0, 0) }
        val msg = ui.label("시놀로지에서 지웁니다. 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다." +
            (if (skipped > 0) "\n\n휴대폰에만 있는(백업 안 된) ${skipped}장은 지우지 않습니다." else "") +
            (if (copies.isEmpty()) "\n\n휴대폰에는 같은 사진이 없습니다." else ""), 14.5f, ui.text)
        val body = ui.col(msg).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0) }
        if (copies.isNotEmpty()) { body.addView(box, ui.lp(top = 14)); body.addView(hint) }
        AlertDialog.Builder(this).setTitle("${sel.size}장을 지울까요?").setView(body)
            .setPositiveButton("지우기") { _, _ ->
                val phoneToo = copies.isNotEmpty() && box.isChecked
                if (copies.isNotEmpty()) pf.putBool("del_phone_too", box.isChecked)
                net.execute {
                    val err = try { sel.chunked(100).forEach { c -> ph.delete(ns, c.map { it.id }) }; null } catch (e: Exception) { e.message }
                    runOnUiThread {
                        if (err != null) { Toast.makeText(this, "지우지 못했습니다: $err", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        g.removeItems(sel.map { it.id }.toSet())
                        if (phoneToo) deletePhone(copies)
                    }
                }
            }.setNegativeButton("그만", null).show()
    }

    /** 휴대폰 파일 지우기 — 안드로이드의 '지울까요?' 확인 창을 거침 */
    private fun deletePhone(list: List<Library.Phone>) {
        phoneDel = list
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val req = android.provider.MediaStore.createDeleteRequest(contentResolver, list.map { it.uri })
                @Suppress("DEPRECATION") startIntentSenderForResult(req.intentSender, 46, null, 0, 0, 0)
            } else {   // 안드로이드 10: 하나씩 (이 앱이 만든 것이 아니면 실패할 수 있음)
                var n = 0
                list.forEach { p -> try { n += contentResolver.delete(p.uri, null, null) } catch (e: Exception) { } }
                phoneDeleted(n > 0)
            }
        } catch (e: Exception) { Toast.makeText(this, "휴대폰에서 지우지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, result, data)
        if (code == 46) phoneDeleted(result == RESULT_OK)
    }

    private fun phoneDeleted(ok: Boolean) {
        val list = phoneDel; phoneDel = emptyList()
        if (!ok) { Toast.makeText(this, "시놀로지에서만 지웠습니다 (휴대폰 사진은 그대로)", Toast.LENGTH_LONG).show(); return }
        Backup.forget(this, list.map { it.key })   // 백업 기록에서도 빼기
        Toast.makeText(this, "휴대폰에서도 ${list.size}개를 지웠습니다", Toast.LENGTH_SHORT).show()
        net.execute { Library.phone(this, force = true); runOnUiThread { stacks.forEach { st -> st.forEach { (_, v) -> ((v as? PhotoView) ?: (v as? GridPage)?.grid)?.phoneChanged(null) } } } }
    }
}

/** 격자를 다른 것(아래에 떠 있는 연도·월·일 고르기 등)과 함께 담은 화면 — 격자를 찾을 수 있게 */
class GridPage(ctx: android.content.Context, content: View, var grid: PhotoView, val floating: View? = null) : FrameLayout(ctx) {
    var onRefresh: (() -> Unit)? = null
    init {
        addView(content, LayoutParams(-1, -1))
        floating?.let { addView(it, LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (14 * ctx.resources.displayMetrics.density).toInt() }) }
    }
}
