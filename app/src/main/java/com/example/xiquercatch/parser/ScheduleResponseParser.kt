package com.example.xiquercatch.parser

import com.example.xiquercatch.data.model.CourseOccurrence
import kotlin.math.max
import kotlin.math.min

class ScheduleResponseParser {

    fun parseResponseBody(rawBody: String): ParsedSchedulePayload? {
        val start = rawBody.indexOf("{jcflag:'")
        if (start < 0) return null
        val payload = rawBody.substring(start)

        val xn = findField(payload, "xn") ?: return null
        val xq = findField(payload, "xq") ?: return null
        val zc = findField(payload, "zc")?.toIntOrNull() ?: return null
        val maxzc = findField(payload, "maxzc")?.toIntOrNull() ?: 0
        val qssj = findField(payload, "qssj").orEmpty()
        val jssj = findField(payload, "jssj").orEmpty()

        val courses = mutableListOf<CourseOccurrence>()
        WEEK_BLOCK_PATTERN.findAll(payload).forEach { weekMatch ->
            val weekday = weekMatch.groupValues[1].toIntOrNull() ?: return@forEach
            val block = weekMatch.groupValues[2]
            COURSE_OBJECT_PATTERN.findAll(block).forEach { courseMatch ->
                val fields = COURSE_FIELD_PATTERN.findAll(courseMatch.groupValues[1])
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val courseName = fields["kcmc"].orEmpty().trim()
                val teacher = fields["rkjs"].orEmpty().trim()
                val location = fields["skdd"].orEmpty().trim()
                val period = parsePeriod(fields["jcxx"].orEmpty()) ?: return@forEach
                if (courseName.isBlank()) return@forEach
                courses += CourseOccurrence(
                    courseName = courseName,
                    weekday = weekday,
                    startSection = period.first,
                    endSection = period.second,
                    teacher = teacher,
                    location = location,
                    weekNo = zc
                )
            }
        }

        return ParsedSchedulePayload(
            xn = xn,
            xq = xq,
            zc = zc,
            maxzc = maxzc,
            qssj = qssj,
            jssj = jssj,
            courses = courses
        )
    }

    fun parseRequestParamDigest(requestBody: String): String {
        val param = parseFormField(requestBody, "param").orEmpty()
        val param2 = parseFormField(requestBody, "param2").orEmpty()
        if (param.isBlank() && param2.isBlank()) return ""
        val digest = sha256("${param}|${param2}")
        return "${digest.take(10)}:${param.length}/${param2.length}"
    }

    private fun parseFormField(body: String, key: String): String? {
        val token = "$key="
        val start = body.indexOf(token)
        if (start < 0) return null
        val end = body.indexOf('&', startIndex = start).let { if (it < 0) body.length else it }
        return body.substring(start + token.length, end)
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun findField(payload: String, fieldName: String): String? {
        val m = Regex("(?:^|,)$fieldName:'([^']*)'").find(payload) ?: return null
        return m.groupValues[1]
    }

    private fun parsePeriod(jcxx: String): Pair<Int, Int>? {
        val nums = Regex("\\d+").findAll(jcxx).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.isEmpty()) return null
        return min(nums.first(), nums.last()) to max(nums.first(), nums.last())
    }

    companion object {
        private val WEEK_BLOCK_PATTERN = Regex(
            "week([1-7]):\\[(.*?)](?=,week[1-7]:|,sjhjinfo:|\\}\\s*$)",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val COURSE_OBJECT_PATTERN = Regex("\\{(.*?)\\}", setOf(RegexOption.DOT_MATCHES_ALL))
        private val COURSE_FIELD_PATTERN = Regex("(\\w+):'([^']*)'")
    }
}
