package com.glassous.hmail

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.PathParser
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import kotlin.math.roundToInt

val folders = linkedMapOf("INBOX" to "收件箱", "STARRED" to "已加星标", "SENT" to "已发送", "DRAFT" to "草稿", "ALL" to "所有邮件", "SPAM" to "垃圾邮件", "TRASH" to "回收站")
/** 认证区页面：登录、注册、重设密码。 */
private val authPages = listOf(R.id.login, R.id.register, R.id.reset)
/** 回退栈的栈底页面，从这里返回即退出应用。 */
private val rootPages = authPages + listOf(R.id.inbox)
fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
fun Context.color(id: Int) = getColor(id)
fun Context.round(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
fun Context.label(value: String, size: Float = 16f, muted: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color(if (muted) R.color.muted else R.color.ink)); setLineSpacing(dp(3).toFloat(), 1f)
}
fun Context.button(title: String, primary: Boolean = false, click: () -> Unit) = MaterialButton(this, null,
    if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
    text = title; isAllCaps = false; minHeight = dp(48); cornerRadius = dp(24); setOnClickListener { click() }
}
fun Fragment.openExternal(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in listOf("https", "http", "mailto")) return
    try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
    catch (_: Exception) { (requireActivity() as MainActivity).model.notice("没有可打开此链接的应用") }
}

class MailIcon(
    private val type: String,
    private val tint: Int,
    size: Int = 24,
    density: Float = Resources.getSystem().displayMetrics.density
) : Drawable() {
    private val pixels = (size * density).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val path = when (type) {
            "menu" -> "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18"
            "back" -> "M19,12 L5,12 M11,6 L5,12 L11,18"
            "search" -> "M16,16 L21,21 M18,10 A8,8 0,1 1,2,10 A8,8 0,1 1,18,10"
            "star", "STARRED" -> "M12,3 L14.8,8.8 L21,9.7 L16.5,14.1 L17.6,20.3 L12,17.3 L6.4,20.3 L7.5,14.1 L3,9.7 L9.2,8.8 Z"
            "send", "SENT" -> "M3,3 L22,12 L3,21 L6,12 Z M6,12 L22,12"
            "edit", "DRAFT" -> "M4,16 L16,4 L20,8 L8,20 L4,20 Z M14,6 L18,10"
            "TRASH", "trash" -> "M3,6 L21,6 M9,6 L9,3 L15,3 L15,6 M6,6 L7,21 L17,21 L18,6 M10,10 L10,17 M14,10 L14,17"
            "SPAM" -> "M12,3 L22,21 L2,21 Z M12,9 L12,14 M12,17 L12,18"
            "tag" -> "M3,3 L12,3 L22,13 L13,22 L3,12 Z M7,7 L7.1,7.1"
            "refresh" -> "M20,10 A8,8 0,1 0,19,17 M20,4 L20,10 L14,10"
            "more" -> "M12,5 L12,5.1 M12,12 L12,12.1 M12,19 L12,19.1"
            "check" -> "M4,12 L9,17 L20,6"
            "attach" -> "M8,15 L15,8 A2,2 0,0 1,18,11 L9,20 A4,4 0,0 1,3,14 L14,3 A5,5 0,0 1,21,10 L11,20"
            "settings" -> "M9,3 L15,3 L16,6 L19,7 L22,12 L19,17 L16,18 L15,21 L9,21 L8,18 L5,17 L2,12 L5,7 L8,6 Z M16,12 A4,4 0,1 1,8,12 A4,4 0,1 1,16,12"
            else -> "M3,5 L21,5 L21,19 L3,19 Z M3,6 L12,13 L21,6"
        }
        PathParser.createPathFromPathData(path)?.let { canvas.drawPath(it, paint) }; canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = pixels
    override fun getIntrinsicHeight() = pixels
}

/** DrawerLayout 默认只响应屏幕边缘拖动，这里让页面内任意区域的右滑也能呼出侧栏。 */
private class SwipeDrawerLayout(ctx: Context) : DrawerLayout(ctx) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y
                // 左边缘仍交给 DrawerLayout 原生拖动；抽屉已打开时不重复处理。
                tracking = !isDrawerVisible(GravityCompat.START) && ev.x > context.dp(32)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = ev.x - startX
                val dy = ev.y - startY
                // 只认横向占主导的右滑，避免与列表点击、纵向滚动互相干扰。
                if (dx > context.dp(48) && dx > kotlin.math.abs(dy) * 2f) { tracking = false; openDrawer(GravityCompat.START) }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.onInterceptTouchEvent(ev)
    }
}

