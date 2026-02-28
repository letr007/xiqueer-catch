package com.example.xiquercatch

import com.example.xiquercatch.data.model.CourseOccurrence
import com.example.xiquercatch.data.model.ScheduleSnapshot
import com.example.xiquercatch.domain.ScheduleMergeService
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleMergeServiceTest {

    @Test
    fun mergeToRows_compressesOddWeeks() {
        val service = ScheduleMergeService()
        val snapshots = listOf(1, 3, 5, 7).map { week ->
            ScheduleSnapshot(
                id = week.toLong(),
                capturedAt = 0L,
                host = "jw.xxgc.edu.cn",
                path = "/jwweb//wap/mycourseschedule.aspx",
                xn = "2025",
                xq = "1",
                zc = week,
                maxzc = 18,
                qssj = "",
                jssj = "",
                requestParamDigest = "",
                courses = listOf(
                    CourseOccurrence(
                        courseName = "Web前端开发技术",
                        weekday = 2,
                        startSection = 7,
                        endSection = 8,
                        teacher = "田赛超",
                        location = "教学楼2-409",
                        weekNo = week
                    )
                )
            )
        }

        val rows = service.mergeToRows(snapshots)
        assertEquals(1, rows.size)
        assertEquals("1-7单", rows.first().weeksText)
    }
}
