package com.example.xiquercatch.parser

import com.example.xiquercatch.data.model.CourseOccurrence

data class ParsedSchedulePayload(
    val xn: String,
    val xq: String,
    val zc: Int,
    val maxzc: Int,
    val qssj: String,
    val jssj: String,
    val courses: List<CourseOccurrence>
)
