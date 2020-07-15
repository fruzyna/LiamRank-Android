package net.fruzyna.liamrank.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import java.io.*
import java.net.URL
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    var webview: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // allow remote debugging of webview content
        if (0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // setup webview
        webview = findViewById(R.id.liamrank_webview)
        webview!!.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webview?.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return false
            }
        }

        // request read permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 0)
            }
        }

        // download files
        CoroutineScope(Dispatchers.Main).launch { startServer() }
    }

    private suspend fun getRepo() = Dispatchers.Default {
        val appDir = getExternalFilesDir("")
        val zipURL = URL("https://github.com/mail929/LiamRank/archive/master.zip")
        var result = ""

        if (appDir == null) {
            result = "Error"
        }
        // check if already downloades
        else if (!File(appDir, "LiamRank-master").exists()) {
            println()
            println("Fetching repo")
            try {
                // download zip
                val dlStream = DataInputStream(zipURL.openStream())
                val length = zipURL.openConnection().contentLength
                if (length < 0) {
                    throw Exception()
                }
                val dlBuffer = ByteArray(length)
                dlStream.readFully(dlBuffer)
                dlStream.close()

                // extract zip
                println("Extracting repo")
                val zipStream = ZipInputStream(ByteArrayInputStream(dlBuffer))
                var entry = zipStream.nextEntry
                while (entry != null) {
                    val filePath = File(appDir, entry.name)
                    if (result == "") {
                        result = filePath.path
                    }

                    // create new directories
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    }
                    // write files
                    else {
                        val fileStream = FileOutputStream(filePath)
                        val zipBuffer = ByteArray(1024)
                        var byteCount = 0
                        while (zipStream.read(zipBuffer).also { byteCount = it } != -1) {
                            fileStream.write(zipBuffer, 0, byteCount)
                        }
                        fileStream.close()
                    }

                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                zipStream.close()
            }
            catch (e: FileNotFoundException) {
                println("File not found!")
                result = "Error"
            }
            catch (e: IOException) {
                println("IO exception!")
                result = "Error"
            }
            catch (e: Exception) {
                println("0 length!")
                result = "Length"
            }
        }
        else {
            println("Using local repo")
            result = File(appDir, "LiamRank-master").path
        }

        return@Default result
    }

    private suspend fun startServer() {
        // attempt to get files
        var result = "Length"
        while (result == "Length") {
            result = getRepo()
        }

        // use hosted version if error
        if (result == "Error") {
            println("Failed to save repo, using hosted version")
            webview?.loadUrl("https://liamrank.fruzyna.net")
        }
        // start server and open page
        else {
            val port = HTTPServer(result!!, getString(R.string.API_KEY)).listeningPort
            println("Running at http://localhost:$port)")
            webview?.loadUrl("http://localhost:$port/index.html")
        }
    }
}