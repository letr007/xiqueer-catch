package com.example.xiquercatch

import com.example.xiquercatch.parser.ScheduleResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScheduleResponseParserTest {

    @Test
    fun parseResponseBody_extractsWeekAndCourse() {
        val raw = """
            HTTP/1.1 200 OK

            {jcflag:'0',maxzc:'18',zc:'02',xn:'2025',xq:'1',qssj:'2026-03-09',jssj:'2026-03-15',week1:[{kcmc:'Java程序设计',rkjs:'王玉芬',skdd:'教学楼2-412(高)',jcxx:'1-2'}],sjhjinfo:[]}
        """.trimIndent()
        val parser = ScheduleResponseParser()
        val parsed = parser.parseResponseBody(raw)

        assertNotNull(parsed)
        assertEquals(2, parsed!!.zc)
        assertEquals(18, parsed.maxzc)
        assertEquals(1, parsed.courses.size)
        assertEquals("Java程序设计", parsed.courses.first().courseName)
        assertEquals(1, parsed.courses.first().weekday)
        assertEquals(1, parsed.courses.first().startSection)
        assertEquals(2, parsed.courses.first().endSection)
    }
}
