package kr.artchijun.myphoto

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 파일별 백업 기록: [기다림 | 올림 | 이미 있음 | 실패]
 *   기다림: 올릴 차례대로 (지금 올리는 파일은 맨 위에 '올리는 중')
 *   올림·이미 있음: 최근 것부터 — 언제, 휴대폰 어느 폴더에서 서버 어느 폴더로
 *   실패: 까닭
 *   올림·이미 있음 목록에서 길게 눌러 고른 뒤 [휴대폰에서 지우기] — 서버에 지금도 있는지 확인한 것만, 안드로이드 확인 창을 거쳐 지움
 */
class BackupListActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private val bg = Executors.newSingleThreadExecutor()
    private val h = Handler(Looper.getMainLooper())
    private var tab = 0
    private var rows: List<Backup.Entry> = emptyList()
    private lateinit var seg: LinearLayout
    private lateinit var info: TextView
    private lateinit var list: ListView
    private val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    private val names = listOf("기다림", "올림", "이미 있음", "실패")
    private val sel = LinkedHashSet<Long>()
    private lateinit var top: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private var pending: List<Backup.Local> = emptyList()
    private val canDelete get() = tab == 1 || tab == 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        tab = intent.getIntExtra("tab", 1).coerceIn(0, 3)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("백업 기록", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)); minimumHeight = ui.dp(56) }
        // 고르는 중: [닫기] N개 고름 [모두] [휴대폰에서 지우기]
        selCount = ui.label("", 17f, ui.text, bold = true)
        selBar = ui.row(ui.iconBtn(Glyph("close", ui.text), "고르기 끝") { clearSel() },
            selCount.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(8) } },
            ui.iconBtn(Glyph("selectall", ui.text), "모두 고르기") { if (sel.size == rows.size) sel.clear() else rows.forEach { sel += it.key }; onSel() },
            ui.label("휴대폰에서 지우기", 14f, ui.red, bold = true).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.press(null, 8); setOnClickListener { deleteSelected() } },
        ).apply { setBackgroundColor(ui.select); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)); minimumHeight = ui.dp(56); visibility = View.GONE }
        seg = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(4)) }
        info = ui.label("", 12.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(6)) }
        list = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); adapter = this@BackupListActivity.adapter }
        list.setOnItemClickListener { _, _, p, _ -> if (sel.isNotEmpty()) toggle(rows[p].key) }
        list.setOnItemLongClickListener { _, _, p, _ -> if (canDelete) { toggle(rows[p].key); true } else false }
        val root = ui.col(android.widget.FrameLayout(this).apply { addView(this@BackupListActivity.top); addView(selBar, android.widget.FrameLayout.LayoutParams(-1, -1)) },
            seg, info, list.apply { layoutParams = ui.lp(h = 0, weight = 1f) }).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
    }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }
    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    // ---------- 고르기 · 휴대폰에서 지우기 ----------
    private fun toggle(k: Long) { if (!sel.remove(k)) sel += k; onSel() }
    private fun clearSel() { sel.clear(); onSel() }
    private fun onSel() {
        selBar.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        val bytes = rows.filter { it.key in sel }.sumOf { it.size }
        val picked = rows.filter { it.key in sel }
        selCount.text = StatusBadge.kinds(picked.count { !it.video }, picked.count { it.video }).ifEmpty { "0개" } + " · ${Fmt.size(bytes)}"
        adapter.notifyDataSetChanged()
    }
    private fun deleteSelected() {
        val pick = rows.filter { it.key in sel }.map { it.toLocal() }
        if (pick.isEmpty()) return
        val dlg = android.app.AlertDialog.Builder(this).setTitle("서버에 지금도 있는지 확인하는 중")
            .setView(ui.col(android.widget.ProgressBar(this)).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }).setCancelable(false).show()
        bg.execute {
            val r = try { Result.success(Backup.verifyOnServer(this, pick)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                dlg.dismiss()
                r.onFailure { e -> android.app.AlertDialog.Builder(this).setTitle("확인하지 못했습니다").setMessage((e.message ?: "") + "\n\n아무것도 지우지 않았습니다.").setPositiveButton("확인", null).show() }
                r.onSuccess { ok ->
                    val missing = pick.size - ok.size
                    if (ok.isEmpty()) { android.app.AlertDialog.Builder(this).setTitle("지울 것이 없습니다").setMessage("고른 ${pick.size}개를 서버에서 찾지 못해 남겨 두었습니다.").setPositiveButton("확인", null).show(); return@onSuccess }
                    if (missing > 0) Toast.makeText(this, "서버에서 찾지 못한 ${missing}개는 남겨 둡니다", Toast.LENGTH_LONG).show()
                    pending = ok
                    try {
                        val req = android.provider.MediaStore.createDeleteRequest(contentResolver, ok.map { it.uri })
                        startIntentSenderForResult(req.intentSender, 42, null, 0, 0, 0)
                    } catch (e: Exception) { Toast.makeText(this, "지우지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, result, data)
        if (code != 42) return
        if (result != RESULT_OK) { pending = emptyList(); Toast.makeText(this, "지우지 않았습니다", Toast.LENGTH_SHORT).show(); return }
        val bytes = pending.sumOf { it.size }
        Backup.forget(this, pending.map { it.key })
        Backup.log(this, "백업 기록에서 휴대폰 사진 ${pending.size}개 지움 (${Fmt.size(bytes)})")
        Toast.makeText(this, "휴대폰에서 ${pending.size}개를 지워 ${Fmt.size(bytes)} 확보했습니다", Toast.LENGTH_LONG).show()
        pending = emptyList(); sel.clear(); onSel(); load()
    }
    @Deprecated("") override fun onBackPressed() { if (sel.isNotEmpty()) clearSel() else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun load() {
        h.removeCallbacksAndMessages(null)
        bg.execute {
            val c = try { Backup.counts(this) } catch (e: Exception) { null }
            val r = try {
                when (tab) {
                    0 -> Backup.entries(this, 0)
                    1 -> Backup.entries(this, 1).filter { it.how != 2 }
                    2 -> Backup.entries(this, 1).filter { it.how == 2 }
                    else -> Backup.entries(this, 2)
                }
            } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rows = r
                paintSeg(c)
                info.text = when (tab) {
                    0 -> if (r.isEmpty()) "기다리는 파일이 없습니다." else "올릴 차례대로 (새로 찍은 것부터). " + (if (Backup.running) "지금 올리는 파일은 맨 위." else "")
                    1 -> if (r.isEmpty()) "아직 올린 파일이 없습니다." else "이 휴대폰에서 서버로 올린 파일 (최근 것부터) · 길게 눌러 고르면 휴대폰에서 지울 수 있음"
                    2 -> if (r.isEmpty()) "없습니다." else "서버의 같은 폴더에 이름·크기가 같은 것이 이미 있어 올리지 않고 백업된 것으로 친 파일 · 길게 눌러 고르면 휴대폰에서 지울 수 있음"
                    else -> if (r.isEmpty()) "실패한 파일이 없습니다." else "다음 백업 때 다시 시도합니다 (6번까지). 백업 화면의 [실패한 것 다시 시도]로 바로 다시"
                } + (if (r.size >= 3000) "  — 최근 3,000개만 보입니다" else "")
                adapter.notifyDataSetChanged()
            }
        }
        // 백업 중이면 1초마다 새로
        // 고르는 동안에는 목록을 새로 고치지 않음 (백업 중에도 고른 것이 흐트러지지 않고 지울 수 있게) — 고르기가 끝나면 다시
        h.postDelayed({ if (sel.isEmpty() && pending.isEmpty()) load() else waitSel() }, if (Backup.running) 1500 else 5000)
    }
    private fun waitSel() { h.postDelayed({ if (sel.isEmpty() && pending.isEmpty()) load() else waitSel() }, 1000) }

    private fun paintSeg(c: Backup.Counts?) {
        seg.removeAllViews()
        val n = if (c == null) listOf(0, 0, 0, 0) else listOf(c.waiting, c.done - c.skipped, c.skipped, c.failed)
        names.forEachIndexed { i, s ->
            val sel = i == tab
            seg.addView(ui.col(ui.label("%,d".format(n[i]), 15f, if (sel) ui.onAccent else ui.text, bold = true).apply { gravity = Gravity.CENTER },
                ui.label(s, 11.5f, if (sel) ui.onAccent else ui.sub).apply { gravity = Gravity.CENTER }).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { if (i < 3) rightMargin = ui.dp(6) }
                background = ui.press(ui.round(if (sel) ui.accent else ui.panel2, 12), 12)
                setOnClickListener { tab = i; rows = emptyList(); this@BackupListActivity.sel.clear(); onSel(); load() }
            })
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = rows[p].key
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val e = rows[p]
            val v = (cv as? LinearLayout) ?: ui.row(
                android.widget.FrameLayout(this@BackupListActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { rightMargin = ui.dp(12) }
                    addView(ImageView(this@BackupListActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(ui.panel2, 8); clipToOutline = true }, android.widget.FrameLayout.LayoutParams(-1, -1))
                    addView(StatusBadge.play(this@BackupListActivity, ui), android.widget.FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
                },
                ui.col(ui.label("", 14.5f, ui.text).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                    ui.label("", 12f, ui.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                    ui.label("", 12f, ui.sub).apply { setSingleLine() }).apply { layoutParams = ui.lp(0, weight = 1f) },
                ui.label("", 11.5f, ui.text, bold = true).apply { setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)) },
            ).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(14), ui.dp(8)); gravity = Gravity.CENTER_VERTICAL }
            val box = v.getChildAt(0) as android.widget.FrameLayout
            val iv = box.getChildAt(0) as ImageView
            Thumbs.loadLocal(iv, e.uri.toString()) {}
            box.getChildAt(1).visibility = if (e.video) View.VISIBLE else View.GONE   // 동영상
            v.setBackgroundColor(if (e.key in sel) ui.select else 0)
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = (if (e.video) "동영상 · " else "") + e.name
            val from = e.rel.trimEnd('/').ifEmpty { "(맨 위)" }
            (col.getChildAt(1) as TextView).text = if (e.srvPath != null && e.state == 1) "$from  →  ${e.srvPath}" else from
            val third = col.getChildAt(2) as TextView
            val tag = v.getChildAt(2) as TextView
            val now = Backup.current
            when {
                e.state == 0 && now != null && now.key == e.key -> { third.text = "올리는 중 ${Fmt.size(now.bytes)} / ${Fmt.size(e.size)}"; third.setTextColor(ui.accent); tag.text = "올리는 중"; tag.background = ui.round(ui.accent, 10); tag.setTextColor(ui.onAccent) }
                e.state == 0 -> { third.text = "${Fmt.size(e.size)} · 찍은 때 ${df.format(Date(e.taken))}"; third.setTextColor(ui.sub); tag.text = "기다림"; tag.background = ui.round(ui.panel2, 10); tag.setTextColor(ui.sub) }
                e.state == 1 -> { third.text = "${Fmt.size(e.size)} · ${if (e.at > 0) df.format(Date(e.at)) else ""}"; third.setTextColor(ui.sub)
                    tag.text = when (e.how) { 1 -> "올림"; 2 -> "이미 있음"; else -> "완료" }; tag.background = ui.round(if (e.how == 2) ui.panel2 else 0xFF1F8A4C.toInt(), 10); tag.setTextColor(if (e.how == 2) ui.sub else android.graphics.Color.WHITE) }
                else -> { third.text = e.err ?: "까닭 모름"; third.setTextColor(ui.red); tag.text = "실패"; tag.background = ui.round(ui.red, 10); tag.setTextColor(android.graphics.Color.WHITE) }
            }
            return v
        }
    }
}
