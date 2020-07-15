package net.fruzyna.liamrank.android

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class HTTPServer(directory: String, apiKey: String) : NanoHTTPD(8080) {
    private var directory = ""
    private var apiKey = ""

    init {
        this.directory = directory
        this.apiKey = apiKey
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response? {
        var request = session.uri
        if (request == "/") {
            request = "/index.html"
        }
        println("Request: $request")

        // determine mime type
        val file = File(directory, request)
        var mime = when (file.extension) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "json" -> "text/plain"
            "ico" -> "image/x-icon"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "File not allowed")
        }

        // return API key (not contained in repo)
        return if (request == "/scripts/keys.js") {
            println("Returning API key")
            newFixedLengthResponse(Response.Status.OK, mime, "API_KEY=\"$apiKey\"")
        }
        // return file if it exists
        else if (file.exists()) {
            newChunkedResponse(Response.Status.OK, mime, FileInputStream(file))
        }
        // return 404
        else {
            println("$request does not exist")
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }
}