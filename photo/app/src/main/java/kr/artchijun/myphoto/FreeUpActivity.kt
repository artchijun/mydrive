package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 공간 확보: 이미 시놀로지에 백업된 사진·동영상을 휴대폰에서 지움.
 *   모든 백업된 항목 / 사용자 지정(시간 범위 · 파일 크기 · 폴더). 미리 보기에서 빼고 싶은 것은 눌러서 뺌.
 *   지우기 직전에 서버 폴더에 같은 이름·크기가 지금도 있는지 다시 확인한 것만 지움. 마지막에 안드로이드가 확인 창을 띄움.
 */
class FreeUpActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private val bg = Executors.newFixedThreadPool(3)
    private var all: List<Backup.Local> = emptyList()
    private var shown: List<Backup.Local> = emptyList()
    private val excluded = HashSet<Long>()
    private lateinit var filters: LinearLayout
    private lateinit var summary: TextView
    private lateinit var go: android.widget.Button
    private lateinit var grid: GridView
    private lateinit var preview: CheckBox
    private val thumbs = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) { override fun sizeOf(k: Long, v: Bitmap) = v.byteCount }
    private var pending: List<Backup.Local> = emptyList()

    private val ages = listOf(0 to "전체", 30 to "1개월 초과", 90 to "3개월 초과", 180 to "6개월 초과", 365 to "1년 초과", 730 to "2년 초과")
    private val sizes = listOf(0L to "전체", 10L to "10MB 초과", 50L to "50MB 초과", 100L to "100MB 초과", 1024L to "1GB 초과")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("공간 확보", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        filters = ui.col()
        summary = ui.label("백업 기록을 읽는 중…", 14f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(8)) }
        grid = GridView(this).apply { numColumns = 4; verticalSpacing = ui.dp(2); horizontalSpacing = ui.dp(2); setBackgroundColor(ui.bg); selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, p, _ -> val k = shown[p].key; if (!excluded.remove(k)) excluded += k; (adapter as BaseAdapter).notifyDataSetChanged(); refresh() } }
        preview = CheckBox(this).apply { text = "정리하기 전에 미리 보기"; setTextColor(ui.text); isChecked = pf.bool("fu_preview", true)
            setOnCheckedChangeListener { _, b -> pf.putBool("fu_preview", b); refresh() } }
        go = ui.button("지울 항목 없음") { start() }
        val bottom = ui.col(ui.row(preview).apply { gravity = Gravity.CENTER }, go.apply { layoutParams = ui.lp(h = ui.dp(52), top = 8) })
            .apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12)); setBackgroundColor(ui.panel) }
        val root = ui.col(top, filters, summary, grid.apply { layoutParams = ui.lp(h = 0, weight = 1f) }, bottom).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
        buildFilters()
        bg.execute {
            val l = try { Backup.backedUp(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { all = l; refresh() }
        }
    }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    private val custom get() = pf.bool("fu_custom", false)
    private val ageDays get() = pf.int("fu_age", 730)
    private val minMb get() = pf.long("fu_size", 0)
    private fun folders(): Set<String> = pf.str("fu_folders", "").split('\n').filter { it.isNotEmpty() }.toSet()

    private fun row(title: String, sub: String?, right: View?, onClick: () -> Unit): View {
        val col = ui.col(ui.label(title, 16f, ui.text)).apply { layoutParams = ui.lp(0, weight = 1f) }
        if (sub != null) col.addView(ui.label(sub, 13.5f, ui.sub).apply { layoutParams = ui.lp(top = 2) })
        val r = ui.row(col).apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(14), ui.dp(14)); background = ui.press(null); setOnClickListener { onClick() } }
        if (right != null) r.addView(right)
        return ui.col(r, View(this).apply { setBackgroundColor(ui.line); layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }

    private fun buildFilters() {
        filters.removeAllViews()
        fun radio(on: Boolean) = RadioButton(this).apply { isChecked = on; isClickable = false; buttonTintList = android.content.res.ColorStateList.valueOf(if (on) ui.accent else ui.sub) }
        filters.addView(row("모든 백업된 항목", null, radio(!custom)) { pf.putBool("fu_custom", false); buildFilters(); refresh() })
        filters.addView(row("사용자 지정", null, radio(custom)) { pf.putBool("fu_custom", true); buildFilters(); refresh() })
        if (!custom) return
        filters.addView(row("시간 범위", ages.firstOrNull { it.first == ageDays }?.second ?: "${ageDays}일 초과", null) {
            AlertDialog.Builder(this).setTitle("시간 범위 (찍은 지 얼마나 지난 것)").setSingleChoiceItems(ages.map { it.second }.toTypedArray(), ages.indexOfFirst { it.first == ageDays }) { d, i ->
                pf.putInt("fu_age", ages[i].first); d.dismiss(); buildFilters(); refresh() }.show()
        })
        filters.addView(row("파일 크기", sizes.firstOrNull { it.first == minMb }?.second ?: "${minMb}MB 초과", null) {
            AlertDialog.Builder(this).setTitle("파일 크기").setSingleChoiceItems(sizes.map { it.second }.toTypedArray(), sizes.indexOfFirst { it.first == minMb }) { d, i ->
                pf.putLong("fu_size", sizes[i].first); d.dismiss(); buildFilters(); refresh() }.show()
        })
        val fs = folders()
        filters.addView(row("폴더", if (fs.isEmpty()) "모든 폴더" else "${fs.size}개 폴더 선택됨", null) { pickFolders() })
    }

    private fun pickFolders() {
        val avail = all.groupBy { it.rel }.mapValues { it.value.size }.toSortedMap()
        val cur = folders().toMutableSet()
        val list = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        avail.forEach { (rel, n) -> list.addView(CheckBox(this).apply { text = "${rel.trimEnd('/')}  ($n)"; setTextColor(ui.text); isChecked = rel in cur
            setOnCheckedChangeListener { _, b -> if (b) cur += rel else cur -= rel } }) }
        if (avail.isEmpty()) list.addView(ui.label("백업된 폴더가 없습니다", 14f, ui.sub))
        AlertDialog.Builder(this).setTitle("폴더 (아무것도 고르지 않으면 모두)").setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("확인") { _, _ -> pf.putStr("fu_folders", cur.joinToString("\n")); buildFilters(); refresh() }.setNegativeButton("그만", null).show()
    }

    /** 지금 조건에 맞는 것 */
    private fun refresh() {
        val now = System.currentTimeMillis()
        val fs = folders()
        shown = if (!custom) all else all.filter {
            (ageDays == 0 || now - it.taken > ageDays * 86_400_000L) && (minMb == 0L || it.size > minMb * 1024 * 1024) && (fs.isEmpty() || it.rel in fs)
        }
        val pick = shown.filter { it.key !in excluded }
        val bytes = pick.sumOf { it.size }
        summary.text = if (shown.isEmpty()) "필터 기준과 일치하는 항목 없음." else "${shown.size}개 중 ${pick.size}개 · ${Fmt.size(bytes)} 확보" + (if (excluded.isNotEmpty()) " (뺀 것 ${shown.size - pick.size}개)" else "") + (if (preview.isChecked) " — 눌러서 빼거나 넣기" else "")
        go.text = if (pick.isEmpty()) "지울 항목 없음" else "${pick.size}개 지우기 (${Fmt.size(bytes)})"
        go.isEnabled = pick.isNotEmpty(); go.alpha = if (pick.isEmpty()) 0.5f else 1f
        grid.visibility = if (preview.isChecked) View.VISIBLE else View.INVISIBLE
        if (preview.isChecked) { if (grid.adapter == null) grid.adapter = adapter else adapter.notifyDataSetChanged() } else grid.adapter = null
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].key
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val it = shown[p]
            val w = (parent.width - ui.dp(2) * 3) / 4
            val f = (cv as? FrameLayout) ?: FrameLayout(this@FreeUpActivity).apply {
                addView(ImageView(this@FreeUpActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.panel2) }, FrameLayout.LayoutParams(-1, -1))
                addView(View(this@FreeUpActivity).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
                addView(ImageView(this@FreeUpActivity), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(5), ui.dp(5), 0) })
            }
            f.layoutParams = android.widget.AbsListView.LayoutParams(w, w)
            val iv = f.getChildAt(0) as ImageView
            iv.tag = it.key
            val hit = thumbs.get(it.key)
            if (hit != null) iv.setImageBitmap(hit) else { iv.setImageDrawable(null); bg.execute {
                val b = try { contentResolver.loadThumbnail(it.uri, Size(256, 256), null) } catch (e: Exception) { null }
                if (b != null) thumbs.put(it.key, b)
                runOnUiThread { if (iv.tag == it.key && b != null) iv.setImageBitmap(b) }
            } }
            val out = it.key in excluded
            f.getChildAt(1).visibility = if (out) View.VISIBLE else View.GONE
            (f.getChildAt(2) as ImageView).apply {
                setImageDrawable(if (out) null else Glyph("check", ui.onAccent))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (!out) setColor(ui.accent) else { setColor(0x33000000); setStroke(ui.dp(2), Color.WHITE) } }
                val pd = ui.dp(3); setPadding(pd, pd, pd, pd)
            }
            return f
        }
    }

    // ---------- 지우기 ----------
    private fun start() {
        val pick = shown.filter { it.key !in excluded }
        if (pick.isEmpty()) return
        val dlg = AlertDialog.Builder(this).setTitle("서버에 있는지 확인하는 중").setView(ui.col(ProgressBar(this)).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }).setCancelable(false).show()
        bg.execute {
            val r = try { Result.success(Backup.verifyOnServer(this, pick)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                dlg.dismiss()
                r.onFailure { e -> AlertDialog.Builder(this).setTitle("확인하지 못했습니다").setMessage((e.message ?: "") + "\n\n시놀로지에 닿을 때 다시 해 주세요. 아무것도 지우지 않았습니다.").setPositiveButton("확인", null).show() }
                r.onSuccess { ok ->
                    val missing = pick.size - ok.size
                    if (missing > 0) Backup.db(this).execSQL("UPDATE media SET state=0, srv_folder=0 WHERE id IN (${(pick - ok.toSet()).joinToString(",") { it.key.toString() }})")
                    if (ok.isEmpty()) { AlertDialog.Builder(this).setTitle("지울 것이 없습니다").setMessage("서버에서 ${missing}개를 찾지 못해 남겨 두었습니다. 다음 백업 때 다시 올립니다.").setPositiveButton("확인", null).show(); return@onSuccess }
                    pending = ok
                    if (missing > 0) Toast.makeText(this, "서버에서 찾지 못한 ${missing}개는 남겨 둡니다 (다시 백업)", Toast.LENGTH_LONG).show()
                    // 안드로이드가 '이 사진들을 지울까요?' 확인 창을 띄움
                    val req = MediaStore.createDeleteRequest(contentResolver, ok.map { it.uri })
                    startIntentSenderForResult(req.intentSender, 41, null, 0, 0, 0)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, result, data)
        if (code != 41) return
        if (result != RESULT_OK) { Toast.makeText(this, "지우지 않았습니다", Toast.LENGTH_SHORT).show(); return }
        val bytes = pending.sumOf { it.size }
        Backup.forget(this, pending.map { it.key })
        Backup.log(this, "공간 확보: ${pending.size}개 지움 (${Fmt.size(bytes)})")
        Toast.makeText(this, "${pending.size}개를 지워 ${Fmt.size(bytes)} 확보했습니다", Toast.LENGTH_LONG).show()
        val gone = pending.map { it.key }.toSet()
        all = all.filter { it.key !in gone }; excluded.removeAll(gone); pending = emptyList(); refresh()
    }
}
