package com.glassous.hmail.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.ui.theme.HmailTheme

/** 状态栏高度（安全区顶部）。 */
@Composable
fun topInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** 系统导航栏高度（安全区底部）。 */
@Composable
fun bottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** 页面通用文字段落，等价于重构前的 `MailFragment.text()`。 */
@Composable
fun SectionText(
    value: String,
    modifier: Modifier = Modifier,
    size: Float = 16f,
    muted: Boolean = false,
    bold: Boolean = false
) {
    Text(
        text = value,
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (muted) HmailTheme.colors.muted else MaterialTheme.colorScheme.onBackground
    )
}

/** 小标题，等价于重构前 `MailFragment.text(value, 18f)`。 */
@Composable
fun SectionTitle(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** 文本输入框：统一样式的 OutlinedTextField（16dp 圆角、可选密码切换与多行）。 */
@Composable
fun HmailField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    maxLines: Int = if (multiline) 14 else 1,
    minLines: Int = if (multiline) 10 else 1,
    trailing: (@Composable () -> Unit)? = null
) {
    var revealed by remember { mutableStateOf(false) }
    val errorColor = MaterialTheme.colorScheme.error
    val trailingContent: (@Composable () -> Unit)? = if (password) {
        {
            IconButton(onClick = { revealed = !revealed }) {
                MailIcon(if (revealed) "eye_off" else "eye", contentDescription = if (revealed) "隐藏密码" else "显示密码")
            }
        }
    } else {
        trailing
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        isError = isError || errorText != null,
        singleLine = !multiline,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onSearch = { onImeAction?.invoke() }, onDone = { onImeAction?.invoke() }),
        supportingText = errorText?.let { { Text(it, color = errorColor) } },
        trailingIcon = trailingContent,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = HmailTheme.colors.outline,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorBorderColor = errorColor
        )
    )
}

/** 主按钮（实心），等价于重构前的 `Context.button(primary = true)`。 */
@Composable
fun PrimaryAction(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) { Text(title, style = MaterialTheme.typography.labelLarge) }
}

/** 次按钮（描边），等价于重构前的 `Context.button(primary = false)`。 */
@Composable
fun SecondaryAction(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
    ) { Text(title, style = MaterialTheme.typography.labelLarge) }
}

/** 无边框文字按钮，用于登录页的"注册/忘记密码"一类次级入口。 */
@Composable
fun TextAction(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ConfirmDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 非主页使用固定紧凑的 MD3 顶栏；无标题页面维持原有布局。 */
@Composable
fun MailPage(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    progress: Boolean = false,
    scroll: Boolean = true,
    horizontalPadding: Dp = 20.dp,
    bottomPadding: Dp = 24.dp,
    navigationIcon: String = "back",
    navigationDescription: String = "返回",
    actions: List<GlassAction> = emptyList(),
    contentArrangement: Arrangement.Vertical = Arrangement.Top,
    /** 页面根容器（背景与全部组件）的修饰符（共享元素过渡用）。 */
    pageModifier: Modifier = Modifier,
    /** 顶栏标题文字的修饰符（共享元素过渡用）。 */
    titleTextModifier: Modifier = Modifier,
    /** 页面级浮层，参数是顶栏下方的内容起始位置。 */
    overlay: (@Composable BoxScope.(Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    val scrollState = rememberScrollState()
    val top = topInset()
    val topSpace = if (title == null) top + 8.dp else top + 64.dp

    Box(modifier.fillMaxSize().background(background).then(pageModifier)) {
        // 内边距放在 scroll 之内：顶部留白会随内容滚走，页面内容才能在半透明顶栏后面穿过。
        val body = Modifier
            .fillMaxSize()
            .imePadding()
        val insets = Modifier.padding(
            start = horizontalPadding,
            end = horizontalPadding,
            top = topSpace,
            bottom = bottomPadding + bottomInset()
        )
        if (scroll) {
            Column(
                modifier = body.verticalScroll(scrollState).then(insets),
                verticalArrangement = contentArrangement,
                content = content
            )
        } else {
            Column(
                modifier = body.then(insets),
                verticalArrangement = contentArrangement,
                content = content
            )
        }
        if (title != null) {
            NativeTopBar(
                title = title,
                navigationIcon = navigationIcon,
                navigationDescription = navigationDescription,
                onNavigationClick = onBack,
                actions = actions,
                titleTextModifier = titleTextModifier,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        if (progress) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopStart)
            )
        }
        overlay?.invoke(this, topSpace)
    }
}

/** 表单行的统一包装：左右两个按钮平分宽度。 */
@Composable
fun SplitRow(first: @Composable RowScope.() -> Unit, second: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.weight(1f), content = first)
        Row(modifier = Modifier.weight(1f), content = second)
    }
}
