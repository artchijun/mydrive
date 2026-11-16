package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

/** 설정: 시놀로지 주소·계정, 화면, 캐시, 새 판. 항목마다 짧은 설명 */
class SettingsActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var body: LinearLayout
    private val bg = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("설정", 18f, ui.text, bold = true).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(4) } })
            .apply { setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4)) }
        body = ui.col().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(32)) }
        val root = ui.col(top, ScrollView(this).apply { addView(body); layoutParams = ui.lp(h = 0, weight = 1f); setBackgroundColor(ui.bg) })
        ui.insets(root)
        setContentView(root)
        build()
    }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    private fun head(s: String) = ui.label(s, 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 20) }
    private fun card(vararg kids: View) = ui.col(*kids).apply { background = ui.round(ui.panel2, 12); setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)); layoutParams = ui.lp(top = 10) }
    private fun item(title: String, desc: String, onClick: () -> Unit) =
        ui.col(ui.label(title, 15f, ui.text), ui.label(desc, 12.5f, ui.sub)).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)); background = ui.press(null); setOnClickListener { onClick() } }

    private fun edit(title: String, hint: String, value: String, done: (String) -> Unit) {
        val e = ui.input(hint, value)
        AlertDialog.Builder(this).setTitle(title).setView(ui.col(e).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) })
            .setPositiveButton("확인") { _, _ -> done(e.text.toString()); build() }.setNegativeButton("그만", null).show()
    }

    private fun build() {
        body.removeAllViews()
        body.addView(head("시놀로지"))
        body.addView(card(
            item("계정", pf.account.ifEmpty { "(로그인 안 됨)" } + " — 누르면 로그아웃") {
                AlertDialog.Builder(this).setTitle("로그아웃할까요?").setMessage("자동 백업도 멈춥니다. 다시 쓰려면 로그인하세요.")
                    .setPositiveButton("로그아웃") { _, _ -> bg.execute { Syno(this).logout(); runOnUiThread {
                        Backup.Settings(this).on = false; BackupSchedule.schedule(this)
                        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() } } }
                    .setNegativeButton("그만", null).show()
            },
            item("집 안 주소", pf.home.ifEmpty { "(없음)" } + " — 집 와이파이에서 먼저 씀") { edit("집 안 주소", "http://192.168.0.6:5000", pf.home) { pf.home = it } },
            item("밖 주소", pf.outside.ifEmpty { "(없음 — 집에서만 씀)" } + " — 집 주소가 안 닿을 때 (Mydrive 서버가 터널로 이어 줌, 큰 파일은 나눠 올림)") { edit("밖 주소", "https://drive.cdluos.com", pf.outside) { pf.outside = it } },
            item("연결 확인", "지금 어느 주소로 닿는지 봄") {
                bg.execute { val r = try { Syno(this).server(true) } catch (e: Exception) { "닿지 않음: ${e.message}" }; runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }
            },
        ))
        body.addView(head("화면"))
        val themes = arrayOf("어둡게 (기본)", "밝게", "휴대폰 설정 따름")
        body.addView(card(
            item("테마", themes[pf.int("theme", 0)]) {
                AlertDialog.Builder(this).setTitle("테마").setSingleChoiceItems(themes, pf.int("theme", 0)) { d, i -> pf.putInt("theme", i); d.dismiss(); recreate() }.show()
            },
            item("한 줄 사진 수", "${pf.int("cols", 4)}칸 — 사진 화면에서 두 손가락으로 오므리거나 벌려도 바뀜") {
                val opts = arrayOf("3", "4", "5", "6", "7", "8")
                AlertDialog.Builder(this).setTitle("한 줄 사진 수").setSingleChoiceItems(opts, pf.int("cols", 4) - 3) { d, i -> pf.putInt("cols", i + 3); d.dismiss(); build() }.show()
            },
            item("썸네일 캐시 비우기", "휴대폰에 저장해 둔 작은 그림 (${Fmt.size(Thumbs.diskSize(this))}) — 다시 볼 때 새로 받음") { Thumbs.clear(this); build() },
        ))
        body.addView(head("앱"))
        body.addView(card(
            item("판", "Myphoto " + Update.current(this) + " — 누르면 새 판 확인") { checkUpdate() },
        ))
    }

    private fun checkUpdate() {
        Toast.makeText(this, "확인 중…", Toast.LENGTH_SHORT).show()
        bg.execute {
            val info = try { Update.latest() } catch (e: Exception) { null }
            runOnUiThread {
                if (info == null || !Update.newer(info.version, Update.current(this))) { Toast.makeText(this, "지금 판이 가장 새것입니다", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("새 판 ${info.version}").setMessage(info.notes.ifEmpty { "새 판이 있습니다" })
                    .setPositiveButton("받아서 설치") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.page))) }.setNegativeButton("나중에", null).show()
            }
        }
    }
}
