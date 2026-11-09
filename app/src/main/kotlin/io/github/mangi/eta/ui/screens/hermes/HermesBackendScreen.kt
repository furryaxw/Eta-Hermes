package io.github.mangi.eta.ui.screens.hermes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.hermes.HermesAccountApi
import io.github.mangi.eta.agent.hermes.HermesApiClient
import io.github.mangi.eta.agent.hermes.HermesAuthMethod
import io.github.mangi.eta.agent.hermes.HermesBridgeService
import io.github.mangi.eta.agent.hermes.HermesSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaSegmentedControl
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

/**
 * Hermes 连接设置。
 *
 * 登录方式二选一：服务器 `hermes eta pair` 打印的一次性配对码，或 Hermes 仪表盘的账号密码。
 * 两者都只换取一个设备令牌，之后对话与手机工具走同一条 WebSocket（`/eta/ws`）。
 */
@Composable
internal fun HermesBackendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(HermesSettings.baseUrl(context)) }
    var authMethod by remember { mutableStateOf(HermesSettings.authMethod(context)) }
    var pairingCode by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(HermesSettings.accountUsername(context)) }
    var password by remember { mutableStateOf("") }
    var signedInUser by remember {
        mutableStateOf(
            HermesSettings.deviceId(context)
                .takeIf { HermesSettings.isSignedIn(context) },
        )
    }
    var trustSelfSigned by remember { mutableStateOf(HermesSettings.isTrustingSelfSigned(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var loginMessage by remember { mutableStateOf<String?>(null) }
    var loginMessageIsError by remember { mutableStateOf(false) }

    fun report(text: String, isError: Boolean = false) {
        message = text
        messageIsError = isError
    }

    fun reportLogin(text: String, isError: Boolean = false) {
        loginMessage = text
        loginMessageIsError = isError
    }

    fun persist() {
        HermesSettings.setBaseUrl(context, baseUrl)
        HermesSettings.setAuthMethod(context, authMethod)
        HermesSettings.setAccountUsername(context, username)
        HermesSettings.setTrustSelfSigned(context, trustSelfSigned)
    }

    val save: () -> Unit = {
        persist()
        HermesBridgeService.sync(context)
        io.github.mangi.eta.agent.hermes.HermesBackendEvents.notifyChanged()
        report(context.getString(R.string.hermes_settings_saved))
    }

    val login: () -> Unit = {
        if (!busy) {
            when {
                baseUrl.isBlank() -> reportLogin(
                    context.getString(R.string.hermes_settings_endpoint_empty),
                    isError = true,
                )
                authMethod == HermesAuthMethod.PAIRING && pairingCode.isBlank() -> reportLogin(
                    context.getString(R.string.hermes_settings_pairing_empty),
                    isError = true,
                )
                authMethod == HermesAuthMethod.PASSWORD &&
                    (username.isBlank() || password.isBlank()) -> reportLogin(
                    context.getString(R.string.hermes_settings_login_empty),
                    isError = true,
                )
                else -> {
                    busy = true
                    loginMessage = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (authMethod == HermesAuthMethod.PAIRING) {
                                    HermesAccountApi.pair(
                                        baseUrl = baseUrl,
                                        code = pairingCode,
                                        deviceName = HermesSettings.deviceName(context),
                                        trustSelfSigned = trustSelfSigned,
                                    )
                                } else {
                                    HermesAccountApi.login(
                                        baseUrl = baseUrl,
                                        username = username,
                                        password = password,
                                        deviceName = HermesSettings.deviceName(context),
                                        trustSelfSigned = trustSelfSigned,
                                    )
                                }
                            }
                        }
                        busy = false
                        result.onSuccess { session ->
                            HermesSettings.setBaseUrl(context, baseUrl)
                            HermesSettings.setAuthMethod(context, authMethod)
                            HermesSettings.setAccountUsername(context, username)
                            HermesSettings.setDeviceId(context, session.deviceId)
                            HermesSettings.setDeviceToken(context, session.token)
                            pairingCode = ""
                            password = ""
                            signedInUser = session.deviceId.ifBlank { session.label }
                            HermesBridgeService.sync(context)
                            io.github.mangi.eta.agent.hermes.HermesBackendEvents.notifyChanged()
                            reportLogin(
                                context.getString(
                                    R.string.hermes_settings_signed_in,
                                    session.label,
                                ),
                            )
                        }.onFailure { failure ->
                            reportLogin(
                                if (isCertificateFailure(failure)) {
                                    context.getString(R.string.hermes_settings_tls_failed)
                                } else {
                                    context.getString(
                                        R.string.hermes_settings_login_failed,
                                        failure.message ?: failure.javaClass.simpleName,
                                    )
                                },
                                isError = true,
                            )
                        }
                    }
                }
            }
        }
    }

    val logout: () -> Unit = {
        HermesSettings.clearDeviceToken(context)
        signedInUser = null
        HermesBridgeService.stop(context)
        io.github.mangi.eta.agent.hermes.HermesBackendEvents.notifyChanged()
        reportLogin(context.getString(R.string.hermes_settings_logged_out))
    }

    val test: () -> Unit = {
        if (!busy) {
            val token = HermesSettings.deviceToken(context)
            if (baseUrl.isBlank() || token.isBlank()) {
                report(context.getString(R.string.hermes_settings_test_missing), isError = true)
            } else {
                busy = true
                message = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            HermesApiClient(
                                context = context,
                                baseUrl = baseUrl,
                                authToken = token,
                                trustSelfSigned = trustSelfSigned,
                            ).capabilitiesStatus()
                        }
                    }
                    busy = false
                    result.onSuccess { code ->
                        if (code in 200..299) {
                            report(context.getString(R.string.hermes_settings_test_ok, code))
                        } else {
                            report(
                                context.getString(
                                    R.string.hermes_settings_test_failed,
                                    "HTTP $code",
                                ),
                                isError = true,
                            )
                        }
                    }.onFailure { failure ->
                        report(
                            context.getString(
                                R.string.hermes_settings_test_failed,
                                if (isCertificateFailure(failure)) {
                                    context.getString(R.string.hermes_settings_tls_failed)
                                } else {
                                    failure.message ?: failure.javaClass.simpleName
                                },
                            ),
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    val switchAuthMethod: (HermesAuthMethod) -> Unit = { method ->
        authMethod = method
        message = null
        loginMessage = null
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.hermes_settings_title),
                onBack = onBack,
            ) {
                Group(title = stringResource(R.string.hermes_settings_group_endpoint)) {
                    HermesFieldColumn {
                        HermesField(
                            label = stringResource(R.string.hermes_settings_base_url),
                            value = baseUrl,
                            placeholder = "https://hermes.example.com:9119/eta",
                            onChange = { baseUrl = it },
                        )
                        HermesCaption(stringResource(R.string.hermes_settings_endpoint_hint))
                    }
                    Divider()
                    HermesTrustRow(
                        checked = trustSelfSigned,
                        onCheckedChange = { enabled ->
                            trustSelfSigned = enabled
                            HermesSettings.setTrustSelfSigned(context, enabled)
                            HermesBridgeService.sync(context)
                            loginMessage = null
                        },
                    )
                    Divider()
                    HermesAuthMethodPicker(
                        selected = authMethod,
                        onSelect = switchAuthMethod,
                    )
                    Divider()
                    if (authMethod == HermesAuthMethod.PAIRING) {
                        HermesPairingFields(
                            code = pairingCode,
                            signedInUser = signedInUser,
                            busy = busy,
                            status = loginMessage,
                            statusIsError = loginMessageIsError,
                            onCodeChange = { pairingCode = it },
                            onLogin = login,
                            onLogout = logout,
                        )
                    } else {
                        HermesAccountFields(
                            username = username,
                            password = password,
                            signedInUser = signedInUser,
                            busy = busy,
                            status = loginMessage,
                            statusIsError = loginMessageIsError,
                            onUsernameChange = { username = it },
                            onPasswordChange = { password = it },
                            onLogin = login,
                            onLogout = logout,
                        )
                    }
                }
                if (authMethod == HermesAuthMethod.PAIRING) {
                    HermesNote(stringResource(R.string.hermes_settings_pairing_note))
                } else {
                    HermesNote(stringResource(R.string.hermes_settings_account_note))
                }
                HermesNote(stringResource(R.string.hermes_settings_enable_note))
                HermesActions(
                    busy = busy,
                    message = message,
                    messageIsError = messageIsError,
                    onTest = test,
                    onSave = save,
                )
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.hermes_settings_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                item(key = "endpoint") {
                    Column {
                        HermesSectionGroup(
                            title = stringResource(R.string.hermes_settings_group_endpoint),
                        ) {
                            HermesFieldColumn {
                                HermesField(
                                    label = stringResource(R.string.hermes_settings_base_url),
                                    value = baseUrl,
                                    placeholder = "https://hermes.example.com:9119/eta",
                                    onChange = { baseUrl = it },
                                )
                                HermesCaption(stringResource(R.string.hermes_settings_endpoint_hint))
                            }
                            HermesTrustRow(
                                checked = trustSelfSigned,
                                onCheckedChange = { enabled ->
                                    trustSelfSigned = enabled
                                    HermesSettings.setTrustSelfSigned(context, enabled)
                                    HermesBridgeService.sync(context)
                                    loginMessage = null
                                },
                            )
                            HermesAuthMethodPicker(
                                selected = authMethod,
                                onSelect = switchAuthMethod,
                            )
                            if (authMethod == HermesAuthMethod.PAIRING) {
                                HermesPairingFields(
                                    code = pairingCode,
                                    signedInUser = signedInUser,
                                    busy = busy,
                                    status = loginMessage,
                                    statusIsError = loginMessageIsError,
                                    onCodeChange = { pairingCode = it },
                                    onLogin = login,
                                    onLogout = logout,
                                )
                            } else {
                                HermesAccountFields(
                                    username = username,
                                    password = password,
                                    signedInUser = signedInUser,
                                    busy = busy,
                                    status = loginMessage,
                                    statusIsError = loginMessageIsError,
                                    onUsernameChange = { username = it },
                                    onPasswordChange = { password = it },
                                    onLogin = login,
                                    onLogout = logout,
                                )
                            }
                        }
                        if (authMethod == HermesAuthMethod.PAIRING) {
                            HermesNote(stringResource(R.string.hermes_settings_pairing_note))
                        } else {
                            HermesNote(stringResource(R.string.hermes_settings_account_note))
                        }
                        HermesNote(stringResource(R.string.hermes_settings_enable_note))
                    }
                }
                item(key = "actions") {
                    HermesActions(
                        busy = busy,
                        message = message,
                        messageIsError = messageIsError,
                        onTest = test,
                        onSave = save,
                    )
                }
            }
        }
    }
}

