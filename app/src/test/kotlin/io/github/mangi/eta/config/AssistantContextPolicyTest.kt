package io.github.mangi.eta.config

import android.app.Application
import android.content.Context
import android.service.voice.VoiceInteractionSession
import io.github.mangi.eta.core.AssistantContextFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AssistantContextPolicyTest {

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("assistant_context_policy_test", Context.MODE_PRIVATE)

    @Test
    fun defaultsSendEveryCategoryOnEveryMessage() {
        val policy = Prefs.assistantContextPolicy(null)
        assertEquals(AssistantContextTiming.EVERY_TURN, policy.timing)
        assertEquals(AssistantContextPart.entries.toSet(), policy.parts)
        assertTrue(policy.needsAssistStructure)
        assertTrue(policy.needsScreenshot)
        assertTrue(policy.shouldAttach(conversationStarted = true))
    }

    @Test
    fun firstTurnOnlyAttachesUntilTheConversationHasATurn() {
        val policy = AssistantContextPolicy(timing = AssistantContextTiming.FIRST_TURN)
        assertTrue(policy.shouldAttach(conversationStarted = false))
        assertFalse(policy.shouldAttach(conversationStarted = true))
    }

    @Test
    fun neverAttachesRegardlessOfConversationState() {
        val policy = AssistantContextPolicy(timing = AssistantContextTiming.NEVER)
        assertFalse(policy.shouldAttach(conversationStarted = false))
        assertFalse(policy.shouldAttach(conversationStarted = true))
    }

    @Test
    fun assistStructureIsOnlyRequestedForTheCategoriesItFeeds() {
        val headersOnly = AssistantContextPolicy(
            parts = setOf(
                AssistantContextPart.SCREENSHOT,
                AssistantContextPart.FOREGROUND_APP,
                AssistantContextPart.CAPTURED_AT,
            ),
        )
        assertFalse(headersOnly.needsAssistStructure)
        assertTrue(headersOnly.needsScreenshot)

        val textOnly = AssistantContextPolicy(parts = setOf(AssistantContextPart.UI_TEXT))
        assertTrue(textOnly.needsAssistStructure)
        assertFalse(textOnly.needsScreenshot)
    }

    @Test
    fun systemShowFlagsFollowThePolicy() {
        val all = AssistantContextPolicy()
        val full = AssistantContextFlags.forSdk(34, all)
        assertTrue(full and VoiceInteractionSession.SHOW_WITH_ASSIST != 0)
        assertTrue(full and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0)

        val noScreenshot = AssistantContextFlags.forSdk(34, all.copy(parts = all.parts - AssistantContextPart.SCREENSHOT))
        assertEquals(0, noScreenshot and VoiceInteractionSession.SHOW_WITH_SCREENSHOT)

        val empty = AssistantContextFlags.forSdk(34, all.copy(parts = emptySet()))
        assertEquals(0, empty)
    }

    @Test
    fun persistedValuesRoundTripThroughPreferences() {
        val prefs = prefs()
        assertTrue(Prefs.putAssistantContextTiming(prefs, AssistantContextTiming.FIRST_TURN))
        prefs.edit().putBoolean(AssistantContextPart.WEB.key, false).commit()

        val policy = Prefs.assistantContextPolicy(prefs)
        assertEquals(AssistantContextTiming.FIRST_TURN, policy.timing)
        assertFalse(policy.includes(AssistantContextPart.WEB))
        assertTrue(policy.includes(AssistantContextPart.SCREENSHOT))
        assertTrue(AssistantContextPart.entries.any { it.key in Prefs.Keys.LOCAL_AGENT_KEYS })
    }

    @Test
    fun unknownTimingFallsBackToEveryTurn() {
        val prefs = prefs()
        prefs.edit().putString(Prefs.Keys.ASSISTANT_CONTEXT_TIMING, "unknown").commit()
        assertEquals(AssistantContextTiming.EVERY_TURN, Prefs.assistantContextPolicy(prefs).timing)
    }
}