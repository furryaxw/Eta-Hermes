package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.hermes.HermesPrompt
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 服务端请求（审批 / 澄清）的对话框。
 *
 * 没有选项时按自由输入处理：澄清类请求往往就是让用户补一句话。
 */
@Composable
internal fun HermesPromptDialog(
    prompt: HermesPrompt,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(prompt.requestId) { mutableStateOf("") }
    val title = prompt.title.ifBlank {
        stringResource(
            if (prompt.isApproval) R.string.hermes_prompt_approval_title else R.string.hermes_prompt_clarify_title,
        )
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = title,
                message = prompt.body.ifBlank { null },
                confirmText = stringResource(R.string.action_cancel),
                onDismissRequest = onDismiss,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (prompt.allowsFreeText) {
                            CouiTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = stringResource(R.string.hermes_prompt_input_hint),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            CouiButton(
                                text = stringResource(R.string.hermes_prompt_send),
                                role = CouiButtonRole.Primary,
                                enabled = input.isNotBlank(),
                                onClick = { onAnswer(input.trim()) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            prompt.choices.forEachIndexed { index, choice ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                CouiButton(
                                    text = choice.label,
                                    role = if (index == 0) CouiButtonRole.Primary else CouiButtonRole.Secondary,
                                    onClick = { onAnswer(choice.value) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
            )
        }

        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                summary = prompt.body.ifBlank { null },
                onDismissRequest = onDismiss,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (prompt.allowsFreeText) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            label = stringResource(R.string.hermes_prompt_input_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MiuixDialogActions(
                            confirmText = stringResource(R.string.hermes_prompt_send),
                            confirmEnabled = input.isNotBlank(),
                            onCancel = onDismiss,
                            onConfirm = { onAnswer(input.trim()) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        prompt.choices.forEach { choice ->
                            TextButton(
                                text = choice.label,
                                onClick = { onAnswer(choice.value) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }
}