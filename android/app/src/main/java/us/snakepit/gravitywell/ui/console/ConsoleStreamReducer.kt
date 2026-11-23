package us.snakepit.gravitywell.ui.console

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import us.snakepit.gravitywell.cache.CachedSession
import us.snakepit.gravitywell.cache.CachedSessionEvent
import us.snakepit.gravitywell.net.AvailableCommandDto
import us.snakepit.gravitywell.net.ConfigOptionDto
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.PendingPermissionDto
import us.snakepit.gravitywell.net.PendingQuestionDto
import us.snakepit.gravitywell.net.PermissionOptionDto
import us.snakepit.gravitywell.net.QueueItemDto
import us.snakepit.gravitywell.net.SessionEventDto
import us.snakepit.gravitywell.net.SessionSnapshotDto
import us.snakepit.gravitywell.net.UsageDto
import us.snakepit.gravitywell.net.WsServerFrame

/**
 * Deterministic, sequence-deduplicating reducer folding SessionSnapshot + ACP events
 * into Vernier Rig ledger strata, subagent swarm badges, and docked gate state.
 */
object ConsoleStreamReducer {

    private val swarmKeywords = listOf("swarm", "subagent", "delegate", "spawn_agent", "worker", "agent_task")

    fun fromCachedSession(
        cached: CachedSession,
        json: Json = NetworkModule.defaultJson,
    ): ReducedSessionState {
        val configs = runCatching {
            json.decodeFromString<List<ConfigOptionDto>>(cached.configJson)
        }.getOrDefault(emptyList())
        val commands = runCatching {
            json.decodeFromString<List<AvailableCommandDto>>(cached.commandsJson)
        }.getOrDefault(emptyList())
        val addDirs = runCatching {
            json.decodeFromString<List<String>>(cached.addDirsJson)
        }.getOrDefault(emptyList())

        return ReducedSessionState(
            sessionId = cached.sessionId,
            title = cached.title,
            titleSource = cached.titleSource,
            cwd = cached.cwd,
            addDirs = addDirs,
            status = cached.status,
            isLive = cached.isLive,
            configOptions = configs,
            availableCommands = commands,
            usage = UsageDto(used = cached.usedTokens, size = cached.contextSize),
            lastSeq = 0L,
            lastCursor = 0L,
        )
    }

    fun mergeSnapshot(
        current: ReducedSessionState,
        snapshot: SessionSnapshotDto,
    ): ReducedSessionState {
        val applyVolatileState = snapshot.cursor >= current.lastCursor
        return current.copy(
            sessionId = snapshot.sessionId,
            title = snapshot.title.ifBlank { current.title },
            titleSource = if (applyVolatileState) snapshot.titleSource else current.titleSource,
            cwd = snapshot.cwd.ifBlank { current.cwd },
            addDirs = snapshot.addDirs,
            status = if (applyVolatileState) snapshot.status else current.status,
            isLive = if (applyVolatileState) snapshot.isLive else current.isLive,
            sandbox = if (applyVolatileState) snapshot.sandbox else current.sandbox,
            configOptions = snapshot.configOptions.ifEmpty { current.configOptions },
            availableModels = snapshot.availableModels.ifEmpty { current.availableModels },
            availableCommands = snapshot.availableCommands.ifEmpty { current.availableCommands },
            usage = if (snapshot.usage.size > 0L || snapshot.usage.used > 0L) snapshot.usage else current.usage,
            pendingPermissions = if (applyVolatileState) snapshot.pendingPermissions else current.pendingPermissions,
            pendingQuestions = if (applyVolatileState) snapshot.pendingQuestions else current.pendingQuestions,
            queuedFollowups = if (applyVolatileState) snapshot.queuedFollowups else current.queuedFollowups,
            lastCursor = maxOf(current.lastCursor, snapshot.cursor),
            lastSeq = maxOf(current.lastSeq, if (current.ledger.isEmpty()) 0L else current.lastSeq),
        )
    }

