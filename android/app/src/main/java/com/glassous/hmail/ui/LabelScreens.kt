package com.glassous.hmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glassous.hmail.MailLabel
import com.glassous.hmail.MailModel
import com.glassous.hmail.obj
import com.glassous.hmail.ui.common.ConfirmDialog
import com.glassous.hmail.ui.common.HmailField
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.PrimaryAction
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SectionText

@Composable
fun LabelsScreen(model: MailModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    revisionOf(model)
    val labels = model.labels.filter { it.type == "user" }
    MailPage(title = "管理标签", onBack = onBack, progress = model.busy) {
        if (model.mailbox.labelsLoading) SectionText("正在加载标签", muted = true)
        model.mailbox.labelsError?.let { SecondaryAction(it) { model.refreshLabels() } }
        if (labels.isEmpty() && !model.mailbox.labelsLoading && model.mailbox.labelsError == null) SectionText("还没有标签", muted = true)
        labels.forEach { label ->
            SecondaryAction(label.name) { onEdit(label.id) }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrimaryAction("新建标签") { onEdit("") }
    }
}

@Composable
fun LabelEditScreen(model: MailModel, labelId: String, onBack: () -> Unit) {
    revisionOf(model)
    val label = model.labels.find { it.id == labelId }
    val group = "label:$labelId"
    val values = model.form(group)
    var name by remember { mutableStateOf(values["name"] ?: label?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf(false) }

    MailPage(title = if (labelId.isBlank()) "新建标签" else "编辑标签", onBack = onBack, progress = model.busy) {
        HmailField(
            value = name,
            onValueChange = { input -> name = input; values["name"] = input; error = null },
            label = "标签名称",
            isError = error != null,
            errorText = error
        )
        Spacer(Modifier.height(8.dp))
        PrimaryAction("保存") {
            model.work {
                val trimmed = name.trim()
                if (trimmed.isBlank()) {
                    error = "请输入标签名称"
                    return@work
                }
                val aid = model.active
                model.api.json(
                    model.path("/labels", aid), "POST",
                    obj("action" to if (labelId.isBlank()) "create" else "rename", "id" to labelId, "name" to trimmed)
                )
                model.refreshLabels(aid)
                model.forms.remove(group)
                onBack()
            }
        }
        if (label != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryAction("删除标签") { pendingDelete = true }
        }
    }

    if (label != null && pendingDelete) {
        ConfirmDialog(
            title = "删除“${label.name}”？",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                pendingDelete = false
                model.work {
                    val aid = model.active
                    model.api.json(model.path("/labels", aid), "POST", obj("action" to "delete", "id" to labelId))
                    model.refreshLabels(aid)
                    if (model.active == aid && model.folder == labelId) model.chooseFolder("INBOX")
                    onBack()
                }
            }
        )
    }
}

@Composable
fun LabelPickScreen(
    model: MailModel,
    threadId: String,
    onBack: () -> Unit,
    onCreateLabel: () -> Unit = onBack
) {
    revisionOf(model)
    val labels = model.labels.filter { it.type == "user" }
    var choice by remember { mutableStateOf(labels.firstOrNull()?.id.orEmpty()) }

    MailPage(title = "选择标签", onBack = onBack, progress = model.busy) {
        if (model.mailbox.labelsLoading) SectionText("正在加载标签", muted = true)
        model.mailbox.labelsError?.let { SecondaryAction(it) { model.refreshLabels() } }
        if (labels.isEmpty()) {
            PrimaryAction("新建标签") { onCreateLabel() }
            return@MailPage
        }
        labels.forEach { label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { choice = label.id },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = choice == label.id, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryAction("应用标签") { apply(model, model.labels, threadId, choice, remove = false, onBack = onBack) }
        Spacer(Modifier.height(8.dp))
        SecondaryAction("移除标签") { apply(model, model.labels, threadId, choice, remove = true, onBack = onBack) }
    }
}

private fun apply(
    model: MailModel,
    labels: List<MailLabel>,
    threadId: String,
    choice: String,
    remove: Boolean,
    onBack: () -> Unit
) {
    if (labels.none { it.id == choice }) return
    model.work {
        val threads = if (threadId.isBlank()) model.selected.toList() else listOf(threadId)
        model.modify(
            add = if (remove) emptyList() else listOf(choice),
            remove = if (remove) listOf(choice) else emptyList(),
            threads = threads
        )
        onBack()
    }
}
