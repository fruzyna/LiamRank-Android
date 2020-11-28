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
import java.net.*
import java.util.zip.ZipInputStream


class MainActivity : AppCompatActivity() {
    private lateinit var webview: WebView
    private lateinit var loading: ProgressDialog
    private lateinit var server: POSTServer

    private val SAVE_CSV = 1

    private var lastDownload = ""

    private lateinit var appDir: File
    private val RELEASE_KEY = "LAST_USED_RELEASE"

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
            val mimeType = url.substring(url.indexOf("data:") + 5, url.indexOf(";"))
            val charset = url.substring(url.indexOf("charset=") + 8, url.indexOf(","))
            lastDownload = URLDecoder.decode(url.substring(url.indexOf(",") + 1), charset)

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
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                ), 0
            )
        }

        loading = ProgressDialog(this)
        loading.setTitle("Loading Application")
        loading.setMessage("Starting app...")
        loading.show()

        // download files
        appDir = getExternalFilesDir("")!!
        if (appDir != null) {
            CoroutineScope(Dispatchers.Main).launch { init() }
        }
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

    // find the last recorded release
    private fun getLastRelease(): String {
        return getPreferences(Context.MODE_PRIVATE).getString("RELEASE", "master").toString()
    }

    // determined if a given release is already downloaded
    private fun isReleaseCached(release: String): Boolean {
        val file = File(appDir, "LiamRank-$release")
        return file.exists()
    }

    // on startup
    private suspend fun init() = Dispatchers.Default {
        // TODO: user input on whether to use latest release
        val useLatest = true
        if (useLatest) {
            runOnUiThread {
                loading.setMessage("Determining latest release...")
            }
            val latestURL = URL("https://github.com/mail929/LiamRank/releases/latest")
            fetchLatest(latestURL)
        }
        else {
            // TODO: user input for release as option
            useRelease("")
        }
    }

    // determines the latest available release and runs server
    private fun fetchLatest(relURL: URL) {
        val relStr = "/mail929/LiamRank/releases/tag/"
        val relStream = DataInputStream(relURL.openStream())
        try {
            var page = relStream.readUTF()
            while (page != null) {
                if (page.contains(relStr)) {
                    var latest = page.substring(page.indexOf(relStr) + relStr.length)
                    latest = latest.substring(0, latest.indexOf("\""))
                    useRelease(latest)
                    return
                }
                page = relStream.readUTF()
            }
        } catch (e: EOFException) {
            // will still work if local repo exists
            println("No release string found!")
        }
        useRelease("")
    }

    // attempt to use a given or the last used release
    private fun useRelease(release: String) {
        var release = release

        // if no release was given
        if (release.isBlank()) {
            // use the last used release
            release = getLastRelease()
        }

        // if the desired release does not exist
        if (!isReleaseCached(release)) {
            // download it
            fetchRelease(release)
        }
        else {
            // otherwise, start app with release
            startRelease(release)
        }
    }

    // fetch a given release from GitHub
    private fun fetchRelease(release: String) {
        runOnUiThread {
            loading.setMessage("Downloading release $release...")
        }

        val zipURL = URL("https://github.com/mail929/LiamRank/archive/${release}.zip")
        val dlStream = DataInputStream(zipURL.openStream())
        val length = zipURL.openConnection().contentLength
        if (length > 0) {
            val dlBuffer = ByteArray(length)
            dlStream.readFully(dlBuffer)
            dlStream.close()
            extractArchive(dlBuffer, release)
        }
        else {
            findReleaseLocal()
        }
    }

    // attempts to extract the archive and start the app, uses local on failure
    private fun extractArchive(buffer: ByteArray, release: String) {
        runOnUiThread {
            loading.setMessage("Extracting release $release...")
        }

        val zipStream = ZipInputStream(ByteArrayInputStream(buffer))
        var entry = zipStream.nextEntry
        while (entry != null) {
            val filePath = File(appDir, entry.name)

            // create new directories
            if (entry.isDirectory) {
                filePath.mkdirs()
            }
            // write files
            else {
                val fileStream = FileOutputStream(filePath)
                val zipBuffer = ByteArray(1024)
                var byteCount: Int
                while (zipStream.read(zipBuffer).also { byteCount = it } != -1) {
                    fileStream.write(zipBuffer, 0, byteCount)
                }
                fileStream.close()
            }

            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()
        startRelease(release)
    }

    // find a release to use locally
    private fun findReleaseLocal() {
        // choose the last release
        var release = getLastRelease()

        // if the last release does not exist
        if (!isReleaseCached(release)) {
            print("[LOCAL] Searching for any existing releases")
            // try and find the newest release
            release = searchForRelease()
        }

        // if a release is found
        if (release.isBlank()) {
            // start
            startRelease(release)
        }
        else {
            // otherwise TODO: fail
            print("[LOCAL] Failed to find any existing releases")
        }
    }

    // attempts to find the latest local release
    private fun searchForRelease(): String {
        runOnUiThread {
            loading.setMessage("Searching for available releases...")
        }

        var newestName = ""
        var newestDate: Long
        newestDate = 0

        // lays out contents of documents folder
        val files = appDir.listFiles()
        if (files != null) {
            for (file in files) {
                val name = file.nameWithoutExtension
                if (name.startsWith("LiamRank-")) {
                    val date = file.lastModified()
                    print("[LOCAL] Found $name from $date")
                    // determines if the directory is newer
                    if (date > newestDate) {
                        newestName = name
                        newestDate = date
                    }
                }
            }

            if (newestName.contains("-")) {
                return newestName.split("-")[1]
            }
        }
        else {
            print("[LOCAL] Unable to get contents of documents directory")
        }
        return ""
    }

    // start the webserver and webview with a given release
    private fun startRelease(release: String) {
        print("[SERVER] Starting server for release $release")
        runOnUiThread {
            loading.setMessage("Starting server...")
        }

        // save the name of the release
        getPreferences(Context.MODE_PRIVATE).edit().also {
            it.putString(RELEASE_KEY, release)
            it.apply()
        }

        // construct the server for the release
        server = POSTServer(
            File(appDir, "LiamRank-$release").path,
            getString(R.string.API_KEY)
        )

        // load the main page
        runOnUiThread {
            webview.loadUrl("http://localhost:${server.listeningPort}/index.html")
            loading.dismiss()
        }
    }
}