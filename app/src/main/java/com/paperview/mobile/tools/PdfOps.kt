package com.paperview.mobile.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Every editing operation the Tools section performs.
 *
 * PDFium renders; it cannot compose. Its public API has no `ImportPages` and no
 * way to create a document, so merge, split, reorder, encryption and anything
 * that draws on a page all run through PDFBox instead.
 *
 * Everything here is blocking file work and hops to [Dispatchers.IO]. Documents
 * are always written to a *new* file — never edited in place, so a failure
 * halfway through can't destroy the user's original.
 */
object PdfOps {

    /** Thrown with a message meant to be shown to the user as-is. */
    class OpException(message: String) : Exception(message)

    /** A password-protected input that we weren't given the password for. */
    class NeedsPassword : Exception("This PDF is password protected")

    // ── Opening ──────────────────────────────────────────────────────────────

    private fun open(file: File, password: String? = null): PDDocument = try {
        if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        throw NeedsPassword()
    } catch (e: Throwable) {
        throw OpException("Could not open ${file.name}: ${e.message ?: "unsupported file"}")
    }

    /** Page count without holding the document open. */
    suspend fun pageCount(file: File, password: String? = null): Int = withContext(Dispatchers.IO) {
        open(file, password).use { it.numberOfPages }
    }

    suspend fun isEncrypted(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { PDDocument.load(file).use { it.isEncrypted } }.getOrElse {
            it is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
        }
    }

    // ── Page-level operations ────────────────────────────────────────────────

    /** Concatenates [inputs] in the order given. */
    suspend fun merge(inputs: List<File>, out: File): File = withContext(Dispatchers.IO) {
        if (inputs.size < 2) throw OpException("Pick at least two PDFs to merge")
        val merger = PDFMergerUtility()
        merger.destinationFileName = out.absolutePath
        inputs.forEach { merger.addSource(it) }
        try {
            // Streaming mode keeps peak memory near one page rather than the
            // whole set, which matters when merging scans on a phone.
            merger.mergeDocuments(null)
        } catch (e: Throwable) {
            throw OpException("Merge failed: ${e.message ?: "unreadable input"}")
        }
        out
    }

    /**
     * Writes the given [pages] (0-based, in the order supplied) to [out].
     * Backs extract, reorder and delete — they differ only in the page list.
     */
    suspend fun writePages(
        input: File,
        pages: List<Int>,
        out: File,
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw OpException("That would leave the document empty")
        open(input, password).use { src ->
            PDDocument().use { dst ->
                for (index in pages) {
                    if (index !in 0 until src.numberOfPages) continue
                    // importPage deep-copies the page's resources into the new
                    // document; adding src's page directly would leave the two
                    // sharing objects and produce a corrupt file once src closes.
                    dst.importPage(src.getPage(index))
                }
                if (dst.numberOfPages == 0) throw OpException("No valid pages selected")
                dst.save(out)
            }
        }
        out
    }

    suspend fun deletePages(input: File, remove: Set<Int>, out: File, password: String? = null): File {
        val total = pageCount(input, password)
        val keep = (0 until total).filterNot { it in remove }
        if (keep.isEmpty()) throw OpException("You can't delete every page")
        return writePages(input, keep, out, password)
    }

    /** Splits into one file per range; returns the files written. */
    suspend fun splitRanges(
        input: File,
        ranges: List<IntRange>,
        outDir: File,
        baseName: String,
        password: String? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        ranges.mapIndexed { i, range ->
            val out = File(outDir, "${baseName}_${range.first + 1}-${range.last + 1}.pdf")
            writePages(input, range.toList(), out, password)
            out
        }.also { if (it.isEmpty()) throw OpException("No ranges given") }
    }

