package kr.artchijun.myphoto

import android.annotation.SuppressLint
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 장소를 지도로: 장소마다 그곳 사진 한 장의 GPS 로 자리를 잡아 (한 번 찾은 자리는 기억) 지도(OpenStreetMap)에 동그라미로.
 * 동그라미를 누르면 이름·장수 → [사진 보기] 로 그 장소의 사진.
 */
@SuppressLint("SetJavaScriptEnabled")
class PlaceMap(private val a: Activity, private val ui: Ui, private val ns: String, private val places: List<Group>, private val open: (Group) -> Unit) : FrameLayout(a) {
    private val web = WebView(a)
    private val note = ui.label("", 13f, ui.text).apply {
        background = ui.round(if (ui.dark) 0xE6262626.toInt() else 0xF2FFFFFF.toInt(), 16); elevation = ui.dpf(4f)
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    }
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var ready = false
    private val queued = ArrayList<JSONObject>()
    private val pf = Prefs(a)

    inner class Bridge {
        @JavascriptInterface fun open(id: String) { val g = places.firstOrNull { it.id.toString() == id } ?: return; a.runOnUiThread { open(g) } }
    }

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "App")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { ready = true; flush() }
        }
        addView(web, LayoutParams(-1, -1))
        addView(note, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(10) })
        val dark = ui.dark
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js"></script>
<style>html,body,#m{margin:0;height:100%;background:${if (dark) "#1b1b1b" else "#eee"}}
.pin{background:#2f8cff;color:#fff;border:2px solid #fff;border-radius:50%;text-align:center;font:600 12px/30px sans-serif;box-shadow:0 1px 4px #0006}
.cl{background:#ff7a2f;color:#fff;border:2px solid #fff;border-radius:50%;text-align:center;font:700 13px/38px sans-serif;box-shadow:0 1px 4px #0006}
.pp b{font-size:15px}.pp a{display:inline-block;margin-top:6px;padding:6px 12px;background:#2f8cff;color:#fff;border-radius:14px;text-decoration:none}</style></head>
<body><div id="m"></div><script>
var map=L.map('m',{zoomControl:false}).setView([36,127],3);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
var cl=L.markerClusterGroup({showCoverageOnHover:false,iconCreateFunction:function(c){var n=0;c.getAllChildMarkers().forEach(function(m){n+=m.options.n});
 return L.divIcon({html:'<div class="cl">'+(n>999?Math.round(n/100)/10+'k':n)+'</div>',className:'',iconSize:[42,42]});}});
map.addLayer(cl);var first=true,all=[],moved=false;
document.getElementById('m').addEventListener('touchstart',function(){moved=true;},{passive:true});
function add(list){list.forEach(function(p){var s=p.n>999?Math.round(p.n/100)/10+'k':p.n;
 var m=L.marker([p.lat,p.lng],{n:p.n,icon:L.divIcon({html:'<div class="pin">'+s+'</div>',className:'',iconSize:[34,34]})});
 m.bindPopup('<div class="pp"><b>'+p.name+'</b><br>'+p.sub+(p.sub?' · ':'')+p.n+'장<br><a href="#" onclick="App.open(\''+p.id+'\');return false">사진 보기</a></div>');
 cl.addLayer(m);all.push([p.lat,p.lng]);});
 if(all.length&&!moved){ map.fitBounds(all,{padding:[30,30],maxZoom:12}); }}
</script></body></html>"""
        web.loadDataWithBaseURL("https://myphoto.local/", html, "text/html", "utf-8", null)
        locate()
    }

    private fun flush() {
        if (!ready) return
        val l = synchronized(queued) { val c = JSONArray(queued); queued.clear(); c }
        if (l.length() > 0) web.evaluateJavascript("add($l)", null)
    }
    private fun push(p: Group, lat: Double, lng: Double) {
        synchronized(queued) { queued += JSONObject().put("id", p.id.toString()).put("name", p.name).put("sub", p.sub).put("n", p.count).put("lat", lat).put("lng", lng) }
    }

    /** 장소마다 자리 찾기: 기억해 둔 것은 바로, 나머지는 그곳 사진 한 장의 GPS (6곳씩 함께) */
    private fun locate() {
        val todo = ArrayList<Group>()
        for (p in places) {
            val c = pf.str("geo_${p.id}", "")
            if (c == "-") continue
            if (c.isNotEmpty()) { val (la, lo) = c.split(','); push(p, la.toDouble(), lo.toDouble()) } else todo += p
        }
        note.text = if (todo.isEmpty()) "장소 ${places.size}곳" else "장소 자리를 찾는 중  0/${todo.size}"
        flush()
        if (todo.isEmpty()) { postDelayed({ note.visibility = View.GONE }, 2500); return }
        val done = java.util.concurrent.atomic.AtomicInteger()
        val found = java.util.concurrent.atomic.AtomicInteger()
        for (p in todo) pool.execute {
            val gps = try { Photos(a).placeGps(ns, p.id) } catch (e: Exception) { null }
            if (gps != null) { pf.putStr("geo_${p.id}", "${gps.first},${gps.second}"); push(p, gps.first, gps.second); found.incrementAndGet() }
            else pf.putStr("geo_${p.id}", "-")
            val n = done.incrementAndGet()
            if (n % 10 == 0 || n == todo.size) a.runOnUiThread {
                flush()
                note.text = if (n < todo.size) "장소 자리를 찾는 중  $n/${todo.size}" else "장소 ${places.size}곳" + (if (found.get() == 0 && todo.size > 0) " — 시놀로지에서 위치를 받지 못했습니다" else "")
                if (n == todo.size) postDelayed({ note.visibility = View.GONE }, 4000)
            }
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow() }
}
