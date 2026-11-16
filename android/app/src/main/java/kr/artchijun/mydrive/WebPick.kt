package kr.artchijun.mydrive

import android.app.Activity
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * NAS 다른 폴더(웹 폴더) 추가: NAS 공유 폴더를 한 단계씩 둘러보고 [이 폴더 추가] → 이름 붙이기.
 *   추가한 폴더는 동기화하지 않고, 열 때마다 NAS 에서 바로 읽음 (웹 드라이브처럼).
 */
object WebPick {
    fun start(a: Activity, ui: Ui, api: Api, done: () -> Unit) = show(a, ui, api, "/", done)

    private fun show(a: Activity, ui: Ui, api: Api, path: String, done: () -> Unit) {
        thread {
            val r = try { Result.success(api.nasDirs(path)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                r.onFailure { e -> Toast.makeText(a, if (e is ApiException && e.status == 404 && path == "/") "NAS 서버를 0.6.12 이상으로 올려야 합니다" else e.message ?: "NAS 에 닿지 않습니다", Toast.LENGTH_LONG).show() }
                r.onSuccess { (cur, dirs) ->
                    val top = cur == "/" || cur.isEmpty()
                    val names = (if (top) emptyList() else listOf("⬆  위 폴더로")) + dirs.map { "📁  $it" }
                    AlertDialog.Builder(a).setTitle("NAS ${if (top) "공유 폴더" else cur}")
                        .setItems(names.toTypedArray()) { _, w ->
                            if (!top && w == 0) show(a, ui, api, cur.substringBeforeLast('/', "").ifEmpty { "/" }, done)
                            else show(a, ui, api, (if (top) "" else cur) + "/" + dirs[if (top) w else w - 1], done)
                        }
                        .apply { if (!top) setPositiveButton("이 폴더 추가") { _, _ -> name(a, ui, api, cur, done) } }
                        .setNegativeButton("닫기", null).show()
                }
            }
        }
    }

    private fun name(a: Activity, ui: Ui, api: Api, dir: String, done: () -> Unit) {
        val def = dir.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
        val e = ui.input("이름 (글자·숫자·_·-)", def)
        val box = FrameLayout(a).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(e) }
        AlertDialog.Builder(a).setTitle("웹 폴더 이름").setMessage("NAS $dir\n동기화하지 않고, 열 때마다 NAS 에서 바로 읽습니다.").setView(box)
            .setPositiveButton("추가") { _, _ ->
                val n = e.text.toString().trim()
                thread {
                    val err = try { api.addWeb(n, dir); null } catch (x: Exception) { x.message ?: "추가하지 못했습니다" }
                    a.runOnUiThread { Toast.makeText(a, err ?: "‘$n’ 을 추가했습니다", Toast.LENGTH_SHORT).show(); if (err == null) done() }
                }
            }.setNegativeButton("취소", null).show()
    }
}
