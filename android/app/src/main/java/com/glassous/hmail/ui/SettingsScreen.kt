package com.glassous.hmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.glassous.hmail.MailModel
import com.glassous.hmail.obj
import com.glassous.hmail.str
import com.glassous.hmail.ui.common.MailPage
import com.glassous.hmail.ui.common.SecondaryAction
import com.glassous.hmail.ui.common.SectionText
import com.glassous.hmail.ui.common.SectionTitle

@Composable
fun SettingsScreen(model: MailModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    revisionOf(model)
    MailPage(title = "设置", onBack = onBack, progress = model.busy) {
        SectionText(model.user?.str("email") ?: "", size = 20f)
        SecondaryAction("邮箱管理") { onNavigate(Routes.Accounts) }
        Spacer(Modifier.height(8.dp))
        SecondaryAction("修改密码") { onNavigate(Routes.Password) }
        Spacer(Modifier.height(16.dp))
        SectionTitle("外观")
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        model.work {
                            model.api.json("/me", "PATCH", obj("theme" to value))
                            model.theme = value
                            model.vault.write("theme", value)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = model.theme == value, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryAction("退出登录") {
            model.work {
                model.saveDraft()
                model.api.json("/auth/logout", "POST")
                model.clearSession()
            }
        }
    }
}