class MainActivity : AppCompatActivity() {
    val model get() = MailModel.of(application)
    private lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView
    lateinit var host: NavHostFragment
    private var appliedTheme = ""
    private var drawerSignature = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(Vault(this).read("theme") ?: "system")
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        drawer = SwipeDrawerLayout(this).apply { setBackgroundColor(color(R.color.background)) }
        val content = FrameLayout(this).apply { id = R.id.main_content }
        drawer.addView(content, DrawerLayout.LayoutParams(-1, -1))
        navigation = NavigationView(this).apply {
            setBackgroundColor(color(R.color.background)); itemIconTintList = ColorStateList.valueOf(color(R.color.muted))
            itemTextColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(R.color.on_selected), color(R.color.ink)))
            itemBackground = android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_checked), round(color(R.color.selected), 28)); addState(intArrayOf(), round(Color.TRANSPARENT)) }
            setItemHorizontalPadding(dp(20)); setItemVerticalPadding(dp(4))
        }
        drawer.addView(navigation, DrawerLayout.LayoutParams(minOf(dp(336), resources.displayMetrics.widthPixels - dp(48)), -1, Gravity.START))
        setContentView(drawer)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            navigation.setPadding(bars.left, bars.top, 0, bars.bottom); insets
        }
        host = (supportFragmentManager.findFragmentById(R.id.main_content) as? NavHostFragment) ?: NavHostFragment.create(R.navigation.routes).also {
            supportFragmentManager.beginTransaction().replace(R.id.main_content, it).setPrimaryNavigationFragment(it).commitNow()
        }
        host.navController.addOnDestinationChangedListener { _, destination, _ ->
            drawer.setDrawerLockMode(if (destination.id == R.id.inbox) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = host.navController.currentDestination?.id
                when {
                    // 抽屉只要露出（含正在拖出）就优先关闭，避免返回键直接退出应用。
                    drawer.isDrawerVisible(GravityCompat.START) -> drawer.closeDrawers()
                    current == R.id.inbox && model.selected.isNotEmpty() -> { model.selected.clear(); model.changed() }
                    current == R.id.compose -> {
                        if (model.uploading || model.composeBusy || model.busy) return
                        model.work { model.closeCompose(); host.navController.popBackStack() }
                    }
                    // 栈底页面直接退出：弹栈只会在原地留下空白页，也会让登录后按返回又回到认证页。
                    current in rootPages -> finish()
                    !host.navController.popBackStack() -> finish()
                }
            }
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.events.collect { Snackbar.make(drawer, it, Snackbar.LENGTH_LONG).setAction("关闭") {}.show() } }
            launch { model.revision.collect {
                applyTheme(model.theme); updateDrawer()
                // 会话恢复期间 user 尚未就绪，此时不能据此判定为未登录。
                if (model.starting) return@collect
                val current = host.navController.currentDestination?.id
                when {
                    // 已登录：仍停留在认证页时落到主页。
                    model.user != null && (current == null || current in authPages) -> root(R.id.inbox)
                    // 未登录：登出、会话失效或恢复失败时回到登录页。
                    model.user == null && (current == null || current !in authPages) -> root(R.id.login)
                }
            } }
            // 邮件只在前台手动刷新或冷启动时同步，不做定时轮询。
            launch { while (isActive) { if (model.user != null && model.vault.read("oauth") != null) { try { model.checkOauth() } catch (e: Exception) { if (e is ApiFailure && e.code == "unauthorized") model.fail(e) } }; delay(3000) } }
        } }
        if (!model.initialized) model.boot()
    }
    override fun onStart() {
        super.onStart()
        // 首屏由本地会话决定，不等任何请求：有会话直接进主页，进入后再加载数据。
        // 放在 onStart 是为了避开 onCreate 内尚未稳定的 Fragment 事务状态，仍早于首次绘制。
        if (model.loggedIn && host.navController.currentDestination?.id == R.id.login) root(R.id.inbox)
    }
    private fun applyTheme(value: String) {
        if (appliedTheme == value) return
        appliedTheme = value
        AppCompatDelegate.setDefaultNightMode(when (value) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "dark" -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
    }
    fun root(id: Int) {
        if (host.navController.currentDestination?.id == id || supportFragmentManager.isStateSaved) return
        host.navController.navigate(id, null, NavOptions.Builder().setPopUpTo(R.id.routes, true).build())
    }
    fun showDrawer() { updateDrawer(); drawer.openDrawer(Gravity.START) }
    private fun updateDrawer() {
        if (!::navigation.isInitialized) return
        val signature = model.accounts.toString() + model.labels.toString() + model.active + model.folder + model.theme
        if (signature == drawerSignature) return
        drawerSignature = signature
        while (navigation.headerCount > 0) navigation.removeHeaderView(navigation.getHeaderView(0))
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(12)) }
        header.addView(label("Hmail", 27f).apply { setTypeface(null, Typeface.BOLD); setTextColor(color(R.color.brand)) })
        val account = accountsSpinner()
        header.addView(account, LinearLayout.LayoutParams(-1, dp(60)))
        header.addView(button("连接邮箱") { drawer.closeDrawers(); host.navController.navigate(R.id.connect) })
        navigation.addHeaderView(header)
        navigation.menu.clear()
        folders.entries.forEachIndexed { i, entry ->
            navigation.menu.add(1, i + 100, i, entry.value).apply { isCheckable = true; isChecked = model.folder == entry.key; icon = MailIcon(entry.key, color(R.color.muted)); setOnMenuItemClickListener { drawer.closeDrawers(); model.chooseFolder(entry.key); true } }
        }
        model.labels.filter { it.type == "user" }.forEachIndexed { i, entry ->
            navigation.menu.add(2, i + 300, i + 100, entry.name).apply { isCheckable = true; isChecked = model.folder == entry.id; icon = MailIcon("tag", color(R.color.muted)); setOnMenuItemClickListener { drawer.closeDrawers(); model.chooseFolder(entry.id); true } }
        }
        listOf("管理标签" to R.id.labels, "设置" to R.id.settings).forEachIndexed { i, entry -> navigation.menu.add(3, 900 + i, 900 + i, entry.first).apply {
            icon = MailIcon(if (i == 0) "tag" else "settings", color(R.color.muted)); setOnMenuItemClickListener { drawer.closeDrawers(); host.navController.navigate(entry.second); true }
        } }
    }
    private fun accountsSpinner() = Spinner(this).apply {
        val values = model.accounts.map { it.email }.ifEmpty { listOf("尚未连接邮箱") }
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(model.accounts.indexOfFirst { it.id == model.active }.coerceAtLeast(0), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (view == null) return // 布局初始化触发的回调，不是用户选择；误切邮箱会清空并重载列表。
                model.accounts.getOrNull(position)?.let { if (it.id != model.active) model.switchAccount(it.id) }
            }
        }
    }
}

