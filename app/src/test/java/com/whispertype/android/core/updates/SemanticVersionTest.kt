package com.whispertype.android.core.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `parse handles plain semver and v prefix`() {
        val v1 = SemanticVersion.parse("1.2.3")
        assertNotNull(v1)
        assertEquals(1, v1!!.major)
        assertEquals(2, v1.minor)
        assertEquals(3, v1.patch)

        val v2 = SemanticVersion.parse("v2.0.1")
        assertNotNull(v2)
        assertEquals(2, v2!!.major)
        assertEquals(0, v2.minor)
        assertEquals(1, v2.patch)
    }

    @Test
    fun `parse handles partial versions and suffixes`() {
        val partial = SemanticVersion.parse("v1.2")
        assertNotNull(partial)
        assertEquals(1, partial!!.major)
        assertEquals(2, partial.minor)
        assertEquals(0, partial.patch)

        val suffix = SemanticVersion.parse("1.2.2-beta.1")
        assertNotNull(suffix)
        assertEquals(1, suffix!!.major)
        assertEquals(2, suffix.minor)
        assertEquals(2, suffix.patch)
    }

    @Test
    fun `parse returns null for invalid strings`() {
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("abc"))
    }

    @Test
    fun `isNewer correctly compares versions`() {
        assertTrue(SemanticVersion.isNewer("v1.2.3", "1.2.2"))
        assertTrue(SemanticVersion.isNewer("1.3.0", "1.2.9"))
        assertTrue(SemanticVersion.isNewer("2.0.0", "1.99.99"))

        assertFalse(SemanticVersion.isNewer("v1.2.2", "1.2.2"))
        assertFalse(SemanticVersion.isNewer("1.2.1", "1.2.2"))
        assertFalse(SemanticVersion.isNewer("1.1.9", "1.2.0"))
    }
}
