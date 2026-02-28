package com.example.xiquercatch.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_ENTRIES = 1200
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = mutableEntries

    @Synchronized
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I/$tag $message")
    }

    @Synchronized
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W/$tag $message${throwable?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E/$tag $message${throwable?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
    }

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }

    private fun append(content: String) {
        val timestamp = formatter.format(Date())
        val line = "$timestamp  $content"
        val current = mutableEntries.value.toMutableList()
        current.add(line)
        if (current.size > MAX_ENTRIES) {
            val drop = current.size - MAX_ENTRIES
            repeat(drop) { current.removeAt(0) }
        }
        mutableEntries.value = current
    }
}
