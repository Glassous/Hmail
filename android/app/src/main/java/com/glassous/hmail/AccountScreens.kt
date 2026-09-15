package com.glassous.hmail

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.json.JSONObject

class AuthFragment : MailFragment() {
    private var mode = "login"
    private var countdownJob: Job? = null
    private lateinit var submit: MaterialButton
    private var sendCode: MaterialButton? = null
    override fun build() {
        mode = when (findNavController().currentDestination?.id) { R.id.register -> "register"; R.id.reset -> "reset"; else -> "login" }
        sendCode = null
        val current = mode
        toolbar.title = when (current) { "register" -> "创建账户"; "reset" -> "重设密码"; else -> "登录" }
        if (current == "login") { toolbar.navigationIcon = null; toolbar.visibility = View.GONE }
        val ctx = requireContext()
        content.gravity = Gravity.BOTTOM
        val sidePadding = maxOf(ctx.dp(28), (resources.displayMetrics.widthPixels - ctx.dp(420)) / 2)
        content.setPadding(sidePadding, ctx.dp(32), sidePadding, ctx.dp(40))
        content.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.hmail)
            contentDescription = "Hmail"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80)).apply {
            gravity = Gravity.START; bottomMargin = ctx.dp(32)
        })
        val group = "auth:$current"
        val email = formField("email", "邮箱地址", group = group).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val password = formField("password", if (current == "login") "密码" else "新密码", password = true, group = group)
        var code: com.google.android.material.textfield.TextInputEditText? = null
        if (current != "login") {
            code = formField("code", "验证码", group = group).apply { inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6)) }
            sendCode = action("获取验证码") { model.work {
                val address = email.text.toString().trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.error = "请输入有效邮箱地址"; return@work }
                val result = model.api.json("/auth/send-code", "POST", obj("email" to address, "purpose" to current))
                model.cooldowns[group] = System.currentTimeMillis() + result.optLong("cooldown", 60) * 1000
                model.notice("验证码已发送")
            } }
        }
        submit = action(toolbar.title.toString(), true) { model.work {
            val address = email.text.toString().trim(); val pass = password.text.toString()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.error = "请输入有效邮箱地址"; return@work }
            if (pass.length !in 10..128) { password.error = "密码需为 10 至 128 个字符"; return@work }
            val data = obj("email" to address, "password" to pass)
            if (current != "login") {
                if (!Regex("[0-9]{6}").matches(code?.text.toString())) { code?.error = "请输入 6 位验证码"; return@work }
                data.put("code", code?.text.toString())
            }
            val result = model.api.json("/auth/$current", "POST", data)
            model.forms.remove(group); password.setText(""); code?.setText("")
            if (current == "reset") { model.notice("密码已重设"); (requireActivity() as MainActivity).root(R.id.login) }
            else { model.setSession(result) }
        } }
        if (current == "login") {
            val links = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            fun link(label: String, destination: Int) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; isAllCaps = false; minHeight = ctx.dp(48)
                strokeWidth = 0; backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setOnClickListener { go(destination) }
            }
            links.addView(link("注册", R.id.register), LinearLayout.LayoutParams(0, -2, 1f))
            links.addView(link("忘记密码", R.id.reset), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        countdownJob?.cancel()
        if (mode != "login") {
            val owner = viewLifecycleOwner
            countdownJob = owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) { update(); delay(1000) }
                }
            }
        }
    }
    override fun update() {
        if (!isAdded || view == null) return
        super.update(); val current = mode
        submit.isEnabled = !model.busy && (current == "login" || model.config.optBoolean("emailCodeEnabled"))
        val remaining = (((model.cooldowns["auth:$current"] ?: 0) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        sendCode?.apply { text = if (remaining > 0) "$remaining 秒后重发" else "获取验证码"; isEnabled = !model.busy && remaining == 0L && model.config.optBoolean("emailCodeEnabled") }
    }
    override fun onDestroyView() {
        countdownJob?.cancel(); countdownJob = null; sendCode = null
        super.onDestroyView()
    }
}

class SettingsFragment : MailFragment() {
    override val title = "设置"
    override fun build() {
        text(model.user?.str("email") ?: "", 20f)
        action("邮箱管理") { go(R.id.accounts) }
        action("修改密码") { go(R.id.password) }
        text("外观", 18f)
        val group = RadioGroup(requireContext())
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
            group.addView(RadioButton(requireContext()).apply {
                id = View.generateViewId(); text = label; setPadding(0, requireContext().dp(8), 0, requireContext().dp(8)); isChecked = model.theme == value
                setOnClickListener { model.work { model.api.json("/me", "PATCH", obj("theme" to value)); model.theme = value; model.vault.write("theme", value) } }
            })
        }
        content.addView(group)
        action("退出登录") { model.work { model.saveDraft(); model.api.json("/auth/logout", "POST"); model.clearSession() } }
    }
}

