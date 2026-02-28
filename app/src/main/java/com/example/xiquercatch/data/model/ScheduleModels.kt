package com.example.xiquercatch.data.model

data class CourseOccurrence(
    val courseName: String,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val teacher: String,
    val location: String,
    val weekNo: Int
)

data class ScheduleSnapshot(
    val id: Long,
    val capturedAt: Long,
    val host: String,
    val path: String,
    val xn: String,
    val xq: String,
    val zc: Int,
    val maxzc: Int,
    val qssj: String,
    val jssj: String,
    val requestParamDigest: String,
    val courses: List<CourseOccurrence>
)

data class SemesterGroup(
    val key: String,
    val title: String,
    val snapshots: List<ScheduleSnapshot>
)

data class ScheduleCsvRow(
    val courseName: String,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val teacher: String,
    val location: String,
    val weeksText: String
)
