package app.gitbuilder

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.core.content.FileProvider
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Tela única e simples de compilação. Apenas exibe uma BuildSession; fechar esta tela NÃO interrompe a compilação. */
class BuildActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val renderQueued = AtomicBoolean(false)
    private val listener: () -> Unit = { requestRender() }
    private lateinit var session: BuildSession

    private val night by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private val bg by lazy { getColor(R.color.app_bg) }
    private val txt by lazy { if (night) Color.WHITE else 0xFF202124.toInt() }
    private val muted by lazy { if (night) 0xFF9AA0A6.toInt() else 0xFF70757A.toInt() }
    private val surface by lazy { if (night) 0xFF2A2B2E.toInt() else 0xFFE8EAED.toInt() }
    private val cardBg by lazy { if (night) 0xFF1E1F22.toInt() else Color.WHITE }
    private val cardLine by lazy { if (night) 0xFF3C4043.toInt() else 0xFFE0E0E0.toInt() }
    private val blue = 0xFF1A73E8.toInt()
    private val green by lazy { if (night) 0xFF81C995.toInt() else 0xFF1E8E3E.toInt() }
    private val red by lazy { if (night) 0xFFF28B82.toInt() else 0xFFD93025.toInt() }

    private val stepNames = listOf("Enviando seu projeto", "Preparando a compilação", "Compilando o app", "Finalizando o APK")

    private lateinit var emoji: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var detailsToggle: TextView
    private lateinit var detailsText: TextView
    private lateinit var detailsCopy: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private val stepIcon = ArrayList<TextView>()
    private val stepSpin = ArrayList<ProgressBar>()
    private val stepLabel = ArrayList<TextView>()
    private var primaryAction: () -> Unit = {}
    private var detailsOpen = false
    private var lastDetails = ""
    private var downloading = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        s.addListener(listener)
        render()
    }

    // ---------- UI ----------
    private fun styleBtn(b: Button, fill: Int, text: Int) {
        b.background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat() }
        b.setTextColor(text); b.isAllCaps = false; b.stateListAnimator = null
        b.minHeight = 0; b.minimumHeight = dp(52)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); fitsSystemWindows = true }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(16))
        }
        emoji = TextView(this).apply { textSize = 56f; gravity = Gravity.CENTER }
        title = TextView(this).apply { textSize = 22f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(txt) }
        subtitle = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        content.addView(emoji, LinearLayout.LayoutParams(-1, -2))
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(24) })

        // Lista de etapas em linguagem simples
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { setColor(cardBg); setStroke(dp(1), cardLine); cornerRadius = dp(16).toFloat() }
        }
        for (name in stepNames) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            val box = FrameLayout(this)
            val icon = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
            val spin = ProgressBar(this).apply { visibility = View.GONE }
            box.addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            box.addView(spin, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            val label = TextView(this).apply { text = name; textSize = 16f; setTextColor(txt) }
            row.addView(box, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            stepIcon.add(icon); stepSpin.add(spin); stepLabel.add(label)
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        // Detalhes técnicos: escondidos por padrão
        detailsToggle = TextView(this).apply {
            text = "Ver detalhes técnicos"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(muted)
            setPadding(dp(12), dp(20), dp(12), dp(12))
            setOnClickListener { detailsOpen = !detailsOpen; render() }
        }
        detailsText = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(txt); setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(12).toFloat() }
        }
        detailsCopy = TextView(this).apply {
            text = "Copiar detalhes"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(blue)
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
            setOnClickListener { copyLogs() }
        }
        content.addView(detailsToggle, LinearLayout.LayoutParams(-1, -2))
        content.addView(detailsText, LinearLayout.LayoutParams(-1, -2))
        content.addView(detailsCopy, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }

        // Botões fixos embaixo
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(20)) }
        btnPrimary = Button(this).apply { setOnClickListener { primaryAction() } }
        btnSecondary = Button(this).apply { setOnClickListener { finish() } }
        bar.addView(btnPrimary, LinearLayout.LayoutParams(-1, -2))
        bar.addView(btnSecondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun requestRender() { if (renderQueued.compareAndSet(false, true)) ui.post { renderQueued.set(false); render() } }

    private fun render() {
        val snap = session.snapshot()
        val fin = session.finalStatus
        val res = session.meta.optString("result")
        val hasRun = session.meta.has("runId")
        val failedIdx = snap.indexOfFirst { it.second == "failure" }

        val kind = when {
            fin == null -> "run"
            res == "success" && session.artifactId > 0 -> "ok"
            res == "success" -> "noapk"
            res == "cancelled" -> "cancelled"
            (res == "interrupted" || res == "running") && hasRun -> "pause"
            res == "interrupted" || res == "running" -> "stopped"
            else -> "fail"
        }

        val e: String; val t: String; val sub: String
        when (kind) {
            "run" -> { e = "⏳"; t = "Compilando seu app…"; sub = "Pode levar alguns minutos. Você pode fechar o app, avisaremos quando terminar." }
            "ok" -> { e = "✅"; t = "Seu app está pronto!"; sub = "Toque em Instalar aplicativo para colocá-lo no seu celular." }
            "noapk" -> { e = "⚠️"; t = "Compilado, mas sem arquivo"; sub = "Não encontramos o APK gerado. Tente novamente." }
            "cancelled" -> { e = "🚫"; t = "Compilação cancelada"; sub = "" }
            "pause" -> { e = "📡"; t = "Perdemos o acompanhamento"; sub = "Seu app pode ter continuado a compilar. Toque em Continuar acompanhando." }
            "stopped" -> { e = "⚠️"; t = "Compilação interrompida"; sub = "O app foi fechado antes de terminar. Selecione o projeto e tente novamente." }
            else -> {
                e = "❌"; t = "Não foi possível compilar"
                sub = when (failedIdx) {
                    0 -> "Não conseguimos enviar o projeto. Confira sua internet e seu token."
                    1 -> "Não conseguimos preparar a compilação. Tente novamente em instantes."
                    2 -> "O projeto tem algum erro e não pôde ser compilado. Veja os detalhes técnicos para mostrar a quem desenvolveu."
                    3 -> "O app foi compilado, mas o APK não ficou pronto."
                    else -> "Algo deu errado. Tente novamente."
                }
            }
        }
        emoji.text = e; title.text = t; subtitle.text = sub
        subtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE

        // Etapas
        for (i in stepIcon.indices) {
            val st = snap.getOrNull(i)?.second ?: "pending"
            val running = st == "running"
            stepSpin[i].visibility = if (running) View.VISIBLE else View.GONE
            stepIcon[i].visibility = if (running) View.GONE else View.VISIBLE
            when (st) {
                "success" -> { stepIcon[i].text = "✓"; stepIcon[i].setTextColor(green); stepLabel[i].setTextColor(txt) }
                "failure" -> { stepIcon[i].text = "✕"; stepIcon[i].setTextColor(red); stepLabel[i].setTextColor(txt) }
                "skipped" -> { stepIcon[i].text = "–"; stepIcon[i].setTextColor(muted); stepLabel[i].setTextColor(muted) }
                else -> { stepIcon[i].text = "○"; stepIcon[i].setTextColor(muted); stepLabel[i].setTextColor(if (running) txt else muted) }
            }
        }
        progress.max = snap.size.coerceAtLeast(1)
        progress.progress = snap.count { it.second == "success" || it.second == "failure" || it.second == "skipped" }

        // Botões
        btnSecondary.visibility = View.GONE
        when (kind) {
            "run" -> { btnPrimary.text = "Continuar em segundo plano"; primaryAction = { goBackground() } }
            "ok" -> {
                btnPrimary.text = if (downloading) "Preparando…" else "Instalar aplicativo"
                btnPrimary.isEnabled = !downloading
                primaryAction = { installApk() }
                btnSecondary.text = "Concluir"; btnSecondary.visibility = View.VISIBLE
            }
            "pause" -> {
                btnPrimary.text = "Continuar acompanhando"; primaryAction = { resume() }
                btnSecondary.text = "Fechar"; btnSecondary.visibility = View.VISIBLE
            }
            else -> { btnPrimary.text = "Fechar"; primaryAction = { finish() } }
        }
        if (kind != "ok") btnPrimary.isEnabled = true
        styleBtn(btnPrimary, if (kind == "ok" || kind == "run" || kind == "pause") blue else surface,
            if (kind == "ok" || kind == "run" || kind == "pause") Color.WHITE else txt)
        styleBtn(btnSecondary, surface, txt)

        // Detalhes técnicos
        detailsToggle.text = if (detailsOpen) "Ocultar detalhes técnicos" else "Ver detalhes técnicos"
        detailsText.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        detailsCopy.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        if (detailsOpen) {
            val d = session.fullText()
            if (d != lastDetails) { detailsText.text = d; lastDetails = d }
        }
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun copyLogs() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("detalhes", session.fullText()))
            toast("Detalhes copiados")
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
            toast("Compilando em segundo plano. Avisaremos quando terminar.")
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

    // ---------- Instalação do APK ----------
    private var pendingInstall = false

    override fun onResume() {
        super.onResume()
        // Voltou da tela de permissão: continua a instalação sozinho
        if (pendingInstall && packageManager.canRequestPackageInstalls()) { pendingInstall = false; installApk() }
    }

    private fun installApk() {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            toast("Permita que o GitBuilder instale apps e volte para cá.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = true; render()
        val gh = session.gh; val full = session.fullName; val art = session.artifactId; val repoName = session.repoName
        Thread {
            var apk: File? = null
            try {
                val tmp = File(cacheDir, "artifact.zip")
                val c = gh.openDownload("/repos/$full/actions/artifacts/$art/zip")
                    ?: throw Exception("O arquivo não está mais disponível")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val dir = File(cacheDir, "apk").apply { deleteRecursively(); mkdirs() }
                ZipInputStream(tmp.inputStream()).use { z ->
                    var item = z.nextEntry
                    while (item != null && apk == null) {
                        if (!item.isDirectory && item.name.endsWith(".apk")) {
                            val f = File(dir, "$repoName.apk")
                            f.outputStream().use { z.copyTo(it) }
                            apk = f
                        }
                        item = z.nextEntry
                    }
                }
                tmp.delete()
                val file = apk ?: throw Exception("Nenhum APK encontrado")
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                ui.post {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (e: Exception) { toast("Não foi possível abrir o instalador: ${e.message}") }
                }
            } catch (e: Exception) {
                toast("Não foi possível preparar a instalação: ${e.message}")
            } finally {
                ui.post { downloading = false; render() }
            }
        }.start()
    }
}
