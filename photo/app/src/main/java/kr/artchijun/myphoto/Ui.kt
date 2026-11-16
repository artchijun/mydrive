package kr.artchijun.myphoto

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// 화면을 코드로 만드는 작은 도구 (XML·외부 부품 없이 가볍게). 색은 PC 의 Myplorer·Mydrive 테마와 같게
//   어두운 화면: Windows 11 탐색기 회색 (목록 191919, 제목줄 202020, 탭·주소 2C2C2C, 선택 505050) + 하늘색 강조 60CDFF
class Ui(val a: Activity) {
    companion object {
        /** 설정의 ‘화면 크기’: 글자·아이콘·여백을 함께 (화면 밀도를 바꿔서). 모든 화면의 attachBaseContext 에서 */
        fun wrap(base: android.content.Context): android.content.Context {
            val pct = base.getSharedPreferences("myphoto", 0).getInt("uiScale", 100)
            if (pct == 100) return base
            val c = Configuration(base.resources.configuration)
            c.densityDpi = c.densityDpi * pct / 100
            return base.createConfigurationContext(c)
        }
    }
    // 테마: 0 어두운 (Myplorer 기본) · 1 밝은 · 2 휴대폰 설정 따름
    val dark = when (a.getSharedPreferences("myphoto", 0).getInt("theme", 0)) {
        1 -> false
        2 -> (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> true
    }
    private fun c(s: String) = Color.parseColor(s)
    val bg = if (dark) c("#191919") else c("#F4F5F7")        // 목록 바탕
    val panel = if (dark) c("#202020") else c("#FFFFFF")     // 제목줄·아래줄
    val panel2 = if (dark) c("#2C2C2C") else c("#ECEEF2")    // 탭·주소 칸·카드
    val text = if (dark) c("#F0F0F0") else c("#1E2128")
    val sub = if (dark) c("#A0A0A0") else c("#666C78")
    val accent = if (dark) c("#60CDFF") else c("#1F6FE0")
    val hover = if (dark) c("#2D2D2D") else c("#E3E7EE")
    val select = if (dark) c("#505050") else c("#CFE0FA")
    val line = if (dark) c("#343434") else c("#D9DDE4")
    val red = c("#E5484D")
    val onAccent = if (dark) c("#0B1A22") else Color.WHITE
    // 예전 이름 (연결 화면)
    val card get() = panel2
    val blue get() = accent

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * a.resources.displayMetrics.density

    fun col(vararg kids: View) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; kids.forEach { addView(it) } }
    fun row(vararg kids: View) = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; kids.forEach { addView(it) } }

    fun label(s: String, size: Float = 15f, color: Int = text, bold: Boolean = false) = TextView(a).apply {
        this.text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun round(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** 누르면 물결이 퍼지는 바탕 */
    fun press(base: Drawable? = null, radiusDp: Int = 0): Drawable {
        val mask = if (radiusDp > 0 || base == null) round(Color.WHITE, radiusDp) else null
        return RippleDrawable(ColorStateList.valueOf((if (dark) 0x33FFFFFF else 0x22000000).toInt()), base, mask)
    }

    /** 아이콘 단추 (그림 + 누름 효과) */
    fun iconBtn(g: Drawable, desc: String, sizeDp: Int = 44, onClick: () -> Unit) = ImageView(a).apply {
        setImageDrawable(g); contentDescription = desc
        val p = (dp(sizeDp) - dp(24)) / 2; setPadding(p, p, p, p)
        background = press(null, sizeDp / 2)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }

    fun input(hint: String, value: String = "", password: Boolean = false) = EditText(a).apply {
        this.hint = hint; setText(value); setSingleLine(); textSize = 15f
        setTextColor(this@Ui.text); setHintTextColor(sub)
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = round(panel2, 8, line)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(a).apply {
        this.text = s; isAllCaps = false; textSize = 15f; stateListAnimator = null
        setTextColor(if (primary) onAccent else this@Ui.text)
        background = press(round(if (primary) accent else panel2, 8, if (primary) 0 else line), 8)
        setOnClickListener { onClick() }
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    /** 시스템 막대(상태 표시줄·내비게이션)를 피해 안쪽 여백 */
    fun insets(v: View) {
        v.setOnApplyWindowInsetsListener { view, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val s = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(s.left, s.top, s.right, s.bottom)
            } else @Suppress("DEPRECATION") {   // 안드로이드 10
                view.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
    }
}
