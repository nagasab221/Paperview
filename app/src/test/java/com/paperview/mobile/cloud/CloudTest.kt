package com.paperview.mobile.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTest {
    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val access = "AKIAIOSFODNN7EXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    /** AWS's documented example: GET /test.txt with a Range header. */
    @Test fun signsGetObjectLikeAws() {
        val auth = S3Backend.sign(
            "GET", "/test.txt", "",
            listOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to empty,
                "x-amz-date" to "20130524T000000Z",
            ),
            empty, access, secret, "us-east-1", "20130524T000000Z",
        )
        assertTrue(auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"))
    }

    /** AWS's documented example: GET /?max-keys=2&prefix=J. */
    @Test fun signsListLikeAws() {
        val q = S3Backend.canonicalQuery(listOf("prefix" to "J", "max-keys" to "2"))
        assertEquals("max-keys=2&prefix=J", q)
        val auth = S3Backend.sign(
            "GET", "/", q,
            listOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "x-amz-content-sha256" to empty,
                "x-amz-date" to "20130524T000000Z",
            ),
            empty, access, secret, "us-east-1", "20130524T000000Z",
        )
        assertTrue(auth.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"))
    }

    @Test fun pathsAndNames() {
        assertEquals("Math/a.pdf", joinPath("Math/", "a.pdf"))
        assertEquals("a.pdf", joinPath("", "a.pdf"))
        assertEquals("Math/Calc", parentPath("Math/Calc/a.pdf"))
        assertEquals("", parentPath("a.pdf"))
        assertEquals("Calc", lastPart("Math/Calc/"))
        assertEquals("a%20b%C3%BC/c.pdf", percentEncode("a bü/c.pdf", keepSlash = true))
        assertTrue(isDocument("X.PDF"))
        assertFalse(isDocument("notes.txt"))
    }

    @Test fun listingOrderAndFilter() {
        val v = listOf(
            CloudFile("b.pdf"), CloudFile("A.pdf"), CloudFile("notes.mp4"),
            CloudFile(".bzEmpty"), CloudFile.dir("zeta/"),
        ).filter(::keep)
        assertEquals(listOf("zeta", "A.pdf", "b.pdf"), sortListing(v).map { it.name })
        assertEquals("1 document and 1 folder", listingSummary(listOf(CloudFile("a.pdf"), CloudFile.dir("d"))))
        assertEquals("empty so far", listingSummary(emptyList()))
    }

    @Test fun accountsKeepTheDesktopFormat() {
        val json = """[{"id":"x","name":"Laptop","kind":"Ssh","address":"nagasab","user":"me",
            "bucket":"","region":"","folder":"PaperView","port":"","insecure_tls":false,"future":1}]"""
        val a = Account.decodeList(json).single()
        assertEquals(Kind.Ssh, a.kind)
        assertEquals("SSH · me@nagasab:PaperView", a.summary)
        assertTrue(Account.encodeList(listOf(a)).contains("\"insecure_tls\":false"))
        assertEquals("PaperView", Account(kind = Kind.Ssh).trimmed().folder)
    }
}
