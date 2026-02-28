package com.example.xiquercatch.domain

import com.example.xiquercatch.data.model.ScheduleCsvRow
import com.example.xiquercatch.data.model.ScheduleSnapshot

class ScheduleMergeService {

    fun mergeToRows(snapshots: List<ScheduleSnapshot>): List<ScheduleCsvRow> {
        if (snapshots.isEmpty()) return emptyList()

        val grouped = linkedMapOf<RowKey, MutableSet<Int>>()
        snapshots.forEach { snapshot ->
            snapshot.courses.forEach { course ->
                val key = RowKey(
                    courseName = course.courseName,
                    weekday = course.weekday,
                    startSection = course.startSection,
                    endSection = course.endSection,
                    teacher = course.teacher,
                    location = course.location
                )
                grouped.getOrPut(key) { linkedSetOf() }.add(snapshot.zc)
            }
        }

        return grouped.entries.map { (k, weeks) ->
            ScheduleCsvRow(
                courseName = k.courseName,
                weekday = k.weekday,
                startSection = k.startSection,
                endSection = k.endSection,
                teacher = k.teacher,
                location = k.location,
                weeksText = compressWeeks(weeks.toList().sorted())
            )
        }.sortedWith(
            compareBy<ScheduleCsvRow> { it.weekday }
                .thenBy { it.startSection }
                .thenBy { it.endSection }
                .thenBy { it.courseName }
        )
    }

    private fun compressWeeks(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        if (weeks.size == 1) return weeks.first().toString()
        val parts = mutableListOf<String>()
        var index = 0
        while (index < weeks.size) {
            if (index == weeks.lastIndex) {
                parts += weeks[index].toString()
                break
            }
            val delta = weeks[index + 1] - weeks[index]
            if (delta == 1) {
                var end = index + 1
                while (end < weeks.lastIndex && weeks[end + 1] - weeks[end] == 1) {
                    end++
                }
                parts += "${weeks[index]}-${weeks[end]}"
                index = end + 1
                continue
            }
            if (delta == 2) {
                var end = index + 1
                while (
                    end < weeks.lastIndex &&
                    weeks[end + 1] - weeks[end] == 2 &&
                    weeks[end + 1] % 2 == weeks[index] % 2
                ) {
                    end++
                }
                val suffix = if (weeks[index] % 2 == 1) "单" else "双"
                if (end == index) {
                    parts += weeks[index].toString()
                } else {
                    parts += "${weeks[index]}-${weeks[end]}$suffix"
                }
                index = end + 1
                continue
            }
            parts += weeks[index].toString()
            index++
        }
        return parts.joinToString("、")
    }

    private data class RowKey(
        val courseName: String,
        val weekday: Int,
        val startSection: Int,
        val endSection: Int,
        val teacher: String,
        val location: String
    )
}
