package com.lucas.kidscreentime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Versão publicada no GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/**
 * Atualização automática pelo GitHub Releases do repositório [BuildConfig.UPDATE_REPO].
 * Convenção da release (o publicar.ps1 explica): tag "v<versionCode>" (ex.: v4), título = versão
 * (ex.: 0.4), descrição = novidades, e o .apk anexado.
 *
 * Chamadas de rede são BLOQUEANTES: rode fora da thread principal (Dispatchers.IO).
 * O Android confere sozinho a assinatura na instalação: só aceita APK assinado com a mesma chave.
 */
object UpdateChecker {

    private const val TAG_PREFIX = "v"

    fun isConfigured(): Boolean = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Última versão publicada, ou null se não houver release válida. Lança exceção em erro de rede. */
    fun fetchLatest(): UpdateInfo? {
        val json = JSONObject(httpGet("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
        val code = json.optString("tag_name").removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isNullOrBlank() || !apkUrl.startsWith("https://")) return null
        return UpdateInfo(
            versionCode = code,
            versionName = json.optString("name").ifBlank { code.toString() },
            apkUrl = apkUrl,
            notes = json.optString("body").trim()
        )
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    /**
     * Baixa o APK para o cache e confere se é mesmo deste app e mais novo.
     * [onProgress] recebe 0–100 (ou -1 se o tamanho for desconhecido).
     */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "tempo-de-tela-${info.versionCode}.apk")

        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} ao baixar")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageArchiveInfo(file.path, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode?.toInt() else pkg?.versionCode
        if (pkg == null || pkg.packageName != context.packageName || code == null || code <= BuildConfig.VERSION_CODE) {
            file.delete()
            throw IOException("O arquivo baixado não é uma versão nova deste app")
        }
        return file
    }

    /** O Android 8+ exige que o usuário libere "instalar apps desconhecidos" para este app. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KidScreenTime/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("nenhuma versão publicada ainda (ou repositório privado)")
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
