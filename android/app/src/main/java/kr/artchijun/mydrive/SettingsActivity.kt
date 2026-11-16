package kr.artchijun.mydrive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * 설정: 화면(테마·화면 크기·아이콘 크기·미리보기…), 홈(보일 NAS 폴더·NAS 다른 폴더), 연결(주소·계정), 정보.
 *   항목마다 짧은 설명을 붙임. 바꾸면 "stamp" 를 올려 탐색기가 돌아올 때 다시 그림.
 */
class SettingsActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Ui.wrap(base)) }

    private lateinit var ui: Ui
    private lateinit var pf: Prefs
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = Ui(this); if (!ui.dark) setTheme(R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        pf = Prefs(this)
        window.statusBarColor = ui.panel; window.navigationBarColor = ui.panel
        if (!ui.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val top = ui.row(ui.iconBtn(Glyph("back", ui.text), "뒤로") { finish() }, ui.label("설정", 18f, ui.text, bold = true).apply { setPadding(ui.dp(8), 0, 0, 0) }).apply {
            setBackgroundColor(ui.panel); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        body = ui.col().apply { setPadding(0, 0, 0, ui.dp(32)) }
        val root = ui.col(top, ScrollView(this).apply { addView(body); layoutParams = ui.lp(h = 0, weight = 1f) }).apply { setBackgroundColor(ui.bg) }
        ui.insets(root)
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun changed(recreateMe: Boolean = false) {
        pf.putInt("stamp", pf.int("stamp", 0) + 1)
        if (recreateMe) recreate() else render()
    }

    // ---------- 줄 만들기 ----------
    private fun head(s: String) = body.addView(ui.label(s, 13f, ui.accent, bold = true).apply { setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(6)) })

    private fun item(icon: String, title: String, desc: String, value: String = "", sw: Boolean? = null, onClick: () -> Unit) {
        val t = ui.label(title, 16f, ui.text)
        val d = ui.label(desc, 12.5f, ui.sub).apply { layoutParams = ui.lp(top = 2) }
        val text = ui.col(t, d).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(18) } }
        if (value.isNotEmpty()) text.addView(ui.label(value, 13f, ui.accent).apply { layoutParams = ui.lp(top = 3); setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE })
        val row = ui.row(ImageView(this).apply { setImageDrawable(Glyph(icon, ui.sub)); layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)) }, text).apply {
            setPadding(ui.dp(20), ui.dp(13), ui.dp(16), ui.dp(13)); background = ui.press(null)
            setOnClickListener { onClick() }
        }
        if (sw != null) row.addView(Switch(this).apply { isChecked = sw; isClickable = false; isFocusable = false; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(10) } })
        body.addView(row)
    }

    private fun choose(title: String, names: Array<String>, now: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names, now) { d, w -> d.dismiss(); if (w != now) pick(w) }.show()
    }

    private fun render() {
        body.removeAllViews()
        // ===== 화면 =====
        head("화면")
        val themes = arrayOf("어두운 화면 (Myplorer 기본)", "밝은 화면", "휴대폰 설정 따름")
        val th = pf.int("theme", 0)
        item("eye", "화면 테마", "탐색기 전체의 바탕색", themes[th]) { choose("화면 테마", themes, th) { pf.putInt("theme", it); changed(true) } }
        val scales = intArrayOf(85, 100, 115, 130); val scaleNames = arrayOf("작게 (85%)", "보통 (100%)", "크게 (115%)", "아주 크게 (130%)")
        val sc = scales.indexOf(pf.int("uiScale", 100)).coerceAtLeast(1)
        item("grid", "화면 크기 (해상도)", "글자·아이콘·여백을 함께 키우거나 줄입니다. 작게 하면 한 화면에 더 많이", scaleNames[sc]) {
            choose("화면 크기", scaleNames, sc) { pf.putInt("uiScale", scales[it]); changed(true) }
        }
        val sizes = arrayOf("작게", "보통", "크게")
        val ri = pf.int("rowIcon", 1)
        item("image", "목록 아이콘 크기", "파일 목록 왼쪽 아이콘과 사진 미리보기 크기", sizes[ri]) { choose("목록 아이콘 크기", sizes, ri) { pf.putInt("rowIcon", it); changed() } }
        val hi = pf.int("tileSize", 1)
        item("home", "홈 아이콘 크기", "홈 바둑판 아이콘 크기. 작게 하면 한 줄에 더 많이 놓입니다", sizes[hi]) { choose("홈 아이콘 크기", sizes, hi) { pf.putInt("tileSize", it); changed() } }
        val colsNames = arrayOf("자동 (아이콘 크기에 맞춤)", "3개", "4개", "5개")
        val hc = pf.int("homeCols", 4).let { if (it in 3..5) it - 2 else 0 }
        item("grid", "홈 한 줄 아이콘 수", "홈 바둑판 한 줄에 놓을 아이콘 수. 많이 놓으면 아이콘이 칸에 맞게 작아집니다", colsNames[hc]) {
            choose("홈 한 줄 아이콘 수", colsNames, hc) { pf.putInt("homeCols", if (it == 0) 0 else it + 2); changed() }
        }
        val thumbs = pf.bool("thumbs", true)
        item("image", "사진·동영상 미리보기", "목록에서 사진·동영상을 작은 그림으로 보여 줍니다 (휴대폰 파일)", sw = thumbs) { pf.putBool("thumbs", !thumbs); changed() }
        val hidden = pf.bool("hidden", false)
        item("eye", "숨김 파일 보기", "이름이 점(.)으로 시작하는 파일·폴더도 보여 줍니다", sw = hidden) { pf.putBool("hidden", !hidden); changed() }
        val viewer = pf.bool("viewer", true)
        item("openwith", "내장 뷰어로 열기", "사진·동영상·PDF·텍스트를 앱 안에서 엽니다. 끄면 휴대폰의 다른 앱으로", sw = viewer) { pf.putBool("viewer", !viewer); changed() }
        val dnt = pf.bool("drawerNewTab", true)
        item("plus", "왼쪽 메뉴는 새 탭으로", "왼쪽 메뉴에서 고른 곳을 새 탭에서 엽니다. 끄면 지금 탭에서", sw = dnt) { pf.putBool("drawerNewTab", !dnt); changed() }

        // ===== 홈 =====
        head("홈")
        val hid = hiddenNas()
        item("nas", "홈에 보일 NAS 폴더", "Mydrive 연결 폴더 가운데 홈과 왼쪽 메뉴에 보일 것만 고릅니다", if (hid.isEmpty()) "모두 보임" else "${hid.size}개 숨김") { pickNas() }

        item("globe", "NAS 다른 폴더 (웹 폴더)", "Mydrive 동기화 폴더가 아닌 NAS 폴더를 추가해, 필요할 때 웹 드라이브처럼 엽니다 (동기화 안 함)") { manageWebs() }

        // ===== 연결 =====
        head("연결")
        item("nas", if (pf.paired) "NAS 연결 다시 하기" else "NAS 연결하기", "DSM 계정으로 이 휴대폰 전용 열쇠를 받습니다 (비밀번호는 저장하지 않음)",
            if (pf.paired) "DSM 사용자: ${pf.user}" else "연결 안 됨") { startActivity(Intent(this, SetupActivity::class.java)) }
        item("home", "집 안 주소", "집 와이파이에서 쓰는 주소. 먼저 이 주소로 해 보고 안 되면 인터넷 주소로", pf.home.ifEmpty { "(없음)" }) {
            ask("집 안 주소", pf.home) { pf.home = it.trimEnd('/'); changed() }
        }
        item("globe", "인터넷 주소", "밖에서 쓰는 주소 (Cloudflare 터널)", pf.outside.ifEmpty { "(없음)" }) {
            ask("인터넷 주소", pf.outside) { pf.outside = it.trimEnd('/'); changed() }
        }
        item("refresh", "연결 확인", "두 주소에 지금 닿는지 확인합니다") { checkConn() }

        // ===== 정보 =====
        head("정보")
        item("info", "Mydrive ${Update.current(this)}", "휴대폰 파일과 NAS 폴더를 탭으로 오가는 탐색기") { }
        item("download", "새 판 확인", "새 판이 있으면 탐색기 위쪽에 [설치] 띠가 나옵니다") { pf.lastUpdateCheck = 0; Toast.makeText(this, "탐색기로 돌아가면 확인합니다", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun hiddenNas(): Set<String> = try { JSONArray(pf.str("nasHide", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (e: Exception) { emptySet() }

    private fun pickNas() {
        if (!pf.paired) { Toast.makeText(this, "먼저 NAS 에 연결해 주세요", Toast.LENGTH_SHORT).show(); return }
        thread {
            val rs = try { pf.api().roots() } catch (e: Exception) { null }
            runOnUiThread {
                if (rs == null) { Toast.makeText(this, "NAS 에 닿지 않습니다", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val names = rs.map { it.name }; val hid = hiddenNas()
                val on = BooleanArray(names.size) { names[it] !in hid }
                AlertDialog.Builder(this).setTitle("홈에 보일 NAS 폴더")
                    .setMultiChoiceItems(rs.map { r -> if (r.share.isNotEmpty()) "${r.name}  (NAS ${r.share})" else r.name }.toTypedArray(), on) { _, i, c -> on[i] = c }
                    .setPositiveButton("확인") { _, _ -> pf.putStr("nasHide", JSONArray(names.filterIndexed { i, _ -> !on[i] }).toString()); changed() }
                    .setNegativeButton("취소", null).show()
            }
        }
    }

    private fun manageWebs() {
        if (!pf.paired) { Toast.makeText(this, "먼저 NAS 에 연결해 주세요", Toast.LENGTH_SHORT).show(); return }
        val api = pf.api()
        thread {
            val ws = try { api.webs() } catch (e: Exception) { null }
            runOnUiThread {
                if (ws == null) { Toast.makeText(this, "NAS 서버를 0.6.12 이상으로 올려야 합니다", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val names = ws.map { "${it.name}   (NAS ${it.share})" }
                AlertDialog.Builder(this).setTitle("웹 폴더" + if (ws.isEmpty()) " — 없음" else " (눌러서 빼기)")
                    .setItems(names.toTypedArray()) { _, w ->
                        val n = ws[w].name
                        AlertDialog.Builder(this).setMessage("‘$n’ 을 목록에서 뺍니다. NAS 의 파일은 그대로 있습니다.")
                            .setPositiveButton("빼기") { _, _ -> thread { val ok = try { api.removeWeb(n); true } catch (e: Exception) { false }; runOnUiThread { if (ok) changed() } } }
                            .setNegativeButton("취소", null).show()
                    }
                    .setPositiveButton("폴더 추가") { _, _ -> WebPick.start(this, ui, api) { changed() } }
                    .setNegativeButton("닫기", null).show()
            }
        }
    }

    private fun checkConn() {
        Toast.makeText(this, "확인하는 중…", Toast.LENGTH_SHORT).show()
        thread {
            val r = listOf("집 안" to pf.home, "인터넷" to pf.outside).filter { it.second.isNotEmpty() }
                .joinToString("\n") { (n, u) -> "$n: " + (if (Api.ping(u, if (u.startsWith("https")) 6000 else 2500)) "닿음 ✓" else "안 닿음") + "\n   $u" }
            runOnUiThread { AlertDialog.Builder(this).setTitle("연결 확인").setMessage(r.ifEmpty { "주소가 없습니다" }).setPositiveButton("닫기", null).show() }
        }
    }

    private fun ask(title: String, value: String, ok: (String) -> Unit) {
        val e = ui.input("", value)
        val box = FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(e) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("확인") { _, _ -> ok(e.text.toString().trim()) }.setNegativeButton("취소", null).show()
    }
}