    /** Rotates [pages] by [degrees] (clockwise) and saves. */
    suspend fun rotate(
        input: File,
        degrees: Int,
        pages: Set<Int>?,
        out: File,
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        open(input, password).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                if (pages != null && i !in pages) continue
                val page = doc.getPage(i)
                // Rotation is cumulative on whatever the page already had.
                page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
            }
            doc.save(out)
        }
        out
    }

    // ── Security ─────────────────────────────────────────────────────────────

    /**
     * Adds password protection. [userPassword] is needed to open the file;
     * [ownerPassword] governs permissions — when blank it mirrors the user
     * password, which is what people expect from "add a password".
     */
    suspend fun lock(
        input: File,
        userPassword: String,
        ownerPassword: String,
        out: File,
        currentPassword: String? = null,
        allowPrinting: Boolean = true,
        allowCopying: Boolean = true,
    ): File = withContext(Dispatchers.IO) {
        if (userPassword.isBlank()) throw OpException("Enter a password")
        open(input, currentPassword).use { doc ->
            val permissions = AccessPermission().apply {
                setCanPrint(allowPrinting)
                setCanExtractContent(allowCopying)
                setCanModify(false)
            }
            val owner = ownerPassword.ifBlank { userPassword }
            doc.protect(StandardProtectionPolicy(owner, userPassword, permissions).apply {
                encryptionKeyLength = 128
            })
            doc.save(out)
        }
        out
    }

    /** Removes protection. Requires the current password. */
    suspend fun unlock(input: File, password: String, out: File): File =
        withContext(Dispatchers.IO) {
            open(input, password).use { doc ->
                if (!doc.isEncrypted) throw OpException("This PDF isn't password protected")
                doc.isAllSecurityToBeRemoved = true
                doc.save(out)
            }
            out
        }

    suspend fun changePassword(
        input: File,
        oldPassword: String,
        newPassword: String,
        out: File,
    ): File {
        if (newPassword.isBlank()) throw OpException("Enter a new password")
        return lock(
            input = input,
            userPassword = newPassword,
            ownerPassword = newPassword,
            out = out,
            currentPassword = oldPassword,
        )
    }

    /** Where a watermark or stamp sits on the page. */
    enum class Anchor { TopLeft, TopCentre, TopRight, Centre, BottomLeft, BottomCentre, BottomRight }

    /** Draws diagonal or positioned text across every page. */
    suspend fun watermarkText(
        input: File,
        text: String,
        out: File,
        opacity: Float = 0.25f,
        rotationDegrees: Float = 45f,
        fontSize: Float = 48f,
        colour: Triple<Float, Float, Float> = Triple(0.4f, 0.4f, 0.4f),
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        if (text.isBlank()) throw OpException("Enter some watermark text")
        open(input, password).use { doc ->
            val font = PDType1Font.HELVETICA_BOLD
            val alpha = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = opacity.coerceIn(0.02f, 1f)
                strokingAlphaConstant = opacity.coerceIn(0.02f, 1f)
            }
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i)
                val box = page.mediaBox
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true,
                ).use { cs ->
                    cs.setGraphicsStateParameters(alpha)
                    cs.setNonStrokingColor(colour.first, colour.second, colour.third)
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    val width = font.getStringWidth(text) / 1000f * fontSize
                    // Rotate about the page centre, then back the text up by
                    // half its own width so it stays centred at any angle.
                    val cx = box.width / 2f
                    val cy = box.height / 2f
                    val radians = Math.toRadians(rotationDegrees.toDouble())
                    val cos = Math.cos(radians).toFloat()
                    val sin = Math.sin(radians).toFloat()
                    val half = width / 2f
                    cs.setTextMatrix(
                        com.tom_roush.pdfbox.util.Matrix(
                            cos, sin, -sin, cos,
                            cx - (half * cos) + (fontSize / 3f * sin),
                            cy - (half * sin) - (fontSize / 3f * cos),
                        ),
                    )
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.save(out)
        }
        out
    }

    /** Stamps a bitmap (a drawn signature) onto one page at a given rect. */
    suspend fun stampImage(
        input: File,
        bitmap: Bitmap,
        pageIndex: Int,
        /** Position and size as fractions of the page, origin top-left. */
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        out: File,
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        open(input, password).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) throw OpException("No such page")
            val page = doc.getPage(pageIndex)
            val box = page.mediaBox
            val image = LosslessFactory.createFromImage(doc, bitmap)
            val w = box.width * widthFraction
            val h = w * bitmap.height / bitmap.width.toFloat()
            val x = box.lowerLeftX + box.width * xFraction
            // PDF space has y growing up; the caller thinks in screen terms.
            val y = box.lowerLeftY + box.height * (1f - yFraction) - h
            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true,
            ).use { cs -> cs.drawImage(image, x, y, w, h) }
            doc.save(out)
        }
        out
    }

    // ── Page numbers ─────────────────────────────────────────────────────────

    suspend fun numberPages(
        input: File,
        out: File,
        anchor: Anchor = Anchor.BottomCentre,
        startAt: Int = 1,
        /** Use {n} for the number and {total} for the count. */
        format: String = "{n}",
        fontSize: Float = 11f,
        skipFirst: Boolean = false,
        password: String? = null,
    ): File = withContext(Dispatchers.IO) {
        open(input, password).use { doc ->
            val font = PDType1Font.HELVETICA
            val total = doc.numberOfPages
            for (i in 0 until total) {
                if (skipFirst && i == 0) continue
                val label = format
                    .replace("{n}", (startAt + i).toString())
                    .replace("{total}", (startAt + total - 1).toString())
                val page = doc.getPage(i)
                val box = page.mediaBox
                val textWidth = font.getStringWidth(label) / 1000f * fontSize
                val margin = 28f
                val x = when (anchor) {
                    Anchor.TopLeft, Anchor.BottomLeft -> box.lowerLeftX + margin
                    Anchor.TopRight, Anchor.BottomRight -> box.upperRightX - margin - textWidth
                    else -> box.lowerLeftX + (box.width - textWidth) / 2f
                }
                val y = when (anchor) {
                    Anchor.TopLeft, Anchor.TopCentre, Anchor.TopRight ->
                        box.upperRightY - margin
                    Anchor.Centre -> box.lowerLeftY + box.height / 2f
                    else -> box.lowerLeftY + margin
                }
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true,
                ).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(x, y)
                    cs.showText(label)
                    cs.endText()
                }
            }
            doc.save(out)
        }
        out
    }

    // ── Conversion ───────────────────────────────────────────────────────────

    /** Page size options for image import. */
    enum class PageFit { FitImage, A4, Letter }

    suspend fun imagesToPdf(
        images: List<File>,
        out: File,
        fit: PageFit = PageFit.FitImage,
        jpegQuality: Float = 0.85f,
        marginPt: Float = 0f,
    ): File = withContext(Dispatchers.IO) {
        if (images.isEmpty()) throw OpException("Pick at least one image")
        PDDocument().use { doc ->
            for (file in images) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw OpException("Could not read ${file.name}")
                try {
                    val image: PDImageXObject =
                        JPEGFactory.createFromImage(doc, bitmap, jpegQuality.coerceIn(0.1f, 1f))
                    val pageSize = when (fit) {
                        PageFit.A4 -> PDRectangle.A4
                        PageFit.Letter -> PDRectangle.LETTER
                        PageFit.FitImage -> PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                    val page = PDPage(pageSize)
                    doc.addPage(page)
                    val availW = pageSize.width - marginPt * 2
                    val availH = pageSize.height - marginPt * 2
                    val scale = min(availW / bitmap.width, availH / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(
                            image,
                            pageSize.lowerLeftX + (pageSize.width - w) / 2f,
                            pageSize.lowerLeftY + (pageSize.height - h) / 2f,
                            w, h,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            doc.save(out)
        }
        out
    }

    // ── Compression ──────────────────────────────────────────────────────────

    data class CompressResult(val out: File, val beforeBytes: Long, val afterBytes: Long) {
        val savedPercent: Int
            get() = if (beforeBytes <= 0) 0
            else (((beforeBytes - afterBytes).toFloat() / beforeBytes) * 100).toInt()
    }

    /**
     * Re-encodes embedded images at a lower quality and resolution.
     *
     * That is where the bytes actually are in a typical PDF — scans especially.
     * Text and vectors are left alone, so the result stays sharp and selectable.
     */
    suspend fun compress(
        input: File,
        out: File,
        jpegQuality: Float = 0.6f,
        maxImageDimension: Int = 1600,
        password: String? = null,
    ): CompressResult = withContext(Dispatchers.IO) {
        val before = input.length()
        open(input, password).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i)
                val resources = page.resources ?: continue
                for (name in resources.xObjectNames.toList()) {
                    val xObject = runCatching { resources.getXObject(name) }.getOrNull()
                    if (xObject !is PDImageXObject) continue
                    val bitmap = runCatching { xObject.image }.getOrNull() ?: continue
                    try {
                        val longest = max(bitmap.width, bitmap.height)
                        if (longest <= 0) continue
                        val scale = if (longest > maxImageDimension) {
                            maxImageDimension.toFloat() / longest
                        } else 1f
                        val target = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                bitmap,
                                max(1, (bitmap.width * scale).toInt()),
                                max(1, (bitmap.height * scale).toInt()),
                                true,
                            )
                        } else bitmap
                        val replacement = JPEGFactory.createFromImage(
                            doc, target, jpegQuality.coerceIn(0.1f, 1f),
                        )
                        resources.put(name, replacement)
                        if (target !== bitmap) target.recycle()
                    } catch (_: Throwable) {
                        // An image we can't re-encode simply stays as it was.
                    } finally {
                        runCatching { bitmap.recycle() }
                    }
                }
            }
            doc.save(out)
        }
        CompressResult(out, before, out.length())
    }
}