    fun applyCachedEvents(
        initial: ReducedSessionState,
        events: List<CachedSessionEvent>,
        json: Json = NetworkModule.defaultJson,
    ): ReducedSessionState {
        var acc = initial
        for (ev in events.sortedBy { it.seq }) {
            val payloadObj = runCatching { json.parseToJsonElement(ev.payloadJson).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            acc = applyEvent(
                state = acc,
                seq = ev.seq,
                cursor = ev.cursor,
                type = ev.eventType,
                ts = ev.ts,
                payload = payloadObj,
                json = json,
            )
        }
        return acc
    }

    fun applyHttpEvents(
        initial: ReducedSessionState,
        events: List<SessionEventDto>,
        json: Json = NetworkModule.defaultJson,
    ): ReducedSessionState {
        var acc = initial
        for (ev in events.sortedBy { it.seq }) {
            acc = applyEvent(
                state = acc,
                seq = ev.seq,
                cursor = ev.cursor,
                type = ev.type,
                ts = ev.ts,
                payload = ev.payload,
                json = json,
            )
        }
        return acc
    }

    fun applyWsEvent(
        state: ReducedSessionState,
        frame: WsServerFrame.Event,
        json: Json = NetworkModule.defaultJson,
    ): ReducedSessionState {
        if (frame.sessionId != null && state.sessionId.isNotEmpty() && frame.sessionId != state.sessionId) {
            return state
        }
        return applyEvent(
            state = state,
            seq = frame.seq,
            cursor = frame.cursor,
            type = frame.type,
            ts = frame.ts,
            payload = frame.payload,
            json = json,
        )
    }

    fun applyEvent(
        state: ReducedSessionState,
        seq: Long,
        cursor: Long,
        type: String,
        ts: String,
        payload: JsonObject,
        json: Json = NetworkModule.defaultJson,
    ): ReducedSessionState {
        if (seq > 0L && seq <= state.lastSeq) {
            return state.copy(lastCursor = maxOf(state.lastCursor, cursor))
        }

        val nextSeq = maxOf(state.lastSeq, seq)
        val nextCursor = maxOf(state.lastCursor, cursor)
        var base = state.copy(lastSeq = nextSeq, lastCursor = nextCursor)

        return when (type) {
            "session_started" -> {
                val snapEl = payload["snapshot"]
                if (snapEl != null) {
                    val snap = runCatching { json.decodeFromJsonElement<SessionSnapshotDto>(snapEl) }.getOrNull()
                    if (snap != null) {
                        base = mergeSnapshot(base, snap).copy(lastSeq = nextSeq, lastCursor = nextCursor)
                    }
                }
                base
            }

            "session_status" -> {
                val newStatus = payload["status"]?.jsonPrimitive?.contentOrNull ?: base.status
                val reason = payload["reason"]?.jsonPrimitive?.contentOrNull
                val settledLedger = if (newStatus == "idle" || newStatus == "closed" || newStatus == "error" || newStatus == "running") {
                    settleStreamingItems(base.ledger)
                } else {
                    base.ledger
                }
                val updatedLedger = if (newStatus == "error" && !reason.isNullOrBlank()) {
                    settledLedger + LedgerItem.SystemNotice(
                        id = "notice_$nextSeq",
                        seq = nextSeq,
                        ts = ts,
                        label = "PROCESS ERROR",
                        message = reason,
                        isError = true,
                    )
                } else {
                    settledLedger
                }
                base.copy(
                    status = newStatus,
                    isLive = newStatus != "closed" && newStatus != "archived" && newStatus != "error",
                    ledger = updatedLedger,
                )
            }

            "session_info_update" -> {
                val newTitle = payload["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: base.title
                val newSource = payload["title_source"]?.jsonPrimitive?.contentOrNull ?: base.titleSource
                base.copy(title = newTitle, titleSource = newSource)
            }

            "user_message_chunk" -> {
                val chunkText = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val attachmentLabels = parseAttachmentLabels(payload)
                val settled = settleStreamingItems(base.ledger).toMutableList()
                val last = settled.lastOrNull()
                if (last is LedgerItem.UserTurn && last.ts == ts && ts.isNotEmpty()) {
                    val mergedLabels = if (attachmentLabels.isEmpty()) {
                        last.attachmentLabels
                    } else {
                        (last.attachmentLabels + attachmentLabels).distinct()
                    }
                    settled[settled.lastIndex] = last.copy(
                        text = last.text + chunkText,
                        attachmentLabels = mergedLabels,
                    )
                } else {
                    settled += LedgerItem.UserTurn(
                        id = "user_$nextSeq",
                        seq = nextSeq,
                        ts = ts,
                        text = chunkText,
                        attachmentLabels = attachmentLabels,
                    )
                }
                base.copy(ledger = settled)
            }

            "agent_thought_chunk" -> {
                val chunkText = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val list = base.ledger.toMutableList()
                val last = list.lastOrNull()
                if (last is LedgerItem.ThoughtBlock && last.isStreaming) {
                    list[list.lastIndex] = last.copy(text = last.text + chunkText)
                } else {
                    val settled = settleStreamingItems(list).toMutableList()
                    settled += LedgerItem.ThoughtBlock(
                        id = "thought_$nextSeq",
                        seq = nextSeq,
                        ts = ts,
                        text = chunkText,
                        isStreaming = true,
                    )
                    return base.copy(ledger = settled)
                }
                base.copy(ledger = list)
            }

            "agent_message_chunk" -> {
                val chunkText = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val list = base.ledger.toMutableList()
                val last = list.lastOrNull()
                if (last is LedgerItem.AgentProse && last.isStreaming) {
                    list[list.lastIndex] = last.copy(text = last.text + chunkText)
                } else {
                    val settled = settleStreamingItems(list).toMutableList()
                    settled += LedgerItem.AgentProse(
                        id = "prose_$nextSeq",
                        seq = nextSeq,
                        ts = ts,
                        text = chunkText,
                        isStreaming = true,
                    )
                    return base.copy(ledger = settled)
                }
                base.copy(ledger = list)
            }

            "tool_call" -> {
                val settled = settleStreamingItems(base.ledger).toMutableList()
                val toolId = payload["tool_call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifEmpty { "tc_$nextSeq" }
                val title = payload["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = payload["kind"]?.jsonPrimitive?.contentOrNull ?: "other"
                val status = payload["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                val rawInput = payload["raw_input"] as? JsonObject ?: JsonObject(emptyMap())
                val diff = DiffEngine.extractFromToolPayload(rawInput, null, payload["diff"])
                val (isSwarm, workerName) = detectSubagentWorker(kind, title, rawInput)

                val existingIdx = settled.indexOfFirst {
                    it is LedgerItem.ToolCallBlock && it.toolCallId == toolId
                }
                val item = LedgerItem.ToolCallBlock(
                    id = if (existingIdx >= 0) settled[existingIdx].id else "tool_${toolId}_$nextSeq",
                    seq = if (existingIdx >= 0) settled[existingIdx].seq else nextSeq,
                    ts = ts,
                    toolCallId = toolId,
                    title = title.ifBlank { kind },
                    kind = kind,
                    status = status,
                    rawInputSummary = formatJsonCompact(rawInput),
                    rawOutputSummary = (settled.getOrNull(existingIdx) as? LedgerItem.ToolCallBlock)?.rawOutputSummary.orEmpty(),
                    parsedDiff = diff ?: (settled.getOrNull(existingIdx) as? LedgerItem.ToolCallBlock)?.parsedDiff,
                    isSubagentWorker = isSwarm,
                    workerLabel = workerName,
                )
                if (existingIdx >= 0) {
                    settled[existingIdx] = item
                } else {
                    settled += item
                }
                base.copy(
                    ledger = settled,
                    swarmWorkers = extractSwarmBadges(settled),
                )
            }

            "tool_call_update" -> {
                val list = base.ledger.toMutableList()
                val toolId = payload["tool_call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifEmpty { "tc_$nextSeq" }
                val status = payload["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = payload["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = payload["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rawOutput = payload["raw_output"] as? JsonObject
                val explicitDiff: JsonElement? = payload["diff"]

                val existingIdx = list.indexOfFirst {
                    it is LedgerItem.ToolCallBlock && it.toolCallId == toolId
                }
                if (existingIdx >= 0) {
                    val prev = list[existingIdx] as LedgerItem.ToolCallBlock
                    val newDiff = DiffEngine.extractFromToolPayload(null, rawOutput, explicitDiff) ?: prev.parsedDiff
                    val newOut = if (rawOutput != null && rawOutput.isNotEmpty()) {
                        formatJsonCompact(rawOutput)
                    } else {
                        prev.rawOutputSummary
                    }
                    val mergedTitle = title.ifBlank { prev.title }
                    val mergedKind = kind.ifBlank { prev.kind }
                    val (isSwarm, workerName) = detectSubagentWorker(mergedKind, mergedTitle, null)
                    list[existingIdx] = prev.copy(
                        status = status.ifBlank { prev.status },
                        title = mergedTitle,
                        kind = mergedKind,
                        rawOutputSummary = newOut,
                        parsedDiff = newDiff,
                        isSubagentWorker = prev.isSubagentWorker || isSwarm,
                        workerLabel = prev.workerLabel ?: workerName,
                    )
                } else {
                    val newDiff = DiffEngine.extractFromToolPayload(null, rawOutput, explicitDiff)
                    val effKind = kind.ifBlank { "tool" }
                    val effTitle = title.ifBlank { effKind }
                    val (isSwarm, workerName) = detectSubagentWorker(effKind, effTitle, null)
                    list += LedgerItem.ToolCallBlock(
                        id = "tool_${toolId}_$nextSeq",
                        seq = nextSeq,
                        ts = ts,
                        toolCallId = toolId,
                        title = effTitle,
                        kind = effKind,
                        status = status.ifBlank { "completed" },
                        rawInputSummary = "",
                        rawOutputSummary = rawOutput?.takeIf { it.isNotEmpty() }?.let(::formatJsonCompact).orEmpty(),
                        parsedDiff = newDiff,
                        isSubagentWorker = isSwarm,
                        workerLabel = workerName,
                    )
                }
                base.copy(
                    ledger = list,
                    swarmWorkers = extractSwarmBadges(list),
                )
            }

            "usage_update" -> {
                val used = payload["used"]?.jsonPrimitive?.longOrNull ?: base.usage.used
                val size = payload["size"]?.jsonPrimitive?.longOrNull ?: base.usage.size
                base.copy(usage = UsageDto(used = used, size = size))
            }

            "config_option_update" -> {
                val optsEl = payload["config_options"]
                val opts = if (optsEl != null) {
                    runCatching { json.decodeFromJsonElement<List<ConfigOptionDto>>(optsEl) }
                        .getOrDefault(base.configOptions)
                } else {
                    base.configOptions
                }
                base.copy(configOptions = opts)
            }

            "available_commands_update" -> {
                val cmdsEl = payload["commands"]
                val cmds = if (cmdsEl != null) {
                    runCatching { json.decodeFromJsonElement<List<AvailableCommandDto>>(cmdsEl) }
                        .getOrDefault(base.availableCommands)
                } else {
                    base.availableCommands
                }
                base.copy(availableCommands = cmds)
            }

            "permission_requested" -> {
                val reqId = payload["request_id"]?.jsonPrimitive?.contentOrNull ?: return base
                val tc = payload["tool_call"] as? JsonObject ?: JsonObject(emptyMap())
                val opts = payload["options"]?.let {
                    runCatching { json.decodeFromJsonElement<List<PermissionOptionDto>>(it) }.getOrNull()
                }.orEmpty()
                val dto = PendingPermissionDto(
                    requestId = reqId,
                    toolCall = tc,
                    options = opts,
                    createdAt = ts,
                )
                val updated = base.pendingPermissions.filterNot { it.requestId == reqId } + dto
                base.copy(
                    status = "awaiting_approval",
                    pendingPermissions = updated,
                )
            }

            "permission_auto_approved" -> {
                val tc = payload["tool_call"] as? JsonObject
                val toolTitle = tc?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: tc?.get("kind")?.jsonPrimitive?.contentOrNull
                    ?: "tool"
                val ledger = base.ledger + LedgerItem.SystemNotice(
                    id = "auto_perm_$nextSeq",
                    seq = nextSeq,
                    ts = ts,
                    label = "WORKSPACE RULE",
                    message = "Auto-approved $toolTitle",
                    isError = false,
                )
                base.copy(ledger = ledger)
            }

            "permission_resolved" -> {
                val reqId = payload["request_id"]?.jsonPrimitive?.contentOrNull ?: return base
                val remaining = base.pendingPermissions.filterNot { it.requestId == reqId }
                base.copy(pendingPermissions = remaining)
            }

            "question_requested" -> {
                val reqId = payload["request_id"]?.jsonPrimitive?.contentOrNull ?: return base
                val mode = payload["mode"]?.jsonPrimitive?.contentOrNull ?: "form"
                val message = payload["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val schema = payload["requested_schema"] as? JsonObject ?: JsonObject(emptyMap())
                val dto = PendingQuestionDto(
                    requestId = reqId,
                    mode = mode,
                    message = message,
                    requestedSchema = schema,
                    createdAt = ts,
                )
                val updated = base.pendingQuestions.filterNot { it.requestId == reqId } + dto
                base.copy(
                    status = "awaiting_question",
                    pendingQuestions = updated,
                )
            }

            "question_resolved" -> {
                val reqId = payload["request_id"]?.jsonPrimitive?.contentOrNull ?: return base
                val remaining = base.pendingQuestions.filterNot { it.requestId == reqId }
                base.copy(pendingQuestions = remaining)
            }

            "queue_updated" -> {
                val itemsEl = payload["items"]
                val items = if (itemsEl != null) {
                    runCatching { json.decodeFromJsonElement<List<QueueItemDto>>(itemsEl) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val paused = payload["paused"]?.jsonPrimitive?.booleanOrNull ?: base.queuePaused
                base.copy(
                    queuedFollowups = items,
                    queuePaused = paused,
                )
            }

            "turn_completed" -> {
                val usageObj = payload["usage"] as? JsonObject
                val used = usageObj?.get("used")?.jsonPrimitive?.longOrNull
                val size = usageObj?.get("size")?.jsonPrimitive?.longOrNull
                val nextUsage = if (used != null && size != null) {
                    UsageDto(used = used, size = size)
                } else {
                    base.usage
                }
                base.copy(
                    ledger = settleStreamingItems(base.ledger),
                    usage = nextUsage,
                )
            }

            "session_closed" -> {
                base.copy(
                    isLive = false,
                    status = "closed",
                    pendingPermissions = emptyList(),
                    pendingQuestions = emptyList(),
                    ledger = settleStreamingItems(base.ledger),
                )
            }

            else -> base
        }
    }

    private fun settleStreamingItems(items: List<LedgerItem>): List<LedgerItem> {
        if (items.none {
                (it is LedgerItem.ThoughtBlock && it.isStreaming) ||
                    (it is LedgerItem.AgentProse && it.isStreaming)
            }
        ) {
            return items
        }
        return items.map { item ->
            when {
                item is LedgerItem.ThoughtBlock && item.isStreaming -> item.copy(isStreaming = false)
                item is LedgerItem.AgentProse && item.isStreaming -> item.copy(isStreaming = false)
                else -> item
            }
        }
    }

    private fun detectSubagentWorker(
        kind: String,
        title: String,
        rawInput: JsonObject?,
    ): Pair<Boolean, String?> {
        val hay = "${kind.lowercase()} ${title.lowercase()}"
        val explicitAgent = rawInput?.let { obj ->
            listOf("agent", "subagent", "worker", "assignee", "role")
                .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        }
        val matched = explicitAgent != null || swarmKeywords.any { kw -> hay.contains(kw) }
        if (!matched) return false to null

        val label = explicitAgent
            ?: title.substringAfter(':').trim().take(18).ifBlank { kind.take(14) }
        return true to label
    }

    private fun extractSwarmBadges(ledger: List<LedgerItem>): List<SwarmWorkerBadge> {
        return ledger.mapNotNull { item ->
            if (item is LedgerItem.ToolCallBlock && item.isSubagentWorker) {
                SwarmWorkerBadge(
                    toolCallId = item.toolCallId,
                    label = item.workerLabel ?: item.title.take(16),
                    status = item.status,
                    ledgerItemId = item.id,
                )
            } else {
                null
            }
        }
    }

    internal fun formatJsonCompact(obj: JsonObject): String {
        if (obj.isEmpty()) return ""
        val cmd = listOf("CommandLine", "command", "cmd")
            .firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        if (cmd != null && obj.size <= 3) {
            val extra = obj.entries
                .filter { it.key !in setOf("CommandLine", "command", "cmd") }
                .joinToString("\n") { (k, v) -> "$k: ${ renderJsonValue(v) }" }
            return if (extra.isEmpty()) "$ $cmd" else "$ $cmd\n$extra"
        }
        return obj.entries.joinToString("\n") { (k, v) -> "$k: ${renderJsonValue(v)}" }
    }

    private fun parseAttachmentLabels(payload: JsonObject): List<String> = runCatching {
        val raw = payload["attachments"] as? JsonArray ?: return@runCatching emptyList()
        raw.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            listOf("filename", "name", "label", "attachment_id")
                .firstNotNullOfOrNull { key ->
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }
        }
    }.getOrElse { emptyList() }

    private fun renderJsonValue(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        is JsonArray -> el.joinToString(", ", "[", "]") { renderJsonValue(it) }
        is JsonObject -> el.toString()
    }
}
