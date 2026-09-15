package com.glassous.hmail.ui

import android.util.Patterns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.MailModel
import com.glassous.hmail.R
import com.glassous.hmail.obj
import com.glassous.hmail.ui.common.HmailField
import com.glassous.hmail.ui.common.PrimaryAction
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SplitRow
import com.glassous.hmail.ui.common.TextAction
import com.glassous.hmail.ui.common.bottomInset
import com.glassous.hmail.ui.common.topInset
import kotlinx.coroutines.delay

@Composable
fun AuthScreen(model: MailModel, mode: String, onNavigate: (String) -> Unit) {
    revisionOf(model)
    val group = "auth:$mode"
    val values = model.form(group)
    var email by remember { mutableStateOf(values["email"] ?: "") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }

    // 验证码重发倒计时；登录页不需要。
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mode) {
        if (mode == "login") return@LaunchedEffect
        while (true) {
            clock = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = (((model.cooldowns[group] ?: 0L) - clock + 999) / 1000).coerceAtLeast(0)

    val title = when (mode) {
        "register" -> "创建账户"
        "reset" -> "重设密码"
        else -> "登录"
    }
    val codeEnabled = model.config.optBoolean("emailCodeEnabled")
    val side = maxOf(28.dp, (LocalConfiguration.current.screenWidthDp.dp - 420.dp) / 2)

    val sendCode: () -> Unit = {
        val address = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            emailError = "请输入有效邮箱地址"
        } else {
            model.work {
                val result = model.api.json("/auth/send-code", "POST", obj("email" to address, "purpose" to mode))
                model.cooldowns[group] = System.currentTimeMillis() + result.optLong("cooldown", 60) * 1000
                model.notice("验证码已发送")
            }
        }
    }

    val submit: () -> Unit = {
        val address = email.trim()
        emailError = null
        passwordError = null
        codeError = null
        when {
            !Patterns.EMAIL_ADDRESS.matcher(address).matches() -> emailError = "请输入有效邮箱地址"
            password.length !in 10..128 -> passwordError = "密码需为 10 至 128 个字符"
            mode != "login" && !Regex("[0-9]{6}").matches(code) -> codeError = "请输入 6 位验证码"
            else -> model.work {
                val data = obj("email" to address, "password" to password)
                if (mode != "login") data.put("code", code)
                val result = model.api.json("/auth/$mode", "POST", data)
                model.forms.remove(group)
                password = ""
                code = ""
                if (mode == "reset") {
                    model.notice("密码已重设")
                    onNavigate(Routes.Login)
                } else {
                    model.setSession(result)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (mode == "login") {
            Spacer(Modifier.height(topInset()))
        } else {
            Spacer(Modifier.height(topInset()))
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onNavigate(Routes.Login) }) { MailIcon("back", contentDescription = "返回") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = side, end = side, top = 24.dp, bottom = 40.dp + bottomInset()),
            verticalArrangement = Arrangement.Bottom
        ) {
            Image(
                painter = painterResource(R.drawable.hmail),
                contentDescription = "Hmail",
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(32.dp))
            HmailField(
                value = email,
                onValueChange = { email = it; values["email"] = it; emailError = null },
                label = "邮箱地址",
                keyboardType = KeyboardType.Email,
                isError = emailError != null,
                errorText = emailError
            )
            HmailField(
                value = password,
                onValueChange = { password = it; passwordError = null },
                label = if (mode == "login") "密码" else "新密码",
                password = true,
                isError = passwordError != null,
                errorText = passwordError
            )
            if (mode != "login") {
                HmailField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(6); codeError = null },
                    label = "验证码",
                    keyboardType = KeyboardType.Number,
                    isError = codeError != null,
                    errorText = codeError
                )
                Spacer(Modifier.height(8.dp))
                SecondaryAction(
                    title = if (remaining > 0) "$remaining 秒后重发" else "获取验证码",
                    enabled = !model.busy && remaining == 0L && codeEnabled,
                    onClick = sendCode
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryAction(title, enabled = !model.busy && (mode == "login" || codeEnabled), onClick = submit)
            if (mode == "login") {
                Spacer(Modifier.height(8.dp))
                SplitRow(
                    first = {
                        TextAction("注册", modifier = Modifier.fillMaxWidth()) { onNavigate(Routes.Register) }
                    },
                    second = {
                        TextAction("忘记密码", modifier = Modifier.fillMaxWidth()) { onNavigate(Routes.Reset) }
                    }
                )
            }
        }
    }
}