@Composable
private fun HermesTrustRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiSwitchPreference(
            title = stringResource(R.string.hermes_settings_trust_self_signed),
            summary = stringResource(R.string.hermes_settings_trust_note),
            checked = checked,
            onCheckedChange = onCheckedChange,
        )

        AppearanceUiStyle.MIUIX -> SwitchPreference(
            title = stringResource(R.string.hermes_settings_trust_self_signed),
            summary = stringResource(R.string.hermes_settings_trust_note),
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/** TLS 失败发生在拿到响应之前，异常链里才有证书信息。 */
private fun isCertificateFailure(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }
        .take(8)
        .any { it is SSLHandshakeException || it is CertPathValidatorException }

@Composable
private fun HermesAuthMethodPicker(
    selected: HermesAuthMethod,
    onSelect: (HermesAuthMethod) -> Unit,
) {
    val options = listOf(
        stringResource(R.string.hermes_settings_auth_pairing),
        stringResource(R.string.hermes_settings_auth_password),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HermesCaption(stringResource(R.string.hermes_settings_auth_method))
        EtaSegmentedControl(
            options = options,
            selectedIndex = if (selected == HermesAuthMethod.PAIRING) 0 else 1,
            onSelectedIndexChange = { index ->
                onSelect(if (index == 0) HermesAuthMethod.PAIRING else HermesAuthMethod.PASSWORD)
            },
        )
    }
}

@Composable
private fun HermesPairingFields(
    code: String,
    signedInUser: String?,
    busy: Boolean,
    status: String?,
    statusIsError: Boolean,
    onCodeChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HermesField(
            label = stringResource(R.string.hermes_settings_pairing_code),
            value = code,
            onChange = onCodeChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesActionButton(
                text = stringResource(R.string.hermes_settings_pair),
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onLogin,
            )
            if (signedInUser != null) {
                HermesActionButton(
                    text = stringResource(R.string.hermes_settings_logout),
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onLogout,
                )
            }
        }
        HermesCaption(
            text = if (signedInUser != null) {
                stringResource(R.string.hermes_settings_signed_in, signedInUser)
            } else {
                stringResource(R.string.hermes_settings_signed_out)
            },
        )
        status?.let { text ->
            HermesCaption(
                text = text,
                color = if (statusIsError) StatusError else StatusSuccess,
            )
        }
    }
}

