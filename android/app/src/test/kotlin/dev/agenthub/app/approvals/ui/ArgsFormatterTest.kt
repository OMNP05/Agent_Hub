package dev.agenthub.app.approvals.ui

import dev.agenthub.app.core.model.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgsFormatterTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun showsEveryFieldInFullInOriginalOrder() {
        val body = "Line one\nLine two " + "x".repeat(3000)
        val lines = ArgsFormatter.format(obj("""{"to":["a@x.com","b@x.com"],"subject":"Quarterly report","body":${Json.encodeToString(kotlinx.serialization.serializer<String>(), body)}}"""))
        assertEquals(listOf("To", "Subject", "Body"), lines.map { it.label })
        assertEquals("a@x.com, b@x.com", lines[0].value)
        assertEquals(body, lines[2].value)
    }

    @Test
    fun labelsAreReadable() {
        assertEquals("Send updates", ArgsFormatter.label("send_updates"))
        assertEquals("Start time", ArgsFormatter.label("start-time"))
    }

    @Test
    fun emptyAndNullValuesAreVisibleNotHidden() {
        val lines = ArgsFormatter.format(obj("""{"bio":"","company":null,"tags":[]}"""))
        assertTrue(lines.all { it.value == ArgsFormatter.EMPTY })
    }

    @Test
    fun nestedStructuresArePrintedInFull() {
        val lines = ArgsFormatter.format(obj("""{"attendees":[{"email":"a@x.com","optional":true}],"time":{"start":"09:00","end":"10:00"}}"""))
        assertTrue(lines[0].value.contains("a@x.com") && lines[0].value.contains("optional"))
        assertTrue(lines[1].value.contains("09:00") && lines[1].value.contains("10:00"))
    }

    @Test
    fun numbersAndBooleansKeepTheirText() {
        val lines = ArgsFormatter.format(obj("""{"count":3,"notify":false}"""))
        assertEquals("3", lines[0].value)
        assertEquals("false", lines[1].value)
    }

    @Test
    fun noArgumentsGiveNoLines() {
        assertEquals(emptyList<ArgLine>(), ArgsFormatter.format(JsonObject(emptyMap())))
    }

    @Test
    fun knownToolsUseCatalogLabelsAndRisk() {
        assertEquals("Send an email", ToolInfo.label("gmail.send_email"))
        assertEquals(RiskLevel.Dangerous, ToolInfo.risk("gmail.send_email"))
        assertEquals(RiskLevel.Write, ToolInfo.risk("github.update_profile"))
    }

    @Test
    fun unknownToolIsShownAsDangerousWithItsRawId() {
        assertEquals("evil.format_disk", ToolInfo.label("evil.format_disk"))
        assertEquals(RiskLevel.Dangerous, ToolInfo.risk("evil.format_disk"))
    }
}
