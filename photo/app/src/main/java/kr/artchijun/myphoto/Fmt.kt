package kr.artchijun.myphoto

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
