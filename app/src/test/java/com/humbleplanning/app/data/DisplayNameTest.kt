package com.humbleplanning.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The lists of the server give `name` and `firstName` apart; the people of a task come with `name` already prefixed. */
class DisplayNameTest {
    @Test fun firstNameThenName() {
        assertEquals("Liam Turner", UserDto(name = "Turner", firstName = "Liam").displayName)
    }

    @Test fun nameAlreadyPrefixedByTheServer() {
        assertEquals("Liam Turner", UserDto(name = "Liam Turner", firstName = "Liam").displayName)
    }

    @Test fun withoutFirstName() {
        assertEquals("Turner", UserDto(name = "Turner", firstName = null).displayName)
        assertEquals("Turner", UserDto(name = "Turner", firstName = " ").displayName)
    }
}
