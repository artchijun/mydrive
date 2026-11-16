package kr.artchijun.myphoto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import java.util.concurrent.Executors

/** 시놀로지 계정으로 로그인 (시놀로지 포토 앱과 같은 DSM 계정). 2단계 인증이 켜져 있으면 코드 칸이 나옴 */
class LoginActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }
    private val bg = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this); val pf = Prefs(this)
        window.statusBarColor = ui.bg; window.navigationBarColor = ui.bg

        val home = ui.input("집 안 주소 (예: http://192.168.0.6:5000)", pf.home)
        val outside = ui.input("밖에서 쓸 주소 (예: https://photo.도메인)", pf.outside)
        val acct = ui.input("계정", pf.account)
        val pw = ui.input("비밀번호", password = true)
        val otp = ui.input("2단계 인증 코드 (6자리)").apply { visibility = View.GONE; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val msg = ui.label("", 13.5f, ui.red).apply { visibility = View.GONE }
        val spin = ProgressBar(this).apply { visibility = View.GONE }
        lateinit var go: android.widget.Button
        go = ui.button("로그인") {
            pf.home = home.text.toString(); pf.outside = outside.text.toString()
            val a = acct.text.toString().trim(); val p = pw.text.toString(); val o = otp.text.toString()
            if (a.isEmpty() || p.isEmpty()) { msg.text = "계정과 비밀번호를 넣어 주세요"; msg.visibility = View.VISIBLE; return@button }
            msg.visibility = View.GONE; spin.visibility = View.VISIBLE; go.isEnabled = false
            bg.execute {
                val err = try { Syno(this).login(a, p, o.ifBlank { null }); null } catch (e: SynoError) { e } catch (e: Exception) { e }
                runOnUiThread {
                    spin.visibility = View.GONE; go.isEnabled = true
                    if (err == null) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); return@runOnUiThread }
                    if (err is SynoError && err.code in setOf(403, 404)) { otp.visibility = View.VISIBLE; otp.requestFocus() }
                    msg.text = err.message ?: "로그인하지 못했습니다"; msg.visibility = View.VISIBLE
                }
            }
        }

        fun hint(s: String) = ui.label(s, 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 4) }
        val col = ui.col(
            ui.label("Myphoto", 28f, ui.text, bold = true),
            ui.label("시놀로지 포토 계정으로 로그인", 15f, ui.sub).apply { layoutParams = ui.lp(top = 4) },
            ui.label("시놀로지 주소", 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 28) },
            home.apply { layoutParams = ui.lp(top = 8) }, hint("집 와이파이에서 쓰는 주소. 닿으면 이 주소를 먼저 씀"),
            outside.apply { layoutParams = ui.lp(top = 12) }, hint("밖에서 쓰는 주소 (비워 두면 집에서만 씀)"),
            ui.label("계정", 13f, ui.accent, bold = true).apply { layoutParams = ui.lp(top = 24) },
            acct.apply { layoutParams = ui.lp(top = 8) }, pw.apply { layoutParams = ui.lp(top = 10) }, otp.apply { layoutParams = ui.lp(top = 10) },
            hint("비밀번호는 이 휴대폰의 열쇠 보관소로 잠가 저장합니다 — 세션이 끝나도 백업이 멈추지 않게 앱이 다시 로그인함"),
            msg.apply { layoutParams = ui.lp(top = 12) },
            ui.row(spin).apply { layoutParams = ui.lp(top = 8); gravity = android.view.Gravity.CENTER },
            go.apply { layoutParams = ui.lp(h = ui.dp(50), top = 12) },
        ).apply { setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(32)) }
        val root = ScrollView(this).apply { setBackgroundColor(ui.bg); addView(col); isFillViewport = true }
        ui.insets(root)
        setContentView(root)
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }
}
