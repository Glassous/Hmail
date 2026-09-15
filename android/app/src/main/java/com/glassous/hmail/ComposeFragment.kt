package com.glassous.hmail

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ComposeFragment : MailFragment() {
    override val title = "写邮件"
    private val inputs = mutableListOf<TextInputEditText>()
    private lateinit var attachments: LinearLayout
    private lateinit var status: TextView
    private lateinit var uncertain: TextView
    private lateinit var sendButton: MaterialButton
    private lateinit var attachButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var checkSent: MaterialButton
    private var attachmentSignature = ""
    private var hadCompose = false
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) upload(uris)
    }
    override fun build() {
        val state = model.compose
        if (state == null) { text("暂无未完成的邮件"); return }
        hadCompose = true; inputs.clear()
        text(model.accounts.find { it.id == state.account }?.email ?: "", 14f, true)
        fun input(key: String, title: String, multiline: Boolean = false): TextInputEditText {
            val view = field(title, state.payload.str(key), multiline = multiline) { value ->
                if (!state.uncertain) { state.payload.put(key, value); model.dirty() }
            }
            if (key in listOf("to", "cc", "bcc")) view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            inputs.add(view); return view
        }
        input("to", "收件人")
        val toggle = action("抄送与密送") {}
        val cc = input("cc", "抄送"); val bcc = input("bcc", "密送")
        fun showRecipients(show: Boolean) { (cc.parent.parent as View).visibility = if (show) View.VISIBLE else View.GONE; (bcc.parent.parent as View).visibility = if (show) View.VISIBLE else View.GONE }
        var expanded = model.form("compose:${state.payload.str("composeId")}")["expanded"] == "true" || state.payload.str("cc").isNotBlank() || state.payload.str("bcc").isNotBlank()
        showRecipients(expanded)
        toggle.setOnClickListener { expanded = !expanded; model.form("compose:${state.payload.str("composeId")}")["expanded"] = expanded.toString(); showRecipients(expanded) }
        input("subject", "主题").filters = arrayOf(android.text.InputFilter.LengthFilter(998))
        input("text", "邮件正文", true).filters = arrayOf(android.text.InputFilter.LengthFilter(500000))
        attachments = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(attachments)
        uncertain = text("发送结果待确认，请先查看已发送。", 14f).apply { setTextColor(requireContext().color(R.color.error)) }
        checkSent = action("查看已发送") {
            model.switchAccount(state.account); model.chooseFolder("SENT"); (requireActivity() as MainActivity).root(R.id.inbox)
        }
        status = text(model.savedText, 12f, true)
        attachButton = action("添加附件") { if (!model.uploading && !model.composeBusy && !state.uncertain) files.launch(arrayOf("*/*")) }
        saveButton = action("保存草稿") { model.work { model.saveDraft(); model.notice("草稿已保存") } }
        sendButton = action("发送", true) { model.work { model.send(); if (model.compose == null) back() } }
        menu("丢弃草稿", "trash") { if (!model.uploading && !model.composeBusy && !model.busy) confirm("丢弃这封草稿？") { model.work { model.discard(); back() } } }
    }
    private fun upload(uris: List<Uri>) {
        val state = model.compose ?: return
        if (state.uncertain || model.uploading || model.composeBusy || model.busy) return
        val resolver = requireContext().applicationContext.contentResolver
        model.work {
            model.uploading = true; model.changed()
            try {
                for (uri in uris) {
                    if (state.attachments.size >= 30) { model.notice("最多添加 30 个附件"); break }
                    val remaining = model.config.optLong("maxAttachmentBytes", 18L * 1024 * 1024) - state.attachments.sumOf { it.size }
                    val upload = withContext(Dispatchers.IO) {
                        var name = "附件"
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name }
                        val bytes = resolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                total += count; if (total > remaining) throw ApiFailure("too_large", 413, "附件总大小超出限制")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw java.io.IOException()
                        Triple(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
                    }
                    val attachment = model.api.upload(state.account, upload.first, upload.second, upload.third)
                    state.attachments = state.attachments + attachment; model.dirty(); model.changed()
                }
            } finally { model.uploading = false; model.dirty(); model.changed() }
        }
    }
    override fun update() {
        super.update()
        val state = model.compose ?: return
        if (!hadCompose) return
        inputs.forEach { it.isEnabled = !state.uncertain }
        sendButton.isEnabled = !model.busy && !model.composeBusy && !model.uploading && !state.uncertain
        attachButton.isEnabled = sendButton.isEnabled
        saveButton.isEnabled = sendButton.isEnabled
        checkSent.visibility = if (state.uncertain) View.VISIBLE else View.GONE
        checkSent.isEnabled = !model.busy && !model.composeBusy
        uncertain.visibility = if (state.uncertain) View.VISIBLE else View.GONE
        status.text = if (model.uploading) "正在添加附件" else model.savedText
        val signature = state.attachments.toString() + state.uncertain + model.composeBusy + model.uploading
        if (signature != attachmentSignature) {
            attachmentSignature = signature; attachments.removeAllViews()
            state.attachments.forEach { attachment ->
                val row = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(requireContext().label("${attachment.name}\n${sizeText(attachment.size)}", 13f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(requireContext().button("移除") { state.attachments = state.attachments - attachment; model.dirty(); model.changed() }.apply { isEnabled = !state.uncertain && !model.composeBusy && !model.uploading })
                attachments.addView(row)
            }
        }
    }
    override fun onStop() { model.persistCompose(); super.onStop() }
}
