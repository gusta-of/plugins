package pt.pem.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PayloadTest {
    private fun feed(vararg lines: String): List<PayloadEvent> {
        val d = PayloadDetector()
        return lines.withIndex().flatMap { (i, l) -> d.push(l, i.toLong()) }
    }

    @Test fun `JSON numa linha com prefixo do log`() {
        val r = feed("2026-10-30 INFO c.x.Client - Request: {\"id\": 7, \"nome\": \"a}b\", \"itens\": [1,2]} fim")
        assertEquals(1, r.size)
        assertEquals("json", r[0].kind)
        assertEquals("{\"id\": 7, \"nome\": \"a}b\", \"itens\": [1,2]}", r[0].text)
        assertEquals(0L, r[0].ref)
    }

    @Test fun `nao confunde INFO, chaveta numerada nem vazio com JSON`() {
        assertEquals(0, feed("[INFO] tudo bem", "Mensagem {0} e {1}", "vazio {} e []", "[ERROR] algo (x)").size)
    }

    @Test fun `JSON em varias linhas com botao na primeira`() {
        val r = feed("<Info> payload: {", "  \"a\": 1,", "  \"b\": { \"c\": \"}\" },", "  \"d\": [1, 2]", "} depois", "linha normal")
        assertEquals(1, r.size)
        assertEquals(0L, r[0].ref)
        assertTrue(r[0].text.contains("\"c\": \"}\""))
    }

    @Test fun `SOAP numa linha e em varias linhas`() {
        val one = feed("x <soapenv:Envelope xmlns:soapenv=\"a\"><soapenv:Body><ns:getX>1</ns:getX></soapenv:Body></soapenv:Envelope> y")
        assertEquals(1, one.size)
        assertTrue(one[0].text.startsWith("<soapenv:Envelope") && one[0].text.endsWith("</soapenv:Envelope>"))
        val multi = feed("Outbound Message", "<S:Envelope xmlns:S=\"a\">", " <S:Body>", "  <getXResponse>ok</getXResponse>", " </S:Body>", "</S:Envelope> fim", "proxima")
        assertEquals(1, multi.size)
        assertEquals(1L, multi[0].ref)
        assertTrue(multi[0].text.endsWith("</S:Envelope>"))
        assertEquals(1, feed("<Envelope><a/></Envelope>").size)
    }

    @Test fun `um bloco que nunca fecha nao apanha o resto`() {
        val lines = listOf("x {\"a\": [") + (0 until 700).map { "linha $it" }
        assertEquals(0, feed(*lines.toTypedArray()).size)
    }

    @Test fun `formatacao de JSON e XML`() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}", PayloadFormat.prettyJson("{\"a\":1,\"b\":[1,2]}"))
        assertEquals("{\n  \"x\": \"<a&b>\"\n}", PayloadFormat.prettyJson("{\"x\":\"<a&b>\"}"))
        val x = PayloadFormat.prettyXml("<s:Envelope xmlns:s=\"a\"><s:Body><getX><id>1</id><vazio></vazio><x/></getX></s:Body></s:Envelope>")
        assertEquals(listOf("<s:Envelope xmlns:s=\"a\">", "  <s:Body>", "    <getX>", "      <id>1</id>", "      <vazio></vazio>", "      <x/>", "    </getX>", "  </s:Body>", "</s:Envelope>").joinToString("\n"), x)
        assertEquals("nao e json", PayloadFormat.format("json", "nao e json"))
    }

    @Test fun `regioes colapsaveis de JSON e XML formatados incluem a linha de fecho`() {
        val j = PayloadFormat.prettyJson("{\"a\":1,\"b\":{\"c\":[1,2],\"d\":{}},\"e\":\"x\"}").split('\n')
        val e = PayloadFold.regions(j)
        assertEquals(10, e[0]); assertEquals(-1, e[1]); assertEquals(8, e[2]); assertEquals(6, e[3]); assertEquals(-1, e[7])
        assertEquals("],", PayloadFold.tail(j, 3, 6))
        assertEquals("},", PayloadFold.tail(j, 2, 8))
        val x = PayloadFormat.prettyXml("<s:Envelope><s:Body><a><b>1</b></a></s:Body></s:Envelope>").split('\n')
        val xe = PayloadFold.regions(x)
        assertEquals(6, xe[0]); assertEquals(5, xe[1]); assertEquals(4, xe[2]); assertEquals(-1, xe[3])
        assertEquals("</s:Envelope>", PayloadFold.tail(x, 0, 6))
        assertEquals("(1 linhas)", PayloadFold.tail(listOf("a", "  b"), 0, 1))
    }
}
