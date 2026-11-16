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
        cur = ui.label("", 12.5f, ui.sub).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; visibility = View.GONE }
        val now = ui.button("지금 백업") { startNow() }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { rightMargin = ui.dp(8) } }
        val stop = ui.button("멈추기", primary = false) { BackupService.stopAsked = true; tick() }.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(46), 1f) }
        val free = ui.button("공간 확보 — 백업된 사진을 휴대폰에서 지우기", primary = false) { startActivity(android.content.Intent(this, FreeUpActivity::class.java)) }.apply { layoutParams = ui.lp(h = ui.dp(46), top = 8) }
        body.addView(card(stateBig, stateSub, bar, cur, ui.row(now, stop).apply { layoutParams = ui.lp(top = 14) }, free))

        // ---- 설정 ----
        body.addView(head("설정"))
        body.addView(card(
            toggle("자동 백업", "새 사진이 생기면 알아서 올림 (앱을 닫아도)", st.on) { b -> st.on = b; if (b) askPermissions(); BackupSchedule.schedule(this); tick() },
            item("올릴 곳", "공유 공간 / " + st.root.ifEmpty { "(정해 주세요)" } + "  — 예전 시놀로지 포토 백업과 같은 폴더면 이미 있는 사진은 건너뜀") { askRoot() },
            item("백업할 휴대폰 폴더", st.buckets.sorted().joinToString(", ").ifEmpty { "(고르지 않음)" }) { pickBuckets() },
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
                stateSub.text = (if (c != null) "완료 ${c.done} · 남음 ${c.waiting}" + (if (c.waitBytes > 0) " (${Fmt.size(c.waitBytes)})" else "") + (if (c.failed > 0) " · 실패 ${c.failed}" else "") else "") +
                    "\n마지막 성공: " + (if (st.lastOk > 0) df.format(Date(st.lastOk)) else "아직 없음") +
                    (if (st.lastMsg.isNotEmpty()) "\n" + st.lastMsg else "")
                if (p != null) {
                    bar.visibility = View.VISIBLE; cur.visibility = View.VISIBLE
                    bar.progress = if (p.size > 0) (p.bytes * 1000 / p.size).toInt() else 0
                    cur.text = "${p.done}/${p.total}  ${p.name}  ${Fmt.size(p.bytes)} / ${Fmt.size(p.size)}"
                } else { bar.visibility = View.GONE; cur.visibility = View.GONE }
                logBox.removeAllViews()
                if (log.isEmpty()) logBox.addView(ui.label("아직 없음", 13.5f, ui.sub))
                log.forEach { (t, m) -> logBox.addView(ui.row(ui.label(df.format(Date(t)), 12f, ui.sub).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(92), -2) }, ui.label(m, 12.5f, ui.text).apply { layoutParams = ui.lp(0, weight = 1f) }).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); gravity = Gravity.TOP }) }
            }
        }
        h.postDelayed({ tick() }, if (Backup.running) 1000 else 4000)
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
        bg.execute {
            val all = try { Backup.deviceBuckets(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                val cur = st.buckets.toMutableSet()
                val list = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
                all.forEach { (rel, n) ->
                    list.addView(CheckBox(this).apply {
                        text = "${rel.trimEnd('/')}  ($n)"; isChecked = rel in cur; setTextColor(ui.text)
                        setOnCheckedChangeListener { _, b -> if (b) cur += rel else cur -= rel }
                    })
                }
                if (all.isEmpty()) list.addView(ui.label("휴대폰에 사진 폴더가 없습니다", 14f, ui.sub))
                AlertDialog.Builder(this).setTitle("백업할 휴대폰 폴더").setView(ScrollView(this).apply { addView(list) })
                    .setPositiveButton("확인") { _, _ -> st.buckets = cur; build() }.setNegativeButton("그만", null).show()
            }
        }
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
