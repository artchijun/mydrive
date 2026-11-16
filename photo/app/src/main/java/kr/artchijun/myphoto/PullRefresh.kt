package kr.artchijun.myphoto

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 위로 당겼다 놓으면 새로 고침: 목록이 맨 위일 때 아래로 끌면 동그란 표시가 내려오고, 충분히 끌고 놓으면 onRefresh.
 * (안드로이드 기본 SwipeRefreshLayout 은 androidx 가 필요해 직접 만듦)
 */
class PullRefresh(ctx: Context, private val ui: Ui, child: View, private val atTop: () -> Boolean, private val onRefresh: () -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val size = ui.dp(40)
    private val trigger get() = ui.dpf(72f)
    private var downY = 0f
    private var pulling = false
    private var dist = 0f
    private val ind = ImageView(ctx).apply {
        setImageDrawable(Glyph("refresh", ui.accent))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ui.dark) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()) }
        elevation = ui.dpf(6f); val q = ui.dp(9); setPadding(q, q, q, q)
        alpha = 0f; translationY = -size.toFloat()
    }

    init {
        addView(child, LayoutParams(-1, -1))
        addView(ind, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; pulling = false; dist = 0f }
            MotionEvent.ACTION_MOVE -> if (!pulling && e.pointerCount == 1 && e.y - downY > slop && atTop()) { pulling = true; downY = e.y; return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!pulling) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { dist = maxOf(0f, e.y - downY) * 0.5f; show() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (dist >= trigger && e.actionMasked == MotionEvent.ACTION_UP) {
                    ind.animate().cancel()
                    ind.animate().translationY(ui.dpf(16f)).rotationBy(720f).setDuration(900).withEndAction { hide() }.start()
                    onRefresh()
                } else hide()
            }
        }
        return true
    }

    private fun show() {
        val k = minOf(1f, dist / trigger)
        ind.animate().cancel()
        ind.alpha = k
        ind.translationY = -size + minOf(dist, trigger * 1.2f) * 0.9f
        ind.rotation = dist * 2.5f
        ind.setImageDrawable(Glyph("refresh", if (k >= 1f) ui.accent else ui.sub))
    }
    private fun hide() { ind.animate().cancel(); ind.animate().alpha(0f).translationY(-size.toFloat()).setDuration(200).start() }
}
