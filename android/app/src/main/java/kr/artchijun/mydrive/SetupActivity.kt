package kr.artchijun.mydrive

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread

// 처음 연결: 서버 주소 + DSM 계정 → 이 휴대폰 전용 열쇠(토큰)를 받음. 비밀번호는 저장하지 않음
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this); val pf = Prefs(this)
        val home = ui.input("집 안 주소 (예: http://192.168.0.6:8642)", pf.home)
        val out = ui.input("인터넷 주소 (https://… — 집 밖에서)", pf.outside)
        val acc = ui.input("DSM 아이디", pf.user)
        val pw = ui.input("DSM 비밀번호", password = true)
        val otp = ui.input("2단계 인증 코드 (쓰는 경우만)")
        val status = ui.label("", 13f, ui.sub)
        lateinit var go: android.widget.Button
        go = ui.button("연결") {
            val servers = listOf(home.text.toString(), out.text.toString()).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
            if (servers.isEmpty() || acc.text.isBlank() || pw.text.isEmpty()) { status.text = "주소, 아이디, 비밀번호를 넣어 주세요"; return@button }
            go.isEnabled = false; status.text = "연결하는 중…"
            val a = acc.text.toString().trim(); val p = pw.text.toString(); val o = otp.text.toString().trim()
            thread {
                var err: String? = null; var token = ""
                // 되는 주소로 연결 (집 안 → 인터넷)
                val s = servers.firstOrNull { Api.ping(it, if (it.startsWith("https")) 6000 else 2500) }
                if (s == null) err = "어느 주소로도 NAS 에 닿지 않습니다. 주소와 와이파이·데이터 연결을 확인해 주세요."
                else try { token = Api.pair(s, a, p, o, "휴대폰 " + Build.MANUFACTURER + " " + Build.MODEL) } catch (e: Exception) { err = e.message }
                runOnUiThread {
                    go.isEnabled = true
                    if (err != null) { status.text = err; return@runOnUiThread }
                    pf.home = home.text.toString().trim().trimEnd('/'); pf.outside = out.text.toString().trim().trimEnd('/')
                    pf.user = a; pf.token = token
                    Toast.makeText(this, "연결했습니다", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java).apply { intent?.let { putExtras(it) ; action = it.action; type = it.type; clipData = it.clipData; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } })
                    finish()
                }
            }
        }
        val form = ui.col(
            ui.label("Mydrive", 26f, ui.text, bold = true),
            ui.label("NAS 에 연결합니다. 집 안 주소와 인터넷 주소 중 되는 쪽을 자동으로 씁니다.", 14f, ui.sub).apply { layoutParams = ui.lp(top = 6) },
            ui.label("서버 주소", 13f, ui.sub).apply { layoutParams = ui.lp(top = 22) }, home.apply { layoutParams = ui.lp(top = 6) }, out.apply { layoutParams = ui.lp(top = 8) },
            ui.label("DSM 계정 (관리자) — 이 휴대폰 전용 열쇠를 받는 데만 쓰고 저장하지 않습니다", 13f, ui.sub).apply { layoutParams = ui.lp(top = 22) },
            acc.apply { layoutParams = ui.lp(top = 6) }, pw.apply { layoutParams = ui.lp(top = 8) }, otp.apply { layoutParams = ui.lp(top = 8) },
            go.apply { layoutParams = ui.lp(top = 20) }, status.apply { layoutParams = ui.lp(top = 12) },
        ).apply { setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28)) }
        val root = ScrollView(this).apply { setBackgroundColor(ui.bg); addView(form) }
        ui.insets(root)
        setContentView(root)
    }
}
