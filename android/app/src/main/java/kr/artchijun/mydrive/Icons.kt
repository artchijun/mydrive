package kr.artchijun.mydrive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

// 그림 글자 없이 직접 그리는 아이콘 — 24칸 격자 기준 선 아이콘 (Myplorer 의 얇은 선 아이콘 느낌)
class Glyph(private val kind: String, private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = this@Glyph.color }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = this@Glyph.color }

    override fun draw(cv: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()) / 24f
        cv.save(); cv.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2); cv.scale(s, s)
        p.strokeWidth = 1.7f
        val path = Path()
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) { cv.drawLine(x1, y1, x2, y2, p) }
        fun poly(vararg v: Float, close: Boolean = false) { path.reset(); path.moveTo(v[0], v[1]); var i = 2; while (i < v.size) { path.lineTo(v[i], v[i + 1]); i += 2 }; if (close) path.close(); cv.drawPath(path, p) }
        fun rr(l: Float, t: Float, r: Float, bt: Float, rad: Float) { cv.drawRoundRect(RectF(l, t, r, bt), rad, rad, p) }
        when (kind) {
            "menu" -> { ln(4f, 7f, 20f, 7f); ln(4f, 12f, 20f, 12f); ln(4f, 17f, 20f, 17f) }
            "search" -> { cv.drawCircle(10.5f, 10.5f, 6f, p); ln(15f, 15f, 20f, 20f) }
            "more" -> { cv.drawCircle(12f, 5.5f, 1.4f, f); cv.drawCircle(12f, 12f, 1.4f, f); cv.drawCircle(12f, 18.5f, 1.4f, f) }
            "back" -> { ln(5f, 12f, 19f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
            "fwd" -> { ln(5f, 12f, 19f, 12f); poly(13f, 6f, 19f, 12f, 13f, 18f) }
            "up" -> { ln(12f, 19f, 12f, 5f); poly(6f, 11f, 12f, 5f, 18f, 11f) }
            "chev" -> { poly(10f, 7f, 15f, 12f, 10f, 17f) }
            "star", "starOn" -> {
                path.reset()
                for (i in 0 until 10) { val r = if (i % 2 == 0) 8.5f else 3.8f; val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = 12 + r * Math.cos(a).toFloat(); val y = 12.6f + r * Math.sin(a).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); cv.drawPath(path, if (kind == "starOn") f else p)
            }
            "close" -> { ln(6.5f, 6.5f, 17.5f, 17.5f); ln(17.5f, 6.5f, 6.5f, 17.5f) }
            "plus" -> { ln(12f, 5f, 12f, 19f); ln(5f, 12f, 19f, 12f) }
            "pin" -> { poly(9f, 4f, 15f, 4f); poly(10f, 4f, 10f, 10f, 7f, 14f, 17f, 14f, 14f, 10f, 14f, 4f); ln(12f, 14f, 12f, 20f) }
            "check" -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            "sort" -> { ln(4f, 7f, 20f, 7f); ln(4f, 12f, 15f, 12f); ln(4f, 17f, 10f, 17f) }
            "refresh" -> { cv.drawArc(RectF(5f, 5f, 19f, 19f), -60f, 300f, false, p); poly(17f, 3.5f, 18.8f, 7.6f, 14.6f, 8.6f) }
            "trash" -> { ln(4f, 6.5f, 20f, 6.5f); poly(9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f); poly(6f, 6.5f, 7f, 20f, 17f, 20f, 18f, 6.5f); ln(10f, 10f, 10f, 16.5f); ln(14f, 10f, 14f, 16.5f) }
            "copy" -> { rr(8f, 8f, 20f, 20f, 2f); poly(16f, 8f, 16f, 4f, 4f, 4f, 4f, 16f, 8f, 16f) }
            "move" -> { rr(3.5f, 6f, 15f, 18f, 2f); ln(11f, 12f, 21f, 12f); poly(17.5f, 8.5f, 21f, 12f, 17.5f, 15.5f) }
            "edit" -> { poly(5f, 19f, 5f, 15.5f, 15.5f, 5f, 19f, 8.5f, 8.5f, 19f, close = true); ln(13f, 7.5f, 16.5f, 11f) }
            "share" -> { cv.drawCircle(17f, 6f, 2.5f, p); cv.drawCircle(7f, 12f, 2.5f, p); cv.drawCircle(17f, 18f, 2.5f, p); ln(9.2f, 10.8f, 14.8f, 7.2f); ln(9.2f, 13.2f, 14.8f, 16.8f) }
            "zip" -> { rr(5f, 3.5f, 19f, 20.5f, 2f); ln(12f, 3.5f, 12f, 5.5f); ln(12f, 7f, 12f, 9f); ln(12f, 10.5f, 12f, 12.5f); rr(10.3f, 13.5f, 13.7f, 17f, 0.8f) }
            "newfolder" -> { poly(3f, 18.5f, 3f, 5.5f, 9f, 5.5f, 11f, 7.5f, 21f, 7.5f, 21f, 18.5f, close = true); ln(12f, 10f, 12f, 16f); ln(9f, 13f, 15f, 13f) }
            "upload" -> { ln(12f, 15f, 12f, 4f); poly(7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f); poly(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f) }
            "download" -> { ln(12f, 4f, 12f, 15f); poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); poly(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f) }
            "phone" -> { rr(6.5f, 2.5f, 17.5f, 21.5f, 2.5f); ln(10.5f, 18.5f, 13.5f, 18.5f) }
            "sd" -> { poly(8f, 3f, 18f, 3f, 18f, 21f, 6f, 21f, 6f, 5f, close = true); ln(10f, 6f, 10f, 9f); ln(13f, 6f, 13f, 9f); ln(16f, 6f, 16f, 9f) }
            "nas" -> { rr(4f, 4f, 20f, 10.5f, 1.5f); rr(4f, 13.5f, 20f, 20f, 1.5f); cv.drawCircle(7.5f, 7.25f, 1f, f); cv.drawCircle(7.5f, 16.75f, 1f, f); ln(11f, 7.25f, 17f, 7.25f); ln(11f, 16.75f, 17f, 16.75f) }
            "history" -> { cv.drawCircle(12f, 12f, 8f, p); poly(12f, 7.5f, 12f, 12f, 15f, 14f) }
            "info" -> { cv.drawCircle(12f, 12f, 8.5f, p); ln(12f, 11f, 12f, 16.5f); cv.drawCircle(12f, 7.8f, 1.1f, f) }
            "selectall" -> { rr(4f, 4f, 20f, 20f, 2.5f); poly(8f, 12f, 11f, 15f, 16.5f, 9f) }
            "restore" -> { cv.drawArc(RectF(5f, 5f, 19f, 19f), 160f, 290f, false, p); poly(3.5f, 8f, 5.6f, 11.9f, 9.4f, 9.8f); poly(12f, 8.5f, 12f, 12f, 14.5f, 13.5f) }
            "home" -> { poly(4f, 11f, 12f, 4f, 20f, 11f); poly(6.5f, 9f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9f) }
            "settings" -> { cv.drawCircle(12f, 12f, 3f, p); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); ln(12 + 6f * Math.cos(a).toFloat(), 12 + 6f * Math.sin(a).toFloat(), 12 + 8.5f * Math.cos(a).toFloat(), 12 + 8.5f * Math.sin(a).toFloat()) }; cv.drawCircle(12f, 12f, 6f, p) }
            "filter" -> poly(4f, 5f, 20f, 5f, 14f, 12.5f, 14f, 19f, 10f, 17f, 10f, 12.5f, close = true)
            "eye" -> { path.reset(); path.moveTo(2.5f, 12f); path.quadTo(12f, 3f, 21.5f, 12f); path.quadTo(12f, 21f, 2.5f, 12f); cv.drawPath(path, p); cv.drawCircle(12f, 12f, 3f, p) }
            "openwith" -> { poly(14f, 4f, 20f, 4f, 20f, 10f); ln(20f, 4f, 11f, 13f); poly(17f, 14f, 17f, 20f, 4f, 20f, 4f, 7f, 10f, 7f) }
            "chart" -> { path.reset(); path.moveTo(11f, 13f); path.lineTo(11f, 5f); path.arcTo(RectF(3f, 5f, 19f, 21f), -90f, -270f); path.close(); cv.drawPath(path, p); path.reset(); path.moveTo(14f, 10f); path.lineTo(14f, 2.5f); path.arcTo(RectF(6.5f, 2.5f, 21.5f, 17.5f), -90f, 90f); path.close(); cv.drawPath(path, p) }
            "image" -> { rr(3.5f, 5f, 20.5f, 19f, 2f); poly(3.5f, 16.5f, 9f, 11f, 13f, 15f, 15.5f, 12.5f, 20.5f, 17f); cv.drawCircle(15.5f, 8.8f, 1.6f, p) }
            "music" -> { ln(9f, 17f, 9f, 5.5f); poly(9f, 5.5f, 19f, 3.5f, 19f, 15f); cv.drawCircle(6.6f, 17f, 2.4f, f); cv.drawCircle(16.6f, 15f, 2.4f, f) }
            "film" -> { rr(4f, 3.5f, 20f, 20.5f, 1.5f); ln(8f, 3.5f, 8f, 20.5f); ln(16f, 3.5f, 16f, 20.5f); ln(8f, 12f, 16f, 12f); for (y in floatArrayOf(6.5f, 10f, 14f, 17.5f)) { ln(4.6f, y, 7.4f, y); ln(16.6f, y, 19.4f, y) } }
            "doc" -> { ln(5f, 6f, 19f, 6f); ln(5f, 10f, 17f, 10f); ln(5f, 14f, 19f, 14f); ln(5f, 18f, 13f, 18f) }
            "android" -> { cv.drawArc(RectF(6f, 6f, 18f, 17f), 180f, 180f, false, p); ln(6f, 11.5f, 18f, 11.5f); rr(6f, 13f, 18f, 20f, 1.5f); ln(8.5f, 6.8f, 7f, 4.5f); ln(15.5f, 6.8f, 17f, 4.5f); cv.drawCircle(9.6f, 9.3f, 0.9f, f); cv.drawCircle(14.4f, 9.3f, 0.9f, f) }
            "globe" -> { cv.drawCircle(12f, 12f, 8.5f, p); cv.drawOval(RectF(8.2f, 3.5f, 15.8f, 20.5f), p); ln(3.5f, 12f, 20.5f, 12f); ln(5f, 7.5f, 19f, 7.5f); ln(5f, 16.5f, 19f, 16.5f) }
            "range" -> { rr(3.5f, 3.5f, 9.5f, 9.5f, 1.5f); rr(14.5f, 14.5f, 20.5f, 20.5f, 1.5f); for (t in floatArrayOf(11.5f, 13.5f)) cv.drawCircle(t, t, 0.9f, f); ln(6.5f, 12.5f, 6.5f, 17.5f); ln(6.5f, 17.5f, 11.5f, 17.5f) }
            "openas" -> { poly(14f, 4f, 20f, 4f, 20f, 10f); ln(20f, 4f, 11f, 13f); poly(17f, 14f, 17f, 20f, 4f, 20f, 4f, 7f, 10f, 7f); cv.drawCircle(8f, 16f, 1f, f) }
            "listview" -> { for (y in floatArrayOf(6f, 12f, 18f)) { rr(3.5f, y - 1.6f, 6.7f, y + 1.6f, 0.8f); ln(9.5f, y, 20.5f, y) } }
            "grid" -> { rr(4f, 4f, 10.5f, 10.5f, 1.5f); rr(13.5f, 4f, 20f, 10.5f, 1.5f); rr(4f, 13.5f, 10.5f, 20f, 1.5f); rr(13.5f, 13.5f, 20f, 20f, 1.5f) }
        }
        cv.restore()
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 72
    override fun getIntrinsicHeight() = 72
}

