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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 파일별 백업 기록: [기다림 | 올림 | 이미 있음 | 실패]
 *   기다림: 올릴 차례대로 (지금 올리는 파일은 맨 위에 '올리는 중')
 *   올림·이미 있음: 최근 것부터 — 언제, 휴대폰 어느 폴더에서 서버 어느 폴더로
 *   실패: 까닭
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        tab = intent.getIntExtra("tab", 1).coerceIn(0, 3)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("백업 기록", 19f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)); minimumHeight = ui.dp(56) }
        seg = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(4)) }
        info = ui.label("", 12.5f, ui.sub).apply { setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(6)) }
        list = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); adapter = this@BackupListActivity.adapter }
        val root = ui.col(top, seg, info, list.apply { layoutParams = ui.lp(h = 0, weight = 1f) }).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
    }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }
    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

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
                    1 -> if (r.isEmpty()) "아직 올린 파일이 없습니다." else "이 휴대폰에서 서버로 올린 파일 (최근 것부터)"
                    2 -> if (r.isEmpty()) "없습니다." else "서버의 같은 폴더에 이름·크기가 같은 것이 이미 있어 올리지 않고 백업된 것으로 친 파일"
                    else -> if (r.isEmpty()) "실패한 파일이 없습니다." else "다음 백업 때 다시 시도합니다 (6번까지). 백업 화면의 [실패한 것 다시 시도]로 바로 다시"
                } + (if (r.size >= 3000) "  — 최근 3,000개만 보입니다" else "")
                adapter.notifyDataSetChanged()
            }
        }
        // 백업 중이면 1초마다 새로
        h.postDelayed({ load() }, if (Backup.running) 1500 else 5000)
    }

    private fun paintSeg(c: Backup.Counts?) {
        seg.removeAllViews()
        val n = if (c == null) listOf(0, 0, 0, 0) else listOf(c.waiting, c.done - c.skipped, c.skipped, c.failed)
        names.forEachIndexed { i, s ->
            val sel = i == tab
            seg.addView(ui.col(ui.label("%,d".format(n[i]), 15f, if (sel) ui.onAccent else ui.text, bold = true).apply { gravity = Gravity.CENTER },
                ui.label(s, 11.5f, if (sel) ui.onAccent else ui.sub).apply { gravity = Gravity.CENTER }).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { if (i < 3) rightMargin = ui.dp(6) }
                background = ui.press(ui.round(if (sel) ui.accent else ui.panel2, 12), 12)
                setOnClickListener { tab = i; rows = emptyList(); adapter.notifyDataSetChanged(); load() }
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
                ImageView(this@BackupListActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(ui.panel2, 8); clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { rightMargin = ui.dp(12) } },
                ui.col(ui.label("", 14.5f, ui.text).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                    ui.label("", 12f, ui.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                    ui.label("", 12f, ui.sub).apply { setSingleLine() }).apply { layoutParams = ui.lp(0, weight = 1f) },
                ui.label("", 11.5f, ui.text, bold = true).apply { setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)) },
            ).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(14), ui.dp(8)); gravity = Gravity.CENTER_VERTICAL }
            val iv = v.getChildAt(0) as ImageView
            Thumbs.loadLocal(iv, e.uri.toString()) {}
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = e.name
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
