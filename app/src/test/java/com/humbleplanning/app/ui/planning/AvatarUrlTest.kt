package com.humbleplanning.app.ui.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarUrlTest {
    private val server = "https://planning.example"

    @Test fun storedPathIsServedUnderTheApi_inWebp() {
        assertEquals("$server/api/avatars/u1.webp", avatarUrl(server, "avatars/u1.avif"))
        assertEquals("$server/api/avatars/u1.webp", avatarUrl(server, "/api/avatars/u1.avif"))
        assertEquals("$server/api/avatars/u1.png", avatarUrl(server, "u1.png"))
    }

    @Test fun nothingElseThanAFileOfTheFolder() {
        assertNull(avatarUrl(server, null))
        assertNull(avatarUrl(server, ""))
        assertNull(avatarUrl(server, "https://elsewhere.example/a.png"))
        assertNull(avatarUrl(server, "//elsewhere.example/a.png"))
        assertNull(avatarUrl(server, "avatars/../secret"))
        assertNull(avatarUrl(server, "../a.png"))
    }
}
