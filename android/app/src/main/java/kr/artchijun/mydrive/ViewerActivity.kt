package kr.artchijun.mydrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

/**
 * 내장 뷰어: 사진(옆으로 넘기기·두 손가락 확대·두 번 눌러 확대), 동영상(NAS 는 받지 않고 바로 재생), PDF, 텍스트.
 * 휴대폰 파일은 그대로, NAS 파일은 앱 안에 받아서 (동영상만 바로 흘려 받기)
 */
class ViewerActivity : Activity() {
    companion object {
        const val IMG = "image"; const val VID = "video"; const val PDF = "pdf"; const val TXT = "text"
        private val textExt = setOf("txt", "md", "log", "csv", "json", "xml", "ini", "cfg", "conf", "yaml", "yml", "py", "kt", "java", "js", "ts", "c", "cpp", "h", "cs", "go", "sh", "bat", "ps1", "html", "css", "srt", "gh", "properties")
        fun kindOf(name: String): String? = when (val k = FileIcon.kindOf(name)) {
            "image" -> if (name.substringAfterLast('.').lowercase() in setOf("svg", "tif", "tiff")) null else IMG
            "video" -> VID
            "pdf" -> PDF
            else -> if (name.substringAfterLast('.', "").lowercase() in textExt) TXT else null
        }

        /** loc 폴더의 같은 종류 파일들 중 index 번째부터 */
        fun start(ctx: Context, loc: Loc, names: List<String>, sizes: List<Long>, mtimes: List<Long>, index: Int, kind: String) {
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java)
                .putExtra("loc", loc.key()).putExtra("names", names.toTypedArray()).putExtra("sizes", sizes.toLongArray())
                .putExtra("mtimes", mtimes.toLongArray()).putExtra("index", index).putExtra("kind", kind))
        }
    }

    private lateinit var ui: Ui
    private lateinit var api: Api
    private lateinit var loc: Loc
    private lateinit var names: Array<String>
    private lateinit var sizes: LongArray
    private lateinit var mtimes: LongArray
    private var index = 0
    private lateinit var kind: String
    private val bg = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<Int, Drawable>(3) {}
    private lateinit var title: TextView
    private lateinit var bar: LinearLayout
    private lateinit var spin: ProgressBar
    private lateinit var stage: FrameLayout
    private var seq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        ui = Ui(this); api = Prefs(this).api()
        loc = Loc.parse(intent.getStringExtra("loc") ?: "")!!
        names = intent.getStringArrayExtra("names")!!; sizes = intent.getLongArrayExtra("sizes")!!; mtimes = intent.getLongArrayExtra("mtimes")!!
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0); kind = intent.getStringExtra("kind") ?: IMG
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK

        title = ui.label("", 15f, Color.WHITE).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val w = Color.WHITE
        bar = ui.row(ui.iconBtn(Glyph("back", w), "닫기") { finish() }, title.apply { layoutParams = ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) } },
            ui.iconBtn(Glyph("share", w), "공유") { withFile { f -> share(f) } },
            ui.iconBtn(Glyph("openwith", w), "다른 앱으로 열기") { withFile { f -> openWith(f) } }).apply {
            setBackgroundColor(0xAA000000.toInt()); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        spin = ProgressBar(this).apply { visibility = View.GONE }
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(stage); addView(spin, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        ui.insets(root)
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("index", index) }
    override fun onDestroy() { super.onDestroy(); bg.shutdownNow() }

    private fun pathOf(i: Int) = if (loc.path.isEmpty()) names[i] else loc.path + "/" + names[i]

    /** 그 파일을 휴대폰 안에서 (NAS 면 앱 안에 받아서) */
    private fun fileOf(i: Int): File {
        if (loc.kind == "local") return File(loc.root, pathOf(i))
        val f = Transfers.openFile(this, loc.root, pathOf(i))
        if (f.exists() && f.length() == sizes[i]) return f
        f.parentFile?.mkdirs()
        api.download(loc.root, Entry(pathOf(i), false, sizes[i], mtimes[i] * 1_000_000), f, { _, _ -> }, { isFinishing })
        return f
    }

    private fun withFile(then: (File) -> Unit) {
        val i = index
        spin.visibility = View.VISIBLE
        bg.execute { val r = runCatching { fileOf(i) }; runOnUiThread { spin.visibility = View.GONE; r.onSuccess(then).onFailure { toast(it.message ?: "받지 못했습니다") } } }
    }

    private fun share(f: File) {
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, FilesProvider.uriFor(this, f)).setType(FilesProvider.mimeOf(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "공유"))
    }
    private fun openWith(f: File) { try { startActivity(Intent.createChooser(Transfers.viewIntent(this, f), f.name)) } catch (e: Exception) { toast("열 앱이 없습니다") } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun show() {
        title.text = if (names.size > 1) "${names[index]}  (${index + 1}/${names.size})" else names[index]
        stage.removeAllViews()
        when (kind) {
            IMG -> showImage()
            VID -> showVideo()
            PDF -> showPdf()
            else -> showText()
        }
    }

    // ---------- 사진 ----------
    private var zoom: ZoomView? = null

    private fun decode(i: Int): Drawable {
        cache.get(i)?.let { return it }
        val f = fileOf(i)
        val dm = resources.displayMetrics; val maxSide = maxOf(dm.widthPixels, dm.heightPixels) * 2
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
            val s = info.size; val k = maxOf(s.width, s.height).toFloat() / maxSide
            if (k > 1f) dec.setTargetSize((s.width / k).toInt().coerceAtLeast(1), (s.height / k).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        cache.put(i, d); return d
    }

    private fun showImage() {
        val z = ZoomView(this) { dir -> go(dir) }.apply { setOnClickListener { toggleBar() } }
        zoom = z; stage.addView(z, FrameLayout.LayoutParams(-1, -1))
        val i = index; val my = ++seq
        cache.get(i)?.let { z.set(it) } ?: run {
            spin.visibility = View.VISIBLE
            bg.execute {
                val r = runCatching { decode(i) }
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    spin.visibility = View.GONE
                    r.onSuccess { d -> z.set(d); (d as? Animatable)?.start() }.onFailure { msg("이 사진을 열 수 없습니다\n${it.message ?: ""}") }
                }
            }
        }
        // 옆 사진 미리 준비
        for (n in listOf(i + 1, i - 1)) if (n in names.indices && cache.get(n) == null) bg.execute { runCatching { decode(n) } }
    }

    private fun go(dir: Int) {
        val n = index + dir
        if (n !in names.indices) { toast(if (dir > 0) "마지막 사진입니다" else "첫 사진입니다"); return }
        index = n; show()
    }

    private fun toggleBar() { bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    private fun msg(s: String) { stage.removeAllViews(); stage.addView(ui.label(s, 15f, 0xFFCCCCCC.toInt()).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1)) }

    // ---------- 동영상 (NAS 는 바로 흘려 받기) ----------
    private fun showVideo() {
        val v = VideoView(this)
        val mc = MediaController(this); mc.setAnchorView(v); v.setMediaController(mc)
        stage.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))   // 원래 비율 그대로 가운데
        v.setOnPreparedListener { mp ->
            spin.visibility = View.GONE
            // 화면에 꽉 차게 (비율 유지)
            val vw = mp.videoWidth; val vh = mp.videoHeight
            if (vw > 0 && vh > 0 && stage.width > 0) {
                val k = minOf(stage.width.toFloat() / vw, stage.height.toFloat() / vh)
                v.layoutParams = FrameLayout.LayoutParams((vw * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
            }
            v.start(); mc.show(3000)
        }
        v.setOnErrorListener { _, _, _ -> spin.visibility = View.GONE; msg("이 동영상을 재생할 수 없습니다 — 오른쪽 위 ‘다른 앱으로 열기’를 써 보세요"); true }
        v.setOnCompletionListener { if (index + 1 < names.size) go(1) }
        spin.visibility = View.VISIBLE
        if (loc.kind == "local") { v.setVideoURI(Uri.fromFile(File(loc.root, pathOf(index)))); return }
        val p = pathOf(index)
        bg.execute {
            val r = runCatching { api.pick() }
            runOnUiThread {
                r.onSuccess { base ->
                    val u = Uri.parse(base + "/v1/file?root=" + Api.enc(loc.root) + "&path=" + Api.enc(p))
                    v.setVideoURI(u, mapOf("Authorization" to "Bearer " + api.token))
                }.onFailure { spin.visibility = View.GONE; msg(it.message ?: "NAS 에 닿지 않습니다") }
            }
        }
    }

    // ---------- PDF ----------
    private var pdf: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    override fun onStop() { super.onStop(); if (isFinishing) { pdf?.close(); pfd?.close() } }

    private fun showPdf() {
        spin.visibility = View.VISIBLE
        bg.execute {
            val r = runCatching { val f = fileOf(index); val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); d to PdfRenderer(d) }
            runOnUiThread {
                spin.visibility = View.GONE
                r.onFailure { msg("이 PDF 를 열 수 없습니다 (암호가 걸렸거나 손상)") }.onSuccess { (d, pr) ->
                    pfd = d; pdf = pr
                    val w = resources.displayMetrics.widthPixels
                    val pages = LruCache<Int, Bitmap>(6)
                    val busy = HashSet<Int>()
                    val list = ListView(this).apply { divider = null; dividerHeight = 0; setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(0, ui.dp(52), 0, 0); clipToPadding = false }
                    lateinit var ad: BaseAdapter
                    ad = object : BaseAdapter() {
                        override fun getCount() = pr.pageCount
                        override fun getItem(p: Int) = p
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
                            val iv = (c as? ImageView) ?: ImageView(this@ViewerActivity).apply { adjustViewBounds = true; setPadding(0, 0, 0, ui.dp(8)) }
                            iv.tag = p
                            pages.get(p)?.let { iv.minimumHeight = 0; iv.setImageBitmap(it); return iv }
                            iv.setImageDrawable(null); iv.minimumHeight = (w * 1.414).toInt()
                            if (!busy.add(p)) return iv
                            bg.execute {
                                val bmp = synchronized(pr) { pr.openPage(p).use { pg ->
                                    val h = (w.toFloat() * pg.height / pg.width).toInt()
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                } }
                                pages.put(p, bmp)
                                runOnUiThread { busy.remove(p); ad.notifyDataSetChanged() }   // 그 쪽을 보여 주는 칸이 무엇이든 다시 그림
                            }
                            return iv
                        }
                    }
                    list.adapter = ad
                    list.setOnItemClickListener { _, _, p, _ -> toggleBar(); title.text = "${names[index]}  (${p + 1}/${pr.pageCount}쪽)" }
                    stage.addView(list, FrameLayout.LayoutParams(-1, -1))
                    title.text = "${names[index]}  (${pr.pageCount}쪽)"
                }
            }
        }
    }

    // ---------- 텍스트 ----------
    private fun showText() {
        spin.visibility = View.VISIBLE
        bg.execute {
            val r = runCatching {
                val f = fileOf(index); val max = 2 * 1024 * 1024
                val b = f.inputStream().use { inp -> val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(65536); var left = max
                    while (left > 0) { val r = inp.read(tmp, 0, minOf(tmp.size, left)); if (r < 0) break; buf.write(tmp, 0, r); left -= r }; buf.toByteArray() }
                val s = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (e: Exception) { String(b, charset("MS949")) }
                s + if (f.length() > max) "\n\n… (앞 2MB 만 보여 줌)" else ""
            }
            runOnUiThread {
                spin.visibility = View.GONE
                r.onFailure { msg("읽을 수 없습니다") }.onSuccess { s ->
                    val tv = ui.label(s, 13.5f, 0xFFE8E8E8.toInt()).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(ui.dp(14), ui.dp(60), ui.dp(14), ui.dp(24)) }
                    stage.addView(ScrollView(this).apply { setBackgroundColor(0xFF191919.toInt()); addView(tv) }, FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
    }
}

/** 확대·이동되는 그림 칸. 원래 크기일 때 옆으로 밀면 이전·다음 */
class ZoomView(ctx: Context, private val turn: (Int) -> Unit) : ImageView(ctx) {
    private val m = Matrix()
    private var base = 1f
    private var cur = 1f
    private var dw = 0; private var dh = 0

    init { scaleType = ScaleType.MATRIX; setBackgroundColor(Color.BLACK) }

    fun set(d: Drawable) { setImageDrawable(d); dw = d.intrinsicWidth; dh = d.intrinsicHeight; post { fit() } }

    private fun fit() {
        if (dw <= 0 || width == 0) return
        base = minOf(width.toFloat() / dw, height.toFloat() / dh); cur = base
        m.reset(); m.postScale(base, base); m.postTranslate((width - dw * base) / 2, (height - dh * base) / 2); imageMatrix = m
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun clamp() {
        val r = RectF(0f, 0f, dw.toFloat(), dh.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        dx = if (r.width() <= width) (width - r.width()) / 2 - r.left else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
        dy = if (r.height() <= height) (height - r.height()) / 2 - r.top else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
        m.postTranslate(dx, dy); imageMatrix = m
    }

    private val sg = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val k = (cur * d.scaleFactor).coerceIn(base, base * 8) / cur
            cur *= k; m.postScale(k, k, d.focusX, d.focusY); clamp(); return true
        }
    })
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
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

    // 원래 크기에서 옆으로 충분히 밀었다 떼면 넘김 (빠르게 튕기지 않아도)
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
    override fun performClick(): Boolean { super.performClick(); return true }
}