class AccountsFragment : MailFragment() {
    override val title = "邮箱管理"
    private var signature = ""
    override fun build() { render() }
    private fun render() {
        content.removeAllViews()
        model.accounts.forEach { account ->
            text(account.email, 18f)
            if (account.status == "reconnect") text("需要重新连接", muted = true)
            action("重新连接") { model.form("ConnectFragment")["email"] = account.email; go(R.id.connect) }
            action("断开邮箱") { confirm("断开 ${account.email}？") { model.work { model.api.json("/gmail-accounts/${enc(account.id)}", "DELETE"); model.refreshAccounts(); model.notice("已断开连接") } } }
        }
        action("连接邮箱", true) { go(R.id.connect) }
    }
    override fun update() { super.update(); val current = model.accounts.toString(); if (signature != current) { signature = current; render() } }
}

class PasswordFragment : MailFragment() {
    override val title = "修改密码"
    override fun build() {
        val current = formField("current", "当前密码", true)
        val password = formField("password", "新密码", true)
        action("保存并重新登录", true) { model.work {
            if (password.text.toString().length !in 10..128) { password.error = "密码需为 10 至 128 个字符"; return@work }
            model.saveDraft(); model.api.json("/me/password", "POST", obj("currentPassword" to current.text.toString(), "password" to password.text.toString()))
            current.setText(""); password.setText(""); model.clearSession(); model.notice("密码已修改")
        } }
    }
}

class ConnectFragment : MailFragment() {
    override val title = "连接邮箱"
    private lateinit var connect: MaterialButton
    private var google: MaterialButton? = null
    override fun build() {
        if (model.config.optBoolean("oauthEnabled")) google = action("使用 Google 连接") { model.work {
            val result = model.api.json("/gmail-accounts/oauth/mobile/start", "POST")
            model.vault.write("oauth", result.str("ticket")); openExternal(result.str("url"))
        } }
        val values = model.form("ConnectFragment")
        val email = formField("email", "邮箱地址").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val password = formField("password", "密码或授权码", true)
        val presets = model.config.array("mailProviders").objects()
        val picker = Spinner(requireContext()).apply { contentDescription = "邮箱服务商"; adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, presets.map { it.str("name") } + "其他邮箱") }
        content.addView(picker, LinearLayout.LayoutParams(-1, requireContext().dp(56)))
        val imapHost = formField("imapHost", "收信服务器")
        val imapPort = formField("imapPort", "收信端口", default = "993").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        fun security(key: String): Spinner {
            return Spinner(requireContext()).apply {
                contentDescription = if (key == "imapSecurity") "收信加密方式" else "发信加密方式"
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("SSL/TLS", "STARTTLS"))
                setSelection(if (values[key] == "starttls") 1 else 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { values[key] = if (position == 0) "ssl" else "starttls" }
                }
                content.addView(this, LinearLayout.LayoutParams(-1, requireContext().dp(56)))
            }
        }
        val imapSecurity = security("imapSecurity")
        val smtpHost = formField("smtpHost", "发信服务器")
        val smtpPort = formField("smtpPort", "发信端口", default = "465").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val smtpSecurity = security("smtpSecurity")
        fun applyPreset(preset: JSONObject) {
            listOf("imap" to Pair(imapHost, imapPort), "smtp" to Pair(smtpHost, smtpPort)).forEach { (key, fields) ->
                val server = preset.getJSONObject(key); fields.first.setText(server.str("host")); fields.second.setText(server.optInt("port").toString())
                (if (key == "imap") imapSecurity else smtpSecurity).setSelection(if (server.str("security") == "starttls") 1 else 0)
            }
        }
        picker.setSelection(values["preset"]?.toIntOrNull() ?: presets.size, false)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (values["preset"] != position.toString()) presets.getOrNull(position)?.let(::applyPreset)
                values["preset"] = position.toString()
            }
        }
        email.setOnFocusChangeListener { _, focused -> if (!focused) {
            val domain = email.text.toString().substringAfter('@', "").lowercase()
            val index = presets.indexOfFirst { p -> (0 until p.array("domains").length()).any { p.array("domains").getString(it).equals(domain, true) } }
            if (index >= 0 && imapHost.text.isNullOrBlank()) { picker.setSelection(index); applyPreset(presets[index]) }
        } }
        connect = action("连接", true) { model.work {
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.text.toString().trim()).matches()) { email.error = "请输入有效邮箱地址"; return@work }
            if (password.text.isNullOrBlank()) { password.error = "请输入密码或授权码"; return@work }
            val ip = imapPort.text.toString().toIntOrNull(); val sp = smtpPort.text.toString().toIntOrNull()
            if (ip == null || ip !in 1..65535) { imapPort.error = "端口需为 1 至 65535"; return@work }
            if (sp == null || sp !in 1..65535) { smtpPort.error = "端口需为 1 至 65535"; return@work }
            val result = model.api.json("/gmail-accounts/imap", "POST", obj("email" to email.text.toString().trim(), "password" to password.text.toString(), "imapHost" to imapHost.text.toString().trim(), "imapPort" to ip, "imapSecurity" to if (imapSecurity.selectedItemPosition == 0) "ssl" else "starttls", "smtpHost" to smtpHost.text.toString().trim(), "smtpPort" to sp, "smtpSecurity" to if (smtpSecurity.selectedItemPosition == 0) "ssl" else "starttls"))
            password.setText(""); model.forms.remove("ConnectFragment"); model.refreshAccounts(); model.switchAccount(result.str("id")); model.notice("邮箱已连接"); back()
        } }
    }
    override fun update() { super.update(); connect.isEnabled = !model.busy; google?.isEnabled = !model.busy }
}

