package kr.artchijun.myphoto

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * 사진 보기: 옆으로 넘기기, 두 손가락 확대, 두 번 눌러 확대. 먼저 큰 썸네일(1280)을 바로 보이고,
 * 확대하면 원본을 받아 바꿔 끼움. 동영상은 받지 않고 바로 재생 (시놀로지의 Streaming).
 * 아래 단추: 정보 · 즐겨찾기 · 휴대폰에 저장 · 공유 · 지우기
 */
class ViewerActivity : Activity() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(Ui.wrap(base)) }
    companion object {
        /** 격자에서 넘겨받는 사진 목록 (수만 장이라 Intent 대신 여기 둠) */
        var items: List<Item> = emptyList()
        var ns: String = Photos.TEAM
        /** 지우거나 즐겨찾기를 바꾼 것 (격자가 돌아와서 반영) */
        val removed = HashSet<Long>()
        val changed = HashMap<Long, Item>()
        fun open(ctx: Context, list: List<Item>, index: Int, ns: String) {
            items = ArrayList(list); this.ns = ns
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java).putExtra("index", index))
        }
    }

    private lateinit var ui: Ui
    private lateinit var ph: Photos
    private val bg = Executors.newFixedThreadPool(3)
    private var index = 0
    private var seq = 0
    private lateinit var stage: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var spin: ProgressBar
    private lateinit var heart: ImageView
    private var bars = true

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        ui = Ui(this); ph = Photos(this)
        if (items.isEmpty()) { finish(); return }
        index = (savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)).coerceIn(0, items.size - 1)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val w = Color.WHITE
        title = ui.label("", 15f, w).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        sub = ui.label("", 12f, 0xFFBBBBBB.toInt()).apply { setSingleLine() }
        topBar = ui.row(ui.iconBtn(Glyph("back", w), "닫기") { finish() }, ui.col(title, sub).apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) } }).apply {
            setBackgroundColor(0x99000000.toInt()); setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4))
        }
        heart = ui.iconBtn(Glyph("heart", w), "즐겨찾기") { toggleFav() }
        fun slot(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(52), 1f) }
        bottomBar = ui.row(
            slot(ui.iconBtn(Glyph("info", w), "정보") { info() }), slot(heart),
            slot(ui.iconBtn(Glyph("download", w), "휴대폰에 저장") { save() }),
            slot(ui.iconBtn(Glyph("share", w), "공유") { share() }),
            slot(ui.iconBtn(Glyph("trash", w), "지우기") { delete() }),
        ).apply { setBackgroundColor(0x99000000.toInt()) }
        spin = ProgressBar(this).apply { visibility = View.GONE }
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(stage)
            addView(spin, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        ui.insets(root)
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("index", index) }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    private val cur get() = items[index]

    private fun toggleBars() {
        bars = !bars
        val v = if (bars) View.VISIBLE else View.GONE
        topBar.visibility = v; bottomBar.visibility = v
    }

    private fun turn(d: Int) {
        val n = index + d
        if (n < 0 || n >= items.size) { Toast.makeText(this, if (d < 0) "첫 사진" else "마지막 사진", Toast.LENGTH_SHORT).show(); return }
        index = n; show()
    }

    private fun show() {
        val my = ++seq
        val it = cur
        title.text = it.name
        sub.text = PhotoGrid.stamp(it.time) + "  ·  " + Fmt.size(it.size) + "  ·  ${index + 1}/${items.size}" + (if (it.local != null) "  ·  휴대폰에만 (백업 안 됨)" else "")
        heart.setImageDrawable(Glyph(if (it.fav) "heartOn" else "heart", Color.WHITE))
        stage.removeAllViews()
        if (it.video) { playVideo(it); return }
        if (it.local != null) { showLocal(it, my); return }
        val zv = ZoomView(this, { d -> turn(d) }, { toggleBars() }, { loadOriginal(it, my) })
        stage.addView(zv, FrameLayout.LayoutParams(-1, -1))
        Thumbs.cached("${ViewerActivity.ns}|unit|${it.cacheKey}|sm")?.let { zv.set(BitmapDrawable(resources, it), it.width, it.height, true) }
        spin.visibility = View.VISIBLE
        bg.execute {
            val b = try { Thumbs.get(this, ViewerActivity.ns, it.unitId, it.cacheKey, "xl") } catch (e: Exception) { null }
            runOnUiThread {
                if (my != seq) return@runOnUiThread
                spin.visibility = View.GONE
                if (b != null) zv.set(BitmapDrawable(resources, b), b.width, b.height, true)
                else loadOriginal(it, my, failToast = true)   // 시놀로지가 아직 썸네일을 못 만든 사진: 원본으로 보여 줌
            }
            // 이웃 사진 미리 받기
            for (k in intArrayOf(index + 1, index - 1)) items.getOrNull(k)?.takeIf { !it.video }?.let { n ->
                try { Thumbs.get(this, ViewerActivity.ns, n.unitId, n.cacheKey, "xl") } catch (_: Exception) {}
            }
        }
    }

    /** 휴대폰에만 있는 사진: 휴대폰의 파일을 바로 보여 줌 */
    private fun showLocal(it: Item, my: Int) {
        val zv = ZoomView(this, { d -> turn(d) }, { toggleBars() }, {})
        stage.addView(zv, FrameLayout.LayoutParams(-1, -1))
        spin.visibility = View.VISIBLE
        bg.execute {
            val b = try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(it.local))) { dec, info, _ ->
                    val max = 4096; val s = maxOf(info.size.width, info.size.height)
                    if (s > max) dec.setTargetSize(info.size.width * max / s, info.size.height * max / s)
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (my != seq) return@runOnUiThread
                spin.visibility = View.GONE
                if (b != null) zv.set(BitmapDrawable(resources, b), b.width, b.height, false) else Toast.makeText(this, "사진을 열지 못했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun localOnly(): Boolean { if (cur.local == null) return false; Toast.makeText(this, "휴대폰에만 있는 사진입니다 (아직 백업 안 됨)", Toast.LENGTH_SHORT).show(); return true }

    /** 크게 확대했을 때: 원본을 받아 더 선명하게 (한 번만) */
    private fun loadOriginal(it: Item, my: Int, failToast: Boolean = false) {
        if (it.size > 60L * 1024 * 1024) { if (failToast) Toast.makeText(this, "사진을 불러오지 못했습니다", Toast.LENGTH_SHORT).show(); return }
        spin.visibility = View.VISIBLE
        bg.execute {
            val f = try { fetchOriginal(it) } catch (e: Exception) { null }
            val b = f?.let { decodeLimited(it) }
            runOnUiThread {
                if (my != seq) return@runOnUiThread
                spin.visibility = View.GONE
                val zv = stage.getChildAt(0) as? ZoomView ?: return@runOnUiThread
                if (b != null) zv.set(BitmapDrawable(resources, b), b.width, b.height, false)
                else if (failToast) Toast.makeText(this, "사진을 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 원본을 앱 캐시에 받음 (공유·저장에도 씀) */
    private fun fetchOriginal(it: Item): File {
        val dir = File(cacheDir, "orig").apply { mkdirs() }
        val f = File(dir, "${it.id}_${it.name}")
        if (f.length() == it.size && it.size > 0) return f
        dir.listFiles()?.sortedBy { x -> x.lastModified() }?.let { all -> var sum = all.sumOf { x -> x.length() }; for (x in all) { if (sum < 500L * 1024 * 1024) break; sum -= x.length(); x.delete() } }
        val syno = Syno(this)
        val tmp = File(f.path + ".part")
        syno.open({ syno.originalUrl(ViewerActivity.ns, it) }, 120_000).inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
        if (!tmp.renameTo(f)) throw java.io.IOException("저장하지 못함")
        return f
    }

    /** 원본이 너무 크면 화면에 맞게 줄여서 (메모리 보호) — 방향(EXIF)도 바르게 */
    private fun decodeLimited(f: File): Bitmap? = try {
        val src = android.graphics.ImageDecoder.createSource(f)
        android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val max = 4096
            val s = maxOf(info.size.width, info.size.height)
            if (s > max) dec.setTargetSize(info.size.width * max / s, info.size.height * max / s)
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) { null }

    private fun playVideo(it: Item) {
        val vv = VideoView(this)
        val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
        stage.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        spin.visibility = View.VISIBLE
        val my = seq
        bg.execute {
            val url = if (it.local != null) it.local else try { Syno(this).originalUrl(ViewerActivity.ns, it) } catch (e: Exception) { null }
            runOnUiThread {
                if (my != seq) return@runOnUiThread
                if (url == null) { spin.visibility = View.GONE; Toast.makeText(this, "동영상을 열지 못했습니다", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                vv.setVideoURI(Uri.parse(url))
                vv.setOnPreparedListener { spin.visibility = View.GONE; vv.start() }
                vv.setOnErrorListener { _, _, _ -> spin.visibility = View.GONE; Toast.makeText(this, "이 동영상은 재생하지 못했습니다 — 공유로 다른 앱에서 열어 보세요", Toast.LENGTH_LONG).show(); true }
            }
        }
        // 옆으로 밀어 넘기기
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(e2.x - e1.x) > vv.width / 6) { turn(if (vx < 0) 1 else -1); return true }
                return false
            }
        })
        vv.setOnTouchListener { _, e -> gd.onTouchEvent(e); if (e.action == MotionEvent.ACTION_UP) { if (mc.isShowing) mc.hide() else mc.show() }; true }
    }

    // ---------- 단추 ----------
    private fun info() {
        val it = cur
        if (it.local != null) {
            val p = Library.phone(this).firstOrNull { x -> -x.key == it.id }
            AlertDialog.Builder(this).setTitle("정보").setMessage("이름: ${it.name}\n찍은 때: ${PhotoGrid.stamp(it.time)}\n크기: ${Fmt.size(it.size)}\n휴대폰 폴더: ${p?.rel?.trimEnd('/') ?: "?"}\n\n서버에서 같은 사진을 찾지 못했습니다 (백업 안 됨).").setPositiveButton("닫기", null).show()
            return
        }
        val lines = arrayListOf("이름: ${it.name}", "찍은 때: ${PhotoGrid.stamp(it.time)}", "크기: ${Fmt.size(it.size)}" + (if (it.w > 0) "  ·  ${it.w}×${it.h}" else ""))
        val dlg = AlertDialog.Builder(this).setTitle("정보").setMessage(lines.joinToString("\n") + "\n\n더 불러오는 중…").setPositiveButton("닫기", null).show()
        bg.execute {
            val more = try {
                val d = ph.syno.call("${ViewerActivity.ns}.Browse.Item", "get", 7, mapOf("id" to JSONArray(listOf(it.id)), "additional" to listOf("exif", "address", "description", "tag", "person")))
                val o = d.optJSONArray("list")?.optJSONObject(0)?.optJSONObject("additional")
                val out = ArrayList<String>()
                o?.optJSONObject("exif")?.let { e -> listOf("camera" to "카메라", "lens" to "렌즈", "aperture" to "조리개", "exposure_time" to "셔터", "iso" to "ISO", "focal_length" to "초점거리")
                    .forEach { (k, n) -> e.optString(k).takeIf { v -> v.isNotEmpty() }?.let { v -> out += "$n: $v" } } }
                o?.optJSONObject("address")?.let { a -> listOf("country", "state", "city", "district", "route").map { k -> a.optString(k) }.filter { v -> v.isNotEmpty() }.distinct().joinToString(" ").takeIf { v -> v.isNotEmpty() }?.let { v -> out += "장소: $v" } }
                o?.optJSONArray("person")?.let { p -> List(p.length()) { i -> p.getJSONObject(i).optString("name") }.filter { n -> n.isNotEmpty() }.takeIf { n -> n.isNotEmpty() }?.let { n -> out += "인물: " + n.joinToString(", ") } }
                o?.optString("description")?.takeIf { v -> v.isNotEmpty() }?.let { v -> out += "설명: $v" }
                out.joinToString("\n")
            } catch (e: Exception) { "더 불러오지 못했습니다 (${e.message})" }
            runOnUiThread { if (dlg.isShowing) dlg.setMessage(lines.joinToString("\n") + (if (more.isNotEmpty()) "\n\n$more" else "")) }
        }
    }

    private fun toggleFav() {
        if (localOnly()) return
        val it = cur; val on = !it.fav
        bg.execute {
            val err = try { ph.setFavorite(ViewerActivity.ns, listOf(it.id), on); null } catch (e: Exception) { e.message }
            runOnUiThread {
                if (err != null) { Toast.makeText(this, "즐겨찾기를 바꾸지 못했습니다: $err", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val n = it.copy(fav = on); (items as? MutableList<Item>)?.set(items.indexOf(it), n); changed[n.id] = n
                if (cur.id == n.id) heart.setImageDrawable(Glyph(if (on) "heartOn" else "heart", Color.WHITE))
            }
        }
    }

    /** 휴대폰의 사진 폴더(Pictures/Myphoto)에 원본 저장 */
    private fun save() {
        if (localOnly()) return
        val it = cur
        Toast.makeText(this, "받는 중…", Toast.LENGTH_SHORT).show()
        bg.execute {
            val err = try {
                val f = fetchOriginal(it)
                val video = it.video
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, it.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Myphoto")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(coll, v) ?: throw java.io.IOException("저장할 곳을 만들지 못함")
                contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { inp -> inp.copyTo(o) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                null
            } catch (e: Exception) { e.message ?: "저장하지 못했습니다" }
            runOnUiThread { Toast.makeText(this, err ?: "휴대폰에 저장했습니다 (${if (it.video) "Movies" else "Pictures"}/Myphoto)", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun share() {
        val it = cur
        if (it.local != null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (it.video) "video/*" else "image/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(it.local))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "공유"))
            return
        }
        Toast.makeText(this, "받는 중…", Toast.LENGTH_SHORT).show()
        bg.execute {
            val f = try { fetchOriginal(it) } catch (e: Exception) { null }
            runOnUiThread {
                if (f == null) { Toast.makeText(this, "받지 못했습니다", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val uri = FilesProvider.uriFor(this, f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(FilesProvider.mimeOf(f.name)).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "공유"))
            }
        }
    }

    private fun delete() {
        if (localOnly()) return
        val it = cur
        AlertDialog.Builder(this).setTitle("지울까요?").setMessage("${it.name}\n\n시놀로지에서 지웁니다. 공유 폴더 휴지통이 켜져 있으면 DSM 휴지통에서 되살릴 수 있습니다.")
            .setPositiveButton("지우기") { _, _ ->
                bg.execute {
                    val err = try { ph.delete(ViewerActivity.ns, listOf(it.id)); null } catch (e: Exception) { e.message }
                    runOnUiThread {
                        if (err != null) { Toast.makeText(this, "지우지 못했습니다: $err", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        removed += it.id
                        val list = ArrayList(items); list.removeAll { x -> x.id == it.id }; items = list
                        if (items.isEmpty()) { finish(); return@runOnUiThread }
                        index = index.coerceAtMost(items.size - 1); show()
                    }
                }
            }.setNegativeButton("그만", null).show()
    }
}

/** 확대·이동되는 그림 칸. 원래 크기일 때 옆으로 밀면 이전·다음. 크게 확대하면 onDeep (원본 받기) */
class ZoomView(ctx: Context, private val turn: (Int) -> Unit, private val tap: () -> Unit, private val onDeep: () -> Unit) : ImageView(ctx) {
    private val m = Matrix()
    private var base = 1f
    private var cur = 1f
    private var dw = 0; private var dh = 0
    private var deepAsked = false
    private var preview = true

    init { scaleType = ScaleType.MATRIX; setBackgroundColor(Color.BLACK) }

    /** 그림 넣기. 미리보기(썸네일)에서 원본으로 바꿀 때는 지금 확대 상태를 그대로 이어감 */
    fun set(d: Drawable, w: Int, h: Int, isPreview: Boolean) {
        val keep = drawable != null && !isPreview && dw > 0 && cur > base * 1.02f
        val oldDw = dw; val vals = FloatArray(9); m.getValues(vals)
        setImageDrawable(d); dw = w; dh = h; preview = isPreview
        if (keep) {   // 같은 화면 비율로 이어서
            val k = oldDw.toFloat() / w
            m.preScale(k, k); cur *= k; base = minOf(width.toFloat() / dw, height.toFloat() / dh); imageMatrix = m
        } else post { fit() }
    }

    private fun fit() {
        if (dw <= 0 || width == 0) return
        base = minOf(width.toFloat() / dw, height.toFloat() / dh); cur = base
        m.reset(); m.postScale(base, base); m.postTranslate((width - dw * base) / 2, (height - dh * base) / 2); imageMatrix = m
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun clamp() {
        val r = RectF(0f, 0f, dw.toFloat(), dh.toFloat()); m.mapRect(r)
        val dx = if (r.width() <= width) (width - r.width()) / 2 - r.left else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
        val dy = if (r.height() <= height) (height - r.height()) / 2 - r.top else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
        m.postTranslate(dx, dy); imageMatrix = m
        if (preview && !deepAsked && cur > base * 1.8f) { deepAsked = true; onDeep() }
    }

    private val sg = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val k = (cur * d.scaleFactor).coerceIn(base, base * 10) / cur
            cur *= k; m.postScale(k, k, d.focusX, d.focusY); clamp(); return true
        }
    })
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cur > base * 1.05f) fit() else { val k = 2.5f; cur *= k; m.postScale(k, k, e.x, e.y); clamp() }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (cur > base * 1.02f) { m.postTranslate(-dx, -dy); clamp() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!turned && cur <= base * 1.02f && e1 != null && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(e2.x - e1.x) > width / 8) { turned = true; turn(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    private var downX = 0f; private var downY = 0f; private var multi = false; private var turned = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; multi = false; turned = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
        }
        sg.onTouchEvent(e); if (!sg.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP && !multi && !turned && cur <= base * 1.02f) {
            val dx = e.x - downX; val dy = e.y - downY
            if (Math.abs(dx) > width / 4 && Math.abs(dx) > Math.abs(dy) * 1.5f) { turned = true; turn(if (dx < 0) 1 else -1) }
        }
        return true
    }
}
