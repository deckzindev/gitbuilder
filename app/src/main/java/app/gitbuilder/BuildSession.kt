package app.gitbuilder

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

class Stage(var title: String, var state: String = "pending", var log: String = "", var ext: String = "")

object Sessions {
    @Volatile var current: BuildSession? = null
    fun runningId(): String? = current?.takeIf { it.running }?.id
}

/** Toda a lógica de compilação, independente de tela. Quem a executa é o BuildService. */
class BuildSession private constructor(private val ctx: Context, val gh: GitHub, val meta: JSONObject) {

    val id: String = meta.getString("id")
    private val stages = ArrayList<Stage>()            // protegido por synchronized(stages)
    @Volatile var finalStatus: String? = null
    @Volatile var running = false
    @Volatile var fullName = ""
    @Volatile var repoName = ""
    @Volatile var artifactId = -1L
    private var curApp = 0
    private var zipUri: Uri? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    companion object {
        val STAGES = listOf(
            "Enviar projeto ao GitHub", "Preparar ambiente (runner, Java, Gradle)",
            "Compilar APK", "Gerar e verificar APK"
        )
        private const val BUILD_STEP = "Build debug APK"
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

        fun create(ctx: Context, token: String, zipName: String, uri: Uri): BuildSession {
            val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val meta = JSONObject().put("id", id).put("zipName", zipName)
                .put("startedAt", System.currentTimeMillis()).put("finishedAt", 0L)
                .put("result", "running").put("summary", "Em andamento")
            val s = BuildSession(ctx.applicationContext, GitHub(token), meta)
            STAGES.forEach { s.stages.add(Stage(it)) }
            s.zipUri = uri
            s.running = true
            s.persist()
            return s
        }

        fun fromHistory(ctx: Context, token: String, id: String): BuildSession? {
            val m = History.meta(ctx, id) ?: return null
            val s = BuildSession(ctx.applicationContext, GitHub(token), m)
            s.fullName = m.optString("repo"); s.repoName = s.fullName.substringAfter('/')
            s.artifactId = m.optLong("artifactId", -1)
            val arr = History.stages(ctx, id)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                s.stages.add(Stage(o.optString("title"), o.optString("state"), o.optString("log")))
            }
            // Sem sessão ativa para este registro: se ainda constava "em andamento", o app foi encerrado no meio.
            s.finalStatus = if (m.optString("result") == "running") "Interrompido: o app foi encerrado antes do fim da compilação"
                            else m.optString("summary")
            return s
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) try { l() } catch (e: Exception) { } }

    // ---------- Leitura de estado ----------
    fun snapshot(): List<Triple<String, String, String>> = synchronized(stages) { stages.map { Triple(it.title, it.state, it.log + it.ext) } }

    private fun isDone(s: String) = s == "success" || s == "failure" || s == "skipped"
    fun progress(): Pair<Int, Int> { val s = snapshot(); return Pair(s.count { isDone(it.second) }, s.size) }
    fun currentLine(): String {
        val snap = snapshot()
        var cur = snap.indexOfFirst { it.second == "running" }
        if (cur < 0) cur = snap.indexOfLast { it.second != "pending" }
        return if (cur >= 0) "Etapa ${cur + 1}/${snap.size} — ${snap[cur].first}" else "Iniciando…"
    }
    fun fullText(): String = "GitBuilder — ${meta.optString("zipName")}\nRepositório: $fullName\n\n" +
        synchronized(stages) {
            stages.withIndex().joinToString("\n") { (i, s) -> "===== [${i + 1}/${stages.size}] ${s.title} (${s.state}) =====\n${s.log}${s.ext}" }
        }

    /** Prepara este objeto para continuar o acompanhamento de uma execução já iniciada no GitHub. */
    fun prepareResume() { meta.put("result", "running"); finalStatus = null; running = true }

    // ---------- Histórico ----------
    private fun persist() {
        try {
            val arr = JSONArray()
            synchronized(stages) { stages.forEach { arr.put(JSONObject().put("title", it.title).put("state", it.state).put("log", it.log + it.ext)) } }
            meta.put("stages", arr.length()).put("repo", fullName).put("artifactId", artifactId)
            History.saveStages(ctx, id, arr)
            History.saveMeta(ctx, meta)
        } catch (e: Exception) { /* histórico é best-effort */ }
    }

    // ---------- Controle de etapas ----------
    private fun setState(i: Int, s: String) { synchronized(stages) { stages[i].state = s }; changed() }
    private fun begin(i: Int) { curApp = i; setState(i, "running"); persist() }
    private fun end(i: Int) { setState(i, "success"); persist() }
    private fun log(s: String, i: Int = curApp) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(stages) { stages[i].log += "$t $s\n" }
        changed()
    }
    private fun retitle(i: Int, t: String) { synchronized(stages) { stages[i].title = t }; changed() }

    private fun mapStep(s: JSONObject): String = when (s.optString("status")) {
        "completed" -> when (s.optString("conclusion")) { "success" -> "success"; "skipped" -> "skipped"; else -> "failure" }
        "in_progress" -> "running"
        else -> "pending"
    }

    private fun buildIndex(steps: JSONArray): Int {
        for (i in 0 until steps.length()) if (steps.getJSONObject(i).optString("name") == BUILD_STEP) return i
        return -1
    }
    // Passos antes do build → etapa 2 (índice 1); o build → etapa 3; o que vem depois → etapa 4.
    private fun groupOf(i: Int, b: Int) = if (b < 0 || i < b) 1 else if (i == b) 2 else 3

    private fun aggregate(l: List<String>): String = when {
        l.isEmpty() -> "pending"
        l.any { it == "failure" } -> "failure"
        l.all { it == "skipped" } -> "skipped"
        l.all { it == "success" || it == "skipped" } -> "success"
        l.any { it != "pending" } -> "running"
        else -> "pending"
    }

    private fun syncSteps(steps: JSONArray) {
        val b = buildIndex(steps)
        val groups = Array(3) { ArrayList<String>() }
        for (i in 0 until steps.length()) groups[groupOf(i, b) - 1].add(mapStep(steps.getJSONObject(i)))
        synchronized(stages) {
            for (g in 1..3) {
                val st = aggregate(groups[g - 1])
                if (!(g == 1 && st == "pending")) stages[g].state = st   // etapa 2 já está "rodando" (aguardando runner)
            }
            // passos "post" rodam mesmo após falha: não contam como APK gerado
            if (stages[1].state == "failure" || stages[2].state == "failure") stages[3].state = "skipped"
        }
        changed()
    }

    private fun applyLog(raw: String, steps: JSONArray) {
        val n = steps.length(); if (n == 0) return
        val b = buildIndex(steps)
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
        val groupText = Array(3) { StringBuilder() }
        for (i in 0 until n) {
            val t = bufs[i].toString()
            if (t.isNotEmpty()) groupText[groupOf(i, b) - 1]
                .append("── ").append(steps.getJSONObject(i).optString("name")).append(" ──\n").append(t)
        }
        synchronized(stages) { for (g in 1..3) stages[g].ext = groupText[g - 1].toString() }
        changed()
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

    fun runResume() {
        // Registro de versão anterior (mais etapas): reorganiza para o formato de 4 etapas
        synchronized(stages) {
            if (stages.size != STAGES.size) {
                stages.clear(); STAGES.forEach { stages.add(Stage(it)) }
                stages[0].state = "success"; stages[0].log = "(registro de versão anterior: etapas reorganizadas)\n"
                stages[1].state = "running"
            }
        }
        val runId = meta.optLong("runId", -1)
        if (runId <= 0) { finish("interrupted", "Sem ID de execução para retomar"); return }
        trackSafely(runId)
    }

    fun runNew() {
        val local = File(File(ctx.filesDir, "zips").apply { mkdirs() }, "$id.zip")
        try {
            // Etapa 1: token, ZIP, repositório, upload, commit e criação da execução
            begin(0)
            val login = gh.call("GET", "/user").getString("login")
            log("Autenticado como $login")
            ctx.contentResolver.openInputStream(zipUri!!)!!.use { i -> local.outputStream().use { o -> i.copyTo(o) } }
            log("ZIP copiado (${local.length() / 1024} KB)")
            val names = ArrayList<String>()
            local.inputStream().use { ins ->
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

            repoName = "apk-build-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val repo = gh.call("POST", "/user/repos", JSONObject()
                .put("name", repoName).put("private", true).put("auto_init", true)
                .put("description", "Build temporário criado pelo GitBuilder"))
            fullName = repo.getString("full_name")
            val branch = repo.getString("default_branch")
            log("Repositório criado: $fullName (branch $branch)")

            val tree = JSONArray()
            var n = 0
            local.inputStream().use { ins ->
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
                            retitle(0, "${STAGES[0]} ($n/${files.size} arquivos)")
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

            var run: JSONObject? = null
            for (i in 1..40) {
                val runs = gh.call("GET", "/repos/$fullName/actions/runs?head_sha=$commit").getJSONArray("workflow_runs")
                if (runs.length() > 0) { run = runs.getJSONObject(0); break }
                Thread.sleep(3000)
            }
            if (run == null) throw Exception("Nenhuma execução do Actions foi iniciada em 2 min. Verifique se o token tem permissão 'workflow'.")
            val runId = run.getLong("id")
            meta.put("runUrl", run.getString("html_url")).put("runId", runId)
            log("Execução iniciada: ${run.getString("html_url")}")
            retitle(0, STAGES[0]); end(0)

            // Etapas 2 a 4: acompanhamento no GitHub
            begin(1)
            log("Aguardando o runner do GitHub Actions…")
            trackSafely(runId)
        } catch (e: Exception) {
            synchronized(stages) {
                val idx = stages.indexOfFirst { it.state == "running" }.let { if (it >= 0) it else curApp }
                stages[idx].state = "failure"
                stages[idx].log += "ERRO: ${e.message}\n"
                for (st in stages) if (st.state == "pending") st.state = "skipped"
            }
            finish("failure", "Falha: ${e.message}")
        } finally {
            local.delete()
        }
    }

    private fun trackSafely(runId: Long) {
        try { track(runId) } catch (e: Exception) {
            log("ERRO ao acompanhar: ${e.message}", 4)
            finish("interrupted", "Acompanhamento interrompido: ${e.message}. A compilação pode ter continuado no GitHub.")
        }
    }

    /** Falta de rede NÃO é falha da compilação: só pausa o acompanhamento. */
    private fun track(runId: Long) {
        var conclusion = ""; var lastSt = ""; var lastPersist = 0L; var offlineSince = 0L
        var jobSeen = meta.optBoolean("jobSeen", false)
        while (true) {
            try {
                val r = gh.call("GET", "/repos/$fullName/actions/runs/$runId")
                val st = r.getString("status")
                if (offlineSince != 0L) { offlineSince = 0L; log("Conexão restabelecida", 1) }
                if (st != lastSt) { lastSt = st; if (!jobSeen) log("Status da execução: $st", 1) }
                val jobs = gh.call("GET", "/repos/$fullName/actions/runs/$runId/jobs").getJSONArray("jobs")
                if (jobs.length() > 0) {
                    val job = jobs.getJSONObject(0)
                    val steps = job.optJSONArray("steps") ?: JSONArray()
                    if (steps.length() > 0 && job.optString("status") != "queued") {
                        if (!jobSeen) {
                            jobSeen = true; meta.put("jobSeen", true)
                            log("Runner iniciou o job '${job.optString("name")}'", 1)
                            log("O status de cada etapa é atualizado ao vivo. Se o GitHub ainda não liberou o texto do log do job, ele aparece nas etapas quando o job terminar.", 1)
                        }
                        syncSteps(steps)
                        val raw = gh.jobLog(fullName, job.getLong("id"))
                        if (raw != null) applyLog(ANSI.replace(raw, ""), steps)
                    }
                }
                if (st == "completed") { conclusion = r.optString("conclusion"); break }
            } catch (e: Exception) {
                val transient = e is java.io.IOException || (e is ApiException && e.code >= 500)
                if (!transient) throw e
                val now = System.currentTimeMillis()
                if (offlineSince == 0L) {
                    offlineSince = now
                    log("Sem conexão com o GitHub (${e.message}). Continuo tentando por até 20 minutos; a compilação segue normalmente no GitHub.", 1)
                } else if (now - offlineSince > 20 * 60_000L) {
                    finish("interrupted", "Sem conexão há mais de 20 min. A compilação segue no GitHub: toque em Retomar acompanhamento.")
                    return
                }
                Thread.sleep(5000); continue
            }
            if (System.currentTimeMillis() - lastPersist > 15000) { persist(); lastPersist = System.currentTimeMillis() }
            Thread.sleep(3000)
        }
        if (!jobSeen) {
            setState(1, "failure")
            log("A execução terminou sem iniciar o job (resultado: $conclusion)", 1)
        }
        synchronized(stages) {
            for (st in stages) {
                if (st.state == "pending") st.state = "skipped"
                else if (st.state == "running") st.state = if (conclusion == "success") "success" else "failure"
            }
        }
        changed()

        // Etapa 4: verificação do artefato
        try {
            val arts = gh.call("GET", "/repos/$fullName/actions/runs/$runId/artifacts").getJSONArray("artifacts")
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                if (!a.optBoolean("expired") && a.optLong("size_in_bytes") > 0) artifactId = a.getLong("id")
            }
            if (artifactId > 0) { log("Artefato 'apk' disponível para download", 3); setState(3, "success") }
            else {
                log(if (conclusion == "success") "A compilação terminou, mas nenhum artefato válido foi encontrado."
                    else "Sem artefato: a compilação não terminou com sucesso.", 3)
                setState(3, if (conclusion == "success") "failure" else "skipped")
            }
        } catch (e: Exception) { log("Não foi possível listar artefatos: ${e.message}", 3); setState(3, "failure") }

        val result = when (conclusion) { "success" -> "success"; "cancelled" -> "cancelled"; else -> "failure" }
        finish(result, when (result) {
            "success" -> "Compilação concluída com sucesso"
            "cancelled" -> "Compilação cancelada"
            else -> "A compilação falhou"
        })
    }

    private fun finish(result: String, msg: String) {
        meta.put("result", result).put("summary", msg).put("finishedAt", System.currentTimeMillis())
        persist()
        finalStatus = msg
        running = false
        changed()
    }
}
