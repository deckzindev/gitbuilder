package app.gitbuilder

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, msg: String) : Exception(msg)

class GitHub(private val token: String) {

    private fun open(method: String, url: String, auth: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = false
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return c
    }

    private fun errorMessage(c: HttpURLConnection): String {
        val body = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        val msg = try { JSONObject(body).optString("message", body) } catch (e: Exception) { body }
        val hint = when (c.responseCode) {
            401 -> " (token inválido ou expirado)"
            403 -> " (token sem permissão ou limite de requisições)"
            else -> ""
        }
        return "HTTP ${c.responseCode}: $msg$hint"
    }

    fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = open(method, "https://api.github.com$path", true)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code >= 400) throw ApiException(code, errorMessage(c))
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** GET que responde com redirect (logs, artefatos). O token NÃO é enviado ao destino. Retorna null em 404. */
    fun openDownload(path: String): HttpURLConnection? {
        val c = open("GET", "https://api.github.com$path", true)
        val code = c.responseCode
        if (code == 404) return null
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw ApiException(code, "Redirecionamento sem destino")
            val d = open("GET", loc, false)
            if (d.responseCode >= 400) throw ApiException(d.responseCode, "Falha no download (HTTP ${d.responseCode})")
            return d
        }
        if (code >= 400) throw ApiException(code, errorMessage(c))
        return c
    }

    /** Logs do job. Retorna null enquanto ainda não existem (job na fila / log não gerado): 404 é esperado. */
    fun jobLog(repo: String, jobId: Long): String? = try {
        openDownload("/repos/$repo/actions/jobs/$jobId/logs")?.inputStream?.bufferedReader()?.use { it.readText() }
    } catch (e: ApiException) {
        if (e.code == 404 || e.code == 410) null else throw e
    }
}
