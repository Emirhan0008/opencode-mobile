package dev.opencode.mobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `question` aracı parçalarının sesli akış için doğru çözümlenmesi:
 * yanıtlanmış/tamamlanmış sorular atlanmalı, bekleyen soru seçenekleriyle
 * birlikte okunabilir hâle gelmeli.
 */
class QuestionParserTest {

    private fun part(status: String, answers: String, multiple: Boolean = false): JsonObject {
        val payload = """
            {"type":"tool","name":"question","state":{
              "status":"$status",
              "input":{"questions":[
                {"header":"Yaklaşım","question":"Hangi yaklaşımı seçelim?","multiple":$multiple,
                 "options":[
                   {"label":"Hızlı çözüm","description":"kısa vadeli"},
                   {"label":"Kalıcı çözüm","description":"uzun vadeli"}
                 ]}
              ]},
              "metadata":{"answers":$answers}
            }}
        """.trimIndent()
        return Json.parseToJsonElement(payload) as JsonObject
    }

    private fun message(vararg parts: JsonObject): JsonObject {
        val json = parts.joinToString(",") { it.toString() }
        return Json.parseToJsonElement("""{"type":"assistant","content":[$json]}""") as JsonObject
    }

    @Test
    fun pendingQuestionIsParsed() {
        val qs = QuestionParser.pending(listOf(message(part("running", "[]"))))
        assertEquals(1, qs.size)
        assertEquals("Yaklaşım", qs[0].header)
        assertEquals("Hangi yaklaşımı seçelim?", qs[0].question)
        assertEquals(2, qs[0].options.size)
        assertEquals("Kalıcı çözüm", qs[0].options[1].label)
    }

    @Test
    fun answeredOrCompletedIsSkipped() {
        assertTrue(QuestionParser.pending(listOf(message(part("running", """[["Hızlı çözüm"]]""")))).isEmpty())
        assertTrue(QuestionParser.pending(listOf(message(part("completed", "[]")))).isEmpty())
    }

    @Test
    fun skipsAnsweredAndFallsBackToOlderPending() {
        val newerAnswered = message(part("running", """[["Hızlı çözüm"]]"""))
        val olderPending = message(part("running", "[]"))
        val qs = QuestionParser.pending(listOf(newerAnswered, olderPending))
        assertEquals("Hangi yaklaşımı seçelim?", qs.single().question)
    }

    @Test
    fun unrelatedToolsAreIgnored() {
        val other = Json.parseToJsonElement(
            """{"type":"tool","name":"bash","state":{"status":"running"}}""",
        ) as JsonObject
        assertTrue(QuestionParser.pending(listOf(message(other))).isEmpty())
    }
}
