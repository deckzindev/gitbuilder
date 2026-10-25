package app.gitbuilder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private var zipUri: Uri? = null
    private var zipName = ""
    private var items: List<JSONObject> = emptyList()
    private lateinit var tokenInput: EditText
    private lateinit var tokenState: TextView
    private lateinit var zipState: TextView
    private lateinit var btnBuild: Button
    private lateinit var tabBuild: Button
    private lateinit var tabHist: Button
    private lateinit var buildPane: View
    private lateinit var histPane: View
    private lateinit var histList: ListView
    private lateinit var histEmpty: TextView
    private var loginInfo = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }

        // Abas
        val tabs = LinearLayout(this)
        tabBuild = Button(this).apply { text = "Compilar"; setOnClickListener { showTab(false) } }
        tabHist = Button(this).apply { text = "Histórico"; setOnClickListener { showTab(true) } }
        tabs.addView(tabBuild, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabHist, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        // Aba Compilar
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        fun add(v: View) = form.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        add(TextView(this).apply { text = "GitBuilder"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        add(TextView(this).apply {
            text = "Compila o ZIP do seu projeto Android com GitHub Actions.\n" +
                "Token clássico com escopos repo e workflow (ou fine-grained com Administration, Contents, Workflows e Actions)."
        })
        tokenInput = EditText(this).apply {
            hint = "Colar token do GitHub"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        add(tokenInput)
        val row = LinearLayout(this)
        row.addView(Button(this).apply { text = "Validar e salvar"; setOnClickListener { saveToken() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Remover token"
            setOnClickListener { TokenStore.clear(this@MainActivity); loginInfo = ""; refresh() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        add(row)
        tokenState = TextView(this); add(tokenState)
        add(Button(this).apply { text = "Selecionar projeto (.zip)"; setOnClickListener { pickZip() } })
        zipState = TextView(this); add(zipState)
        btnBuild = Button(this).apply {
            text = "Compilar"
            setOnClickListener {
                val t = TokenStore.get(this@MainActivity); val u = zipUri
                if (t == null || u == null) return@setOnClickListener
                if (Sessions.runningId() != null) { toast("Já existe uma compilação em andamento. Veja no Histórico."); return@setOnClickListener }
                val session = BuildSession.create(applicationContext, t, zipName.ifEmpty { "projeto.zip" }, u)
                Sessions.current = session
                // A compilação roda no serviço em primeiro plano: continua com o app fechado.
                startForegroundService(Intent(this@MainActivity, BuildService::class.java)
                    .setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                startActivity(Intent(this@MainActivity, BuildActivity::class.java).putExtra("history_id", session.id))
            }
        }
        add(btnBuild)
        buildPane = ScrollView(this).apply { addView(form) }

        // Aba Histórico
        histList = ListView(this)
        histEmpty = TextView(this).apply { text = "Nenhuma compilação ainda."; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        histList.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, BuildActivity::class.java).putExtra("history_id", items[pos].getString("id")))
        }
        histList.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setTitle("Excluir do histórico?")
                .setMessage("O registro e os logs salvos neste aparelho serão apagados.")
                .setPositiveButton("Excluir") { _, _ -> History.delete(this, items[pos].getString("id")); refreshHistory() }
                .setNegativeButton("Cancelar", null).show()
            true
        }
        val hist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hist.addView(histEmpty)
        hist.addView(histList, LinearLayout.LayoutParams(-1, 0, 1f))
        histPane = hist

        val frame = FrameLayout(this)
        frame.addView(buildPane); frame.addView(histPane)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        showTab(false)
        refresh()
        checkLogin()
    }

    override fun onResume() { super.onResume(); refresh(); if (histPane.visibility == View.VISIBLE) refreshHistory() }

    private fun showTab(hist: Boolean) {
        buildPane.visibility = if (hist) View.GONE else View.VISIBLE
        histPane.visibility = if (hist) View.VISIBLE else View.GONE
        tabBuild.isEnabled = hist; tabHist.isEnabled = !hist
        if (hist) refreshHistory()
    }

    private fun refresh() {
        val t = TokenStore.get(this)
        tokenState.text = if (t != null) "Token salvo: ${TokenStore.mask(t)}$loginInfo" else "Nenhum token salvo."
        zipState.text = if (zipUri != null) "Arquivo: $zipName" else "Nenhum arquivo selecionado."
        btnBuild.isEnabled = t != null && zipUri != null
    }

    /** Confere o token salvo SEM apagá-lo se a rede falhar. */
    private fun checkLogin() {
        val t = TokenStore.get(this) ?: return
        Thread {
            val info = try { " · conectado como ${GitHub(t).call("GET", "/user").getString("login")}" }
            catch (e: ApiException) { if (e.code == 401) " · o GitHub recusou este token, cole um novo" else " · ${e.message}" }
            catch (e: Exception) { " · sem conexão para verificar agora" }
            runOnUiThread { loginInfo = info; refresh() }
        }.start()
    }

    private fun saveToken() {
        val t = tokenInput.text.toString().trim()
        if (t.isEmpty()) { toast("Cole o token primeiro"); return }
        tokenState.text = "Validando token…"
        Thread {
            try {
                GitHub(t).call("GET", "/user")
                if (!TokenStore.save(this, t)) throw Exception("não foi possível gravar com segurança neste aparelho")
                runOnUiThread { tokenInput.text.clear(); toast("Token salvo. Não precisa informar de novo."); refresh(); checkLogin() }
            } catch (e: Exception) {
                runOnUiThread { refresh(); toast("Token não salvo: ${e.message}") }
            }
        }.start()
    }

    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        startActivityForResult(i, 1)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data?.data != null) {
            zipUri = data.data; zipName = nameOf(zipUri!!)
            try { contentResolver.takePersistableUriPermission(zipUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            refresh()
        }
    }

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: u.lastPathSegment ?: "arquivo"
    } catch (e: Exception) { "arquivo" }

    // ---------- Histórico ----------
    private fun label(m: JSONObject): String {
        val tag = when (m.optString("result")) {
            "running" -> if (m.getString("id") == Sessions.runningId()) "⏳ Em andamento" else "⚠ Interrompido"
            "success" -> "✔ Sucesso"
            "cancelled" -> "Cancelado"
            "interrupted" -> "⚠ Acompanhamento interrompido"
            else -> "✘ Falhou"
        }
        val start = m.optLong("startedAt"); val fin = m.optLong("finishedAt")
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(start))
        val dur = if (fin > 0) { val s = (fin - start) / 1000; " · ${s / 60}m%02ds".format(s % 60) } else ""
        return "$tag — $date\n${m.optString("zipName")} · ${m.optInt("stages")} etapas$dur"
    }

    private fun refreshHistory() {
        items = History.list(this)
        histList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { label(it) })
        histEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
