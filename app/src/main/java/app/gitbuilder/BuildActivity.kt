package app.gitbuilder

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class BuildActivity : Activity() {

    companion object {
        // Workflow injetado no repositório temporário: compila o projeto ENVIADO PELO USUÁRIO.
        private const val WORKFLOW = """name: Build APK
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - name: Build debug APK
        run: |
          if [ -f gradlew ]; then chmod +x gradlew; ./gradlew --no-daemon assembleDebug --stacktrace; else gradle --no-daemon assembleDebug --stacktrace; fi
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: '**/build/outputs/apk/**/*.apk'
          if-no-files-found: error
"""
        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val appLog = StringBuilder()
    @Volatile private var runnerLog = ""
    @Volatile private var stopped = false
    private var artifactId = -1L
    private var repoName = ""
    private var fullName = ""
    private lateinit var gh: GitHub
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var actions: LinearLayout
    private lateinit var btnApk: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.data
        val token = TokenStore.get(this)
        if (uri == null || token == null) { finish(); return }
        buildUi()
        gh = GitHub(token)
        Thread { runPipeline(uri) }.start()
    }

    override fun onDestroy() { stopped = true; super.onDestroy() }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows = true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD)
            setPadding(32, 24, 32, 24); setBackgroundColor(0xFF202124.toInt()); text = "Iniciando…"
        }
        logView = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt()); typeface = Typeface.MONOSPACE; textSize = 11f
            setPadding(24, 16, 24, 16); setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        actions = LinearLayout(this).apply { visibility = View.GONE; setPadding(16, 16, 16, 16); setBackgroundColor(0xFF202124.toInt()) }
        btnApk = Button(this).apply { text = "Baixar APK"; visibility = View.GONE; setOnClickListener { downloadApk() } }
        val btnCopy = Button(this).apply { text = "Copiar logs"; setOnClickListener { copyLogs() } }
        actions.addView(btnApk, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btnCopy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun setStatus(s: String) { ui.post { status.text = s } }
    private fun fullText() = synchronized(appLog) { appLog.toString() } + runnerLog
    private fun refresh() {
        val t = fullText()
        ui.post {
            val child = scroll.getChildAt(0)
            val atBottom = child.bottom - (scroll.height + scroll.scrollY) < 120
            logView.text = t
            if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private fun log(s: String) { synchronized(appLog) { appLog.append("[app] ").append(s).append('\n') }; refresh() }
    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun copyLogs() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("logs", fullText()))
            toast("Logs copiados")
        } catch (e: Exception) { toast("Não foi possível copiar: ${e.message}") }
    }

    // ---------- Pipeline ----------
    private fun cleanPath(raw: String, prefix: String): String? {
        if (raw.startsWith("__MACOSX/")) return null
        val p = raw.removePrefix(prefix)
        if (p.isEmpty() || p.split('/').any { it == ".." }) return null
        if (p.startsWith(".git/") || p.startsWith(".github/workflows/") || p.endsWith(".DS_Store")) return null
        return p
    }

    private fun entry(path: String, sha: String) = JSONObject().put("path", path)
        .put("mode", if (path == "gradlew" || path.endsWith("/gradlew")) "100755" else "100644")
        .put("type", "blob").put("sha", sha)

    private fun runPipeline(uri: Uri) {
        try {
            setStatus("Validando token…")
            val login = gh.call("GET", "/user").getString("login")
            log("Autenticado como $login")

            // Lê o ZIP (1ª passada: nomes)
            val names = ArrayList<String>()
            contentResolver.openInputStream(uri)!!.use { ins ->
                val z = ZipInputStream(ins)
                var e = z.nextEntry
                while (e != null) { if (!e.isDirectory) names.add(e.name); e = z.nextEntry }
            }
            val roots = names.filter { !it.startsWith("__MACOSX/") }.map { it.substringBefore('/', "") }.toSet()
            val prefix = if (roots.size == 1 && roots.first().isNotEmpty()) roots.first() + "/" else ""
            val files = names.mapNotNull { cleanPath(it, prefix) }
            if (files.none { it == "settings.gradle" || it == "settings.gradle.kts" })
                throw Exception("O ZIP precisa conter um projeto Gradle (settings.gradle[.kts]) na raiz.")
            log("ZIP lido: ${files.size} arquivos")

            repoName = "apk-build-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            setStatus("Criando repositório privado…")
            val repo = gh.call("POST", "/user/repos", JSONObject()
                .put("name", repoName).put("private", true).put("auto_init", true)
                .put("description", "Build temporário criado pelo GitBuilder"))
            fullName = repo.getString("full_name")
            val branch = repo.getString("default_branch")
            log("Repositório criado: $fullName")

            // 2ª passada: envia blobs
            val tree = JSONArray()
            var n = 0
            contentResolver.openInputStream(uri)!!.use { ins ->
                val z = ZipInputStream(ins)
                var en = z.nextEntry
                while (en != null) {
                    if (stopped) return
                    if (!en.isDirectory) {
                        val p = cleanPath(en.name, prefix)
                        if (p != null) {
                            val data = z.readBytes()
                            val sha = gh.call("POST", "/repos/$fullName/git/blobs", JSONObject()
                                .put("content", Base64.encodeToString(data, Base64.NO_WRAP))
                                .put("encoding", "base64")).getString("sha")
                            tree.put(entry(p, sha)); n++
                            setStatus("Enviando arquivos ($n/${files.size})…")
                        }
                    }
                    en = z.nextEntry
                }
            }
            val wfSha = gh.call("POST", "/repos/$fullName/git/blobs", JSONObject()
                .put("content", WORKFLOW).put("encoding", "utf-8")).getString("sha")
            tree.put(entry(".github/workflows/build.yml", wfSha))
            log("Arquivos enviados: $n (+ workflow)")

            setStatus("Criando commit…")
            var parent: String? = null
            for (i in 1..10) {
                try {
                    parent = gh.call("GET", "/repos/$fullName/git/ref/heads/$branch").getJSONObject("object").getString("sha")
                    break
                } catch (e: ApiException) { if (e.code != 404 && e.code != 409) throw e; Thread.sleep(1000) }
            }
            if (parent == null) throw Exception("Branch inicial do repositório não ficou disponível")
            val treeSha = gh.call("POST", "/repos/$fullName/git/trees", JSONObject().put("tree", tree)).getString("sha")
            val commit = gh.call("POST", "/repos/$fullName/git/commits", JSONObject()
                .put("message", "Projeto para build").put("tree", treeSha).put("parents", JSONArray().put(parent))).getString("sha")
            gh.call("PATCH", "/repos/$fullName/git/refs/heads/$branch", JSONObject().put("sha", commit).put("force", true))
            log("Commit enviado: ${commit.take(7)}")

            // Aguarda a execução do Actions disparada por este commit
            setStatus("Aguardando GitHub Actions…")
            var run: JSONObject? = null
            for (i in 1..40) {
                if (stopped) return
                val runs = gh.call("GET", "/repos/$fullName/actions/runs?head_sha=$commit").getJSONArray("workflow_runs")
                if (runs.length() > 0) { run = runs.getJSONObject(0); break }
                Thread.sleep(3000)
            }
            if (run == null) throw Exception("Nenhuma execução do Actions foi iniciada em 2 min. Verifique se o token tem permissão 'workflow'.")
            val runId = run.getLong("id")
            log("Execução iniciada: ${run.getString("html_url")}")

            // Acompanha status e logs reais
            var fails = 0
            var conclusion = ""
            while (true) {
                if (stopped) return
                try {
                    val r = gh.call("GET", "/repos/$fullName/actions/runs/$runId")
                    val st = r.getString("status")
                    val jobs = gh.call("GET", "/repos/$fullName/actions/runs/$runId/jobs").getJSONArray("jobs")
                    if (jobs.length() > 0) {
                        val raw = gh.jobLog(fullName, jobs.getJSONObject(0).getLong("id"))
                        if (raw != null) {
                            runnerLog = "\n──── Logs do GitHub Actions ────\n" + ANSI.replace(raw.removePrefix("\uFEFF"), "")
                            refresh()
                        }
                    }
                    fails = 0
                    if (st == "completed") { conclusion = r.optString("conclusion"); break }
                    setStatus(if (st == "in_progress") "Compilando…" else "Na fila do GitHub Actions…")
                } catch (e: java.io.IOException) {
                    if (++fails > 5) throw e
                    log("Falha de rede (${e.message}), tentando novamente…")
                }
                Thread.sleep(3000)
            }

            // Artefato válido?
            try {
                val arts = gh.call("GET", "/repos/$fullName/actions/runs/$runId/artifacts").getJSONArray("artifacts")
                for (i in 0 until arts.length()) {
                    val a = arts.getJSONObject(i)
                    if (!a.optBoolean("expired") && a.optLong("size_in_bytes") > 0) artifactId = a.getLong("id")
                }
            } catch (e: Exception) { log("Não foi possível listar artefatos: ${e.message}") }

            val msg = when (conclusion) {
                "success" -> "Compilação concluída com sucesso"
                "failure" -> "A compilação falhou"
                "cancelled" -> "Compilação cancelada"
                else -> "Compilação finalizada: $conclusion"
            }
            finishUi(msg)
        } catch (e: Exception) {
            log("ERRO: ${e.message}")
            finishUi("Falha: ${e.message}")
        }
    }

    private fun finishUi(msg: String) = ui.post {
        status.text = msg
        actions.visibility = View.VISIBLE
        btnApk.visibility = if (artifactId > 0) View.VISIBLE else View.GONE
    }

    // ---------- Download do APK ----------
    private fun downloadApk() {
        btnApk.isEnabled = false
        Thread {
            try {
                val tmp = File(cacheDir, "artifact.zip")
                val c = gh.openDownload("/repos/$fullName/actions/artifacts/$artifactId/zip")
                    ?: throw Exception("Artefato não encontrado")
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
