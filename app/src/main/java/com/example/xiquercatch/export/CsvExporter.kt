package com.example.xiquercatch.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.xiquercatch.data.model.ScheduleCsvRow
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExporter {

    fun export(context: Context, rows: List<ScheduleCsvRow>): ExportResult {
        if (rows.isEmpty()) {
            return ExportResult(false, "", "没有可导出的课表数据")
        }

        val csv = buildCsv(rows)
        val fileName = "xiqueer_schedule_${timestamp()}.csv"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, fileName, csv)
            } else {
                saveLegacy(fileName, csv)
            }
        }.fold(
            onSuccess = { ExportResult(true, it, null) },
            onFailure = { ExportResult(false, "", it.message ?: "导出失败") }
        )
    }

    private fun buildCsv(rows: List<ScheduleCsvRow>): String {
        val lines = mutableListOf("课程名称,星期,开始节数,结束节数,老师,地点,周数")
        rows.forEach { row ->
            lines += listOf(
                row.courseName,
                row.weekday.toString(),
                row.startSection.toString(),
                row.endSection.toString(),
                row.teacher,
                row.location,
                row.weeksText
            ).joinToString(",") { it.csvEscape() }
        }
        return lines.joinToString("\n")
    }

    private fun saveWithMediaStore(context: Context, fileName: String, csv: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        resolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write('\uFEFF'.code)
                writer.write(csv)
            }
        } ?: error("无法写入文件")
        return "Download/$fileName"
    }

    private fun saveLegacy(fileName: String, csv: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write('\uFEFF'.code)
                writer.write(csv)
            }
        }
        return file.absolutePath
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    }

    private fun String.csvEscape(): String {
        if (contains(',') || contains('"') || contains('\n')) {
            return "\"${replace("\"", "\"\"")}\""
        }
        return this
    }
}

data class ExportResult(
    val success: Boolean,
    val path: String,
    val message: String?
)
