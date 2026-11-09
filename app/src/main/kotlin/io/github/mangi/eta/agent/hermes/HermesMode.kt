package io.github.mangi.eta.agent.hermes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hermes 模式的可观察开关。
 *
 * 设置页写入偏好后必须让已经组合的界面同步隐藏/恢复本机 Agent 功能，所以状态集中在这里，
 * 而不是每次读取偏好。
 */
internal object HermesMode {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun refresh(context: Context) {
        mutableEnabled.value = HermesSettings.isEnabled(context)
    }

    fun set(context: Context, enabled: Boolean) {
        HermesSettings.setEnabled(context, enabled)
        mutableEnabled.value = enabled
    }
}
