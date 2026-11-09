package io.github.mangi.eta.core

import android.service.voice.VoiceInteractionSession
import io.github.mangi.eta.config.AssistantContextPolicy
import io.github.mangi.eta.config.Prefs

internal object AssistantContextFlags {

    /**
     * 向系统声明本次唤醒需要哪些数据。
     *
     * 用户关掉的类别不写进 flag，系统就不会采集，回调也不会送达——开关在源头生效，
     * 而不是采集完再丢弃。
     */
    fun forSdk(
        sdk: Int,
        policy: AssistantContextPolicy = Prefs.assistantContextPolicy(),
    ): Int {
        var flags = 0
        if (policy.needsAssistStructure) {
            flags = flags or VoiceInteractionSession.SHOW_WITH_ASSIST
            if (sdk >= 37) {
                flags = flags or VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT
            }
        }
        if (policy.needsScreenshot) {
            flags = flags or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        }
        return flags
    }
}