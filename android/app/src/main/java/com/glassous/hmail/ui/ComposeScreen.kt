package com.glassous.hmail.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.ApiFailure
import com.glassous.hmail.MailModel
import com.glassous.hmail.sizeText
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.ConfirmDialog
import com.glassous.hmail.ui.common.GlassAction
import com.glassous.hmail.ui.common.HmailField
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.PrimaryAction
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SectionText
import com.glassous.hmail.ui.common.TextAction
import com.glassous.hmail.ui.theme.HmailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ComposeScreen(
    model: MailModel,
    draft: Boolean,
    onBack: () -> Unit,
    onOpenSent: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    revisionOf(model)
    val context = LocalContext.current
    val state = model.compose
    // 顶部栏标题跟随入口：从「继续写信」进来时与按钮上的文字保持一致。
    val title = if (draft) "继续写信" else "写邮件"
    // 整页容器（背景与全部组件）与主页入口按钮的背景共享；图标与顶部栏返回键、标题与按钮文字分别共享。
    val pageModifier = sharedTransitionScope.sharedPageElement(ComposeSharedKeys.page(draft), animatedVisibilityScope)
    val iconModifier = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.icon(draft), animatedVisibilityScope)
    val titleModifier = sharedTransitionScope.sharedTextElement(ComposeSharedKeys.title(draft), animatedVisibilityScope)
    if (state == null) {
        MailPage(
            title = title,
            onBack = onBack,
            pageModifier = pageModifier,
            navigationIconModifier = iconModifier,
            titleTextModifier = titleModifier
        ) { SectionText("暂无未完成的邮件", muted = true) }
        return
    }

    val payload = state.payload
    val group = "compose:${payload.str("composeId")}"
    var to by remember { mutableStateOf(payload.str("to")) }
    var cc by remember { mutableStateOf(payload.str("cc")) }
    var bcc by remember { mutableStateOf(payload.str("bcc")) }
    var subject by remember { mutableStateOf(payload.str("subject")) }
    var body by remember { mutableStateOf(payload.str("text")) }
    var showRecipients by remember {
        mutableStateOf(
            model.form(group)["expanded"] == "true" || payload.str("cc").isNotBlank() || payload.str("bcc").isNotBlank()
        )
    }
    var pendingDiscard by remember { mutableStateOf(false) }
    val enabled = !state.uncertain && !model.composeBusy && !model.uploading

    fun edit(key: String, value: String) {
        if (state.uncertain) return
        payload.put(key, value)
        model.dirty()
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addAttachments(model, state, uris, context.applicationContext.contentResolver)
    }

    MailPage(
        title = title,
        onBack = onBack,
        progress = model.busy,
        pageModifier = pageModifier,
        navigationIconModifier = iconModifier,
        titleTextModifier = titleModifier,
        actions = listOf(
            GlassAction("丢弃草稿", "trash", enabled = enabled && !model.busy) { pendingDiscard = true }
        )
    ) {
        val accountEmail = model.accounts.find { it.id == state.account }?.email ?: ""
        SectionText(accountEmail, size = 14f, muted = true)

        HmailField(
            value = to,
            onValueChange = { to = it; edit("to", it) },
            label = "收件人",
            keyboardType = KeyboardType.Email,
            enabled = !state.uncertain
        )
        TextAction("抄送与密送") {
            showRecipients = !showRecipients
            model.form(group)["expanded"] = showRecipients.toString()
        }
        if (showRecipients) {
            HmailField(
                value = cc,
                onValueChange = { cc = it; edit("cc", it) },
                label = "抄送",
                keyboardType = KeyboardType.Email,
                enabled = !state.uncertain
            )
            HmailField(
                value = bcc,
                onValueChange = { bcc = it; edit("bcc", it) },
                label = "密送",
                keyboardType = KeyboardType.Email,
                enabled = !state.uncertain
            )
        }
        HmailField(
            value = subject,
            onValueChange = { subject = it.take(998); edit("subject", it.take(998)) },
            label = "主题",
            enabled = !state.uncertain
        )
        HmailField(
            value = body,
            onValueChange = { body = it.take(500000); edit("text", it.take(500000)) },
            label = "邮件正文",
            multiline = true,
            enabled = !state.uncertain
        )

        state.attachments.forEach { attachment ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(sizeText(attachment.size), fontSize = 13.sp, color = HmailTheme.colors.muted)
                }
                TextButton(
                    onClick = {
                        state.attachments = state.attachments - attachment
                        model.dirty()
                        model.changed()
                    },
                    enabled = enabled && !model.busy
                ) { Text("移除") }
            }
        }

        if (state.uncertain) {
            Text(
                text = "发送结果待确认，正在自动核对…请勿重复发送；也可稍后在“已发送”中确认。",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
            SecondaryAction("查看已发送") {
                model.switchAccount(state.account)
                model.chooseFolder("SENT")
                onOpenSent()
            }
            Spacer(Modifier.height(8.dp))
        }

        SectionText(if (model.uploading) "正在添加附件" else model.savedText, size = 12f, muted = true)

        SecondaryAction(
            title = "添加附件",
            enabled = enabled && !model.busy,
            onClick = { files.launch(arrayOf("*/*")) }
        )
        Spacer(Modifier.height(8.dp))
        SecondaryAction(
            title = "保存草稿",
            enabled = enabled && !model.busy,
            onClick = { model.work { model.saveDraft() } }
        )
        Spacer(Modifier.height(8.dp))
        PrimaryAction(
            title = "发送",
            enabled = enabled && !model.busy,
            onClick = {
                model.work {
                    model.send()
                    if (model.compose == null) onBack()
                }
            }
        )
    }

    if (pendingDiscard) {
        ConfirmDialog(
            title = "丢弃这封草稿？",
            onDismiss = { pendingDiscard = false },
            onConfirm = {
                pendingDiscard = false
                model.work {
                    model.discard()
                    onBack()
                }
            }
        )
    }
}

/** 附件上传：与重构前一致的上限校验（最多 30 个、总大小受 `maxAttachmentBytes` 限制）。 */
private fun addAttachments(
    model: MailModel,
    state: com.glassous.hmail.ComposeState,
    uris: List<Uri>,
    resolver: android.content.ContentResolver
) {
    if (state.uncertain || model.uploading || model.composeBusy || model.busy) return
    model.work {
        model.uploading = true
        model.changed()
        try {
            for (uri in uris) {
                if (state.attachments.size >= 30) {
                    break
                }
                val remaining = model.config.optLong("maxAttachmentBytes", 18L * 1024 * 1024) -
                    state.attachments.sumOf { it.size }
                val payload = withContext(Dispatchers.IO) {
                    var name = "附件"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > remaining) throw ApiFailure("too_large", 413, "附件总大小超出限制")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw java.io.IOException()
                    Triple(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
                }
                val attachment = model.api.upload(state.account, payload.first, payload.second, payload.third)
                state.attachments = state.attachments + attachment
                model.dirty()
                model.changed()
            }
        } finally {
            model.uploading = false
            model.dirty()
            model.changed()
        }
    }
}
