package us.snakepit.gravitywell.ui.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.WsServerFrame
import us.snakepit.gravitywell.theme.DefaultGravityWellColors
import us.snakepit.gravitywell.ui.sessions.SessionSupplementaryApi

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsoleEnginesAndControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dbHelper: GravityWellDbHelper
    private lateinit var cacheStore: OfflineCacheStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbHelper = GravityWellDbHelper(context, File(tempFolder.root, "console_test.db").absolutePath)
        cacheStore = OfflineCacheStore(dbHelper)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dbHelper.close()
        server.close()
    }

    @Test
    fun `stream reducer coalesces thinking, auto-settles on prose, dedupes seq, and tracks swarm workers`() {
        var state = ReducedSessionState(sessionId = "s1")

        // Streaming thought chunks coalesce with isStreaming = true
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 1L,
            cursor = 10L,
            type = "agent_thought_chunk",
            ts = "2026-11-23T15:00:01Z",
            payload = Json.parseToJsonElement("""{"text":"Line 1\n"}""").jsonObject,
        )
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 2L,
            cursor = 11L,
            type = "agent_thought_chunk",
            ts = "2026-11-23T15:00:02Z",
            payload = Json.parseToJsonElement("""{"text":"Line 2"}""").jsonObject,
        )
        // Replaying seq=2 again MUST be ignored (idempotent cursor replay)
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 2L,
            cursor = 11L,
            type = "agent_thought_chunk",
            ts = "2026-11-23T15:00:02Z",
            payload = Json.parseToJsonElement("""{"text":"DUPLICATE"}""").jsonObject,
        )

        val streamingThought = state.ledger.single() as LedgerItem.ThoughtBlock
        assertTrue(streamingThought.isStreaming)
        assertEquals("Line 1\nLine 2", streamingThought.text)
        assertEquals(2, streamingThought.lineCount)

        // Agent message chunk arrives -> settles thought block (isStreaming = false -> auto-collapses!)
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 3L,
            cursor = 12L,
            type = "agent_message_chunk",
            ts = "2026-11-23T15:00:03Z",
            payload = Json.parseToJsonElement("""{"text":"Here is the fix."}""").jsonObject,
        )
        val settledThought = state.ledger[0] as LedgerItem.ThoughtBlock
        val activeProse = state.ledger[1] as LedgerItem.AgentProse
        assertFalse("Thought block must settle once agent prose begins", settledThought.isStreaming)
        assertTrue(activeProse.isStreaming)

        // Subagent / swarm tool call populates swarmWorkers strip
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 4L,
            cursor = 13L,
            type = "tool_call",
            ts = "2026-11-23T15:00:04Z",
            payload = Json.parseToJsonElement(
                """{"tool_call_id":"tc_swarm_1","title":"delegate: researcher","kind":"subagent","status":"in_progress","raw_input":{"agent":"researcher"}}""",
            ).jsonObject,
        )
        assertEquals(1, state.swarmWorkers.size)
        assertEquals("researcher", state.swarmWorkers.single().label)
        assertTrue(state.swarmWorkers.single().isActive)

        // Tool call update completes worker and attaches unified diff
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 5L,
            cursor = 14L,
            type = "tool_call_update",
            ts = "2026-11-23T15:00:05Z",
            payload = Json.parseToJsonElement(
                """{"tool_call_id":"tc_swarm_1","status":"completed","raw_output":{"status":"ok"},"diff":"--- a/auth.py\n+++ b/auth.py\n@@ -1,1 +1,1 @@\n-allow = True\n+allow = hmac.compare_digest(a, b)"}""",
            ).jsonObject,
        )
        assertFalse(state.swarmWorkers.single().isActive)
        val toolBlock = state.ledger.last() as LedgerItem.ToolCallBlock
        val toolDiff = toolBlock.parsedDiff
        assertNotNull(toolDiff)
        assertEquals("auth.py", toolDiff!!.filePath)
        assertEquals(1, toolDiff.addedCount)
        assertEquals(1, toolDiff.removedCount)
    }

    @Test
    fun `stream reducer parses user attachment labels with fallback order and distinct merge`() {
        var state = ReducedSessionState(sessionId = "s_att")
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 1L,
            cursor = 1L,
            type = "user_message_chunk",
            ts = "2026-11-23T15:05:00Z",
            payload = Json.parseToJsonElement(
                """{"text":"Inspect ","attachments":[{"filename":"shot.png","name":"ignored"},{"name":"notes.txt"},{"label":"doc.pdf"},{"attachment_id":"att_1234567890abcdef"}]}""",
            ).jsonObject,
        )
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 2L,
            cursor = 2L,
            type = "user_message_chunk",
            ts = "2026-11-23T15:05:00Z",
            payload = Json.parseToJsonElement(
                """{"text":"these","attachments":[{"filename":"shot.png"},{"attachment_id":"att_extra"}]}""",
            ).jsonObject,
        )
        val userTurn = state.ledger.single() as LedgerItem.UserTurn
        assertEquals("Inspect these", userTurn.text)
        assertEquals(
            listOf("shot.png", "notes.txt", "doc.pdf", "att_1234567890abcdef", "att_extra"),
            userTurn.attachmentLabels,
        )
    }

    @Test
    fun `diff engine parses unified and before-after hunks with word-level LCS spans`() {
        val rawUnified = """
            --- a/gateway/auth.py
            +++ b/gateway/auth.py
            @@ -10,2 +10,2 @@
             import hmac
            -return token == expected
            +return hmac.compare_digest(token, expected)
        """.trimIndent()

        val parsed = DiffEngine.parseUnifiedDiff(rawUnified)
        assertNotNull(parsed)
        assertEquals("gateway/auth.py", parsed!!.filePath)
        val lines = parsed.hunks.single().lines
        assertEquals(DiffLineKind.HUNK_HEADER, lines[0].kind)
        assertEquals(DiffLineKind.CONTEXT, lines[1].kind)
        assertEquals(10, lines[1].oldLineNumber)
        assertEquals(10, lines[1].newLineNumber)

        val remLine = lines[2]
        val addLine = lines[3]
        assertEquals(DiffLineKind.REMOVED, remLine.kind)
        assertEquals(11, remLine.oldLineNumber)
        assertNull(remLine.newLineNumber)
        assertEquals(DiffLineKind.ADDED, addLine.kind)
        assertNull(addLine.oldLineNumber)
        assertEquals(11, addLine.newLineNumber)

        // Word-level spans highlight removed `== ` vs added `hmac.compare_digest(..., )`
        val remSpans = remLine.wordSpans
        val addSpans = addLine.wordSpans
        assertNotNull(remSpans)
        assertNotNull(addSpans)
        assertTrue(remSpans!!.any { it is WordDiffSpan.Unchanged && it.text.contains("return") })
        assertTrue(remSpans.any { it is WordDiffSpan.Removed })
        assertTrue(addSpans!!.any { it is WordDiffSpan.Added && it.text.contains("compare_digest") })
    }

    @Test
    fun `markdown parser handles streaming unclosed code fences, tables, lists, and inline styles`() {
        val streamingMd = """
            # Vernier Telemetry
            Check `gateway/auth.py` and **hmac** [docs](https://example.com).

            | Col A | Col B |
            | --- | --- |
            | 10 | 20 |

            - First item
            - Second item

            ```kotlin
            fun verify(): Boolean {
                return true
        """.trimIndent()

        val blocks = ConsoleMarkdownParser.parseBlocks(streamingMd)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        val table = blocks[2] as MarkdownBlock.Table
        assertEquals(listOf("Col A", "Col B"), table.headers)
        assertEquals(listOf(listOf("10", "20")), table.rows)
        val list = blocks[3] as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        val fence = blocks[4] as MarkdownBlock.CodeFence
        assertEquals("kotlin", fence.language)
        assertFalse("Unclosed streaming fence must mark isClosed=false", fence.isClosed)
        assertTrue(fence.code.contains("fun verify()"))

        val inline = ConsoleMarkdownParser.parseInline("Use `code` and **bold**", DefaultGravityWellColors)
        assertTrue(inline.text.contains("code"))
        assertTrue(inline.text.contains("bold"))
    }

    @Test
    fun `at-file tokenizer and magic-byte attachment sniffer enforce bounds and header signatures`() {
        // @file mention detection ignores emails and replaces cleanly
        assertNull(ComposerAutocompleteHelper.detectAtMention("contact jason@example.com", 25))
        val mention = ComposerAutocompleteHelper.detectAtMention("Inspect @gatew please", 14)
        assertNotNull(mention)
        assertEquals("gatew", mention!!.query)
        val (replaced, newCursor) = ComposerAutocompleteHelper.applyAtMentionReplacement(
            "Inspect @gatew please",
            mention,
            "gateway/auth.py",
        )
        assertEquals("Inspect @gateway/auth.py  please", replaced)
        assertEquals("Inspect @gateway/auth.py ".length, newCursor)

        // Slash command filter
        val slash = ComposerAutocompleteHelper.filterSlashCommands("/pl", forceShowSlashBar = false, available = emptyList())
        assertEquals(listOf("plan"), slash.map { it.name })

        // Magic-byte attachment sniffer: valid PNG
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        )
        val validPng = AttachmentSniffer.sniffAndValidate(pngHeader, "shot.png", "image/png")
        assertTrue(validPng is SniffedAttachmentResult.Valid)
        assertEquals("image/png", (validPng as SniffedAttachmentResult.Valid).mimeType)

        // Mismatched text disguised as .png -> 415
        val fakePng = AttachmentSniffer.sniffAndValidate("not a png".toByteArray(), "fake.png", "image/png")
        assertTrue(fakePng is SniffedAttachmentResult.Rejected)
        assertEquals(415, (fakePng as SniffedAttachmentResult.Rejected).status)

        // ELF executable disguised as .txt -> 415
        val elfBytes = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01)
        val rejectedElf = AttachmentSniffer.sniffAndValidate(elfBytes, "notes.txt", "text/plain")
        assertTrue(rejectedElf is SniffedAttachmentResult.Rejected)
        assertEquals(415, (rejectedElf as SniffedAttachmentResult.Rejected).status)

        // > 10 MiB pre-check -> 413
        val tooBig = AttachmentSniffer.validateSizeOnly(AttachmentSniffer.MAX_ATTACHMENT_BYTES + 1L)
        assertNotNull(tooBig)
        assertEquals(413, tooBig!!.status)
    }

    @Test
    fun `console controller preserves composer draft on Send Now and resolves permissions questions and artifacts`() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String>>() // method + path with query
        val postedBodies = CopyOnWriteArrayList<Pair<String, String>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val bodyUtf8 = request.body?.utf8().orEmpty()
                requests += (request.method to request.target)
                if (request.method == "POST" || request.method == "PATCH") {
                    postedBodies += (path to bodyUtf8)
                }

                return when {
                    path == "/api/sessions/sess_live" && request.method == "GET" -> {
                        MockResponse.Builder().code(200).body(
                            """{
                              "session_id":"sess_live",
                              "title":"Console Spec",
                              "title_source":"acp",
                              "cwd":"/home/jason/projects/gravity-well",
                              "status":"awaiting_approval",
                              "is_live":true,
                              "sandbox":true,
                              "usage":{"used":8709,"size":1048576},
                              "pending_permissions":[{
                                "request_id":"perm_1",
                                "tool_call":{"kind":"execute","rawInput":{"CommandLine":"pytest -q"}},
                                "options":[{"optionId":"allow-once","name":"Allow Once","kind":"allow_once"},{"optionId":"allow-always","name":"Allow Always","kind":"allow_always"}]
                              }],
                              "pending_questions":[{
                                "request_id":"qst_1",
                                "mode":"form",
                                "message":"Pick target lane",
                                "requested_schema":{
                                  "type":"object",
                                  "required":["q_0"],
                                  "properties":{"q_0":{"type":"string","title":"Lane","enum":["alpha","beta"],"enumNames":["Alpha Lane","Beta Lane"]}}
                                }
                              }],
                              "queued_followups":[{"item_id":"q_99","position":0,"text":"Run lint after","attachment_ids":[],"status":"queued"}],
                              "cursor":20,
                              "seq":3,
                              "updated_at":"2026-11-23T15:00:00Z"
                            }""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/events" -> {
                        MockResponse.Builder().code(200).body(
                            """{"session_id":"sess_live","after":0,"min_cursor_retained":1,"latest_cursor":20,"truncated":false,"events":[]}""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/queue" && request.method == "GET" -> {
                        MockResponse.Builder().code(200).body(
                            """{"items":[{"item_id":"q_99","position":0,"text":"Run lint after","attachment_ids":[],"status":"queued"}],"paused":false}""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/queue/q_99/send-now" -> {
                        MockResponse.Builder().code(202).body("""{"status":"interrupting","session_id":"sess_live","item_id":"q_99"}""").build()
                    }

                    path == "/api/sessions/sess_live/permissions/perm_1" -> {
                        MockResponse.Builder().code(200).body("""{"request_id":"perm_1","resolved":true,"option_id":"allow-once"}""").build()
                    }

                    path == "/api/sessions/sess_live/questions/qst_1" -> {
                        MockResponse.Builder().code(200).body("""{"request_id":"qst_1","resolved":true,"action":"accept"}""").build()
                    }

                    path == "/api/sessions/sess_live/files" -> {
                        MockResponse.Builder().code(200).body(
                            """{"items":[{"path":"gateway/auth.py","root":"/home/jason/projects/gravity-well","size_bytes":2048}]}""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/attachments" -> {
                        MockResponse.Builder().code(201).body(
                            """{"attachment_id":"att_0123456789abcdef","filename":"note.md","mime_type":"text/markdown","size_bytes":12,"sha256":"abc"}""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/artifacts" -> {
                        MockResponse.Builder().code(200).body(
                            """{"session_id":"sess_live","items":[{"path":"logs/transcript.jsonl","size_bytes":128,"mime_type":"application/x-ndjson","modified_at":"2026-11-23T15:00:00Z"}]}""",
                        ).build()
                    }

                    path == "/api/sessions/sess_live/artifacts/logs/transcript.jsonl" -> {
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/x-ndjson; charset=utf-8")
                            .body("""{"line":"token=[REDACTED]"}""")
                            .build()
                    }

                    path == "/api/sessions/sess_live/prompt" -> {
                        MockResponse.Builder().code(202).body(
                            """{"status":"started","session_id":"sess_live","turn_id":"turn_1"}""",
                        ).build()
                    }

                    else -> MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"Not found: $path","status":404}}""").build()
                }
            }
        }
        server.start()

        val host = CachedHost(
            hostId = "h1",
            label = "aurora",
            baseUrl = server.url("/").toString(),
            defaultCwd = "/home/jason/projects/gravity-well",
            isActive = true,
            createdAt = "2026-11-23T14:00:00Z",
            lastVerifiedAt = "2026-11-23T14:00:00Z",
        )
        cacheStore.upsertHost(host)

        val okHttp = NetworkModule.createOkHttpClient(tokenProvider = { "gw_tok_live" })
        val gwApi = NetworkModule.createGatewayApi(server.url("/"), okHttp)
        val suppApi = SessionSupplementaryApi.create(server.url("/"), okHttp)

        val ctrl = SessionConsoleController(
            host = host,
            sessionId = "sess_live",
            baseUrl = server.url("/"),
            okHttpClient = okHttp,
            gatewayApi = gwApi,
            supplementaryApi = suppApi,
            cacheStore = cacheStore,
            scope = scope,
            fileSearchDebounceMs = 0L,
        )

        ctrl.syncWithGateway()
        awaitCondition { ctrl.state.value.session.pendingPermissions.size == 1 }

        // User types unsent draft with @file trigger
        val unsentDraft = "Half-written composer note @auth"
        ctrl.updateDraft(unsentDraft, unsentDraft.length)
        awaitCondition { ctrl.state.value.atFileCandidates.isNotEmpty() }
        assertEquals("gateway/auth.py", ctrl.state.value.atFileCandidates.single().path)
        assertEquals(unsentDraft, cacheStore.getComposerDraft("h1", "sess_live")?.draftText)

        // D6 CRITICAL INVARIANT: Tapping Send Now on queued follow-up MUST NOT touch composer draft!
        ctrl.sendQueueItemNow("q_99", interrupt = true)
        awaitCondition { postedBodies.any { it.first == "/api/sessions/sess_live/queue/q_99/send-now" } }
        assertEquals(
            "Send Now must preserve in-memory composer draftText",
            unsentDraft,
            ctrl.state.value.draftText,
        )
        assertEquals(
            "Send Now must preserve SQLite composer_drafts entry",
            unsentDraft,
            cacheStore.getComposerDraft("h1", "sess_live")?.draftText,
        )

        // Permission Gate resolution
        ctrl.resolvePermission("perm_1", decision = "allow_once", optionId = "allow-once")
        awaitCondition { ctrl.state.value.session.pendingPermissions.isEmpty() }

        // Question Broker resolution preserving exact q_0 schema key
        ctrl.updateQuestionDraftAnswer("qst_1", "q_0", "beta")
        ctrl.resolveQuestion("qst_1", action = "accept")
        awaitCondition { ctrl.state.value.session.pendingQuestions.isEmpty() }
        val qstBody = postedBodies.first { it.first == "/api/sessions/sess_live/questions/qst_1" }.second
        val qstJson = Json.parseToJsonElement(qstBody).jsonObject
        assertEquals("accept", qstJson["action"]!!.jsonPrimitive.content)
        assertEquals("beta", qstJson["answers"]!!.jsonObject["q_0"]!!.jsonPrimitive.content)

        // Magic-byte valid Markdown attachment upload + prompt submit clears draft
        ctrl.attachFileBytes("note.md", "text/markdown", "# Heading\nBody".toByteArray(StandardCharsets.UTF_8))
        awaitCondition { ctrl.state.value.draftAttachments.singleOrNull()?.state == AttachmentUploadState.READY }

        ctrl.submitComposerPrompt()
        awaitCondition { ctrl.state.value.draftText.isEmpty() && !ctrl.state.value.isSubmittingPrompt }
        assertNull("Submitting composer prompt clears SQLite draft", cacheStore.getComposerDraft("h1", "sess_live"))

        // brain/ Artifacts listing & redacted text viewer
        ctrl.openArtifactsSheet()
        awaitCondition { ctrl.state.value.artifacts.size == 1 }
        ctrl.openArtifactItem(ctrl.state.value.artifacts.single())
        awaitCondition { ctrl.state.value.selectedArtifact != null }
        assertTrue(ctrl.state.value.selectedArtifact!!.isSecretRedactedText)
        assertTrue(ctrl.state.value.selectedArtifact!!.textContent!!.contains("[REDACTED]"))

        // WS resync_required sets RESYNCED indicator
        ctrl.onWebSocketFrame(WsServerFrame.ResyncRequired(after = 2L, minCursorRetained = 15L, latestCursor = 20L))
        assertEquals(ConsoleStreamIndicator.RESYNCED, ctrl.state.value.streamIndicator)
    }

    @Test
    fun `required boolean question field is submittable via defaultValue and syncWithGateway prunes stale approvals with 2000 event catchup`() = runBlocking {
        val boolSchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "required":["q_confirm"],
              "properties":{
                "q_confirm":{"type":"boolean","title":"Proceed?","default":false}
              }
            }""",
        ).jsonObject
        val parsedFields = QuestionSchemaParser.parseFields(boolSchema)
        assertTrue(
            "Required BooleanField must be submittable without toggling twice",
            QuestionSchemaParser.areRequiredAnswersSatisfied(parsedFields, emptyMap()),
        )
        val builtPayload = QuestionSchemaParser.buildAnswersPayload(parsedFields, emptyMap())
        assertEquals("false", builtPayload["q_confirm"]!!.jsonPrimitive.content)

        val observedEventsQueries = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/sessions/sess_resync" -> MockResponse.Builder().code(200).body(
                        """{
                          "session_id":"sess_resync",
                          "title":"Resync Session",
                          "title_source":"acp",
                          "cwd":"/home/jason/projects/gravity-well",
                          "status":"idle",
                          "is_live":true,
                          "pending_permissions":[],
                          "pending_questions":[],
                          "queued_followups":[],
                          "cursor":2500,
                          "seq":2500,
                          "updated_at":"2026-11-23T16:00:00Z"
                        }""",
                    ).build()
                    path == "/api/sessions/sess_resync/events" -> {
                        observedEventsQueries += request.target
                        val after = request.url.queryParameter("after")?.toLongOrNull() ?: 0L
                        if (after > 0L) {
                            MockResponse.Builder().code(200).body(
                                """{"session_id":"sess_resync","after":$after,"min_cursor_retained":501,"latest_cursor":2500,"truncated":true,"events":[]}""",
                            ).build()
                        } else {
                            MockResponse.Builder().code(200).body(
                                """{"session_id":"sess_resync","after":0,"min_cursor_retained":501,"latest_cursor":2500,"truncated":false,"events":[{"cursor":2500,"seq":2500,"type":"agent_message_chunk","ts":"2026-11-23T16:00:00Z","payload":{"text":"Caught up 2000"}}]}""",
                            ).build()
                        }
                    }
                    path == "/api/sessions/sess_resync/queue" -> MockResponse.Builder().code(200).body(
                        """{"items":[],"paused":false}""",
                    ).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()

        val host = CachedHost(
            hostId = "h1",
            label = "aurora",
            baseUrl = server.url("/").toString(),
            defaultCwd = "/home/jason/projects/gravity-well",
            isActive = true,
            createdAt = "2026-11-23T14:00:00Z",
            lastVerifiedAt = "2026-11-23T14:00:00Z",
        )
        cacheStore.upsertHost(host)
        cacheStore.upsertSession(
            us.snakepit.gravitywell.cache.CachedSession(
                hostId = "h1",
                sessionId = "sess_resync",
                title = "Resync Session",
                cwd = "/home/jason/projects/gravity-well",
                status = "awaiting_approval",
                isLive = true,
                lastCursor = 10L,
                lastSeq = 10L,
                updatedAt = "2026-11-23T15:00:00Z",
            ),
        )
        cacheStore.insertSessionEvent(
            us.snakepit.gravitywell.cache.CachedSessionEvent(
                hostId = "h1",
                sessionId = "sess_resync",
                seq = 10L,
                cursor = 10L,
                eventType = "user_message_chunk",
                payloadJson = """{"text":"old"}""",
                ts = "2026-11-23T15:00:00Z",
            ),
        )
        cacheStore.upsertPendingApproval(
            us.snakepit.gravitywell.cache.CachedPendingApproval(
                hostId = "h1",
                sessionId = "sess_resync",
                requestId = "stale_perm_99",
                kind = "permission",
                title = "Stale Gate",
                payloadJson = "{}",
                createdAt = "2026-11-23T15:00:00Z",
            ),
        )

        val okHttp = NetworkModule.createOkHttpClient(tokenProvider = { "gw_tok_resync" })
        val gwApi = NetworkModule.createGatewayApi(server.url("/"), okHttp)
        val suppApi = SessionSupplementaryApi.create(server.url("/"), okHttp)
        val ctrl = SessionConsoleController(
            host = host,
            sessionId = "sess_resync",
            baseUrl = server.url("/"),
            okHttpClient = okHttp,
            gatewayApi = gwApi,
            supplementaryApi = suppApi,
            cacheStore = cacheStore,
            scope = scope,
        )

        ctrl.syncWithGateway()
        awaitCondition { ctrl.state.value.streamIndicator == ConsoleStreamIndicator.RESYNCED }

        assertTrue(
            "Truncated catch-up must request limit=2000, got $observedEventsQueries",
            observedEventsQueries.any { it.contains("after=0") && it.contains("limit=2000") },
        )
        assertTrue(
            "Stale pending_approvals must be pruned when absent from snapshot",
            cacheStore.listPendingApprovals("h1", "sess_resync").isEmpty(),
        )
    }

    @Test
    fun `stream reducer parses and deduplicates attachment labels on user_message_chunk`() {
        var state = ReducedSessionState(sessionId = "s_att")
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 1L,
            cursor = 1L,
            type = "user_message_chunk",
            ts = "2026-11-23T15:10:00Z",
            payload = Json.parseToJsonElement(
                """{"text":"Inspect files","attachments":[{"filename":"a.png"},{"name":"b.txt"},{"label":"c.md"},{"attachment_id":"att_1234567890abcdef"},{"filename":"a.png"}]}""",
            ).jsonObject,
        )
        state = ConsoleStreamReducer.applyEvent(
            state,
            seq = 2L,
            cursor = 2L,
            type = "user_message_chunk",
            ts = "2026-11-23T15:10:00Z",
            payload = Json.parseToJsonElement(
                """{"text":" please","attachments":[{"attachment_id":"att_1234567890abcdef"},{"filename":"d.log"}]}""",
            ).jsonObject,
        )

        val turn = state.ledger.single() as LedgerItem.UserTurn
        assertEquals("Inspect files please", turn.text)
        assertEquals(
            listOf("a.png", "b.txt", "c.md", "att_1234567890abcdef", "d.log"),
            turn.attachmentLabels,
        )
    }

    @Test
    fun `mergeSnapshot ignores stale cursor overwrite on volatile status gates and queue`() {
        val current = ReducedSessionState(
            sessionId = "s_guard",
            title = "Live",
            status = "running",
            isLive = true,
            lastCursor = 50L,
            lastSeq = 10L,
        )
        val stale = us.snakepit.gravitywell.net.SessionSnapshotDto(
            sessionId = "s_guard",
            title = "Live",
            status = "idle",
            isLive = false,
            cursor = 40L,
            seq = 8L,
        )
        val afterStale = ConsoleStreamReducer.mergeSnapshot(current, stale)
        assertEquals("running", afterStale.status)
        assertTrue(afterStale.isLive)
        assertEquals(50L, afterStale.lastCursor)

        val newer = stale.copy(status = "idle", isLive = false, cursor = 55L, seq = 11L)
        val afterNewer = ConsoleStreamReducer.mergeSnapshot(afterStale, newer)
        assertEquals("idle", afterNewer.status)
        assertFalse(afterNewer.isLive)
        assertEquals(55L, afterNewer.lastCursor)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(20L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }
}
