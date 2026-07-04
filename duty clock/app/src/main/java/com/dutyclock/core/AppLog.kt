package com.dutyclock.core

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val _logs = mutableListOf<String>()
    private const val MAX_LOGS = 2000
    private const val LOG_FILE = "dutyclock_log.txt"

    private val clients = mutableListOf<PrintWriter>()
    private var logServer: ServerSocket? = null
    @Volatile
    var logServerReady = false
        private set

    private var logFileWriter: PrintWriter? = null
    private var appContext: Context? = null

    val logs: List<String> get() = synchronized(_logs) { _logs.toList() }

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) {
                val reader = BufferedReader(InputStreamReader(file.inputStream(), "UTF-8"))
                val oldLines = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    oldLines.add(line!!)
                }
                reader.close()
                synchronized(_logs) {
                    val start = maxOf(0, oldLines.size - MAX_LOGS)
                    _logs.addAll(oldLines.subList(start, oldLines.size))
                }
            }
            logFileWriter = PrintWriter(FileWriter(file, true), true)
        } catch (e: Exception) {
            android.util.Log.e("AppLog", "init log file failed", e)
        }
    }

    fun startLogServer(port: Int = 19877) {
        if (logServer != null) return
        Thread {
            try {
                val server = ServerSocket(port)
                server.reuseAddress = true
                logServer = server
                logServerReady = true
                android.util.Log.i("AppLog", "Log TCP Server listening on $port")
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), "UTF-8"), true)
                        synchronized(clients) {
                            clients.add(writer)
                        }
                        synchronized(_logs) {
                            for (line in _logs) {
                                writer.println(line)
                            }
                        }
                        android.util.Log.i("AppLog", "Log client connected: ${client.remoteSocketAddress}")
                        Thread {
                            try {
                                val buf = ByteArray(1024)
                                while (client.getInputStream().read(buf) >= 0) {}
                            } catch (_: Exception) {}
                            finally {
                                synchronized(clients) { clients.remove(writer) }
                                try { client.close() } catch (_: Exception) {}
                            }
                        }.start()
                    } catch (e: Exception) {
                        if (!server.isClosed) android.util.Log.e("AppLog", "Log accept error", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppLog", "Log server error", e)
            } finally {
                logServerReady = false
            }
        }.start()
    }

    fun stopLogServer() {
        try { logServer?.close() } catch (_: Exception) {}
        logServer = null
        logServerReady = false
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
    }

    fun i(tag: String, msg: String) {
        add("I", tag, msg)
        android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        add("W", tag, msg)
        android.util.Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        add("E", tag, msg + if (tr != null) "\n${tr.stackTraceToString().take(500)}" else "")
        android.util.Log.e(tag, msg, tr)
    }

    fun d(tag: String, msg: String) {
        add("D", tag, msg)
        android.util.Log.d(tag, msg)
    }

    private fun add(level: String, tag: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time $level/$tag: $msg"
        synchronized(_logs) {
            _logs.add(line)
            while (_logs.size > MAX_LOGS) _logs.removeAt(0)
        }
        try {
            synchronized(this) {
                logFileWriter?.println(line)
            }
        } catch (_: Exception) {}
        broadcast(line)
    }

    private fun broadcast(line: String) {
        synchronized(clients) {
            val dead = mutableListOf<PrintWriter>()
            for (writer in clients) {
                try {
                    writer.println(line)
                    if (writer.checkError()) dead.add(writer)
                } catch (_: Exception) {
                    dead.add(writer)
                }
            }
            if (dead.isNotEmpty()) {
                clients.removeAll(dead)
            }
        }
    }

    fun clear() {
        synchronized(_logs) { _logs.clear() }
        synchronized(this) {
            try {
                logFileWriter?.close()
                val file = appContext?.filesDir?.let { File(it, LOG_FILE) }
                file?.delete()
                logFileWriter = file?.let { PrintWriter(FileWriter(it, true), true) }
            } catch (e: Exception) {
                android.util.Log.e("AppLog", "clear failed", e)
            }
        }
    }
}
