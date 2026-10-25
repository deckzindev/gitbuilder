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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class BuildActivity : Activity() {

    class Stage(var title: String, var state: String = "pending", var log: String = "")

    companion object {
        @Volatile var runningId: String? = null
        private val APP_STAGES = listOf(
            "Validar token e ler o ZIP", "Criar repositório privado", "Enviar arquivos",
            "Criar commit", "Aguardar o GitHub Actions"
        )
        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

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
    }

    private val ui = Handler(Looper.getMainLooper())
    private val stages = ArrayList<Stage>()               // protegido por synchronized(stages)
    private val renderQueued = AtomicBoolean(false)
    private val expanded = HashMap<Int, Boolean>()        // escolhas manuais do usuário
    private val headerViews = ArrayList<TextView>()
    private val bodyViews = ArrayList<TextView>()
    private val shown = ArrayList<String>()
    @Volatile private var finalStatus: String? = null
    private var curApp = 0
    private var artifactId = -1L
    private var repoName = ""
    private var fullName = ""
    private lateinit var meta: JSONObject
    private lateinit var gh: GitHub
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var btnApk: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val hid = intent.getStringExtra("history_id")
        if (hid != null) { loadHistory(hid); return }

        val uri = intent.data
        val token = TokenStore.get(this)
        if (uri == null || token == null) { finish(); return }
        gh = GitHub(token)
        APP_STAGES.forEach { stages.add(Stage(it)) }
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        meta = JSONObject().put("id", id).put("zipName", intent.getStringExtra("zip_name") ?: "projeto.zip")
            .put("startedAt", System.currentTimeMillis()).put("finishedAt", 0L)
            .put("result", "running").put("summary", "Em andamento")
        runningId = id
        persist()
        requestRender()
        // A compilação segue em segundo plano mesmo que você saia desta tela; o resultado vai para o Histórico.
        Thread { runPipeline(uri) }.start()
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
        btnApk = Button(this).apply { text = "Baixar APK"; visibility = View.GONE; setOnClickListener { downloadApk() } }
        val btnCopy = Button(this).apply { text = "Copiar logs"; setOnClickListener { copyLogs() } }
        actions.addView(btnApk, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btnCopy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
            setOnClickListener {
                expanded[i] = bodyViews[i].visibility != View.VISIBLE
                render()
            }
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
        val snap = synchronized(stages) { stages.map { Triple(it.title, it.state, it.log) } }
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
        var cur = snap.indexOfFirst { it.second == "running" }
        if (cur < 0) cur = snap.indexOfLast { it.second != "pending" }
        status.text = finalStatus ?: if (cur >= 0) "Etapa ${cur + 1}/$total — ${snap[cur].first}" else "Iniciando…"
        if (atBottom && finalStatus == null) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun fullText(): String = "GitBuilder — ${meta.optString("zipName")}\nRepositório: $fullName\n\n" +
        synchronized(stages) {
            stages.withIndex().joinToString("\n") { (i, s) ->
                "===== [${i + 1}/${stages.size}] ${s.title} (${s.state}) =====\n${s.log}"
            }
        }

    private fun copyLogs() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("logs", fullText()))
            toast("Logs copiados")
        } catch (e: Exception) { toast("Não foi possível copiar: ${e.message}") }
    }

    // ---------- Histórico ----------
    private fun persist() {
        try {
            val arr = JSONArray()
            synchronized(stages) { stages.forEach { arr.put(JSONObject().put("title", it.title).put("state", it.state).put("log", it.log)) } }
            meta.put("stages", arr.length()).put("repo", fullName).put("artifactId", artifactId)
            History.saveStages(this, meta.getString("id"), arr)
            History.saveMeta(this, meta)
        } catch (e: Exception) { /* histórico é best-effort */ }
    }

    private fun loadHistory(id: String) {
        val m = History.meta(this, id)
        if (m == null) { finish(); return }
        meta = m
        fullName = m.optString("repo"); repoName = fullName.substringAfter('/')
        artifactId = m.optLong("artifactId", -1)
        val arr = History.stages(this, id)
        synchronized(stages) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stages.add(Stage(o.optString("title"), o.optString("state"), o.optString("log")))
            }
        }
        val interrupted = m.optString("result") == "running" && id != runningId
        finalStatus = if (interrupted) "Interrompido: o app foi fechado antes do fim da compilação" else m.optString("summary", "Em andamento")
        gh = GitHub(TokenStore.get(this) ?: "")
        actions.visibility = View.VISIBLE
        btnApk.visibility = if (artifactId > 0) View.VISIBLE else View.GONE
        render()
    }

    // ---------- Controle de etapas ----------
    private fun setState(i: Int, s: String) { synchronized(stages) { stages[i].state = s }; requestRender() }
    private fun begin(i: Int) { curApp = i; setState(i, "running"); persist() }
    private fun end(i: Int) { setState(i, "success"); persist() }
    private fun log(s: String, i: Int = curApp) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(stages) { stages[i].log += "$t $s\n" }
        requestRender()
    }
    private fun retitle(i: Int, t: String) { synchronized(stages) { stages[i].title = t }; requestRender() }

    private fun mapStep(s: JSONObject): String = when (s.optString("status")) {
        "completed" -> when (s.optString("conclusion")) { "success" -> "success"; "skipped" -> "skipped"; else -> "failure" }
        "in_progress" -> "running"
        else -> "pending"
    }

    /** Cria/atualiza as etapas do runner a partir dos steps reais do job. */
    private fun syncSteps(steps: JSONArray) {
        synchronized(stages) {
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                val idx = APP_STAGES.size + i
                while (stages.size <= idx) stages.add(Stage(""))
                stages[idx].title = s.optString("name"); stages[idx].state = mapStep(s)
            }
        }
        requestRender()
    }

    /** Separa o log do job por etapa usando o horário de início de cada step. */
    private fun applyLog(raw: String, steps: JSONArray) {
        val n = steps.length(); if (n == 0) return
        val starts = Array(n) { i ->
            val s = steps.getJSONObject(i).optString("started_at", "")
            if (s.isEmpty() || s == "null") "" else try { OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).format(TS_FMT) } catch (e: Exception) { "" }
        }
        val bufs = Array(n) { StringBuilder() }
        var cur = 0
        for (l in raw.lineSequence()) {
            val line = l.removePrefix("\uFEFF")
            var text = line
            if (line.length > 21 && line[4] == '-' && line[7] == '-' && line[10] == 'T') {
                val ts = line.substring(0, 19)
                for (j in n - 1 downTo cur + 1) if (starts[j].isNotEmpty() && starts[j] <= ts) { cur = j; break }
                val sp = line.indexOf(' ')
                text = ts.substring(11) + (if (sp > 0) line.substring(sp) else "")
            }
            bufs[cur].append(text).append('\n')
        }
        synchronized(stages) { for (i in 0 until n) stages[APP_STAGES.size + i].log = bufs[i].toString() }
        requestRender()
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
            // 1) Token + ZIP
            begin(0)
            val login = gh.call("GET", "/user").getString("login")
            log("Autenticado como $login")
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
            log("ZIP lido: ${files.size} arquivos" + if (prefix.isNotEmpty()) " (pasta raiz '$prefix' descartada)" else "")
            end(0)

            // 2) Repositório
            begin(1)
            repoName = "apk-build-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val repo = gh.call("POST", "/user/repos", JSONObject()
                .put("name", repoName).put("private", true).put("auto_init", true)
                .put("description", "Build temporário criado pelo GitBuilder"))
            fullName = repo.getString("full_name")
            val branch = repo.getString("default_branch")
            log("Repositório criado: $fullName (branch $branch)")
            end(1)

            // 3) Upload
            begin(2)
            val tree = JSONArray()
            var n = 0
            contentResolver.openInputStream(uri)!!.use { ins ->
                val z = ZipInputStream(ins)
                var en = z.nextEntry
                while (en != null) {
                    if (!en.isDirectory) {
                        val p = cleanPath(en.name, prefix)
                        if (p != null) {
                            val data = z.readBytes()
                            val sha = gh.call("POST", "/repos/$fullName/git/blobs", JSONObject()
                                .put("content", Base64.encodeToString(data, Base64.NO_WRAP))
                                .put("encoding", "base64")).getString("sha")
                            tree.put(entry(p, sha)); n++
                            retitle(2, "Enviar arquivos ($n/${files.size})")
                            if (files.size <= 100 || n % 25 == 0 || n == files.size) log("✓ $p")
                        }
                    }
                    en = z.nextEntry
                }
            }
            val wfSha = gh.call("POST", "/repos/$fullName/git/blobs", JSONObject()
                .put("content", WORKFLOW).put("encoding", "utf-8")).getString("sha")
            tree.put(entry(".github/workflows/build.yml", wfSha))
            log("Workflow de build adicionado (.github/workflows/build.yml)")
            end(2)

            // 4) Commit
            begin(3)
            var parent: String? = null
            for (i in 1..10) {
                try {
                    parent = gh.call("GET", "/repos/$fullName/git/ref/heads/$branch").getJSONObject("object").getString("sha")
                    break
                } catch (e: ApiException) { if (e.code != 404 && e.code != 409) throw e; Thread.sleep(1000) }
            }
            if (parent == null) throw Exception("Branch inicial do repositório não ficou disponível")
            val treeSha = gh.call("POST", "/repos/$fullName/git/trees", JSONObject().put("tree", tree)).getString("sha")
            log("Árvore criada: ${treeSha.take(7)}")
            val commit = gh.call("POST", "/repos/$fullName/git/commits", JSONObject()
                .put("message", "Projeto para build").put("tree", treeSha).put("parents", JSONArray().put(parent))).getString("sha")
            gh.call("PATCH", "/repos/$fullName/git/refs/heads/$branch", JSONObject().put("sha", commit).put("force", true))
            log("Commit enviado: ${commit.take(7)}")
            end(3)

            // 5) Aguardar Actions + etapas do runner
            begin(4)
            var run: JSONObject? = null
            for (i in 1..40) {
                val runs = gh.call("GET", "/repos/$fullName/actions/runs?head_sha=$commit").getJSONArray("workflow_runs")
                if (runs.length() > 0) { run = runs.getJSONObject(0); break }
                Thread.sleep(3000)
            }
            if (run == null) throw Exception("Nenhuma execução do Actions foi iniciada em 2 min. Verifique se o token tem permissão 'workflow'.")
            val runId = run.getLong("id")
            meta.put("runUrl", run.getString("html_url"))
            log("Execução iniciada: ${run.getString("html_url")}")

            var fails = 0; var conclusion = ""; var lastSt = ""; var jobSeen = false; var lastPersist = 0L
            while (true) {
                try {
                    val r = gh.call("GET", "/repos/$fullName/actions/runs/$runId")
                    val st = r.getString("status")
                    if (st != lastSt) { lastSt = st; if (!jobSeen) log("Status da execução: $st") }
                    val jobs = gh.call("GET", "/repos/$fullName/actions/runs/$runId/jobs").getJSONArray("jobs")
                    if (jobs.length() > 0) {
                        val job = jobs.getJSONObject(0)
                        val steps = job.optJSONArray("steps") ?: JSONArray()
                        if (steps.length() > 0 && job.optString("status") != "queued") {
                            if (!jobSeen) {
                                jobSeen = true
                                log("Runner iniciou o job '${job.optString("name")}'")
                                setState(4, "success")
                            }
                            syncSteps(steps)
                            val raw = gh.jobLog(fullName, job.getLong("id"))
                            if (raw != null) applyLog(ANSI.replace(raw, ""), steps)
                        }
                    }
                    fails = 0
                    if (st == "completed") { conclusion = r.optString("conclusion"); break }
                } catch (e: java.io.IOException) {
                    if (++fails > 5) throw e
                    log("Falha de rede (${e.message}), tentando novamente…")
                }
                if (System.currentTimeMillis() - lastPersist > 15000) { persist(); lastPersist = System.currentTimeMillis() }
                Thread.sleep(3000)
            }
            if (!jobSeen) {
                setState(4, "failure")
                log("A execução terminou sem iniciar o job (resultado: $conclusion)", 4)
            }

            // Etapa final: artefato
            val artIdx = synchronized(stages) { stages.add(Stage("Verificar artefato do APK", "running")); stages.size - 1 }
            requestRender()
            try {
                val arts = gh.call("GET", "/repos/$fullName/actions/runs/$runId/artifacts").getJSONArray("artifacts")
                for (i in 0 until arts.length()) {
                    val a = arts.getJSONObject(i)
                    if (!a.optBoolean("expired") && a.optLong("size_in_bytes") > 0) artifactId = a.getLong("id")
                }
                if (artifactId > 0) { log("Artefato 'apk' disponível para download", artIdx); setState(artIdx, "success") }
                else {
                    log(if (conclusion == "success") "A compilação terminou, mas nenhum artefato válido foi encontrado."
                        else "Sem artefato: a compilação não terminou com sucesso.", artIdx)
                    setState(artIdx, if (conclusion == "success") "failure" else "skipped")
                }
            } catch (e: Exception) { log("Não foi possível listar artefatos: ${e.message}", artIdx); setState(artIdx, "failure") }

            val result = when (conclusion) { "success" -> "success"; "cancelled" -> "cancelled"; else -> "failure" }
            val msg = when (result) {
                "success" -> "Compilação concluída com sucesso"
                "cancelled" -> "Compilação cancelada"
                else -> "A compilação falhou"
            }
            finish(result, msg)
        } catch (e: Exception) {
            synchronized(stages) {
                val idx = stages.indexOfFirst { it.state == "running" }.let { if (it >= 0) it else curApp }
                stages[idx].state = "failure"
                stages[idx].log += "ERRO: ${e.message}\n"
                for (i in 0 until APP_STAGES.size) if (stages[i].state == "pending") stages[i].state = "skipped"
            }
            finish("failure", "Falha: ${e.message}")
        }
    }

    private fun finish(result: String, msg: String) {
        finalStatus = msg
        meta.put("result", result).put("summary", msg).put("finishedAt", System.currentTimeMillis())
        persist()
        if (runningId == meta.getString("id")) runningId = null
        ui.post {
            actions.visibility = View.VISIBLE
            btnApk.visibility = if (artifactId > 0) View.VISIBLE else View.GONE
            render()
        }
    }

    // ---------- Download do APK ----------
    private fun downloadApk() {
        btnApk.isEnabled = false
        Thread {
            try {
                val tmp = File(cacheDir, "artifact.zip")
                val c = gh.openDownload("/repos/$fullName/actions/artifacts/$artifactId/zip")
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