// 파일·폴더 아이콘: 노란 폴더 / 종류별 색 종이에 확장자 글자
class FileIcon(private val dir: Boolean, private val name: String, private val special: String = "") : Drawable() {
    companion object {
        fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif", "svg" -> "image"
            "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "wmv" -> "video"
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "wma" -> "audio"
            "pdf" -> "pdf"
            "doc", "docx", "hwp", "hwpx", "txt", "md", "rtf", "odt" -> "doc"
            "xls", "xlsx", "csv", "ods" -> "sheet"
            "ppt", "pptx", "key", "odp" -> "slide"
            "zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2" -> "zip"
            "apk" -> "apk"
            "dwg", "dxf", "3dm", "skp", "ifc", "rvt", "gh", "step", "stp", "obj", "stl" -> "cad"
            else -> "file"
        }
        fun colorOf(kind: String): Int = when (kind) {
            "image" -> 0xFF3FB27F.toInt(); "video" -> 0xFFE0565B.toInt(); "audio" -> 0xFFC86BD6.toInt()
            "pdf" -> 0xFFE5484D.toInt(); "doc" -> 0xFF4F8DF5.toInt(); "sheet" -> 0xFF2EA866.toInt()
            "slide" -> 0xFFF0883E.toInt(); "zip" -> 0xFF9A7B4F.toInt(); "apk" -> 0xFF7CB342.toInt()
            "cad" -> 0xFF26A6B8.toInt(); else -> 0xFF8A93A3.toInt()
        }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    override fun draw(cv: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()) / 24f
        cv.save(); cv.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2); cv.scale(s, s)
        if (dir) {
            // Myplorer 처럼 노란 폴더 (뒤판 진하게, 앞판 밝게)
            val path = Path()
            fill.color = 0xFFE8A92E.toInt()
            path.moveTo(2f, 6f); path.quadTo(2f, 4f, 4f, 4f); path.lineTo(9f, 4f); path.lineTo(11f, 6f); path.lineTo(20f, 6f); path.quadTo(22f, 6f, 22f, 8f)
            path.lineTo(22f, 18f); path.quadTo(22f, 20f, 20f, 20f); path.lineTo(4f, 20f); path.quadTo(2f, 20f, 2f, 18f); path.close(); cv.drawPath(path, fill)
            fill.color = 0xFFFFCA45.toInt()
            cv.drawRoundRect(RectF(2f, 8.2f, 22f, 20f), 2f, 2f, fill)
            if (special.isNotEmpty()) {   // 다운로드·NAS 같은 특별한 폴더: 앞판에 작은 표시
                val g = Glyph(special, 0xFF7A5200.toInt()); g.setBounds(0, 0, 9, 9)
                cv.save(); cv.translate(7.5f, 10.2f); cv.scale(1f, 1f); g.draw(cv); cv.restore()
            }
        } else {
            val kind = kindOf(name)
            val path = Path()
            fill.color = colorOf(kind)
            path.moveTo(5f, 2f); path.lineTo(14.5f, 2f); path.lineTo(20f, 7.5f); path.lineTo(20f, 20.5f); path.quadTo(20f, 22f, 18.5f, 22f)
            path.lineTo(5.5f, 22f); path.quadTo(4f, 22f, 4f, 20.5f); path.lineTo(4f, 3.5f); path.quadTo(4f, 2f, 5.5f, 2f); path.close()
            cv.drawPath(path, fill)
            fill.color = 0x55FFFFFF
            val fold = Path(); fold.moveTo(14.5f, 2f); fold.lineTo(14.5f, 7.5f); fold.lineTo(20f, 7.5f); fold.close(); cv.drawPath(fold, fill)
            val ext = name.substringAfterLast('.', "").uppercase().take(4)
            if (ext.isNotEmpty() && name.contains('.')) {
                txt.textSize = if (ext.length <= 3) 5.6f else 4.6f
                cv.drawText(ext, 12f, 17.5f, txt)
            }
        }
        cv.restore()
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
