package com.glassous.hmail.ui

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.R
import com.glassous.hmail.array
import com.glassous.hmail.enc
import com.glassous.hmail.obj
import com.glassous.hmail.objects
import com.glassous.hmail.openExternal
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.ConfirmDialog
import com.glassous.hmail.ui.common.HmailField
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.PrimaryAction
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SectionText
import com.glassous.hmail.ui.common.SectionTitle
import com.glassous.hmail.ui.common.SplitRow
import com.glassous.hmail.ui.common.TextAction
import com.glassous.hmail.ui.theme.HmailTheme
import org.json.JSONObject

@Composable
fun AccountsScreen(model: MailModel, onBack: () -> Unit, onConnect: () -> Unit) {
    revisionOf(model)
    var pending by remember { mutableStateOf<String?>(null) }
    MailPage(title = "邮箱管理", onBack = onBack, progress = model.busy) {
        if (model.mailbox.accountsLoading) SectionText("正在加载邮箱", muted = true)
        model.mailbox.accountsError?.let { SecondaryAction(it) { model.retryAccounts() } }
        if (model.accounts.isEmpty() && !model.mailbox.accountsLoading && model.mailbox.accountsError == null)
            SectionText("尚未连接邮箱", muted = true)
        model.accounts.forEach { account ->
            SectionTitle(account.email)
            if (account.isDefault) SectionText("默认邮箱", muted = true)
            if (account.status == "reconnect") SectionText("需要重新连接", muted = true)
            SplitRow(
                first = {
                    SecondaryAction("重新连接") {
                        model.form("ConnectFragment")["email"] = account.email
                        onConnect()
                    }
                },
                second = {
                    SecondaryAction(if (account.isDefault) "取消默认" else "设为默认") {
                        model.work { model.setDefaultAccount(if (account.isDefault) "" else account.id) }
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            SecondaryAction("断开邮箱") { pending = account.id }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrimaryAction("连接邮箱") { onConnect() }
    }
    val account = pending?.let { id -> model.accounts.find { it.id == id } }
    if (account != null) {
        ConfirmDialog(
            title = "断开 ${account.email}？",
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                model.work {
                    model.api.json("/gmail-accounts/${enc(account.id)}", "DELETE")
                    model.refreshAccounts()
                }
            }
        )
    }
}

@Composable
fun PasswordScreen(model: MailModel, onBack: () -> Unit) {
    revisionOf(model)
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    MailPage(title = "修改密码", onBack = onBack, progress = model.busy) {
        HmailField(current, { current = it }, "当前密码", password = true)
        HmailField(
            value = password,
            onValueChange = { password = it; error = null },
            label = "新密码",
            password = true,
            isError = error != null,
            errorText = error
        )
        Spacer(Modifier.height(8.dp))
        PrimaryAction("保存并重新登录") {
            model.work {
                if (password.length !in 10..128) {
                    error = "密码需为 10 至 128 个字符"
                    return@work
                }
                model.saveDraft()
                model.api.json(
                    "/me/password", "POST",
                    obj("currentPassword" to current, "password" to password)
                )
                current = ""
                password = ""
                model.clearSession()
            }
        }
    }
}

@Composable
fun ConnectScreen(model: MailModel, onBack: () -> Unit) {
    val revision = revisionOf(model)
    val context = LocalContext.current
    val values = model.form("ConnectFragment")
    val presets = remember(revision) { model.config.array("mailProviders").objects() }
    val oauthEnabled = model.config.optBoolean("oauthEnabled")
    // 先选择连接方式，再进入对应界面：进入 IMAP＋SMTP 后不再显示 Google 入口。
    var step by remember { mutableStateOf("choose") }

    var email by remember { mutableStateOf(values["email"] ?: "") }
    var password by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf(values["imapHost"] ?: "") }
    var imapPort by remember { mutableStateOf(values["imapPort"] ?: "993") }
    var imapSecurity by remember { mutableStateOf(values["imapSecurity"] ?: "ssl") }
    var smtpHost by remember { mutableStateOf(values["smtpHost"] ?: "") }
    var smtpPort by remember { mutableStateOf(values["smtpPort"] ?: "465") }
    var smtpSecurity by remember { mutableStateOf(values["smtpSecurity"] ?: "ssl") }
    var presetIndex by remember { mutableStateOf(values["preset"]?.toIntOrNull() ?: presets.size) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var imapError by remember { mutableStateOf<String?>(null) }
    var smtpError by remember { mutableStateOf<String?>(null) }

    val applyPreset: (JSONObject) -> Unit = { preset ->
        val imap = preset.optJSONObject("imap")
        val smtp = preset.optJSONObject("smtp")
        if (imap != null) {
            imapHost = imap.str("host")
            imapPort = imap.optInt("port").toString()
            imapSecurity = imap.str("security").ifBlank { "ssl" }
        }
        if (smtp != null) {
            smtpHost = smtp.str("host")
            smtpPort = smtp.optInt("port").toString()
            smtpSecurity = smtp.str("security").ifBlank { "ssl" }
        }
    }

    // IMAP 表单里的系统返回键先回到方式选择，而不是直接退出连接页。
    BackHandler(enabled = step == "imap") { step = "choose" }

    MailPage(title = "连接邮箱", onBack = onBack, progress = model.busy) {
        if (step == "choose") {
            SectionText("选择连接方式。已连接的邮箱会更新凭据或切换连接方式，邮件仍保留在原来的邮箱服务商。", muted = true)
            ConnectOptionCard(
                title = "使用 Google 连接",
                enabled = oauthEnabled,
                icon = {
                    Image(
                        painter = painterResource(R.drawable.google_icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            ) {
                model.work {
                    val result = model.api.json("/gmail-accounts/oauth/mobile/start", "POST")
                    model.vault.write("oauth", result.str("ticket"))
                    openExternal(context, result.str("url"))
                }
            }
            Spacer(Modifier.height(12.dp))
            ConnectOptionCard(
                title = "使用 IMAP＋SMTP 连接",
                icon = {
                    MailIcon(
                        "read",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            ) { step = "imap" }
            if (!oauthEnabled) {
                Spacer(Modifier.height(16.dp))
                SectionText("尚未配置 Google OAuth，请使用 IMAP＋SMTP 连接。", muted = true)
            }
        } else {
            TextAction("返回选择其他连接方式") { step = "choose" }
            HmailField(
                value = email,
                onValueChange = { email = it; emailError = null },
                label = "邮箱地址",
                keyboardType = KeyboardType.Email,
                isError = emailError != null,
                errorText = emailError,
                modifier = Modifier.onFocusChanged { focus ->
                    if (focus.isFocused) return@onFocusChanged
                    // 失焦时按域名自动选中服务商，只在服务器还是空的时候填充，避免覆盖手工填写。
                    val domain = email.substringAfter('@', "").lowercase()
                    val index = presets.indexOfFirst { preset ->
                        val domains = preset.array("domains")
                        (0 until domains.length()).any { domains.getString(it).equals(domain, true) }
                    }
                    if (index >= 0 && imapHost.isBlank()) {
                        presetIndex = index
                        applyPreset(presets[index])
                    }
                }
            )
            HmailField(
                value = password,
                onValueChange = { password = it; passwordError = null },
                label = "密码或授权码",
                password = true,
                isError = passwordError != null,
                errorText = passwordError
            )
            OptionField(
                label = "邮箱服务商",
                options = presets.map { it.str("name") } + "其他邮箱",
                selected = presetIndex,
                onSelect = { index ->
                    presetIndex = index
                    presets.getOrNull(index)?.let(applyPreset)
                }
            )
            Spacer(Modifier.height(8.dp))
            HmailField(imapHost, { imapHost = it }, "收信服务器")
            HmailField(
                value = imapPort,
                onValueChange = { imapPort = it.filter { c -> c.isDigit() }.take(5); imapError = null },
                label = "收信端口",
                keyboardType = KeyboardType.Number,
                isError = imapError != null,
                errorText = imapError
            )
            OptionField(
                label = "收信加密方式",
                options = listOf("SSL/TLS", "STARTTLS"),
                selected = if (imapSecurity == "starttls") 1 else 0,
                onSelect = { imapSecurity = if (it == 0) "ssl" else "starttls" }
            )
            Spacer(Modifier.height(8.dp))
            HmailField(smtpHost, { smtpHost = it }, "发信服务器")
            HmailField(
                value = smtpPort,
                onValueChange = { smtpPort = it.filter { c -> c.isDigit() }.take(5); smtpError = null },
                label = "发信端口",
                keyboardType = KeyboardType.Number,
                isError = smtpError != null,
                errorText = smtpError
            )
            OptionField(
                label = "发信加密方式",
                options = listOf("SSL/TLS", "STARTTLS"),
                selected = if (smtpSecurity == "starttls") 1 else 0,
                onSelect = { smtpSecurity = if (it == 0) "ssl" else "starttls" }
            )
            Spacer(Modifier.height(8.dp))
            PrimaryAction("连接") {
                val address = email.trim()
                val incoming = imapPort.toIntOrNull()
                val outgoing = smtpPort.toIntOrNull()
                emailError = null
                passwordError = null
                imapError = null
                smtpError = null
                when {
                    !Patterns.EMAIL_ADDRESS.matcher(address).matches() -> emailError = "请输入有效邮箱地址"
                    password.isBlank() -> passwordError = "请输入密码或授权码"
                    incoming == null || incoming !in 1..65535 -> imapError = "端口需为 1 至 65535"
                    outgoing == null || outgoing !in 1..65535 -> smtpError = "端口需为 1 至 65535"
                    else -> model.work {
                        val result = model.api.json(
                            "/gmail-accounts/imap", "POST",
                            obj(
                                "email" to address,
                                "password" to password,
                                "imapHost" to imapHost.trim(),
                                "imapPort" to incoming,
                                "imapSecurity" to imapSecurity,
                                "smtpHost" to smtpHost.trim(),
                                "smtpPort" to outgoing,
                                "smtpSecurity" to smtpSecurity
                            )
                        )
                        password = ""
                        model.forms.remove("ConnectFragment")
                        model.refreshAccounts()
                        model.switchAccount(result.str("id"))
                        onBack()
                    }
                }
            }
        }
    }
}

/** 连接方式入口卡片：图标 + 主标题，与 Web 端连接卡片保持一致。 */
@Composable
private fun ConnectOptionCard(
    title: String,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .border(1.dp, HmailTheme.colors.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/** 下拉选择项，用于服务商与加密方式。 */
@Composable
private fun OptionField(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, HmailTheme.colors.outline, RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = HmailTheme.colors.muted)
                Text(
                    options.getOrElse(selected) { "" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            MailIcon("chevron_down", tint = HmailTheme.colors.muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    }
                )
            }
        }
    }
}
