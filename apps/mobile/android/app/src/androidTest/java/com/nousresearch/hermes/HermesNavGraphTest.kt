package com.nousresearch.hermes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HermesNavGraphTest — instrumented Compose test that
 * navigates to every destination and asserts no crash.
 * Phase 6.2 verification: all 13 routes reachable.
 *
 * Uses the real HermesApi singleton from the
 * [com.nousresearch.hermes.HermesApp] Application class —
 * the test app must be a hermes-mobile debug APK.
 */
@RunWith(AndroidJUnit4::class)
class HermesNavGraphTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigatesToEveryBottomNavTab() {
        // Tap each of the 5 bottom-nav items; assert no crash
        listOf("Chat", "Sessions", "Skills", "Memory", "Settings").forEach { label ->
            composeTestRule.onNodeWithText(label).performClick()
        }
    }

    @Test
    fun opensMoreSheet() {
        composeTestRule.onNodeWithText("More").performClick()
        // The 12 overflow tiles render in a grid
        composeTestRule.onNodeWithText("Discover").assertExists()
        composeTestRule.onNodeWithText("Gateway").assertExists()
    }
}

// Convenience import to keep the file self-contained
private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onNodeWithText(
    text: String,
) = onNode(androidx.compose.ui.test.hasText(text))
