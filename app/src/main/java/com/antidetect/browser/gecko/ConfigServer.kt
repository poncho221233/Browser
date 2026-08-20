package com.antidetect.browser.gecko

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Tiny loopback HTTP server that serves the active fingerprint JSON.
 * Content-scripts fetch http://127.0.0.1:PORT/cfg at document_start so the
 * built-in extension (ensureBuiltIn) always gets the correct GPU/screen
 * without relying on XPI install or native messaging.
 */
object ConfigServer {
    private const val TAG = "ConfigServer"
    /** Fixed loopback port – also referenced in inject.js */
    const val PORT = 17351

    private val configJson = AtomicReference("{}")
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun updateConfig(json: String) {
        if (json.isNotBlank()) configJson.set(json)
    }

    fun start() {
        if (running) return
        synchronized(this) {
            if (running) return
            try {
                val ss = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                server = ss
                running = true
                thread(name = "GestorCfgHttp", isDaemon = true) {
                    Log.i(TAG, "Listening on 127.0.0.1:$PORT")
                    while (running) {
                        try {
                            val socket = ss.accept()
                            thread(isDaemon = true) { handle(socket) }
                        } catch (e: Exception) {
                            if (running) Log.w(TAG, "accept: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind :$PORT – ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val body = configJson.get()
            val out = OutputStreamWriter(socket.getOutputStream())
            if (requestLine.startsWith("GET /cfg") || requestLine.startsWith("GET /config")) {
                out.write("HTTP/1.1 200 OK\r\n")
                out.write("Content-Type: application/json; charset=utf-8\r\n")
                out.write("Access-Control-Allow-Origin: *\r\n")
                out.write("Cache-Control: no-store\r\n")
                out.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                out.write("Connection: close\r\n\r\n")
                out.write(body)
            } else if (requestLine.startsWith("OPTIONS")) {
                out.write("HTTP/1.1 204 No Content\r\n")
                out.write("Access-Control-Allow-Origin: *\r\n")
                out.write("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                out.write("Access-Control-Allow-Headers: *\r\n")
                out.write("Connection: close\r\n\r\n")
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
            }
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "handle: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
