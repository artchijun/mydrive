package kr.artchijun.myphoto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 백업 화면: 한눈에 상태 (완료·남음·실패, 지금 올리는 파일, 마지막 성공), 지금 백업·멈추기, 실패 다시 시도,
 * 설정 (켜기, 올릴 기기 폴더, 휴대폰 폴더 고르기, 와이파이·충전·동영상·연/월 폴더), 최근 기록
 */
class BackupActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var st: Backup.Settings
    private val bg = Executors.newSingleThreadExecutor()
    private val h = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var stateBig: TextView
    private lateinit var stateSub: TextView
    private lateinit var bar: ProgressBar
    private lateinit var cur: TextView
    private lateinit var curBox: LinearLayout
    private lateinit var curThumb: android.widget.ImageView
    private lateinit var curName: TextView
    private lateinit var curWhere: TextView
    private lateinit var nextBox: LinearLayout
    private lateinit var countRow: LinearLayout
    private var curKey = 0L
    private lateinit var logBox: LinearLayout
    private val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); st = Backup.Settings(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("사진 백업", 18f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        body = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(32)) }
        val root = ui.col(top, ScrollView(this).apply { addView(body); layoutParams = ui.lp(h = 0, weight = 1f); setBackgroundColor(ui.bg) })
        ui.insets(root)
        setContentView(root)
        build()
    }

    override fun onResume() { super.onResume(); tick() }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    private fun card(vararg kids: View) = ui.col(*kids).apply { background = ui.round(ui.panel2, 12); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); layoutParams = ui.lp(top = 12) }
    private fun head(s: String) = ui.label(s, 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 20) }

    /** 켜고 끄는 줄: 제목 + 짧은 설명 */
    private fun toggle(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit): View {
        val sw = Switch(this).apply { isChecked = on }
        val v = ui.row(ui.col(ui.label(title, 15f, ui.text), ui.label(desc, 12.5f, ui.sub)).apply { layoutParams = ui.lp(0, weight = 1f) }, sw)
            .apply { setPadding(0, ui.dp(8), 0, ui.dp(8)); background = ui.press(null) }
        sw.setOnCheckedChangeListener { _, b -> set(b) }
        v.setOnClickListener { sw.toggle() }
        return v
    }
    private fun item(title: String, desc: String, onClick: () -> Unit) =
        ui.row(ui.col(ui.label(title, 15f, ui.text), ui.label(desc, 12.5f, ui.sub)).apply { layoutParams = ui.lp(0, weight = 1f) }, android.widget.ImageView(this).apply { setImageDrawable(Glyph("chev", ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)) })
            .apply { setPadding(0, ui.dp(10), 0, ui.dp(10)); background = ui.press(null); setOnClickListener { onClick() } }

    private fun build() {
        body.removeAllViews()
        // ---- 상태 ----
        stateBig = ui.label("", 22f, ui.text, bold = true)
        stateSub = ui.label("", 13.5f, ui.sub).apply { layoutParams = ui.lp(top = 4) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; layoutParams = ui.lp(top = 12); visibility = View.GONE }
        cur = ui.label("", 12.5f, ui.sub).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
        // 지금 올리는 파일: 썸네일 · 이름 · 휴대폰 폴더 → 서버 폴더 · 보낸 양·빠르기·남은 시간
        curThumb = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = ui.round(ui.panel, 8); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { rightMargin = ui.dp(12) } }
        curName = ui.label("", 14.5f, ui.text, bold = true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        curWhere = ui.label("", 12f, ui.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        curBox = ui.row(curThumb, ui.col(ui.label("지금 올리는 중", 12f, ui.accent, bold = true), curName, curWhere, cur).apply { layoutParams = ui.lp(0, weight = 1f) })
            .apply { layoutParams = ui.lp(top = 14); gravity = Gravity.TOP; visibility = View.GONE }
        nextBox = ui.col().apply { layoutParams = ui.lp(top = 10); visibility = View.GONE }
        // 개수: 기다림 · 올림 · 이미 있음 · 실패 — 누르면 파일 목록
        countRow = ui.row().apply { layoutParams = ui.lp(top = 12) }
        val now = ui.button("지금 백업") { startNow() }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { rightMargin = ui.dp(8) } }
        val stop = ui.button("멈추기", primary = false) { BackupService.stopAsked = true; tick() }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(46), 1f) }
        val free = ui.button("공간 확보 — 백업된 사진을 휴대폰에서 지우기", primary = false) { startActivity(android.content.Intent(this, FreeUpActivity::class.java)) }.apply { layoutParams = ui.lp(h = ui.dp(46), top = 8) }
        body.addView(card(stateBig, stateSub, countRow, curBox, bar, nextBox, ui.row(now, stop).apply { layoutParams = ui.lp(top = 14) }, free,
            ui.button("파일별 백업 기록 보기", primary = false) { startActivity(android.content.Intent(this, BackupListActivity::class.java)) }.apply { layoutParams = ui.lp(h = ui.dp(46), top = 8) }))

        // ---- 설정 ----
        body.addView(head("설정"))
        body.addView(card(
            toggle("자동 백업", "새 사진이 생기면 알아서 올림 (앱을 닫아도)", st.on) { b -> st.on = b; if (b) askPermissions(); BackupSchedule.schedule(this); tick() },
            item("올릴 곳", "공유 공간 / " + st.root.ifEmpty { "(정해 주세요)" } + "  — 예전 시놀로지 포토 백업과 같은 폴더면 이미 있는 사진은 건너뜀") { askRoot() },
            item("백업할 휴대폰 폴더", BucketPickActivity.describe(st.buckets).ifEmpty { "(고르지 않음)" }) { pickBuckets() },
            toggle("와이파이에서만", "모바일 데이터를 쓰지 않음", st.wifiOnly) { b -> st.wifiOnly = b; BackupSchedule.schedule(this) },
            toggle("충전 중에만", "배터리를 아낌", st.chargingOnly) { b -> st.chargingOnly = b; BackupSchedule.schedule(this) },
            toggle("동영상도", "끄면 사진만 올림", st.videos) { b -> st.videos = b },
            toggle("연/월 폴더로 나누기", "…/Camera/2026/11 처럼 찍은 달마다 폴더", st.byMonth) { b -> st.byMonth = b },
            item("배터리 최적화 빼기", if (ignoringBattery()) "빠져 있음 — 배경에서도 끊기지 않음" else "권장 — 휴대폰이 배경 백업을 멈추지 않게") { askBattery() },
        ))

        // ---- 실패 ----
        body.addView(head("실패한 파일"))
        val failBox = ui.col()
        body.addView(card(failBox, ui.button("실패한 것 다시 시도", primary = false) { Backup.retryFailed(this); startNow() }.apply { layoutParams = ui.lp(h = ui.dp(44), top = 10) }))
        bg.execute {
            val f = Backup.failures(this)
            runOnUiThread {
                if (f.isEmpty()) failBox.addView(ui.label("없음", 13.5f, ui.sub))
                f.take(30).forEach { (p, e) -> failBox.addView(ui.col(ui.label(p, 13.5f, ui.text).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, ui.label(e, 12f, ui.red)).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }) }
            }
        }

        // ---- 기록 ----
        body.addView(head("최근 기록"))
        logBox = ui.col()
        body.addView(card(logBox))
    }

    private fun tick() {
        h.removeCallbacksAndMessages(null)
        bg.execute {
            val c = try { Backup.counts(this) } catch (e: Exception) { null }
            val next = try { Backup.entries(this, 0, 5) } catch (e: Exception) { emptyList() }
            val log = try { Backup.recentLog(this, 40) } catch (e: Exception) { emptyList() }
            val block = Backup.blocker(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val p = Backup.current
                stateBig.text = when {
                    !st.on -> "자동 백업 꺼짐"
                    Backup.running -> "백업 중"
                    c != null && c.waiting == 0 && c.failed == 0 -> "모두 백업됨"
                    block != null -> block
                    else -> "대기 중"
                }
                stateSub.text = "마지막 성공: " + (if (st.lastOk > 0) df.format(Date(st.lastOk)) else "아직 없음") +
                    (if (c != null && c.waitBytes > 0) "  ·  남은 양 ${Fmt.size(c.waitBytes)}" else "") +
                    (if (st.lastMsg.isNotEmpty()) "\n" + st.lastMsg else "")
                if (c != null) paintCounts(c)
                if (p != null) {
                    bar.visibility = View.VISIBLE; cur.visibility = View.VISIBLE; curBox.visibility = View.VISIBLE
                    bar.progress = if (p.size > 0) (p.bytes * 1000 / p.size).toInt() else 0
                    val sec = (System.currentTimeMillis() - p.started) / 1000.0
                    val speed = if (sec > 0.5 && p.bytes > 0) p.bytes / sec else 0.0
                    val left = if (speed > 0) ((p.size - p.bytes) / speed).toLong() else -1
                    curName.text = "${p.name}  (${p.done}/${p.total})"
                    curWhere.text = "휴대폰 ${p.rel.trimEnd('/').ifEmpty { "(맨 위)" }}  →  서버 ${p.target}"
                    cur.text = "${Fmt.size(p.bytes)} / ${Fmt.size(p.size)}" + (if (speed > 0) "  ·  ${Fmt.size(speed.toLong())}/s" else "") +
                        (if (left >= 0) "  ·  " + (if (left < 60) "${left}초 남음" else "약 ${(left + 59) / 60}분 남음") else "")
                    if (p.key != curKey) { curKey = p.key; loadThumb(p.key, curThumb) }
                } else { bar.visibility = View.GONE; cur.visibility = View.GONE; curBox.visibility = View.GONE; curKey = 0 }
                // 다음 차례
                nextBox.removeAllViews()
                val upcoming = next.filter { it.key != p?.key }.take(3)
                if (upcoming.isNotEmpty() && (Backup.running || (c?.waiting ?: 0) > 0)) {
                    nextBox.visibility = View.VISIBLE
                    nextBox.addView(ui.label(if (Backup.running) "다음 차례" else "올릴 차례 (기다림)", 12f, ui.sub, bold = true))
                    upcoming.forEach { e -> nextBox.addView(ui.label("· ${e.name}   ${e.rel.trimEnd('/')} · ${Fmt.size(e.size)}", 12.5f, ui.text).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, ui.dp(2), 0, 0) }) }
                } else nextBox.visibility = View.GONE
                logBox.removeAllViews()
                if (log.isEmpty()) logBox.addView(ui.label("아직 없음", 13.5f, ui.sub))
                log.forEach { (t, m) -> logBox.addView(ui.row(ui.label(df.format(Date(t)), 12f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(92), -2) }, ui.label(m, 12.5f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); gravity = Gravity.TOP }) }
            }
        }
        h.postDelayed({ tick() }, if (Backup.running) 1000 else 4000)
    }

    /** 개수 칩: 기다림 · 올림 · 이미 있음 · 실패 (누르면 그 목록) */
    private fun paintCounts(c: Backup.Counts) {
        countRow.removeAllViews()
        val other = c.done - c.uploaded - c.skipped   // 예전 기록 (어떻게 끝났는지 모름)
        listOf(Triple("기다림", c.waiting, 0), Triple("올림", c.uploaded + other, 1), Triple("이미 있음", c.skipped, 2), Triple("실패", c.failed, 3)).forEach { (name, n, tab) ->
            countRow.addView(ui.col(ui.label("%,d".format(n), 17f, if (tab == 3 && n > 0) ui.red else ui.text, bold = true).apply { gravity = Gravity.CENTER },
                ui.label(name, 11.5f, ui.sub).apply { gravity = Gravity.CENTER }).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(58), 1f).apply { if (tab < 3) rightMargin = ui.dp(6) }
                background = ui.press(ui.round(ui.panel, 10), 10)
                setOnClickListener { startActivity(android.content.Intent(this@BackupActivity, BackupListActivity::class.java).putExtra("tab", tab)) }
            })
        }
    }
    private fun loadThumb(key: Long, iv: android.widget.ImageView) {
        iv.setImageDrawable(null)
        val uri = android.content.ContentUris.withAppendedId(if (key % 2 == 1L) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key / 2)
        Thumbs.loadLocal(iv, uri.toString()) {}
    }

    private fun startNow() {
        if (!st.on) { st.on = true; BackupSchedule.schedule(this); build() }
        if (!hasMediaPermission()) { askPermissions(); return }
        if (st.root.isEmpty()) { askRoot(); return }
        BackupService.start(this); tick()
    }

    // ---------- 고르기 ----------
    private fun askRoot() {
        // 공유 공간 맨 위 폴더들 중에서 고르거나 새 이름
        val spin = AlertDialog.Builder(this).setMessage("폴더 목록을 읽는 중…").show()
        bg.execute {
            val names = try { Photos(this).folders(Photos.TEAM, 1).map { it.name } } catch (e: Exception) { emptyList() }
            runOnUiThread {
                spin.dismiss()
                val items = (names + "새 이름…").toTypedArray()
                AlertDialog.Builder(this).setTitle("올릴 기기 폴더 (공유 공간)").setItems(items) { _, i ->
                    if (i < names.size) { st.root = names[i]; build() }
                    else {
                        val e = ui.input("예: JUN PHONE", Build.MODEL ?: "")
                        AlertDialog.Builder(this).setTitle("새 기기 폴더 이름").setView(ui.col(e).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) })
                            .setPositiveButton("확인") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let { st.root = it; build() } }.setNegativeButton("그만", null).show()
                    }
                }.show()
            }
        }
    }

    private fun pickBuckets() {
        if (!hasMediaPermission()) { askPermissions(); return }
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, BucketPickActivity::class.java), 51)
    }
    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req == 51 && res == RESULT_OK) build()
    }

    // ---------- 권한 ----------
    private fun hasMediaPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33)
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) { want += Manifest.permission.READ_MEDIA_IMAGES; want += Manifest.permission.READ_MEDIA_VIDEO; want += Manifest.permission.POST_NOTIFICATIONS }
        else want += Manifest.permission.READ_EXTERNAL_STORAGE
        want += Manifest.permission.ACCESS_MEDIA_LOCATION
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7)
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) { super.onRequestPermissionsResult(code, perms, res); build(); tick() }

    private fun ignoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        if (ignoringBattery()) return
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
