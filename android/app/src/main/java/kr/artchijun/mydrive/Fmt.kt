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

    /** 바뀐 때 표시 (설정): 0 둘 다(기본) · 1 몇 분 전만 · 2 날짜·시각만. 앱이 켜질 때·설정에서 돌아올 때 맞춤 */
    @Volatile var mode = 0
    val relative get() = mode != 2
    fun modeOf(pf: Prefs): Int = pf.int("timeMode", if (pf.bool("relTime", true)) 0 else 2)   // 옛 스위치(relTime) 이어받기
    private val dfDay = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    /** 지금부터 얼마 전: 방금 전 · 5분 전 · 3시간 전 · 12일 전 (30일이 넘으면 날짜) */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val s = (now - ms) / 1000
        return when {
            s < -120 -> df.format(Date(ms))           // 앞날 (시계가 어긋난 파일)
            s < 60 -> "방금 전"
            s < 3600 -> "${s / 60}분 전"
            s < 86_400 -> "${s / 3600}시간 전"
            s < 86_400L * 30 -> "${s / 86_400}일 전"
            else -> dfDay.format(Date(ms))
        }
    }

    /** 목록 줄에 쓰는 때: 둘 다 '3일 전 · 2026-11-20 08:06' / '3일 전' / '2026-11-20 08:06'.
     *  30일이 넘으면 '얼마 전'이 날짜와 같아지므로 날짜·시각만 */
    fun listTime(ns: Long): String {
        if (ns <= 0) return ""
        val ms = ns / 1_000_000
        val old = System.currentTimeMillis() - ms >= 86_400_000L * 30
        return when {
            mode == 2 || (mode == 0 && old) -> time(ns)
            mode == 1 -> ago(ms)
            else -> ago(ms) + " · " + time(ns)
        }
    }

    /** 짧은 때 (동기화 '지난번'): 둘 다 '5분 전 (11월 23일 08:06)' / '5분 전' / '11월 23일 08:06' */
    private val dfShort = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    fun shortTime(ms: Long): String = if (ms <= 0) "" else when (mode) {
        1 -> ago(ms)
        2 -> dfShort.format(Date(ms))
        else -> ago(ms) + " (" + dfShort.format(Date(ms)) + ")"
    }

    /** 자세히 보기용: 날짜·시각과 얼마 전을 함께 — 2026-11-20 08:06 (3일 전) */
    fun fullTime(ns: Long): String = if (ns <= 0) "" else time(ns) + "  (" + ago(ns / 1_000_000) + ")"

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
