package kr.artchijun.mydrive

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// 화면을 코드로 만드는 작은 도구 (XML·외부 부품 없이 가볍게)
class Ui(val a: Activity) {
    val dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) Color.parseColor("#121418") else Color.parseColor("#F6F7F9")
    val card = if (dark) Color.parseColor("#1C1F25") else Color.WHITE
    val text = if (dark) Color.parseColor("#E6E8EB") else Color.parseColor("#1B1D21")
    val sub = if (dark) Color.parseColor("#9AA0A8") else Color.parseColor("#6B7280")
    val line = if (dark) Color.parseColor("#2A2E36") else Color.parseColor("#E5E7EB")
    val blue = Color.parseColor("#4F7CFF")
    val red = Color.parseColor("#E5484D")

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun col(vararg kids: View) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; kids.forEach { addView(it) } }
    fun row(vararg kids: View) = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; kids.forEach { addView(it) } }

    fun label(s: String, size: Float = 15f, color: Int = text, bold: Boolean = false) = TextView(a).apply {
        this.text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun input(hint: String, value: String = "", password: Boolean = false) = EditText(a).apply {
        this.hint = hint; setText(value); setSingleLine(); textSize = 15f
        setTextColor(this@Ui.text); setHintTextColor(sub)
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(10).toFloat(); setStroke(dp(1), line) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(a).apply {
        this.text = s; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else this@Ui.text)
        background = GradientDrawable().apply { setColor(if (primary) blue else card); cornerRadius = dp(10).toFloat(); if (!primary) setStroke(dp(1), line) }
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
