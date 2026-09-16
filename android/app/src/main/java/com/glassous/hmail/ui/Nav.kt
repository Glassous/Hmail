package com.glassous.hmail.ui

/** 全部页面路由（Navigation Compose 以代码定义，替代原 res/navigation/routes.xml）。 */
object Routes {
    const val Login = "login"
    const val Register = "register"
    const val Reset = "reset"
    const val Inbox = "inbox"
    const val Compose = "compose"
    const val Accounts = "accounts"
    const val Connect = "connect"
    const val Settings = "settings"
    const val Password = "password"
    const val Labels = "labels"

    const val Thread = "thread?account={account}&thread={thread}"
    const val LabelEdit = "label_edit?label={label}"
    const val LabelPick = "label_pick?thread={thread}"

    fun thread(account: String, thread: String) = "thread?account=${enc(account)}&thread=${enc(thread)}"
    fun labelEdit(label: String) = "label_edit?label=${enc(label)}"
    fun labelPick(thread: String) = "label_pick?thread=${enc(thread)}"

    /** 认证区页面：会话恢复/失效时就近跳转。 */
    val auth = setOf(Login, Register, Reset)

    /** 回退栈栈底页面：从这里返回即退出应用。 */
    val root = auth + Inbox

    private fun enc(value: String) = android.net.Uri.encode(value)
}

object Args {
    const val Account = "account"
    const val Thread = "thread"
    const val Label = "label"
}