abstract class MailFragment : Fragment() {
    val model get() = (requireActivity() as MainActivity).model
    lateinit var shell: LinearLayout
    lateinit var toolbar: MaterialToolbar
    lateinit var content: LinearLayout
    lateinit var progress: ProgressBar
    open val title = "Hmail"
    open val scroll = true
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        shell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ctx.color(R.color.background)) }
        toolbar = MaterialToolbar(ctx).apply {
            title = this@MailFragment.title; setTitleTextColor(ctx.color(R.color.ink)); setBackgroundColor(ctx.color(R.color.background))
            navigationIcon = MailIcon("back", ctx.color(R.color.ink)); navigationContentDescription = "返回"; setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
        shell.addView(toolbar, LinearLayout.LayoutParams(-1, ctx.dp(64)))
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        shell.addView(progress, LinearLayout.LayoutParams(-1, ctx.dp(2)))
        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(24)) }
        if (scroll) shell.addView(ScrollView(ctx).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        build(); return shell
    }
    abstract fun build()
    open fun update() { progress.visibility = if (model.busy) View.VISIBLE else View.GONE }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { model.revision.collect { update() } } }
    }
    fun go(id: Int, args: Bundle? = null) { if (isAdded && findNavController().currentDestination?.id != id) findNavController().navigate(id, args) }
    fun back() { if (isAdded) findNavController().popBackStack() }
    fun text(value: String, size: Float = 16f, muted: Boolean = false) = requireContext().label(value, size, muted).also { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = requireContext().dp(16) }) }
    fun action(value: String, primary: Boolean = false, click: () -> Unit): MaterialButton = requireContext().button(value, primary, click).also { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dp(8) }) }
    fun field(name: String, value: String = "", password: Boolean = false, multiline: Boolean = false, edit: (String) -> Unit = {}): TextInputEditText {
        val ctx = requireContext()
        val box = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = name; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; setBoxCornerRadii(ctx.dp(16).toFloat(), ctx.dp(16).toFloat(), ctx.dp(16).toFloat(), ctx.dp(16).toFloat()); if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE }
        val input = TextInputEditText(ctx).apply {
            setText(value); textSize = 16f; setTextColor(ctx.color(R.color.ink)); isSaveEnabled = false
            inputType = when { password -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; multiline -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; else -> android.text.InputType.TYPE_CLASS_TEXT }
            if (multiline) { minLines = 10; gravity = Gravity.TOP } else setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edit(s.toString()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2)); content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(16) }); return input
    }
    fun formField(key: String, name: String, password: Boolean = false, default: String = "", group: String = javaClass.simpleName): TextInputEditText {
        val values = model.form(group); return field(name, values[key] ?: default, password) { values[key] = it }
    }
    fun menu(title: String, icon: String? = null, click: () -> Unit) {
        toolbar.menu.add(title).apply { if (icon != null) { this.icon = MailIcon(icon, requireContext().color(R.color.ink)); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }; setOnMenuItemClickListener { click(); true } }
    }
    fun confirm(title: String, click: () -> Unit) { MaterialAlertDialogBuilder(requireContext()).setTitle(title).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> click() }.show() }
}
