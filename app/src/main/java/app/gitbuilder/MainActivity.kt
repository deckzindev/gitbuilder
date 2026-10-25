package app.gitbuilder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
    private lateinit var tokenConnected: View
    private lateinit var tokenEntry: View
    private lateinit var entryState: TextView
    private lateinit var zipState: TextView
    private lateinit var btnBuild: Button
    private lateinit var tabBuild: Button
    private lateinit var tabHist: Button
    private lateinit var buildPane: View
    private lateinit var histPane: View
    private lateinit var histList: ListView
    private lateinit var histEmpty: TextView
    private var loginInfo = ""
    private var tokenInvalid = false
    private val tokenHint = "Escopos necessários: repo e workflow"
    private var entryMsg = tokenHint

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
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        fun add(parent: LinearLayout, v: View, top: Int = 10) = parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        fun sectionLabel(t: String) = TextView(this).apply { text = t; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF70757A.toInt()) }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), 0xFFE0E0E0.toInt()); cornerRadius = dp(16).toFloat() }
        }

        add(form, TextView(this).apply { text = "GitBuilder"; textSize = 26f; setTypeface(null, Typeface.BOLD) }, 0)

        // Conta GitHub: com token salvo mostra só o status; sem token mostra o campo
        add(form, sectionLabel("CONTA GITHUB"), 20)
        val tokenCard = card()
        tokenConnected = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tokenState = TextView(this@MainActivity).apply { textSize = 15f }
            addView(tokenState, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Remover"; textSize = 12f; isAllCaps = false
                setOnClickListener { TokenStore.clear(this@MainActivity); loginInfo = ""; tokenInvalid = false; entryMsg = tokenHint; refresh() }
            }, LinearLayout.LayoutParams(-2, dp(40)))
        }
        tokenEntry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tokenInput = EditText(this@MainActivity).apply {
                hint = "Colar token do GitHub"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(tokenInput, LinearLayout.LayoutParams(-1, -2))
            entryState = TextView(this@MainActivity).apply { textSize = 12f; setTextColor(0xFF70757A.toInt()) }
            addView(entryState, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(Button(this@MainActivity).apply { text = "Validar e salvar"; setOnClickListener { saveToken() } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        tokenCard.addView(tokenConnected, LinearLayout.LayoutParams(-1, -2))
        tokenCard.addView(tokenEntry, LinearLayout.LayoutParams(-1, -2))
        add(form, tokenCard, 6)

        // Projeto
        add(form, sectionLabel("PROJETO"), 20)
        val projCard = card()
        add(projCard, Button(this).apply { text = "Selecionar projeto (.zip)"; setOnClickListener { pickZip() } }, 0)
        zipState = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        add(projCard, zipState, 8)
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
        add(projCard, btnBuild, 8)
        add(form, projCard, 6)
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
        val showEntry = t == null || tokenInvalid
        tokenEntry.visibility = if (showEntry) View.VISIBLE else View.GONE
        tokenConnected.visibility = if (showEntry) View.GONE else View.VISIBLE
        entryState.text = entryMsg
        tokenState.text = if (loginInfo.isNotEmpty()) loginInfo else if (t != null) "Verificando conta…" else ""
        zipState.text = if (zipUri != null) "📦 $zipName" else "Nenhum arquivo selecionado"
        btnBuild.isEnabled = t != null && !tokenInvalid && zipUri != null
    }

    /** Confere o token salvo SEM apagá-lo se a rede falhar. */
    private fun checkLogin() {
        val t = TokenStore.get(this) ?: return
        Thread {
            var invalid = false
            val info = try { "✔ Conectado como ${GitHub(t).call("GET", "/user").getString("login")}" }
            catch (e: ApiException) { if (e.code == 401) { invalid = true; "" } else "⚠ ${e.message}" }
            catch (e: Exception) { "⚠ Sem conexão para verificar agora" }
            runOnUiThread {
                tokenInvalid = invalid; loginInfo = info
                if (invalid) entryMsg = "O GitHub recusou o token salvo. Cole um novo."
                refresh()
            }
        }.start()
    }

    private fun saveToken() {
        val t = tokenInput.text.toString().trim()
        if (t.isEmpty()) { toast("Cole o token primeiro"); return }
        entryMsg = "Validando token…"; refresh()
        Thread {
            try {
                GitHub(t).call("GET", "/user")
                if (!TokenStore.save(this, t)) throw Exception("não foi possível gravar com segurança neste aparelho")
                runOnUiThread { tokenInput.text.clear(); tokenInvalid = false; loginInfo = ""; entryMsg = tokenHint; toast("Token salvo. Não precisa informar de novo."); refresh(); checkLogin() }
            } catch (e: Exception) {
                runOnUiThread { entryMsg = "Token não salvo: ${e.message}"; refresh() }
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
