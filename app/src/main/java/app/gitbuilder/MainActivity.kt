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
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private var zipUri: Uri? = null
    private var zipName = ""
    private var items: List<JSONObject> = emptyList()
    private lateinit var tokenInput: EditText
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
    private var tokenInvalid = false
    private val tokenHint = "Escopos necessários: repo e workflow"
    private var entryMsg = tokenHint

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(getColor(R.color.app_bg)) }

        // Abas
        val tabs = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }
        tabBuild = Button(this).apply { text = "Compilar"; setOnClickListener { showTab(false) } }
        tabHist = Button(this).apply { text = "Histórico"; setOnClickListener { showTab(true) } }
        tabs.addView(tabBuild, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        tabs.addView(tabHist, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        // Aba Compilar
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(48)) }
        fun add(parent: LinearLayout, v: View, top: Int = 10) = parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        val gray = muted

        // Token: só aparece enquanto não houver token válido salvo
        tokenEntry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(if (night) 0xFF1E1F22.toInt() else Color.WHITE)
                setStroke(dp(1), if (night) 0xFF3C4043.toInt() else 0xFFE0E0E0.toInt()); cornerRadius = dp(16).toFloat()
            }
            tokenInput = EditText(this@MainActivity).apply {
                hint = "Colar token do GitHub"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(tokenInput, LinearLayout.LayoutParams(-1, -2))
            entryState = TextView(this@MainActivity).apply { textSize = 12f; setTextColor(gray) }
            addView(entryState, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(Button(this@MainActivity).apply { text = "Validar e salvar"; setOnClickListener { saveToken() } }.also { styleBtn(it, surface, txt) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        add(form, tokenEntry, 0)

        // Projeto
        add(form, Button(this).apply { text = "Selecionar projeto (.zip)"; setOnClickListener { pickZip() } }.also { styleBtn(it, surface, txt) }, 16)
        zipState = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(gray) }
        add(form, zipState, 8)
        btnBuild = Button(this).apply {
            text = "Compilar"
            setOnClickListener {
                val t = TokenStore.get(this@MainActivity); val u = zipUri
                if (t == null || u == null) return@setOnClickListener
                if (Sessions.runningId() != null) { toast("Já existe uma compilação em andamento. Veja no Histórico."); return@setOnClickListener }
                val session = BuildSession.create(applicationContext, t, zipName.ifEmpty { "projeto.zip" }, u)
                Sessions.current = session
                // A compilação roda no serviço em primeiro plano: continua com o app fechado.
                startForegroundService(Intent(this@MainActivity, BuildService::class.java))
                startActivity(Intent(this@MainActivity, BuildActivity::class.java).putExtra("history_id", session.id))
            }
        }
        add(form, btnBuild, 8)
        buildPane = ScrollView(this).apply { isFillViewport = true; addView(form) }

        // Aba Histórico
        histList = ListView(this)
        histEmpty = TextView(this).apply { text = "Nenhuma compilação ainda."; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(20), dp(48), dp(20), dp(20)) }
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
        styleBtn(tabBuild, if (!hist) surface else Color.TRANSPARENT, if (!hist) txt else muted)
        styleBtn(tabHist, if (hist) surface else Color.TRANSPARENT, if (hist) txt else muted)
        tabBuild.minimumHeight = 0; tabHist.minimumHeight = 0
        if (hist) refreshHistory()
    }

    private fun refresh() {
        val t = TokenStore.get(this)
        tokenEntry.visibility = if (t == null || tokenInvalid) View.VISIBLE else View.GONE
        entryState.text = entryMsg
        zipState.text = if (zipUri != null) zipName else "Nenhum arquivo selecionado"
        val pkg = if (zipUri != null) getDrawable(R.drawable.ic_package)?.mutate()?.apply { setTint(muted); setBounds(0, 0, dp(20), dp(20)) } else null
        zipState.setCompoundDrawablesRelative(pkg, null, null, null)
        zipState.compoundDrawablePadding = dp(8)
        val ready = t != null && !tokenInvalid && zipUri != null
        btnBuild.isEnabled = ready
        styleBtn(btnBuild, if (ready) blue else surface, if (ready) Color.WHITE else muted)
    }

    /** Confere o token salvo SEM apagá-lo se a rede falhar. Só reabre o campo se o GitHub recusar o token. */
    private fun checkLogin() {
        val t = TokenStore.get(this) ?: return
        Thread {
            val invalid = try { GitHub(t).call("GET", "/user"); false }
            catch (e: ApiException) { e.code == 401 }
            catch (e: Exception) { false }
            runOnUiThread {
                tokenInvalid = invalid
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
                runOnUiThread { tokenInput.text.clear(); tokenInvalid = false; entryMsg = tokenHint; toast("Token salvo. Não precisa informar de novo."); refresh(); checkLogin() }
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
    /** Ícone, cor e rótulo simples de cada compilação. */
    private fun status(m: JSONObject): Triple<Int, Int, String> {
        return when (m.optString("result")) {
            "running" -> if (m.getString("id") == Sessions.runningId()) Triple(R.drawable.ic_hourglass, blue, "Compilando")
                         else Triple(R.drawable.ic_warning, amber, "Interrompido")
            "success" -> Triple(R.drawable.ic_check_circle, green, "Pronto")
            "cancelled" -> Triple(R.drawable.ic_cancel_circle, muted, "Cancelado")
            "interrupted" -> Triple(R.drawable.ic_warning, amber, "Interrompido")
            else -> Triple(R.drawable.ic_error_circle, red, "Não compilou")
        }
    }

    private fun refreshHistory() {
        items = History.list(this)
        histList.adapter = object : ArrayAdapter<JSONObject>(this, 0, items) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val m = getItem(pos)!!
                val (icon, color, tag) = status(m)
                val start = m.optLong("startedAt"); val fin = m.optLong("finishedAt")
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(start))
                val dur = if (fin > 0) { val s = (fin - start) / 1000; " · ${s / 60}m%02ds".format(s % 60) } else ""
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14)) }
                row.addView(ImageView(this@MainActivity).apply { setImageResource(icon); setColorFilter(color) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply { text = "$tag — $date"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(txt) })
                col.addView(TextView(this@MainActivity).apply { text = m.optString("zipName") + dur; textSize = 13f; setTextColor(muted) })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
                return row
            }
        }
        histEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
