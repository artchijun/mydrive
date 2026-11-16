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
 */
class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var ph: Photos
    private val net = Executors.newFixedThreadPool(3)
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
            ui.iconBtn(Glyph("settings", ui.text), "설정") { startActivity(Intent(this, SettingsActivity::class.java)) },
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
    private fun grid(): PhotoGrid? = if (tab < 4) stacks[tab].lastOrNull()?.second as? PhotoGrid else null
    private fun refresh() {
        val top = stacks[tab].lastOrNull() ?: return
        (top.second as? PhotoGrid)?.reload() ?: run {
            stacks[tab].removeAt(stacks[tab].size - 1)
            select(tab)   // 맨 위 화면이면 다시 만들기
        }
    }

    private fun onSel(n: Int) {
        selBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        selCount.text = "${n}장 고름"
    }

    private fun photoGrid(grouped: Boolean, filter: Map<String, Any>): PhotoGrid =
        PhotoGrid(this, ui, net, ns, grouped, { off, lim -> ph.list(ns, off, lim, filter) }, { list, i -> ViewerActivity.open(this, list, i, ns) }, { n -> onSel(n) })

    // ---------- 사진 (전체) ----------
    private fun timelinePage(): View = photoGrid(true, emptyMap())

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
                        stacks[tab].removeAt(stacks[tab].size - 1); push(name to photoGrid(false, mapOf("folder_id" to id))); return@onSuccess
                    }
                    val rows = ArrayList<Folder?>(); if (id != 1L) rows += null; rows.addAll(subs)
                    list.adapter = object : BaseAdapter() {
                        override fun getCount() = rows.size
                        override fun getItem(p: Int) = rows[p]
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                            val f = rows[p]
                            val iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(ui.panel2, 10); clipToOutline = true; layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)) }
                            if (f == null) { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph("image", ui.accent)) }
                            else if (f.cover != null) Thumbs.load(iv, ns, f.cover.first, f.cover.second, "sm") else { iv.scaleType = ImageView.ScaleType.CENTER; iv.setImageDrawable(Glyph("folder", ui.sub)) }
                            return ui.row(iv, ui.label(f?.name ?: "이 폴더의 사진", 15.5f, if (f == null) ui.accent else ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) } },
                                ImageView(this@MainActivity).apply { setImageDrawable(Glyph("chev", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)) })
                                .apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(12), ui.dp(6)); background = ui.press(null) }
                        }
                    }
                    list.setOnItemClickListener { _, _, p, _ ->
                        val f = rows[p]
                        if (f == null) push(name to photoGrid(false, mapOf("folder_id" to id)))
                        else push(f.name to folderPage(f.id, f.name))
                    }
                }
            }
        }
        return wrap
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
                            val iv = ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                                background = if (round) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.panel2) } else ui.round(ui.panel2, 12)
                                layoutParams = LinearLayout.LayoutParams(w, if (round) w else w * 3 / 4)
                            }
                            if (g.coverKey.isNotEmpty()) Thumbs.load(iv, ns, g.coverUnit, g.coverKey, "sm")
                            return ui.col(iv, ui.label(g.name.ifEmpty { "이름 없음" }, 14f, ui.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutParams = ui.lp(top = 6); if (round) gravity = Gravity.CENTER },
                                ui.label("${g.count}장" + (if (g.sub.isNotEmpty() && g.sub != g.name) " · ${g.sub}" else ""), 12f, ui.sub).apply { setSingleLine(); if (round) gravity = Gravity.CENTER })
                        }
                    }
                    gv.setOnItemClickListener { _, _, p, _ -> open(gs[p]) }
                }
            }
        }
        return wrap
    }

    private fun personPage(): View = groupPage({ ph.persons(ns) }, true) { g -> push(g.name.ifEmpty { "이름 없음" } to photoGrid(true, mapOf("person_id" to g.id))) }
    private fun placePage(): View = groupPage({ ph.places(ns) }, false) { g -> push(g.name to photoGrid(true, mapOf("geocoding_id" to g.id))) }

    // ---------- 고른 사진 ----------
    private fun favSelected() {
        val g = grid() ?: return
        val sel = g.selectedItems(); if (sel.isEmpty()) return
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

    private fun deleteSelected() {
        val g = grid() ?: return
        val sel = g.selectedItems(); if (sel.isEmpty()) return
        AlertDialog.Builder(this).setTitle("${sel.size}장을 지울까요?")
            .setMessage("시놀로지에서 지웁니다. 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다.")
            .setPositiveButton("지우기") { _, _ ->
                net.execute {
                    val err = try { sel.chunked(100).forEach { c -> ph.delete(ns, c.map { it.id }) }; null } catch (e: Exception) { e.message }
                    runOnUiThread {
                        if (err != null) Toast.makeText(this, "지우지 못했습니다: $err", Toast.LENGTH_SHORT).show()
                        else g.removeItems(sel.map { it.id }.toSet())
                    }
                }
            }.setNegativeButton("그만", null).show()
    }
}
