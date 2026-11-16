package kr.artchijun.mydrive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }
    /** 걸린·남은 시간: 45초 · 3분 20초 · 1시간 5분 */
    fun dur(sec: Long): String = when {
        sec < 0 -> "알 수 없음"
        sec < 60 -> "${sec}초"
        sec < 3600 -> "${sec / 60}분" + (if (sec % 60 > 0 && sec < 600) " ${sec % 60}초" else "")
        else -> "${sec / 3600}시간" + (if (sec / 60 % 60 > 0) " ${sec / 60 % 60}분" else "")
    }
    fun speed(bps: Long): String = if (bps <= 0) "—" else size(bps) + "/초"
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    fun time(ns: Long): String = if (ns <= 0) "" else df.format(Date(ns / 1_000_000))

    /** 같은 이름이 있으면 "이름 (2).확장자" … */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot); val ext = name.substring(dot)
        var i = 2
        while (taken("$stem ($i)$ext")) i++
        return "$stem ($i)$ext"
    }
}
