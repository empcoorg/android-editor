package org.empco.buyerverifier

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Renders the plain-text report produced by [ReportBuilder] onto A4 pages.
 * Severity tags are colorised; section headers are bold. No extra libraries.
 */
object PdfReport {

    private const val PAGE_W = 595 // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val TEXT_SIZE = 9f
    private const val LINE_HEIGHT = 12f
    private const val INDENT_CONT = 14f

    private val COLOR_BODY = Color.rgb(40, 40, 40)
    private val COLOR_FAIL = Color.rgb(190, 30, 30)
    private val COLOR_WARN = Color.rgb(210, 110, 0)
    private val COLOR_OK = Color.rgb(0, 120, 70)
    private val COLOR_INFO = Color.rgb(70, 70, 70)
    private val COLOR_HEAD = Color.rgb(13, 71, 161)

    fun write(context: Context, text: String, file: File): File {
        @Suppress("UNUSED_EXPRESSION") context // API symmetry with future options
        file.parentFile?.mkdirs()

        val doc = PdfDocument()
        val bodyPaint = paint(TEXT_SIZE, Typeface.MONOSPACE)
        val headPaint = paint(TEXT_SIZE + 1.5f, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
        val titlePaint = paint(TEXT_SIZE + 5f, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
        val maxWidth = PAGE_W - 2 * MARGIN

        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN + 10f

        var lineNumber = 0
        for (raw in text.lines()) {
            val isTitle = lineNumber == 0
            val isHead = raw.startsWith("==")
            val paint = when {
                isTitle -> titlePaint
                isHead -> headPaint
                else -> bodyPaint
            }
            paint.color = when {
                isTitle -> Color.BLACK
                isHead -> COLOR_HEAD
                raw.trimStart().startsWith("[FAIL]") -> COLOR_FAIL
                raw.trimStart().startsWith("[WARN]") -> COLOR_WARN
                raw.trimStart().startsWith("[ OK ]") -> COLOR_OK
                raw.trimStart().startsWith("[INFO]") -> COLOR_INFO
                else -> COLOR_BODY
            }

            if (raw.isBlank()) {
                if (y + LINE_HEIGHT > PAGE_H - MARGIN) {
                    doc.finishPage(page)
                    pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                    canvas = page.canvas
                    y = MARGIN + 10f
                }
                y += LINE_HEIGHT
                lineNumber++
                continue
            }

            for (segment in wrap(raw, paint, maxWidth, INDENT_CONT)) {
                if (y + LINE_HEIGHT > PAGE_H - MARGIN) {
                    doc.finishPage(page)
                    pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                    canvas = page.canvas
                    y = MARGIN + 10f
                }
                canvas.drawText(segment.text, MARGIN + segment.indent, y, paint)
                y += LINE_HEIGHT
            }
            lineNumber++
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private data class Segment(val text: String, val indent: Float)

    /** Greedy word wrap against the actual paint metrics; overlong tokens are hard-split. */
    private fun wrap(line: String, paint: Paint, maxWidth: Float, contIndent: Float): List<Segment> {
        val usable = maxWidth - contIndent
        val words = mutableListOf<String>()
        for (token in line.trim().split(Regex("\\s+"))) {
            if (paint.measureText(token) <= usable) {
                words += token
                continue
            }
            val chunk = StringBuilder()
            for (ch in token) {
                chunk.append(ch)
                if (paint.measureText(chunk.toString()) > usable) {
                    chunk.deleteCharAt(chunk.length - 1)
                    if (chunk.isNotEmpty()) words += chunk.toString()
                    chunk.setLength(0)
                    chunk.append(ch)
                }
            }
            if (chunk.isNotEmpty()) words += chunk.toString()
        }

        val out = mutableListOf<Segment>()
        val sb = StringBuilder()
        var firstLine = true
        fun flush() {
            if (sb.isNotEmpty()) {
                out += Segment(sb.toString(), if (firstLine) 0f else contIndent)
                sb.setLength(0)
                firstLine = false
            }
        }
        for (word in words) {
            val indent = if (firstLine) 0f else contIndent
            val candidate = if (sb.isEmpty()) word else "$sb $word"
            if (sb.isNotEmpty() && paint.measureText(candidate) > maxWidth - indent) {
                flush()
                sb.append(word)
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(word)
            }
        }
        flush()
        return out.ifEmpty { listOf(Segment(line, 0f)) }
    }

    private fun paint(size: Float, typeface: Typeface): Paint = Paint().apply {
        textSize = size
        this.typeface = typeface
        isAntiAlias = true
    }
}
