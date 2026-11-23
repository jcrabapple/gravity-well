package us.snakepit.gravitywell.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineCacheStoreTest {

    private lateinit var dbHelper: GravityWellDbHelper
    private lateinit var store: OfflineCacheStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbHelper = GravityWellDbHelper(context, "test_gravity_well_cache.db")
        store = OfflineCacheStore(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun `wal mode and foreign keys are active and hosts table stores zero token columns`() {
        assertEquals("wal", store.queryJournalMode())
        assertTrue(store.queryForeignKeysEnabled())

        val cols = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("PRAGMA table_info(hosts);", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                cols += c.getString(nameIdx)
            }
        }
        assertFalse(cols.any { it.contains("token") || it.contains("secret") || it.contains("password") })
    }

    @Test
    fun `session_events prunes oldest rows beyond 1000 per session while preserving other sessions`() {
        val host = sampleHost("h1", isActive = true)
        store.upsertHost(host)
        store.upsertSession(sampleSession("h1", "s1", isLive = true))
        store.upsertSession(sampleSession("h1", "s2", isLive = true))

        val batch = (1L..1050L).map { n ->
            CachedSessionEvent(
                hostId = "h1",
                sessionId = "s1",
                seq = n,
                cursor = n * 10L,
                eventType = "agent_message_chunk",
                payloadJson = """{"text":"chunk-$n"}""",
                ts = "2026-11-23T15:00:00Z",
            )
        }
        store.insertSessionEvents(batch)
        store.insertSessionEvent(
            CachedSessionEvent(
                hostId = "h1",
                sessionId = "s2",
                seq = 1L,
                cursor = 99999L,
                eventType = "agent_message_chunk",
                payloadJson = """{"text":"other-session"}""",
                ts = "2026-11-23T15:00:01Z",
            ),
        )

        assertEquals(1000L, store.countSessionEvents("h1", "s1"))
        val retained = store.listSessionEvents("h1", "s1", afterCursor = 0L, limit = 1500)
        assertEquals(1000, retained.size)
        assertEquals(51L, retained.first().seq)
        assertEquals(1050L, retained.last().seq)

        assertEquals(1L, store.countSessionEvents("h1", "s2"))
    }

    @Test
    fun `deleting host cascades across sessions events followups approvals drafts and observatory`() {
        store.upsertHost(sampleHost("h1", isActive = true))
        store.upsertHost(sampleHost("h2", isActive = false))
        store.upsertSession(sampleSession("h1", "s1", isLive = true))
        store.upsertSession(sampleSession("h2", "s2", isLive = true))

        store.insertSessionEvent(
            CachedSessionEvent("h1", "s1", 1L, 1L, "user_message_chunk", """{"text":"hi"}""", "2026-11-23T15:00:00Z"),
        )
        store.replaceQueuedFollowups(
            "h1",
            "s1",
            listOf(CachedQueuedFollowup("h1", "s1", "item_1", 0, "next", "[]", "queued", "2026-11-23T15:00:00Z")),
        )
        store.upsertPendingApproval(
            CachedPendingApproval("h1", "s1", "req_1", "permission", "Run test", "{}", "2026-11-23T15:00:00Z"),
        )
        store.saveComposerDraft(
            CachedComposerDraft("h1", "s1", "unsent draft text", "[]", "2026-11-23T15:00:00Z"),
        )
        store.saveObservatorySnapshot(
            CachedObservatorySnapshot("h1", """{"groups":[]}""", """{"cpu":1}""", "2026-11-23T15:00:00Z"),
        )

        assertEquals(1, store.deleteHost("h1"))
        assertNull(store.getHost("h1"))
        assertTrue(store.listSessions("h1").isEmpty())
        assertEquals(0L, store.countSessionEvents("h1", "s1"))
        assertTrue(store.listQueuedFollowups("h1", "s1").isEmpty())
        assertTrue(store.listPendingApprovals("h1", "s1").isEmpty())
        assertNull(store.getComposerDraft("h1", "s1"))
        assertNull(store.getObservatorySnapshot("h1"))

        assertNotNull(store.getHost("h2"))
        assertNotNull(store.getSession("h2", "s2"))
    }

    @Test
    fun `active host switching and composer draft persistence work cleanly`() {
        store.upsertHost(sampleHost("h1", isActive = true))
        store.upsertHost(sampleHost("h2", isActive = false))
        assertEquals("h1", store.getActiveHost()?.hostId)

        store.setActiveHost("h2")
        assertEquals("h2", store.getActiveHost()?.hostId)
        assertFalse(store.getHost("h1")!!.isActive)

        store.saveComposerDraft(CachedComposerDraft("h2", "s_draft", "keep me on send-now", "[]", "2026-11-23T15:01:00Z"))
        assertEquals("keep me on send-now", store.getComposerDraft("h2", "s_draft")?.draftText)
        store.clearComposerDraft("h2", "s_draft")
        assertNull(store.getComposerDraft("h2", "s_draft"))
    }

    private fun sampleHost(id: String, isActive: Boolean) = CachedHost(
        hostId = id,
        label = "Host $id",
        baseUrl = "https://$id.example.ts.net/",
        hostname = id,
        defaultCwd = "/home/jason",
        agyVersion = "1.5.15",
        capabilitiesJson = """{"fork":false}""",
        lastCursor = 0L,
        isActive = isActive,
        createdAt = "2026-11-23T14:00:00Z",
        lastVerifiedAt = "2026-11-23T14:00:00Z",
    )

    private fun sampleSession(hostId: String, sessionId: String, isLive: Boolean) = CachedSession(
        hostId = hostId,
        sessionId = sessionId,
        title = "Session $sessionId",
        cwd = "/home/jason/projects/gravity-well",
        status = if (isLive) "idle" else "archived",
        isLive = isLive,
        updatedAt = "2026-11-23T15:00:00Z",
    )
}
