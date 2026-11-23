package us.snakepit.gravitywell.ui.deck

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.snakepit.gravitywell.cache.CachedHost
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.ui.sessions.SessionSupplementaryApi

class ControlDeckControllerTest {

    @Test
    fun `validation enforces agent slug pattern and strict 2-key settings allowlist`() {
        assertTrue(ControlDeckValidation.isValidAgentSlug("code-auditor"))
        assertTrue(ControlDeckValidation.isValidAgentSlug("sec_agent_01"))
        assertFalse(ControlDeckValidation.isValidAgentSlug(""))
        assertFalse(ControlDeckValidation.isValidAgentSlug("UpperCase"))
        assertFalse(ControlDeckValidation.isValidAgentSlug("-flag-inject"))
        assertFalse(ControlDeckValidation.isValidAgentSlug("../escape"))
        assertFalse(ControlDeckValidation.isValidAgentSlug("a".repeat(65)))

        val previewPatch = ControlDeckValidation.buildAllowlistedSettingsPatch(
            ControlDeckValidation.KEY_PREVIEW_FEATURES,
            true,
        )
        assertEquals("""{"general":{"previewFeatures":true}}""", previewPatch.toString())

        val completionPatch = ControlDeckValidation.buildAllowlistedSettingsPatch(
            ControlDeckValidation.KEY_ENABLE_PROMPT_COMPLETION,
            false,
        )
        assertEquals("""{"general":{"enablePromptCompletion":false}}""", completionPatch.toString())

        val forbiddenKeys = listOf(
            "security.auth",
            "security.enableYolo",
            "oauth.clientId",
            "general.unknownKey",
        )
        for (forbidden in forbiddenKeys) {
            val threw = runCatching {
                ControlDeckValidation.buildAllowlistedSettingsPatch(forbidden, true)
            }.isFailure
            assertTrue("Expected $forbidden to be rejected by client allowlist", threw)
        }
    }

    @Test
    fun `control deck controller executes mcp plugin agent rule settings and default model operations`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recordedCalls = CopyOnWriteArrayList<Triple<String, String, String>>()

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val method = request.method
                    val path = request.url.encodedPath
                    val body = request.body?.utf8().orEmpty()
                    recordedCalls += Triple(method, path, body)

                    return when {
                        method == "GET" && path == "/api/ecosystem/mcp" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "items": [
                                    {
                                      "name": "codegraph",
                                      "type": "stdio",
                                      "disabled": false,
                                      "status": "enabled",
                                      "command": "uvx",
                                      "args": ["codegraph-mcp"],
                                      "env": {"API_ID": "[REDACTED]"},
                                      "headers": {},
                                      "env_keys": ["API_ID"],
                                      "header_keys": []
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "POST" && path == "/api/ecosystem/mcp" -> MockResponse.Builder()
                            .code(201)
                            .body("""{"name":"docs-http","type":"http","disabled":false,"status":"enabled","server_url":"https://mcp.example.com"}""")
                            .build()

                        method == "POST" && path == "/api/ecosystem/mcp/codegraph/disable" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"name":"codegraph","disabled":true,"status":"disabled"}""")
                            .build()

                        method == "DELETE" && path == "/api/ecosystem/mcp/codegraph" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"deleted":true,"name":"codegraph"}""")
                            .build()

                        method == "GET" && path == "/api/ecosystem/plugins" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "items": [
                                    {"name": "lint-guard", "displayName": "Lint Guard", "version": "0.2.0", "enabled": true}
                                  ],
                                  "builtins": [
                                    {"name": "swarm", "display_name": "Swarm", "description": "Parallel subagents", "builtin": true, "enabled": true}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "POST" && path == "/api/ecosystem/plugins/validate" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"valid":true,"strict":true,"report":{"ok":true},"errors":""}""")
                            .build()

                        method == "POST" && path == "/api/ecosystem/plugins/install" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"installed":true,"target":"https://example.com/plugin.git","output":"Installed plugin"}""")
                            .build()