class LabelsFragment : MailFragment() {
    override val title = "管理标签"
    private var signature = ""
    override fun build() { render() }
    private fun render() {
        content.removeAllViews()
        model.labels.filter { it.type == "user" }.forEach { label -> action(label.name) { go(R.id.label_edit, Bundle().apply { putString("label", label.id) }) } }
        action("新建标签", true) { go(R.id.label_edit) }
    }
    override fun update() { super.update(); if (signature != model.labels.toString()) { signature = model.labels.toString(); render() } }
}
class LabelEditFragment : MailFragment() {
    override val title get() = if (arguments?.getString("label") == null) "新建标签" else "编辑标签"
    override fun build() {
        val id = arguments?.getString("label").orEmpty()
        val label = model.labels.find { it.id == id }
        val name = formField("name", "标签名称", default = label?.name.orEmpty(), group = "label:$id").apply { filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
        action("保存", true) { model.work {
            if (name.text.isNullOrBlank()) { name.error = "请输入标签名称"; return@work }
            model.api.json(model.path("/labels"), "POST", obj("action" to if (id.isBlank()) "create" else "rename", "id" to id, "name" to name.text.toString().trim()))
            model.labels = model.api.list(model.path("/labels")).map(MailLabel::from); model.forms.remove("label:$id"); back()
        } }
        if (label != null) action("删除标签") { confirm("删除“${label.name}”？") { model.work {
            model.api.json(model.path("/labels"), "POST", obj("action" to "delete", "id" to id))
            model.labels = model.api.list(model.path("/labels")).map(MailLabel::from)
            if (model.folder == id) model.chooseFolder("INBOX"); back()
        } } }
    }
}
class LabelPickFragment : MailFragment() {
    override val title = "选择标签"
    override fun build() {
        val labels = model.labels.filter { it.type == "user" }
        if (labels.isEmpty()) { action("新建标签", true) { go(R.id.label_edit) }; return }
        val group = RadioGroup(requireContext()); var choice = labels.first().id
        labels.forEachIndexed { i, label -> group.addView(RadioButton(requireContext()).apply { id = View.generateViewId(); text = label.name; isChecked = i == 0; setOnClickListener { choice = label.id } }) }
        content.addView(group)
        fun apply(remove: Boolean) { model.work {
            val thread = arguments?.getString("thread")
            model.modify(add = if (remove) emptyList() else listOf(choice), remove = if (remove) listOf(choice) else emptyList(), threads = thread?.let { listOf(it) } ?: model.selected.toList()); back()
        } }
        action("应用标签", true) { apply(false) }; action("移除标签") { apply(true) }
    }
    override fun onResume() { super.onResume(); content.removeAllViews(); build() }
}
