package cn.lhup.wechatmood

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.lhup.wechatmood.analysis.LoginPoll
import cn.lhup.wechatmood.analysis.LoginStart
import cn.lhup.wechatmood.analysis.OpenAiCompatible
import cn.lhup.wechatmood.analysis.RemoteModel
import cn.lhup.wechatmood.analysis.WorkBuddy
import cn.lhup.wechatmood.core.AndroidCacheDatabase
import cn.lhup.wechatmood.core.AppConfig
import cn.lhup.wechatmood.core.AppConfigStore
import cn.lhup.wechatmood.core.Diagnostics
import cn.lhup.wechatmood.core.License
import cn.lhup.wechatmood.core.LicenseClient
import cn.lhup.wechatmood.core.LicenseInfo
import cn.lhup.wechatmood.core.LicenseState
import cn.lhup.wechatmood.core.ModulePrefs
import cn.lhup.wechatmood.core.PromptSettings
import cn.lhup.wechatmood.core.SharedStorage
import cn.lhup.wechatmood.core.WorkBuddyRegion
import cn.lhup.wechatmood.reply.ReplyIdentityStore
import cn.lhup.wechatmood.reply.ReplyRole
import cn.lhup.wechatmood.reply.ReplyRoleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 微话助手设置页：聊天分析、回复与话题、激活、关于。 */
class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private val tabs = mutableListOf<TextView>()
    private var page = 0
    private var loginJob: Job? = null
    private var loginState: TextView? = null

    private val blue = Color.parseColor("#2F6FE4")
    private val blueLight = Color.parseColor("#5C97FF")
    private val blueDeep = Color.parseColor("#2359C6")
    private val page0 = Color.parseColor("#F7FAFF")
    private val page1 = Color.parseColor("#EAF1FB")
    private val ink = Color.parseColor("#10263F")
    private val muted = Color.parseColor("#5C7695")
    private val card = Color.parseColor("#FFFFFF")
    private val border = Color.parseColor("#E4EDF9")
    private val soft = Color.parseColor("#EEF4FE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedStorage.init(this)
        ModulePrefs.init(this)
        window.statusBarColor = page0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = page1
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(buildRoot())
        select(0)
    }

    override fun onResume() {
        super.onResume()
        if (page == 0) select(0)
    }

    override fun onDestroy() {
        loginJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(page0, page1))
        }
        root.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildTabs())
        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(20), dp(18), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "微"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradient(intArrayOf(blueLight, blue), 13)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "v${BuildConfig.VERSION_NAME} · 聊天分析助手"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "运行日志"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(blue)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pressable(rounded(Color.WHITE, 18, border), blue)
            setOnClickListener { Diagnostics.show(this@MainActivity) }
        }, LinearLayout.LayoutParams(-2, -2))
        return header
    }

    private fun buildTabs(): View {
        tabs.clear()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(14))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.WHITE, 22, border)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = dp(8).toFloat()
        }
        listOf("聊天分析", "回复与话题", "激活", "关于").forEachIndexed { index, title ->
            val tab = TextView(this).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(42)
                setTextColor(muted)
                isClickable = true
                isFocusable = true
                setOnClickListener { select(index) }
            }
            tabs += tab
            row.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        wrapper.addView(row, LinearLayout.LayoutParams(-1, -2))
        return wrapper
    }

    private fun select(index: Int) {
        page = index
        tabs.forEachIndexed { position, tab ->
            val active = position == index
            tab.setTextColor(if (active) Color.WHITE else muted)
            tab.setTypeface(tab.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            tab.background = if (active) gradient(intArrayOf(blueLight, blue), 17) else null
        }
        content.removeAllViews()
        when (index) {
            0 -> analysisPage()
            1 -> rolesPage()
            2 -> activationPage()
            else -> aboutPage()
        }
    }

    // ---------------------------------------------------------------- 聊天分析

    private fun analysisPage() {
        val config = AppConfigStore.load(force = true)
        content.addView(section("存储与权限"))
        content.addView(card {
            addView(caption("共享目录：${SharedStorage.displayPath()}"))
            addView(caption(
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q)
                    "Android 9-10：需要存储读写权限，授权后可读写微信目录。"
                else "Android 11+：需要「所有文件访问」权限才能读写微信媒体目录。"))
            addView(pill(if (storageReady()) "存储已可读写" else "尚未获得存储权限",
                if (storageReady()) good() else warn()))
            addView(action(if (storageReady()) "重新检查 / 重新授权" else "去授权存储权限") { requestStorage() },
                blockParams(top = 10))
        })

        content.addView(section("模型服务"))
        content.addView(card {
            val providers = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            providers.addView(action("WorkBuddy", primary = !config.useCustom) {
                AppConfigStore.mutate { it.copy(useCustom = false) }
                select(0)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            providers.addView(action("自定义 API", primary = config.useCustom) {
                AppConfigStore.mutate { it.copy(useCustom = true) }
                select(0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(providers, blockParams(top = 4))
            if (!config.useCustom) {
                addView(caption("选择线路（国内 copilot.tencent.com / 国际 workbuddy.ai）"), blockParams(top = 8))
                val regions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                WorkBuddyRegion.entries.forEach { region ->
                    regions.addView(action(region.label, primary = config.workBuddyRegion == region) {
                        AppConfigStore.mutate { it.copy(region = region.id) }
                        select(0)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                }
                addView(regions, blockParams(top = 6))
                addView(pill(loginLabel(config), if (config.loggedIn) good() else neutral()))
                loginState = caption("登录使用官方设备授权：浏览器确认后自动完成。")
                addView(loginState)
                val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(action(if (config.loggedIn) "重新登录" else "登录 WorkBuddy", primary = true) {
                    startLogin(config.workBuddyRegion)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                buttons.addView(action("退出登录", quiet = true) { logout() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(buttons, blockParams(top = 10))
                addView(action("选择模型：${config.model}") {
                    chooseModel({ WorkBuddy.models(AppConfigStore.load(force = true)) }) { value ->
                        AppConfigStore.mutate { it.copy(model = value) }
                    }
                }, blockParams(top = 8))
            } else {
                loginState = null
                addView(field("接口地址（OpenAI 兼容，例如 https://api.example.com/v1）", config.customBaseUrl) { value ->
                    AppConfigStore.mutate { it.copy(customBaseUrl = value.trim()) }
                }, blockParams(top = 8))
                addView(field("API Key", config.customApiKey) { value ->
                    AppConfigStore.mutate { it.copy(customApiKey = value.trim()) }
                }, blockParams(top = 8))
                addView(action(if (config.customModel.isBlank()) "获取模型名称" else "选择模型：${config.customModel}") {
                    chooseModel({
                        val current = AppConfigStore.load(force = true)
                        OpenAiCompatible.models(current)
                    }) { value ->
                        AppConfigStore.mutate { it.copy(customModel = value) }
                    }
                }, blockParams(top = 8))
                addView(caption("填写接口地址和 Key 后即可获取模型名称；接口需兼容 /chat/completions 与 /models。"),
                    blockParams(top = 6))
            }
            addView(action("测试连接") { testConnection() }, blockParams(top = 8))
        })

        content.addView(section("分析开关"))
        content.addView(card {
            addView(toggle("启用自动分析", "仅在聊天滑到底部时分析最新一条消息；向上浏览历史不会触发。",
                config.autoAnalyze) { value -> AppConfigStore.mutate { it.copy(autoAnalyze = value) } })
            addView(toggle("自动展示可选回复", "在最后一条消息下方展示可直接点击填入的可选回复。",
                config.autoReplies) { value -> AppConfigStore.mutate { it.copy(autoReplies = value) } })
            addView(toggle("为「我」的消息生成可选回复", "关闭后只对对方的最新消息生成可选回复。",
                config.repliesForSent) { value -> AppConfigStore.mutate { it.copy(repliesForSent = value) } })
            addView(toggle("分析我已发送的消息", "对话结束时评估自己发出的回复是否得体。",
                config.analyzeSent) { value -> AppConfigStore.mutate { it.copy(analyzeSent = value) } })
            addView(toggle("在消息下显示发送时间", "默认关闭；开启后每条消息下方只显示发送时间。",
                config.showMessageTime) { value -> AppConfigStore.mutate { it.copy(showMessageTime = value) } })
            addView(numberRow("可选回复条数", config.replyCount, 1, 10) { value ->
                AppConfigStore.mutate { it.copy(replyCount = value) }
            })
            addView(numberRow("上下文回溯条数（0=不限）", config.contextMessages, 0, 999) { value ->
                AppConfigStore.mutate { it.copy(contextMessages = value) }
            })
            addView(numberRow("帮我回历史条数", config.replyHistoryMessages, 3, 60) { value ->
                AppConfigStore.mutate { it.copy(replyHistoryMessages = value) }
            })
        })

        content.addView(section("保留条数"))
        content.addView(card {
            addView(caption("0 表示保留全部已生成结果；1 表示只显示最新一条消息；N 表示保留最近 N 条消息的卡片。"))
            addView(numberRow("可选回复保留条数", config.replyKeepMessages, 0, 50) { value ->
                AppConfigStore.mutate { it.copy(replyKeepMessages = value) }
            })
            addView(numberRow("对话分析保留条数", config.analysisKeepMessages, 0, 50) { value ->
                AppConfigStore.mutate { it.copy(analysisKeepMessages = value) }
            })
            addView(numberRow("复盘分析保留条数", config.sentKeepMessages, 0, 50) { value ->
                AppConfigStore.mutate { it.copy(sentKeepMessages = value) }
            })
        })

        content.addView(section("提示词"))
        content.addView(card {
            addView(caption("每一项都可单独修改并立即生效；对话框内可恢复默认。"))
            addView(promptButton("上下文分析提示词", config.prompts.analysis, PromptSettings.DEFAULT_ANALYSIS) {
                AppConfigStore.mutate { it.copy(prompts = it.prompts.copy(analysis = this)) }
            }, blockParams(top = 4))
            addView(promptButton("已发送复盘提示词", config.prompts.sent, PromptSettings.DEFAULT_SENT) {
                AppConfigStore.mutate { it.copy(prompts = it.prompts.copy(sent = this)) }
            }, blockParams(top = 4))
            addView(promptButton("可选回复提示词", config.prompts.replies, PromptSettings.DEFAULT_REPLIES) {
                AppConfigStore.mutate { it.copy(prompts = it.prompts.copy(replies = this)) }
            }, blockParams(top = 4))
            addView(promptButton("军师提示词（帮我回）", config.prompts.assistant, PromptSettings.DEFAULT_ASSISTANT) {
                AppConfigStore.mutate { it.copy(prompts = it.prompts.copy(assistant = this)) }
            }, blockParams(top = 4))
            addView(promptButton("找话题提示词", config.prompts.topic, PromptSettings.DEFAULT_TOPIC) {
                AppConfigStore.mutate { it.copy(prompts = it.prompts.copy(topic = this)) }
            }, blockParams(top = 4))
        })
    }

    private fun startLogin(region: WorkBuddyRegion) {
        loginJob?.cancel()
        loginState?.text = "正在获取授权链接…"
        loginJob = scope.launch {
            when (val start = WorkBuddy.startLogin(region)) {
                is LoginStart.Failed -> loginState?.text = start.message
                is LoginStart.Ready -> {
                    loginState?.text = "请在浏览器完成授权，完成后会自动返回。"
                    openBrowser(start.url)
                    var elapsed = 0
                    while (elapsed < 180_000) {
                        delay(2000); elapsed += 2000
                        when (val poll = WorkBuddy.pollLogin(region, start.state)) {
                            LoginPoll.Pending -> loginState?.text = "等待授权…（${elapsed / 1000}s）"
                            is LoginPoll.Failed -> { loginState?.text = poll.message; return@launch }
                            is LoginPoll.Success -> {
                                val tokens = poll.tokens
                                AppConfigStore.mutate {
                                    it.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
                                        expiresAt = tokens.expiresAt, userId = tokens.userId,
                                        enterpriseId = tokens.enterpriseId, domain = tokens.domain, region = region.id)
                                }
                                Toast.makeText(this@MainActivity, "WorkBuddy 登录成功", Toast.LENGTH_SHORT).show()
                                select(0)
                                chooseModel({ WorkBuddy.models(AppConfigStore.load(force = true)) }) { value ->
                                    AppConfigStore.mutate { it.copy(model = value) }
                                }
                                return@launch
                            }
                        }
                    }
                    loginState?.text = "授权超时，请重新登录。"
                }
            }
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            AlertDialog.Builder(this).setTitle("请手动打开授权链接")
                .setMessage(url)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun logout() {
        AppConfigStore.mutate { it.copy(accessToken = "", refreshToken = "", expiresAt = 0) }
        select(0)
    }

    private fun testConnection() {
        Toast.makeText(this, "正在请求模型服务…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    WorkBuddy.Client({ AppConfigStore.load() }) { tokens ->
                        AppConfigStore.mutate {
                            it.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
                                expiresAt = tokens.expiresAt, userId = tokens.userId,
                                enterpriseId = tokens.enterpriseId, domain = tokens.domain)
                        }
                    }.complete("你是一个测试助手。", "只回复两个字：可用", 32)
                }
            }
            AlertDialog.Builder(this@MainActivity).setTitle("模型连接测试")
                .setMessage(result.getOrElse { "失败：${it.message ?: it.javaClass.simpleName}" })
                .setPositiveButton("知道了", null).show()
        }
    }

    /** 拉取模型列表并让用户选择；自定义接口与 WorkBuddy 共用。 */
    private fun chooseModel(fetch: suspend () -> List<RemoteModel>, apply: (String) -> Unit) {
        val loading = AlertDialog.Builder(this).setTitle("正在获取模型列表…")
            .setMessage("请稍候").setNegativeButton("取消", null).create().also { it.show() }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetch() } }
            runCatching { loading.dismiss() }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    val input = EditText(this@MainActivity).apply { setSingleLine(); setTextColor(ink) }
                    AlertDialog.Builder(this@MainActivity).setTitle("模型列表为空，可手动填写")
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isNotEmpty()) { apply(value); select(0) }
                        }
                        .setNegativeButton("取消", null).show()
                    return@onSuccess
                }
                val labels = models.map { if (it.name == it.id) it.id else "${it.name}（${it.id}）" } +
                    "手动填写模型名称…"
                AlertDialog.Builder(this@MainActivity).setTitle("选择模型")
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == models.size) {
                            val input = EditText(this@MainActivity).apply { setSingleLine(); setTextColor(ink) }
                            AlertDialog.Builder(this@MainActivity).setTitle("模型名称")
                                .setView(input)
                                .setPositiveButton("保存") { _, _ ->
                                    val value = input.text.toString().trim()
                                    if (value.isEmpty()) {
                                        Toast.makeText(this@MainActivity, "模型名称不能为空", Toast.LENGTH_SHORT).show()
                                    } else {
                                        apply(value); select(0)
                                    }
                                }
                                .setNegativeButton("取消", null).show()
                            return@setItems
                        }
                        apply(models[which].id)
                        Toast.makeText(this@MainActivity, "已选择 ${models[which].id}", Toast.LENGTH_SHORT).show()
                        select(0)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure {
                AlertDialog.Builder(this@MainActivity).setTitle("获取模型失败")
                    .setMessage(it.message ?: it.javaClass.simpleName)
                    .setPositiveButton("知道了", null).show()
            }
        }
    }

    // ---------------------------------------------------------------- 回复角色

    private fun rolesPage() {
        content.addView(section("回复角色"))
        content.addView(caption("角色用于“帮我回·找话题”抽屉，可选择语气与背景，对话中即时生效。"))
        val roles = runCatching {
            val db = AndroidCacheDatabase.open(this)
            ReplyRoleStore(db, ReplyIdentityStore(db)).list()
        }.getOrElse { emptyList() }
        if (roles.isEmpty()) {
            content.addView(card { addView(caption("还没有可用角色。")) })
        }
        roles.forEach { role -> content.addView(roleCard(role)) }
        content.addView(action("新建回复角色", primary = true) { editRole(null) }, blockParams(top = 12))
    }

    private fun roleCard(role: ReplyRole): View = card {
        addView(TextView(this@MainActivity).apply {
            text = role.name
            textSize = 15f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(caption(role.background.ifBlank { "（无背景）" }.take(120)), blockParams(top = 4))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(action("编辑", quiet = true) { editRole(role) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action("删除", quiet = true) { deleteRole(role) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row, blockParams(top = 6))
    }

    private fun editRole(role: ReplyRole?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = "角色名称"
            setText(role?.name.orEmpty())
            setTextColor(ink)
            setSingleLine()
        }
        val background = EditText(this).apply {
            hint = "角色背景：对方是谁、关系、语气偏好等"
            setText(role?.background.orEmpty())
            setTextColor(ink)
            minLines = 4
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(name)
        container.addView(background, blockParams(top = 8))
        AlertDialog.Builder(this).setTitle(if (role == null) "新建回复角色" else "编辑回复角色")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    val db = AndroidCacheDatabase.open(this)
                    ReplyRoleStore(db, ReplyIdentityStore(db)).save(role?.id, name.text.toString(),
                        background.text.toString(), role?.revision)
                }.onSuccess { select(1) }
                    .onFailure { Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteRole(role: ReplyRole) {
        AlertDialog.Builder(this).setTitle("删除角色")
            .setMessage("确定删除「${role.name}」？")
            .setPositiveButton("删除") { _, _ ->
                runCatching {
                    val db = AndroidCacheDatabase.open(this)
                    ReplyRoleStore(db, ReplyIdentityStore(db)).delete(role.id, role.revision)
                }.onSuccess { select(1) }
                    .onFailure { Toast.makeText(this, it.message ?: "删除失败", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 激活

    private fun activationPage() {
        val config = AppConfigStore.load(force = true)
        val state = License.status(config, this)
        val info = License.info(config)

        content.addView(section("授权状态"))
        content.addView(card {
            addView(TextView(this@MainActivity).apply {
                text = when (state) {
                    LicenseState.ACTIVE -> "已激活"
                    LicenseState.EXPIRED -> "授权已到期"
                    LicenseState.INVALID -> "授权校验失败"
                    LicenseState.NONE -> "未激活"
                }
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(when (state) {
                    LicenseState.ACTIVE -> good()
                    LicenseState.EXPIRED -> warn()
                    LicenseState.INVALID -> Color.parseColor("#B03A3A")
                    LicenseState.NONE -> muted
                })
            })
            info?.let {
                addView(caption("激活码：${it.code}"))
                addView(caption(if (state == LicenseState.EXPIRED)
                    "到期时间：${dateText(it.expiresAt)}（已过期）"
                else "到期时间：${dateText(it.expiresAt)}"))
            }
            addView(caption("设备编号：${License.deviceId(this@MainActivity)}"))
            addView(action("复制设备编号") {
                copyText(License.deviceId(this@MainActivity))
                Toast.makeText(this@MainActivity, "设备编号已复制", Toast.LENGTH_SHORT).show()
            })
        })

        if (state != LicenseState.ACTIVE) {
            content.addView(section("填写激活码"))
            content.addView(card {
                val input = EditText(this@MainActivity).apply {
                    hint = "LH-XXXX-XXXX-XXXX"
                    setTextColor(ink)
                    textSize = 16f
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(Color.parseColor("#F5F8FE"), 12, border)
                }
                addView(input)
                addView(caption("激活码不区分大小写；首次激活会把当前设备绑定到该激活码。"))
                lateinit var submit: TextView
                submit = action("立即激活", primary = true) {
                    val code = input.text.toString().trim()
                    if (code.isEmpty()) {
                        Toast.makeText(this@MainActivity, "请输入激活码", Toast.LENGTH_SHORT).show()
                        return@action
                    }
                    submit.isEnabled = false
                    submit.text = "正在激活…"
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { LicenseClient.activate(this@MainActivity, code) }
                        }
                        result.onSuccess { activation ->
                            AppConfigStore.mutate { it.copy(license = activation.license) }
                            Toast.makeText(this@MainActivity,
                                "激活成功，有效期至 ${dateText(activation.info.expiresAt)}",
                                Toast.LENGTH_LONG).show()
                            select(page)
                        }.onFailure {
                            Toast.makeText(this@MainActivity, it.message ?: "激活失败，请重试",
                                Toast.LENGTH_LONG).show()
                        }
                        submit.isEnabled = true
                        submit.text = "立即激活"
                    }
                }
                addView(submit, blockParams(top = 8))
            })
        } else {
            content.addView(section("授权管理"))
            content.addView(card {
                addView(caption("当前授权已生效，微信内的高级功能可以正常使用。"))
                addView(action("刷新授权状态") {
                    Toast.makeText(this@MainActivity, "正在向后台确认…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { LicenseClient.refresh(this@MainActivity) }
                        }
                        result.onSuccess { activation ->
                            if (activation == null) {
                                AppConfigStore.mutate { it.copy(license = "") }
                                Toast.makeText(this@MainActivity, "后台已取消该设备授权，请联系客服",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                AppConfigStore.mutate { it.copy(license = activation.license) }
                                Toast.makeText(this@MainActivity, "授权有效，已同步最新到期时间",
                                    Toast.LENGTH_SHORT).show()
                            }
                            select(page)
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "刷新失败：${it.message ?: "请检查网络"}",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                })
            })
        }

        content.addView(section("授权说明"))
        content.addView(card {
            addView(caption("• 一机一码：激活码首次使用后绑定当前设备，不能换机重复使用。"))
            addView(caption("• 按天授权：到期后高级功能暂停，续费后使用新的激活码。"))
            addView(caption("• 后台可控：管理员可随时禁用、延期或解绑激活码。"))
            addView(caption("• 离线可用：激活成功后不依赖网络，到期前均可正常使用。"))
        })
    }

    private fun dateText(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(seconds * 1000L))

    private fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("微话助手", text))
    }

    // ---------------------------------------------------------------- 关于

    private fun aboutPage() {
        content.addView(section("关于"))
        content.addView(card {
            addView(TextView(this@MainActivity).apply {
                text = "微话助手"
                textSize = 18f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(caption("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"))
            addView(caption("基于微信聊天记录的多轮上下文分析与回复助手。消息内容仅发送到你登录的 WorkBuddy。"))
        })

        content.addView(section("使用教程"))
        content.addView(card {
            addView(caption("按顺序完成下面几步，即可在微信里使用分析卡片与可选回复。"))
            tutorialSteps.forEachIndexed { index, step -> addView(stepRow(index + 1, step)) }
        })

        content.addView(section("版权与联系"))
        content.addView(card {
            addView(caption("版权归属：泓创"))
            addView(contactRow("微信", "longhong2021"))
            addView(contactRow("邮箱", "1129738201@qq.com"))
        })
        content.addView(section("数据与诊断"))
        content.addView(card {
            addView(caption("配置与分析缓存：${SharedStorage.displayPath()}"))
            addView(action("运行日志 / 导出") { Diagnostics.show(this@MainActivity) }, blockParams(top = 8))
        })
    }

    private val tutorialSteps = listOf(
        "在 LSPosed 中启用「微话助手」，作用域勾选微信，然后彻底结束微信进程再重新打开。",
        "打开本设置页，按提示授予存储权限（Android 11+ 为「所有文件访问」）。",
        "在「模型服务」中登录 WorkBuddy，或填写自定义 API 与 Key，然后选择可用模型。",
        "打开任意聊天并滑到底部：新消息会自动展示可选回复，点击回复即可填入输入框（不会自动发送）。",
        "长按对方消息可「分析这段对话」，长按自己的消息可「复盘我的回复」。",
        "聊天页「+」菜单中提供「帮我回 / 找话题」「聊天分析」等入口。",
        "「保留条数」控制每类卡片显示最近几条消息：0 表示全部保留，1 表示只显示最新一条。",
        "若没有出现卡片：确认模块已启用、微信已重启、存储权限与模型配置均正常。",
    )

    private fun stepRow(index: Int, text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(this).apply {
            this.text = index.toString()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(withAlpha(blue, 0x18), 12)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun contactRow(label: String, value: String): View =
        TextView(this).apply {
            text = "$label：$value（点击复制）"
            textSize = 14f
            setTextColor(blue)
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                Toast.makeText(this@MainActivity, "已复制 $value", Toast.LENGTH_SHORT).show()
            }
        }

    // ---------------------------------------------------------------- 权限

    private fun storageReady(): Boolean = runCatching { SharedStorage.accessible(this) }.getOrDefault(false)

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "已获得所有文件访问权限", Toast.LENGTH_SHORT).show()
                select(0)
                return
            }
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.onFailure {
                    Toast.makeText(this, "请手动到系统设置中开启「所有文件访问」", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && storageReady()) {
            Toast.makeText(this, "存储权限已就绪", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "存储权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未获得存储权限，分析与缓存将无法保存", Toast.LENGTH_LONG).show()
        }
        select(0)
    }

    // ---------------------------------------------------------------- 控件

    private fun section(title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(24), dp(6), dp(8))
        }
        row.addView(View(this).apply {
            background = gradient(intArrayOf(blueLight, blue), 2)
        }, LinearLayout.LayoutParams(dp(4), dp(15)))
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun card(build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(card, 20, border)
            elevation = dp(2).toFloat()
            build()
        }.also { it.layoutParams = blockParams() }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(muted)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun pill(text: String, tint: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(tint)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = rounded(withAlpha(tint, 0x1A), 10)
        layoutParams = blockParams(top = 8)
    }

    private fun action(text: String, primary: Boolean = false, quiet: Boolean = false,
        onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setTextColor(if (primary) Color.WHITE else blue)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = when {
            primary -> pressable(gradient(intArrayOf(blueLight, blueDeep), 14), blue)
            quiet -> pressable(rounded(withAlpha(blue, 0x10), 14), blue)
            else -> pressable(rounded(soft, 14, border), blue)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(ink)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(2), dp(8), 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#C9D7E8")))
            trackTintList = ColorStateList(states, intArrayOf(blue, Color.parseColor("#E1EAF6")))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun numberRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setTextColor(ink)
            textSize = 14f
            gravity = Gravity.CENTER
            setSingleLine()
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = rounded(Color.parseColor("#F5F8FE"), 10, border)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val parsed = s?.toString()?.trim()?.toIntOrNull() ?: return
                    if (parsed in min..max) onChange(parsed)
                }
            })
        }, LinearLayout.LayoutParams(dp(64), -2))
        row.addView(TextView(this).apply {
            text = "  $min-$max"
            textSize = 12f
            setTextColor(muted)
        })
        return row
    }

    private fun field(label: String, value: String, onChange: (String) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(muted)
        })
        box.addView(EditText(this).apply {
            setText(value)
            setTextColor(ink)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString()?.trim().orEmpty())
                }
            })
        })
        return box
    }

    private fun promptButton(title: String, current: String, default: String, save: String.() -> Unit): TextView =
        action(title) {
            val input = EditText(this).apply {
                setText(current)
                setTextColor(ink)
                minLines = 6
                maxLines = 16
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            AlertDialog.Builder(this).setTitle(title)
                .setView(ScrollView(this).apply {
                    setPadding(dp(16), dp(8), dp(16), 0)
                    addView(input)
                })
                .setPositiveButton("保存") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isEmpty()) {
                        Toast.makeText(this, "提示词不能为空", Toast.LENGTH_SHORT).show()
                    } else {
                        save(text); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("恢复默认") { _, _ ->
                    save(default)
                    Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
                    select(0)
                }
                .setNegativeButton("取消", null)
                .show()
        }

    private fun loginLabel(config: AppConfig): String = when {
        config.loggedIn && config.userId.isNotBlank() -> "已登录 · ${config.userId}"
        config.loggedIn -> "已登录"
        else -> "未登录"
    }

    private fun good() = Color.parseColor("#1B7F5A")
    private fun warn() = Color.parseColor("#B26A00")
    private fun neutral() = muted

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }

    private fun gradient(colors: IntArray, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun pressable(content: Drawable, tint: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 0x26)), content, null)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun blockParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8 + top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
