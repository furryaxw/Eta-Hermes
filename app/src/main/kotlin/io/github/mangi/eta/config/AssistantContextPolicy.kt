package io.github.mangi.eta.config

/**
 * 发消息时自动附带的助理上下文。
 *
 * 只描述"带什么"和"什么时候带"：采集侧按 [parts] 决定向系统要什么数据、解析哪些行，
 * 发送侧按 [timing] 决定这一轮附不附带。
 */
internal enum class AssistantContextPart(val key: String, val default: Boolean = true) {
    /** 唤醒时系统给的截屏。 */
    SCREENSHOT("assistant_context_screenshot"),

    /** 界面文字：控件文本、内容描述、输入提示与窗口标题。 */
    UI_TEXT("assistant_context_ui_text"),

    /** 界面结构：控件类别名，只描述布局骨架，不含文字。 */
    UI_STRUCTURE("assistant_context_ui_structure"),

    /** 焦点应用、应用组件名与应用名称。 */
    FOREGROUND_APP("assistant_context_foreground_app"),

    /** 采集时刻的 Unix 毫秒。 */
    CAPTURED_AT("assistant_context_captured_at"),

    /** assist content：网页链接、结构化数据与页面动作。 */
    WEB("assistant_context_web"),

    /** 应用附加数据：剪贴数据文本与应用随 assist 附带的键值。 */
    APP_DATA("assistant_context_app_data"),
}

internal enum class AssistantContextTiming(val persistedValue: String) {
    EVERY_TURN("every_turn"),
    FIRST_TURN("first_turn"),
    NEVER("never"),
    ;

    companion object {
        fun fromPersisted(value: String?): AssistantContextTiming =
            entries.firstOrNull { it.persistedValue == value } ?: EVERY_TURN
    }
}

internal data class AssistantContextPolicy(
    val timing: AssistantContextTiming = AssistantContextTiming.EVERY_TURN,
    val parts: Set<AssistantContextPart> = AssistantContextPart.entries.toSet(),
) {
    fun includes(part: AssistantContextPart): Boolean = part in parts

    /** 需要系统把 assist 结构交给我们时才有意义请求的那部分。 */
    val needsAssistStructure: Boolean
        get() = parts.any { it in ASSIST_PARTS }

    val needsScreenshot: Boolean get() = includes(AssistantContextPart.SCREENSHOT)

    /**
     * 这一轮要不要附带上下文。
     *
     * [conversationStarted] 表示该会话已经有用户轮次：只有"仅首条"需要它，
     * 其余两档不依赖会话状态。
     */
    fun shouldAttach(conversationStarted: Boolean): Boolean = when (timing) {
        AssistantContextTiming.NEVER -> false
        AssistantContextTiming.EVERY_TURN -> true
        AssistantContextTiming.FIRST_TURN -> !conversationStarted
    }

    private companion object {
        val ASSIST_PARTS = setOf(
            AssistantContextPart.UI_TEXT,
            AssistantContextPart.UI_STRUCTURE,
            AssistantContextPart.WEB,
            AssistantContextPart.APP_DATA,
        )
    }
}