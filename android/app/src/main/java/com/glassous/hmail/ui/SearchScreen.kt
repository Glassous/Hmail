package com.glassous.hmail.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glassous.hmail.MailModel
import com.glassous.hmail.ui.common.HmailField
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.PrimaryAction

@Composable
fun SearchScreen(model: MailModel, onBack: () -> Unit) {
    revisionOf(model)
    var query by remember { mutableStateOf(model.query) }
    val run: () -> Unit = {
        model.search(query)
        onBack()
    }
    MailPage(title = "搜索邮件", onBack = onBack, progress = model.busy) {
        HmailField(
            value = query,
            onValueChange = { query = it },
            label = "搜索",
            imeAction = ImeAction.Search,
            onImeAction = run
        )
        Spacer(Modifier.height(8.dp))
        PrimaryAction("搜索", enabled = !model.busy, onClick = run)
    }
}
