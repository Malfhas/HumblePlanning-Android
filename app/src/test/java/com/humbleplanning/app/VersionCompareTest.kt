package com.humbleplanning.app

import com.humbleplanning.app.data.Repository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test fun olderVersions() {
        assertTrue(Repository.isOlderVersion("1.0.0", "9.0.0"))
        assertTrue(Repository.isOlderVersion("1.9.0", "1.10.0"))
        assertTrue(Repository.isOlderVersion("1.0", "1.0.1"))
    }

    @Test fun sameOrNewerVersions() {
        assertFalse(Repository.isOlderVersion("1.0.0", "1.0.0"))
        assertFalse(Repository.isOlderVersion("1.0.0", "1.0"))
        assertFalse(Repository.isOlderVersion("2.0.0", "1.9.9"))
    }

    @Test fun noMinimum() {
        assertFalse(Repository.isOlderVersion("1.0.0", null))
        assertFalse(Repository.isOlderVersion("1.0.0", ""))
    }
}