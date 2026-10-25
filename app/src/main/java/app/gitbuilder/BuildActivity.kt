package app.gitbuilder

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Apenas exibe uma BuildSession (ao vivo ou do histórico). Fechar esta tela NÃO interrompe a compilação. */
class BuildActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val renderQueued = AtomicBoolean(false)
    private val expanded = HashMap<Int, Boolean>()
    private val headerViews = ArrayList<TextView>()
    private val bodyViews = ArrayList<TextView>()
    private val shown = ArrayList<String>()
    private val listener: () -> Unit = { requestRender() }
    private lateinit var session: BuildSession
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var runBar: LinearLayout
    private lateinit var btnApk: Button
    private lateinit var btnResume: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val id = intent.getStringExtra("history_id")
        val s = Sessions.current?.takeIf { it.id == id }
            ?: id?.let { BuildSession.fromHistory(this, TokenStore.get(this) ?: "", it) }
        if (s == null) { finish(); return }
        attach(s)
    }

    override fun onDestroy() { if (::session.isInitialized) session.removeListener(listener); super.onDestroy() }

    private fun attach(s: BuildSession) {
        if (::session.isInitialized) session.removeListener(listener)
        session = s
        expanded.clear()
        s.addListener(listener)
        render()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD)
            setPadding(32, 24, 32, 16); setBackgroundColor(0xFF202124.toInt()); text = "Iniciando…"
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(container) }
        actions = LinearLayout(this).apply { visibility = View.GONE; setPadding(16, 16, 16, 16); setBackgroundColor(0xFF202124.toInt()) }
        runBar = LinearLayout(this).apply { visibility = View.GONE; setPadding(16, 16, 16, 16); setBackgroundColor(0xFF202124.toInt()) }
        runBar.addView(Button(this).apply { text = "Continuar em segundo plano"; setOnClickListener { goBackground() } }, LinearLayout.LayoutParams(-1, -2))
        btnResume = Button(this).apply { text = "Retomar acompanhamento"; visibility = View.GONE; setOnClickListener { resume() } }
        btnApk = Button(this).apply { text = "Baixar APK"; visibility = View.GONE; setOnClickListener { downloadApk() } }
        val btnCopy = Button(this).apply { text = "Copiar logs"; setOnClickListener { copyLogs() } }
        actions.addView(btnResume, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btnApk, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btnCopy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(runBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun color(s: String) = when (s) {
        "running" -> 0xFFFFD54F.toInt(); "success" -> 0xFF81C995.toInt(); "failure" -> 0xFFF28B82.toInt(); else -> 0xFF9AA0A6.toInt()
    }
    private fun icon(s: String) = when (s) { "running" -> "▶"; "success" -> "✔"; "failure" -> "✘"; "skipped" -> "—"; else -> "○" }

    private fun addStageViews(i: Int) {
        val h = TextView(this).apply {
            textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(32, 22, 32, 22)
            setBackgroundColor(0xFF1B1B1F.toInt())
            setOnClickListener { expanded[i] = bodyViews[i].visibility != View.VISIBLE; render() }
        }
        val body = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt()); typeface = Typeface.MONOSPACE; textSize = 11f
            setPadding(24, 12, 24, 12); setTextIsSelectable(true); visibility = View.GONE
        }
        container.addView(h, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3 })
        container.addView(body, LinearLayout.LayoutParams(-1, -2))
        headerViews.add(h); bodyViews.add(body); shown.add("")
    }

    private fun requestRender() { if (renderQueued.compareAndSet(false, true)) ui.post { renderQueued.set(false); render() } }

    private fun render() {
        val snap = session.snapshot()
        val total = snap.size
        val child = scroll.getChildAt(0)
        val atBottom = child.bottom - (scroll.height + scroll.scrollY) < 200
        while (headerViews.size < total) addStageViews(headerViews.size)
        for ((i, s) in snap.withIndex()) {
            val h = headerViews[i]
            val ht = "${icon(s.second)}  [${i + 1}/$total] ${s.first}"
            if (h.text.toString() != ht) h.text = ht
            h.setTextColor(color(s.second))
            val open = expanded[i] ?: (s.second == "running" || s.second == "failure")
            val b = bodyViews[i]
            if (open && s.third.isNotEmpty()) {
                if (shown[i] != s.third) { b.text = s.third; shown[i] = s.third }
                b.visibility = View.VISIBLE
            } else b.visibility = View.GONE
        }
        progress.max = total
        progress.progress = snap.count { it.second == "success" || it.second == "failure" || it.second == "skipped" }
        val fin = session.finalStatus
        status.text = fin ?: session.currentLine()
        actions.visibility = if (fin != null) View.VISIBLE else View.GONE
        runBar.visibility = if (fin == null && session.running) View.VISIBLE else View.GONE
        btnApk.visibility = if (fin != null && session.artifactId > 0) View.VISIBLE else View.GONE
        val res = session.meta.optString("result")
        btnResume.visibility = if (fin != null && !session.running && session.meta.has("runId") && (res == "interrupted" || res == "running")) View.VISIBLE else View.GONE
        if (atBottom && fin == null) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun copyLogs() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("logs", session.fullText()))
            toast("Logs copiados")
        } catch (e: Exception) { toast("Não foi possível copiar: ${e.message}") }
    }

    // ---------- Segundo plano ----------
    private fun goBackground() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        continueInBackground()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 3) continueInBackground()   // mesmo sem permissão a compilação segue; só a notificação fica oculta
    }

    private fun continueInBackground() {
        if (Sessions.current === session && session.running) {
            startForegroundService(Intent(this, BuildService::class.java))   // garante o serviço e a notificação
            toast("Compilando em segundo plano. Acompanhe pela notificação.")
            moveTaskToBack(true)
        }
    }

    private fun resume() {
        val token = TokenStore.get(this)
        if (token == null) { toast("Salve o token primeiro"); return }
        if (Sessions.runningId() != null) { toast("Já existe uma compilação em andamento"); return }
        val ns = BuildSession.fromHistory(this, token, session.id) ?: return
        ns.prepareResume()
        Sessions.current = ns
        attach(ns)
        startForegroundService(Intent(this, BuildService::class.java).setAction(BuildService.ACTION_RESUME))
    }

    // ---------- Download do APK ----------
    private fun downloadApk() {
        btnApk.isEnabled = false
        val gh = session.gh; val full = session.fullName; val art = session.artifactId; val repoName = session.repoName
        Thread {
            try {
                val tmp = File(cacheDir, "artifact.zip")
                val c = gh.openDownload("/repos/$full/actions/artifacts/$art/zip")
                    ?: throw Exception("Artefato não encontrado (expirou ou o repositório foi apagado)")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                var count = 0
                ZipInputStream(tmp.inputStream()).use { z ->
                    var item = z.nextEntry
                    while (item != null) {
                        if (!item.isDirectory && item.name.endsWith(".apk")) {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, "$repoName-${item!!.name.substringAfterLast('/')}")
                                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            }
                            val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw Exception("Não foi possível criar o arquivo em Downloads")
                            contentResolver.openOutputStream(out)!!.use { z.copyTo(it) }
                            count++
                        }
                        item = z.nextEntry
                    }
                }
                tmp.delete()
                if (count == 0) throw Exception("O artefato não contém APK")
                toast("$count APK(s) salvo(s) na pasta Downloads")
            } catch (e: Exception) {
                toast("Erro no download: ${e.message}")
            } finally {
                ui.post { btnApk.isEnabled = true }
            }
        }.start()
    }
}