@Composable
private fun HermesAccountFields(
    username: String,
    password: String,
    signedInUser: String?,
    busy: Boolean,
    status: String?,
    statusIsError: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HermesField(
            label = stringResource(R.string.hermes_settings_username),
            value = username,
            onChange = onUsernameChange,
        )
        HermesField(
            label = stringResource(R.string.hermes_settings_password),
            value = password,
            secret = true,
            onChange = onPasswordChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesActionButton(
                text = stringResource(R.string.hermes_settings_login),
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onLogin,
            )
            if (signedInUser != null) {
                HermesActionButton(
                    text = stringResource(R.string.hermes_settings_logout),
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onLogout,
                )
            }
        }
        HermesCaption(
            text = if (signedInUser != null) {
                stringResource(R.string.hermes_settings_signed_in, signedInUser)
            } else {
                stringResource(R.string.hermes_settings_signed_out)
            },
            color = if (signedInUser != null) StatusSuccess else null,
        )
        status?.let { text ->
            HermesCaption(
                text = text,
                color = if (statusIsError) StatusError else StatusSuccess,
            )
        }
    }
}

@Composable
private fun HermesActions(
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiPageDefaults.horizontalMargin
                } else {
                    16.dp
                },
            )
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HermesActionButton(
                text = stringResource(R.string.hermes_settings_test),
                primary = false,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onTest,
            )
            HermesActionButton(
                text = stringResource(R.string.hermes_settings_save),
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onSave,
            )
        }
        message?.let { text ->
            HermesCaption(
                text = text,
                color = if (messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HermesActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiButton(
            text = text,
            enabled = enabled,
            role = if (primary) CouiButtonRole.Primary else CouiButtonRole.Secondary,
            modifier = modifier,
            onClick = onClick,
        )

        AppearanceUiStyle.MIUIX -> TextButton(
            text = text,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            colors = if (primary) {
                ButtonDefaults.textButtonColorsPrimary()
            } else {
                ButtonDefaults.textButtonColors()
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun HermesSectionGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> Group(title = title, content = content)
        AppearanceUiStyle.MIUIX -> {
            SmallTitle(title)
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun HermesFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun HermesField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    secret: Boolean = false,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiTextField(
                value = value,
                onValueChange = onChange,
                label = label,
                placeholder = placeholder,
                singleLine = true,
                visualTransformation = if (secret && !visible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (secret) {
                    {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(28.dp)
                                .clickable(role = Role.Button) { visible = !visible },
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiIcon(
                                imageVector = if (visible) {
                                    Icons.Rounded.Visibility
                                } else {
                                    Icons.Rounded.VisibilityOff
                                },
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = colors.summary,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }

        AppearanceUiStyle.MIUIX -> Column {
            TextField(
                value = value,
                onValueChange = onChange,
                label = label,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (secret && !visible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (secret) {
                    {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) {
                                    Icons.Rounded.Visibility
                                } else {
                                    Icons.Rounded.VisibilityOff
                                },
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                            )
                        }
                    }
                } else {
                    null
                },
            )
            if (!placeholder.isNullOrBlank() && value.isBlank()) {
                HermesCaption(
                    text = placeholder,
                    modifier = Modifier.padding(top = 6.dp).padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HermesCaption(
    text: String,
    color: Color? = null,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = color ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = textAlign ?: TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
private fun HermesNote(text: String, modifier: Modifier = Modifier) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> Footer(text = text, modifier = modifier)
        AppearanceUiStyle.MIUIX -> Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
        )
    }
}
