package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {

    @Test
    fun testDetectDelimiter() {
        assertEquals(',', CsvViewerActivity.detectDelimiter("name,age,city\nJohn,30,NYC", "data.csv"))
        assertEquals('\t', CsvViewerActivity.detectDelimiter("name\tage\tcity\nJohn\t30\tNYC", "data.tsv"))
        assertEquals(';', CsvViewerActivity.detectDelimiter("name;age;city\nJohn;30;NYC", "data.csv"))
        assertEquals('|', CsvViewerActivity.detectDelimiter("name|age|city\nJohn|30|NYC", "data.csv"))
    }

    @Test
    fun testParseSimpleCsv() {
        val sample = "col1,col2,col3\nval1,val2,val3\nval4,val5,val6"
        val rows = CsvViewerActivity.parseCsv(sample, ',')
        assertEquals(3, rows.size)
        assertEquals(listOf("col1", "col2", "col3"), rows[0])
        assertEquals(listOf("val1", "val2", "val3"), rows[1])
        assertEquals(listOf("val4", "val5", "val6"), rows[2])
    }

    @Test
    fun testParseQuotedAndEscapedCsv() {
        val sample = """
            "Product","Description","Price"
            "Widget, Large","A ""super"" widget","$19.99"
            "Simple",Clean,5
        """.trimIndent()
        val rows = CsvViewerActivity.parseCsv(sample, ',')
        assertEquals(3, rows.size)
        assertEquals(listOf("Product", "Description", "Price"), rows[0])
        assertEquals(listOf("Widget, Large", "A \"super\" widget", "$19.99"), rows[1])
        assertEquals(listOf("Simple", "Clean", "5"), rows[2])
    }

    @Test
    fun testFormatCsvRoundTrip() {
        val original = listOf(
            listOf("ID", "Name", "Notes"),
            listOf("1", "Alice", "Developer, Android"),
            listOf("2", "Bob", "Quotes \"test\"")
        )
        val formatted = CsvViewerActivity.formatCsv(original, ',')
        val parsed = CsvViewerActivity.parseCsv(formatted, ',')
        assertEquals(original, parsed)
    }
}
