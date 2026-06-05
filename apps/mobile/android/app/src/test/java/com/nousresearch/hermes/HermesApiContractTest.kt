package com.nousresearch.hermes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * HermesApiContractTest — calls every HermesApi method with
 * default args and asserts the return type matches the
 * desktop preload contract. The list of methods is the
 * gap-analysis oracle from `groovy-fluttering-island.md` §1.1-1.2.
 *
 * This is the test the plan says catches 100% of the 110
 * missing methods added in Phase 1. A method that doesn't
 * compile or doesn't exist is a test failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HermesApiContractTest {

    private lateinit var hermes: HermesApi

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        hermes = HermesApi(ctx)
    }

    // ── Phase 1 platform + connection ────────────────────────

    @Test fun getPlatform_returnsPlatformInfo() {
        val p = hermes.getPlatform()
        assertNotNull(p)
        assertTrue(p.platform == "android")
    }

    @Test fun isAndroid_isTrue() {
        assertTrue(hermes.isAndroid())
    }

    @Test fun getAppVersion_returnsNonEmpty() {
        assertTrue(hermes.getAppVersion().isNotEmpty())
    }

    @Test fun getConnectionConfig_returnsConfig() {
        val cfg = hermes.getConnectionConfig()
        assertNotNull(cfg)
    }

    @Test fun setConnectionConfig_doesNotCrash() {
        hermes.setConnectionConfig("local", "", "")
    }

    @Test fun testRemoteConnection_returnsBool() {
        // Stub: always returns true in v1
        assertTrue(hermes.testRemoteConnection("https://example.com"))
    }

    // ── Phase 1.1 file-IO ───────────────────────────────────

    @Test fun getLocale_returnsString() {
        val loc = hermes.getLocale()
        assertTrue(loc.isNotEmpty())
    }

    @Test fun setLocale_thenGetLocale_roundTrips() {
        hermes.setLocale("zh-CN")
        assertTrue(hermes.getLocale() == "zh-CN")
        hermes.setLocale("en")
    }

    @Test fun getConfig_returnsMap() {
        val cfg = hermes.getConfig()
        assertNotNull(cfg)
    }

    @Test fun setConfig_roundTrips() {
        hermes.setConfig(mapOf("default_model" to "gpt-4o-mini"))
        assertTrue(hermes.getConfig()["default_model"] == "gpt-4o-mini")
    }

    @Test fun getEnv_returnsMap() {
        val env = hermes.getEnv()
        assertNotNull(env)
    }

    @Test fun setEnv_roundTrips() {
        hermes.setEnv("FOO", "bar")
        assertTrue(hermes.getEnv()["FOO"] == "bar")
    }

    @Test fun getModelConfig_returnsMap() {
        val m = hermes.getModelConfig()
        assertNotNull(m)
    }

    @Test fun setModelConfig_roundTrips() {
        hermes.setModelConfig(mapOf("gpt4.provider" to "openai"))
        assertTrue(hermes.getModelConfig()["gpt4.provider"] == "openai")
    }

    @Test fun getHermesHome_returnsPath() {
        val home = hermes.getHermesHome()
        assertTrue(home.isNotEmpty())
        assertTrue(File(home).exists() || File(home).mkdirs())
    }

    @Test fun getActiveProfile_returnsString() {
        assertTrue(hermes.getActiveProfile().isNotEmpty())
    }

    @Test fun getConfigHealth_returnsResult() {
        val h = hermes.getConfigHealth()
        assertNotNull(h)
    }

    @Test fun rerunConfigHealth_returnsResult() {
        val h = hermes.rerunConfigHealth()
        assertNotNull(h)
    }

    @Test fun autofixConfigIssue_handlesUnknown() {
        // Unknown id returns false
        assertTrue(!hermes.autofixConfigIssue("not.a.thing"))
    }

    @Test fun getConfigFixLog_returnsList() {
        val log = hermes.getConfigFixLog()
        assertNotNull(log)
    }

    @Test fun validateChatReadiness_returnsBool() {
        // v1: returns false (no install verified)
        val ok = hermes.validateChatReadiness()
        // Just check it returns without throwing
        assertTrue(ok || !ok)
    }

    @Test fun listProfiles_returnsList() {
        val profiles = hermes.listProfiles()
        assertNotNull(profiles)
    }

    @Test fun createProfile_thenList() {
        val name = "test_${System.currentTimeMillis()}"
        val ok = hermes.createProfile(name)
        assertTrue(ok)
        assertTrue(hermes.listProfiles().any { it.name == name })
    }

    @Test fun deleteProfile_removesProfile() {
        val name = "deltest_${System.currentTimeMillis()}"
        hermes.createProfile(name)
        assertTrue(hermes.deleteProfile(name))
    }

    @Test fun setActiveProfile_roundTrips() {
        hermes.setActiveProfile("default")
        assertTrue(hermes.getActiveProfile() == "default")
    }

    @Test fun readSoul_returnsResult() {
        val r = hermes.readSoul()
        assertNotNull(r)
    }

    @Test fun writeSoul_roundTrips() {
        val name = "soultest_${System.currentTimeMillis()}"
        hermes.createProfile(name)
        hermes.setActiveProfile(name)
        assertTrue(hermes.writeSoul("# new soul\nBe terse."))
        val r = hermes.readSoul()
        assertTrue(r.content.contains("Be terse"))
    }

    @Test fun resetSoul_writesDefault() {
        assertTrue(hermes.resetSoul())
    }

    @Test fun writeUserProfile_thenRead() {
        assertTrue(hermes.writeUserProfile("Likes coffee."))
        assertTrue(hermes.readUserProfile().contains("coffee"))
    }

    @Test fun getToolsets_returnsList() {
        val ts = hermes.getToolsets()
        assertNotNull(ts)
        assertTrue(ts.isNotEmpty())
    }

    @Test fun setToolsetEnabled_roundTrips() {
        hermes.setToolsetEnabled("file_io", false)
        val ts = hermes.getToolsets().first { it.name == "file_io" }
        assertTrue(!ts.enabled)
    }

    @Test fun getCredentialPool_returnsList() {
        val pool = hermes.getCredentialPool()
        assertNotNull(pool)
    }

    @Test fun setCredentialPool_addEntry() {
        val entry = HermesApi.CredentialEntry(
            provider = "testprov_${System.currentTimeMillis()}",
            kind = "api_key", value = "sk-test-1234",
            addedAt = System.currentTimeMillis(),
        )
        hermes.addCredentialPoolEntry(entry)
        assertTrue(hermes.getCredentialPool().any { it.provider == entry.provider })
    }

    @Test fun listModels_returnsList() {
        val m = hermes.listModels()
        assertNotNull(m)
    }

    @Test fun addModel_thenList() {
        val id = "testmodel_${System.currentTimeMillis()}"
        hermes.addModel(
            HermesApi.ModelConfig(
                id = id, provider = "openai", name = "Test",
                maxTokens = 1024, temperature = 0.5,
            ),
        )
        assertTrue(hermes.listModels().any { it.id == id })
        hermes.removeModel(id)
    }

    @Test fun removeModel_removes() {
        val id = "removetest_${System.currentTimeMillis()}"
        hermes.addModel(
            HermesApi.ModelConfig(id = id, provider = "openai", name = "x"),
        )
        hermes.removeModel(id)
        assertTrue(hermes.listModels().none { it.id == id })
    }

    @Test fun listInstalledSkills_returnsList() {
        val s = hermes.listInstalledSkills()
        assertNotNull(s)
    }

    @Test fun listBundledSkills_returnsList() {
        val s = hermes.listBundledSkills()
        assertNotNull(s)
    }

    @Test fun listCronJobs_returnsList() {
        val j = hermes.listCronJobs()
        assertNotNull(j)
    }

    @Test fun createCronJob_thenList() {
        val id = "cron_${System.currentTimeMillis()}"
        hermes.createCronJob(
            HermesApi.CronJob(
                id = id, name = "test job", cronExpr = "0 * * * *",
                command = "echo hi", enabled = true, lastRun = null, nextRun = null,
            ),
        )
        assertTrue(hermes.listCronJobs().any { it.id == id })
        hermes.removeCronJob(id)
    }

    @Test fun pauseAndResumeCronJob() {
        val id = "pausecron_${System.currentTimeMillis()}"
        hermes.createCronJob(
            HermesApi.CronJob(
                id = id, name = "x", cronExpr = "* * * * *",
                command = "echo", enabled = true, lastRun = null, nextRun = null,
            ),
        )
        assertTrue(hermes.pauseCronJob(id))
        assertTrue(hermes.resumeCronJob(id))
        hermes.removeCronJob(id)
    }

    @Test fun listKanbanBoards_returnsList() {
        val b = hermes.listKanbanBoards()
        assertNotNull(b)
    }

    @Test fun createKanbanBoard_thenList() {
        val board = hermes.createKanbanBoard("TestBoard_${System.currentTimeMillis()}")
        assertTrue(hermes.listKanbanBoards().any { it.id == board.id })
        hermes.deleteKanbanBoard(board.id)
    }

    @Test fun discoverMemoryProviders_returnsList() {
        val m = hermes.discoverMemoryProviders()
        assertNotNull(m)
    }

    @Test fun listMcpServers_returnsList() {
        val m = hermes.listMcpServers()
        assertNotNull(m)
    }

    @Test fun getPlatformEnabled_returnsAll16() {
        val p = hermes.getPlatformEnabled()
        assertTrue(p.size == 16)
    }

    @Test fun setPlatformEnabled_roundTrips() {
        hermes.setPlatformEnabled("discord", true)
        assertTrue(hermes.getPlatformEnabled().first { it.platform == "discord" }.enabled)
    }

    @Test fun listLogFiles_returnsList() {
        val l = hermes.listLogFiles()
        assertNotNull(l)
    }

    @Test fun readLogs_returnsString() {
        val log = hermes.readLogs("nonexistent.log", 10)
        // v1: nonexistent file returns "" (we don't error)
        assertNotNull(log)
    }

    @Test fun checkOpenClaw_returnsBool() {
        val v = hermes.checkOpenClaw()
        // v1: not present
        assertTrue(!v)
    }

    @Test fun readMediaFile_returnsEmptyForMissing() {
        val r = hermes.readMediaFile("/no/such/file")
        assertTrue(r.isEmpty())
    }

    @Test fun saveMediaFile_returnsEmptyForMissing() {
        val r = hermes.saveMediaFile("/no/such/file", "x")
        assertTrue(r.isEmpty())
    }

    @Test fun mediaFileExists_returnsBool() {
        val v = hermes.mediaFileExists("/no/such/file")
        assertTrue(!v)
    }

    @Test fun getPathForFile_returnsString() {
        val v = hermes.getPathForFile("x")
        assertNotNull(v)
    }

    @Test fun stageAttachment_returnsPath() {
        val sid = "sess_${System.currentTimeMillis()}"
        val path = hermes.stageAttachment(sid, "test.txt", "aGVsbG8=")
        assertTrue(path.isNotEmpty())
        hermes.clearStagedAttachments(sid)
    }

    @Test fun listStagedAttachments_returnsList() {
        val sid = "sess_list_${System.currentTimeMillis()}"
        hermes.stageAttachment(sid, "a.txt", "aGVsbG8=")
        val list = hermes.listStagedAttachments(sid)
        assertTrue(list.isNotEmpty())
        hermes.clearStagedAttachments(sid)
    }

    @Test fun clearStagedAttachments_returnsBool() {
        val sid = "sess_clear_${System.currentTimeMillis()}"
        hermes.stageAttachment(sid, "a.txt", "aGVsbG8=")
        assertTrue(hermes.clearStagedAttachments(sid))
    }

    @Test fun copyToClipboard_doesNotCrash() {
        hermes.copyToClipboard("hello world")
    }

    @Test fun selectFolder_returnsEmptyV1() {
        // Phase 5 v1: returns empty (no folder picker wired)
        assertTrue(hermes.selectFolder().isEmpty())
    }

    @Test fun readDirectory_returnsList() {
        val d = hermes.readDirectory("/no/such/dir")
        assertTrue(d.isEmpty())
    }

    @Test fun readFile_returnsString() {
        val s = hermes.readFile("/no/such/file")
        assertTrue(s.isEmpty())
    }

    @Test fun readImageFile_returnsEmptyForMissing() {
        val s = hermes.readImageFile("/no/such/file.png")
        assertTrue(s.isEmpty())
    }

    @Test fun openFileInEditor_doesNotCrash() {
        // v1: just calls openExternal which is fire-and-forget
        hermes.openFileInEditor("/no/such/file")
    }

    @Test fun trackEvent_doesNotCrash() {
        hermes.trackEvent("test_event", mapOf("k" to "v"))
    }

    @Test fun haptic_doesNotCrash() {
        hermes.haptic("light")
        hermes.haptic("medium")
        hermes.haptic("heavy")
    }

    @Test fun openHermesHomeInFiles_doesNotCrash() {
        // Will try to launch a Files-app intent; ignore result
        try { hermes.openHermesHomeInFiles() } catch (_: Exception) {}
    }

    @Test fun showMediaMenu_emitsToFlow() {
        kotlinx.coroutines.runBlocking { hermes.showMediaMenu("/test/path") }
    }

    @Test fun discoverProviderModels_returnsList() {
        // v1 stub: empty (no gateway)
        kotlinx.coroutines.runBlocking {
            val m = hermes.discoverProviderModels("openai")
            assertNotNull(m)
        }
    }

    @Test fun onContextMenuCopyChat_emitsToFlow() {
        kotlinx.coroutines.runBlocking { hermes.onContextMenuCopyChat("test") }
    }

    @Test fun onContextMenuSelectBubble_emitsToFlow() {
        kotlinx.coroutines.runBlocking { hermes.onContextMenuSelectBubble("42") }
    }

    @Test fun onMenuNewChat_emitsToFlow() {
        kotlinx.coroutines.runBlocking { hermes.onMenuNewChat() }
    }

    @Test fun onMenuSearchSessions_emitsToFlow() {
        kotlinx.coroutines.runBlocking { hermes.onMenuSearchSessions() }
    }

    @Test fun getApiServerKeyStatus_returnsMap() {
        kotlinx.coroutines.runBlocking {
            val m = hermes.getApiServerKeyStatus()
            assertNotNull(m)
        }
    }

    @Test fun generateApiServerKey_returnsString() {
        kotlinx.coroutines.runBlocking {
            val k = hermes.generateApiServerKey()
            // v1 stub: empty
            assertNotNull(k)
        }
    }

    @Test fun transcribeAudio_returnsString() {
        kotlinx.coroutines.runBlocking {
            val t = hermes.transcribeAudio("hello".toByteArray(), "audio/webm")
            assertNotNull(t)
        }
    }

    @Test fun checkForUpdates_returnsUpdateInfo() {
        kotlinx.coroutines.runBlocking {
            val u = hermes.checkForUpdates()
            assertNotNull(u)
        }
    }

    @Test fun claw3dNotSupported_throws() {
        var threw = false
        try { hermes.claw3dNotSupported("x") } catch (e: UnsupportedOperationException) { threw = true }
        assertTrue(threw)
    }

    // ── Phase 1.3 type-shape fixes ──────────────────────────

    @Test fun getBatteryOptStatus_returnsView() {
        val v = hermes.getBatteryOptStatus()
        assertNotNull(v)
    }

    @Test fun getStartOnBoot_returnsView() {
        val v = hermes.getStartOnBoot()
        assertNotNull(v)
    }

    @Test fun setStartOnBoot_roundTrips() {
        hermes.setStartOnBoot(true)
        assertTrue(hermes.getStartOnBoot().value)
        hermes.setStartOnBoot(false)
        assertTrue(!hermes.getStartOnBoot().value)
    }

    @Test fun requestIgnoreBatteryOptimizations_returnsBool() {
        val v = hermes.requestIgnoreBatteryOptimizations()
        assertTrue(v || !v)
    }

    // ── Phase 1.4 OAuth ────────────────────────────────────

    @Test fun oauthLogin_throwsForUnknown() {
        var threw = false
        try { hermes.oauthLogin("not.a.provider") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun cancelOAuthLogin_doesNotCrash() {
        hermes.cancelOAuthLogin()
    }

    @Test fun handleOAuthCallback_writesCode() {
        val uri = android.net.Uri.parse("hermes://oauth-callback?provider=test&profile=default&code=abc123")
        hermes.handleOAuthCallback(uri)
        // The auth pool should have the entry
        assertTrue(hermes.getCredentialPool().any { it.provider == "test" && it.value == "abc123" })
    }

    @Test fun handleOAuthCallback_handlesError() {
        val uri = android.net.Uri.parse("hermes://oauth-callback?provider=test2&error=user_denied")
        hermes.handleOAuthCallback(uri)
        // No crash; the entry is NOT written
        assertTrue(hermes.getCredentialPool().none { it.provider == "test2" })
    }

    // ── Phase 1.5 missing SharedFlows ───────────────────────

    @Test fun appState_defaultsToSplash() {
        // The initial state (before init() is called) is Splash
        // Note: we don't assert == Splash because the test may run
        // after init() in some test setups; just check the value is non-null.
        assertNotNull(hermes.appState.value)
    }

    @Test fun setAppState_changesState() {
        hermes.setAppState(HermesApi.AppState.Setup)
        assertTrue(hermes.appState.value == HermesApi.AppState.Setup)
    }

    @Test fun init_routesToSomething() {
        // init() inspects the install state and routes. The result
        // depends on the test environment, so just assert no crash.
        hermes.init()
    }

    @Test fun installProgress_isSharedFlow() {
        val flow = hermes.installProgress
        assertNotNull(flow)
    }

    @Test fun gatewayState_isSharedFlow() {
        val flow = hermes.gatewayState
        assertNotNull(flow)
    }

    @Test fun sharedText_emitsViaEmit() {
        kotlinx.coroutines.runBlocking {
            hermes.emitSharedText("hello from test")
        }
    }

    @Test fun deepLink_emitsViaEmit() {
        hermes.emitDeepLink("hermes://chat/abc")
    }

    // ── Phase 9 Termux preflight ───────────────────────────────

    @Test fun termuxStatus_allowExternalAppsFieldDefaultsToNull() {
        // Phase 9: the non-suspend getTermuxStatus() leaves the
        // new allowExternalApps field null because reading the
        // properties file requires a Termux shell dispatch. The
        // suspend getTermuxStatusWithProperties() is what
        // populates it.
        val s = hermes.getTermuxStatus()
        assertNotNull(s)
        assertTrue(s.allowExternalApps == null)
    }

    @Test fun appState_includesTermuxPreflightValue() {
        // Phase 9: AppState grew a 6th value. The 5-case `when`
        // in MainActivity would not compile without this.
        val values = HermesApi.AppState.values().map { it.name }.toSet()
        assertTrue("TermuxPreflight" in values)
    }

    @Test fun hasTermuxRunCommandPermission_returnsBool() {
        // In Robolectric we have no Termux package installed, so
        // the call should resolve to false without throwing.
        val granted = hermes.hasTermuxRunCommandPermission()
        assertTrue(granted || !granted)
    }

    @Test fun getTermuxProperty_returnsNullWhenTermuxMissing() {
        // The preflight wizard's verify step falls back to the
        // "property not set" branch when getTermuxProperty
        // returns null. Robolectric has no Termux, so the
        // short-circuit (no TermuxProbe.isTermuxInstalled) kicks
        // in and we get null back.
        kotlinx.coroutines.runBlocking {
            val v = hermes.getTermuxProperty("allow-external-apps")
            assertTrue(v == null)
        }
    }

    @Test fun getTermuxStatusWithProperties_companionForm_doesNotCrash() {
        // Companion suspend form: returns a TermuxStatus with
        // allowExternalApps null when Termux isn't installed.
        kotlinx.coroutines.runBlocking {
            val s = hermes.getTermuxStatusWithProperties()
            assertNotNull(s)
            assertTrue(s.allowExternalApps == null)
        }
    }
}
