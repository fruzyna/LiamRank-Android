package net.fruzyna.liamrank.android

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import java.io.*
import java.net.BindException
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var webview: WebView
    private lateinit var loading: ProgressDialog
    private lateinit var server: POSTServer

    private val SAVE_CSV = 1

    private var lastDownload = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // allow remote debugging of webview content
        if (0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // setup webview
        webview = findViewById(R.id.liamrank_webview)
        webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return false
            }
        }
        webview.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
        webview.setDownloadListener { url, _, _, _, _ ->
            // parse download string
            val mimeType = url.substring(url.indexOf("data:")+5, url.indexOf(";"))
            val charset = url.substring(url.indexOf("charset=")+8, url.indexOf(","))
            lastDownload = URLDecoder.decode(url.substring(url.indexOf(",")+1), charset)

            // prompt for save location
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, "export.csv")
            }
            startActivityForResult(intent, SAVE_CSV)
        }

        // request read permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA), 0)
        }

        loading = ProgressDialog(this)
        loading.setTitle("Loading Application")
        loading.setMessage("Starting app...")
        loading.show()

        // download files
        CoroutineScope(Dispatchers.Main).launch { startServer() }
    }

    override fun onResume() {
        super.onResume()

        // restart server when reopened
        try {
            server.start()
        }
        catch (e: UninitializedPropertyAccessException) {
            println("Server not initialized!")
        }
        catch (e: BindException) {
            println("Server already initialized")
        }
    }

    override fun onPause() {
        super.onPause()

        // pause server before closing app
        try {
            server.stop()
        }
        catch (e: UninitializedPropertyAccessException) {
            println("Server not initialized!")
        }
    }

    override fun onBackPressed() {
        // go back in webview if back pressed
        if (webview.copyBackForwardList().currentIndex > 0) {
            webview.goBack()
        }
        else {
            super.onBackPressed()
        }
    }

    // check if connected to the internet
    private fun isConnected(host: String = "github.com"): Boolean {
        try {
            return !InetAddress.getByName(host).equals("")
        }
        catch (e: Exception) {
            return false
        }
    }

    // receive chosen file location for save
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        // save to file
        if (requestCode == SAVE_CSV && resultCode == Activity.RESULT_OK) {
            try {
                contentResolver.openFileDescriptor(resultData?.data!!, "w")?.use { it ->
                    FileOutputStream(it.fileDescriptor).use {
                        it.write(lastDownload.toByteArray())
                    }
                }
            } catch (e: FileNotFoundException) {
                Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            } catch (e: IOException) {
                Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
        else {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getRepo() = Dispatchers.Default {
        val appDir = getExternalFilesDir("")
        val prefs = getPreferences(Context.MODE_PRIVATE)
        var result = ""

        val installed = prefs.getString("RELEASE", "")
        val installedDir = File(appDir, "LiamRank-$installed")
        var latest = ""
        if (isConnected()) {
            // download releases page
            loading.setMessage("Querying latest app version...")
            val relURL = URL("https://github.com/mail929/LiamRank/releases/latest")
            val relStr = "/mail929/LiamRank/releases/tag/"
            val relStream = DataInputStream(relURL.openStream())
            try {
                var page = relStream.readUTF()
                while (page != null) {
                    if (page.contains(relStr)) {
                        latest = page.substring(page.indexOf(relStr) + relStr.length)
                        latest = latest.substring(0, latest.indexOf("\""))
                        break
                    }
                    page = relStream.readUTF()
                }
            } catch (e: EOFException) {
                // will still work if local repo exists
                println("No release string found!")
                result = "Error"
            }
        }

        if (appDir == null) {
            result = "Error"
        }
        // check if already downloaded, or there is an update
        else if (isConnected() && (!File(appDir, "LiamRank-$installed").exists() || (latest.isNotEmpty() && (installed != latest)))) {
            println("Fetching repo")
            loading.setMessage("Fetching release: ${latest}...")
            try {
                // download zip
                val zipURL = URL("https://github.com/mail929/LiamRank/archive/${latest}.zip")
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
        else if (installedDir.exists()) {
            println("Using local repo")
            result = installedDir.path
        }
        else {
            result = "Error"
        }

        // save release name
        if (latest.isNotEmpty()) {
            val edit = prefs.edit()
            edit.putString("RELEASE", latest)
            edit.apply()
        }

        return@Default result
    }

    private suspend fun startServer() {
        // attempt to get files
        var result = "Length"
        while (result == "Length") {
            result = getRepo()
        }

        when {
            // start server and open page
            result != "Error" -> {
                loading.setMessage("Starting server...")
                server = POSTServer(result, getString(R.string.API_KEY))
                val port = server.listeningPort
                println("Running at http://localhost:$port)")
                webview.loadUrl("http://localhost:$port/index.html")
            }
            // use hosted version if error
            isConnected("wildrank.fruzyna.net") -> {
                loading.setMessage("Loading page...")
                println("Failed to save repo, using hosted version")
                Toast.makeText(this, "Unable to get app, using remote server", Toast.LENGTH_SHORT).show()
                webview.loadUrl("https://liamrank.fruzyna.net")
            }
            // no cached app and no internet connection
            else -> Toast.makeText(this, "No internet connection, cannot load app", Toast.LENGTH_SHORT).show()
        }

        loading.dismiss()
    }
}