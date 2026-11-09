package io.github.mangi.eta.agent.hermes

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 后端配置/登录状态变化的通知。
 *
 * 改了地址或重新配对后，会话列表和连接都要立刻跟着换，而不是等用户重启 App。
 */
internal object HermesBackendEvents {

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val changed = _changed.asSharedFlow()

    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }
}