                        method == "POST" && path == "/api/ecosystem/plugins/lint-guard/disable" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"name":"lint-guard","enabled":false,"status":"disabled"}""")
                            .build()

                        method == "DELETE" && path == "/api/ecosystem/plugins/lint-guard" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"deleted":true,"name":"lint-guard"}""")
                            .build()

                        method == "GET" && path == "/api/ecosystem/agents" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "items": [
                                    {"name": "reviewer", "slug": "reviewer", "description": "Read-only auditor", "prompt": "Audit diffs.", "source": "antigravity-cli", "editable": true},
                                    {"name": "builtin-arch", "slug": "builtin-arch", "description": "System", "prompt": "Sys", "source": "gemini", "editable": false}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "POST" && path == "/api/ecosystem/agents" -> MockResponse.Builder()
                            .code(201)
                            .body("""{"name":"rust-pro","slug":"rust-pro","description":"Rust specialist","prompt":"Use cargo check.","source":"antigravity-cli","editable":true}""")
                            .build()

                        method == "DELETE" && path == "/api/ecosystem/agents/reviewer" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"deleted":true,"name":"reviewer"}""")
                            .build()

                        method == "GET" && path == "/api/permissions/rules" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "items": [
                                    {"rule_id": "rule_99", "workspace_root": "/home/jason/projects/gravity-well", "tool_kind": "execute", "command_prefix": "pytest"}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "DELETE" && path == "/api/permissions/rules/rule_99" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"revoked":true,"rule_id":"rule_99"}""")
                            .build()

                        method == "GET" && path == "/api/ecosystem/settings" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "settings": {"general": {"previewFeatures": false, "enablePromptCompletion": true}},
                                  "gateway_prefs": {"model": "gemini-4-neon", "thought_level": "high"},
                                  "allowlist": ["general.enablePromptCompletion", "general.previewFeatures"]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "PATCH" && path == "/api/ecosystem/settings" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "settings": {"general": {"previewFeatures": true, "enablePromptCompletion": true}},
                                  "gateway_prefs": {"model": "gemini-4-neon", "thought_level": "high"},
                                  "allowlist": ["general.enablePromptCompletion", "general.previewFeatures"]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "GET" && path == "/api/ecosystem/models" -> MockResponse.Builder()
                            .code(200)
                            .body(
                                """
                                {
                                  "default_model_id": "gemini-4-neon-high",
                                  "gateway_default_model": "gemini-4-neon",
                                  "gateway_default_thought_level": "high",
                                  "items": [
                                    {"model_id": "gemini-4-neon-high", "display_name": "Gemini 4 Neon", "family": "gemini-4-neon", "thought_level": "high", "is_default": true}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            .build()

                        method == "PUT" && path == "/api/ecosystem/models/default" -> MockResponse.Builder()
                            .code(200)
                            .body("""{"model":"claude-sonnet-5-5","thought_level":"medium"}""")
                            .build()

                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()

            val host = CachedHost(
                hostId = "h_deck",
                label = "aurora",
                baseUrl = server.url("/").toString(),
                defaultCwd = "/home/jason",
                isActive = true,
                createdAt = "2026-11-23T14:00:00Z",
                lastVerifiedAt = "2026-11-23T14:00:00Z",
            )
            val okHttp = NetworkModule.createOkHttpClient(tokenProvider = { "gw_deck_tok" })
            val ecoApi = EcosystemApi.create(server.url("/"), okHttp)
            val suppApi = SessionSupplementaryApi.create(server.url("/"), okHttp)

            val controller = ControlDeckController(
                host = host,
                ecosystemApi = ecoApi,
                supplementaryApi = suppApi,
                scope = scope,
            )

            controller.refreshAll()
            awaitCondition {
                !controller.state.value.isLoading &&
                    controller.state.value.mcpServers.isNotEmpty() &&
                    controller.state.value.plugins.size == 2
            }

            assertEquals("codegraph", controller.state.value.mcpServers.single().name)
            assertEquals(listOf("API_ID"), controller.state.value.mcpServers.single().envKeys)
            assertTrue(controller.state.value.promptCompletionEnabled)
            assertFalse(controller.state.value.previewFeaturesEnabled)

            // Disable MCP server & confirm-remove it
            controller.toggleMcpServerEnabled("codegraph", enabled = false)
            awaitCondition { controller.state.value.mcpServers.single().disabled }

            controller.requestRemoveMcpServer("codegraph")
            controller.confirmRemoveMcpServer()
            awaitCondition { controller.state.value.mcpServers.isEmpty() }

            // Validate & disable & uninstall plugin
            controller.updatePluginTargetDraft("https://example.com/plugin.git")
            controller.setPluginValidateStrict(true)
            controller.validatePluginTarget()
            awaitCondition { controller.state.value.lastPluginValidation?.valid == true }

            controller.togglePluginEnabled("lint-guard", enabled = false)
            awaitCondition {
                controller.state.value.plugins.first { it.name == "lint-guard" }.enabled == false
            }
            controller.requestUninstallPlugin("lint-guard")
            controller.confirmUninstallPlugin()
            awaitCondition { controller.state.value.plugins.none { it.name == "lint-guard" } }
            assertTrue("Builtin swarm plugin must remain", controller.state.value.plugins.any { it.name == "swarm" && it.builtin })

            // Create custom agent (reject bad slug first, then save valid slug)
            controller.openNewAgentSheet()
            controller.updateAgentForm { it.copy(slug = "Bad Slug!", prompt = "Prompt") }
            controller.submitAgentForm()
            assertNotNull(controller.state.value.errorBanner)
            assertFalse(recordedCalls.any { it.first == "POST" && it.second == "/api/ecosystem/agents" })

            controller.updateAgentForm { it.copy(slug = "rust-pro", description = "Rust", prompt = "Use cargo check.") }
            controller.submitAgentForm()
            awaitCondition { !controller.state.value.showAgentEditorSheet }
            assertTrue(recordedCalls.any { it.first == "POST" && it.second == "/api/ecosystem/agents" })

            // Revoke workspace allow-rule
            assertEquals(1, controller.state.value.permissionRules.size)
            controller.revokePermissionRule("rule_99")
            awaitCondition { controller.state.value.permissionRules.isEmpty() }

            // Patch allowlisted setting & reject disallowed setting locally
            controller.updateAllowlistedSetting("security.auth", true)
            assertFalse(recordedCalls.any { it.first == "PATCH" && it.third.contains("security") })

            controller.updateAllowlistedSetting(ControlDeckValidation.KEY_PREVIEW_FEATURES, true)
            awaitCondition { controller.state.value.previewFeaturesEnabled }

            // Set gateway default model
            controller.updateGatewayDefaultModel("claude-sonnet-5-5", "medium")
            awaitCondition { controller.state.value.gatewayDefaultModel == "claude-sonnet-5-5" }
            assertEquals("medium", controller.state.value.gatewayDefaultThoughtLevel)
        } finally {
            scope.cancel()
            server.close()
        }
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
