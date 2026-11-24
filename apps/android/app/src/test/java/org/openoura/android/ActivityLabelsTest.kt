package org.openoura.android

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.EffortSession
import org.openoura.android.data.UserLabel
import org.openoura.android.ui.ActivityLabels

class ActivityLabelsTest {
    @Test
    fun a_known_kind_is_called_by_its_name_and_keeps_its_note_apart() {
        val l = UserLabel("dance", "Salsa class")
        assertEquals("Dance", ActivityLabels.title(l))
        assertEquals("Salsa class", ActivityLabels.note(l))
    }

    @Test
    fun other_with_a_note_is_called_by_the_note() {
        val l = UserLabel("other", "  Bouldering ")
        assertEquals("Bouldering", ActivityLabels.title(l))
        assertNull(ActivityLabels.note(l))
        assertEquals("Other", ActivityLabels.title(UserLabel("other", " ")))
    }

    @Test
    fun a_kind_from_a_later_build_still_shows() {
        assertEquals("Climbing", ActivityLabels.title(UserLabel("climbing")))
    }

    @Test
    fun the_core_s_effort_json_parses_with_and_without_a_label() {
        val json = Json { ignoreUnknownKeys = true }
        val labelled = json.decodeFromString<EffortSession>(
            """{"start":1.0,"end":2.0,"ring":{"serial":"R","capture":[10,20],"start_ds":5,"end_ds":9},
               "user_label":{"kind":"run","note":null,"updated_unix":7}}""",
        )
        assertEquals(UserLabel("run", null, 7), labelled.userLabel)
        assertEquals(listOf(10L, 20L), labelled.ring?.capture)
        val bare = json.decodeFromString<EffortSession>("""{"start":1.0,"end":2.0,"user_label":null}""")
        assertNull(bare.userLabel)
        assertNull(bare.ring)
    }
}
