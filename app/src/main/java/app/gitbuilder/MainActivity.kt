package app.gitbuilder

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private var zipUri: Uri? = null
    private lateinit var tokenInput: EditText
    private lateinit var tokenState: TextView
    private lateinit var zipState: TextView
    private lateinit var btnBuild: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            fitsSystemWindows = true
        }
        fun add(v: View) = root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        add(TextView(this).apply { text = "GitBuilder"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        add(TextView(this).apply {
            text = "Compila o ZIP do seu projeto Android com GitHub Actions.\n" +
                "Token clássico com escopos repo e workflow (ou fine-grained com Administration, Contents, Workflows e Actions)."
        })
        tokenInput = EditText(this).apply {
            hint = "Token do GitHub"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        add(tokenInput)
        val row = LinearLayout(this)
        val btnSave = Button(this).apply { text = "Validar e salvar"; setOnClickListener { saveToken() } }
        val btnClear = Button(this).apply {
            text = "Remover token"
            setOnClickListener { TokenStore.clear(this@MainActivity); refresh() }
        }
        row.addView(btnSave, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f))
        add(row)
        tokenState = TextView(this); add(tokenState)

        add(Button(this).apply { text = "Selecionar projeto (.zip)"; setOnClickListener { pickZip() } })
        zipState = TextView(this); add(zipState)

        btnBuild = Button(this).apply {
            text = "Compilar"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BuildActivity::class.java)
                    .setData(zipUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        add(btnBuild)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        val has = TokenStore.get(this) != null
        tokenState.text = if (has) "Token salvo com segurança neste aparelho." else "Nenhum token salvo."
        zipState.text = zipUri?.let { "Arquivo: ${nameOf(it)}" } ?: "Nenhum arquivo selecionado."
        btnBuild.isEnabled = has && zipUri != null
    }

    private fun saveToken() {
        val t = tokenInput.text.toString().trim()
        if (t.isEmpty()) { toast("Cole o token primeiro"); return }
        tokenState.text = "Validando token…"
        Thread {
            try {
                val login = GitHub(t).call("GET", "/user").getString("login")
                TokenStore.save(this, t)
                runOnUiThread { tokenInput.text.clear(); refresh(); toast("Token salvo (usuário $login)") }
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
        if (req == 1 && res == RESULT_OK && data?.data != null) { zipUri = data.data; refresh() }
    }

    private fun nameOf(u: Uri): String = try {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: u.lastPathSegment ?: "arquivo"
    } catch (e: Exception) { "arquivo" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
