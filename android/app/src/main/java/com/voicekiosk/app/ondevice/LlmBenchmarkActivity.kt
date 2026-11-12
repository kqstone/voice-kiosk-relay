package com.voicekiosk.app.ondevice

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S0 on-device LLM benchmark (端侧模型基准测试).
 *
 * Reached only from Settings → 「端侧模型基准测试」; runs in its own process
 * (`:llmbench`, see AndroidManifest) so a native OOM or crash can never take down the
 * voice service, and nothing here is touched by the normal voice pipeline.
 */
class LlmBenchmarkActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vk-llm-bench") }
    private val busy = AtomicBoolean(false)
    private val benchCancelled = AtomicBoolean(false)

    private lateinit var downloader: OnDeviceModelDownloader
    @Volatile
    private var engine: LlmEngine? = null
    private lateinit var profile: DeviceProfile
    private lateinit var decision: TierDecision

    private lateinit var deviceText: TextView
    private lateinit var modelGroup: RadioGroup
    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var runsGroup: RadioGroup
    private lateinit var threadsGroup: RadioGroup
    private lateinit var modeGroup: RadioGroup
    private lateinit var evalSizeGroup: RadioGroup
    private lateinit var evalCoolGroup: RadioGroup
    private lateinit var ctxGroup: RadioGroup
    private lateinit var kvGroup: RadioGroup
    private lateinit var faGroup: RadioGroup
    private lateinit var repackGroup: RadioGroup
    private lateinit var pinGroup: RadioGroup
    private lateinit var mmapGroup: RadioGroup
    private lateinit var ffGroup: RadioGroup
    private val topology: CpuTopology by lazy { CpuTopology.read() }
    private val modelButtons = mutableMapOf<Int, OnDeviceModel>()
    private var lastReport: String = ""

    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = OnDeviceModelDownloader(
            OnDeviceModelDownloader.defaultDir(this),
            allowUnverified = isDebuggable
        )
        profile = DeviceTierDetector.collect(this)
        decision = DeviceTierDetector.classify(profile)
        setContentView(buildUi())
        refreshDeviceText()
        refreshModelStates()
    }

    override fun onDestroy() {
        benchCancelled.set(true)
        downloader.cancel()
        engine?.cancel()
        val e = engine
        engine = null
        worker.execute { try { e?.close() } catch (_: Throwable) {} }
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#18181B"))
            setStroke(dp(1), Color.parseColor("#27272A"))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, color: String = "#E4E4E7") = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun choiceGroup(options: List<String>, checkedIndex: Int): RadioGroup = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        options.forEachIndexed { i, o ->
            addView(RadioButton(this@LlmBenchmarkActivity).apply {
                id = View.generateViewId()
                text = o
                setTextColor(Color.WHITE)
                tag = o
                if (i == checkedIndex) isChecked = true
            })
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#09090B"))
        }
        val back = label("← 返回", 15f, color = "#A1A1AA").apply {
            setPadding(0, dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        }
        root.addView(row(back, label("端侧模型基准测试（S0.2 实验功能）", 17f, bold = true, color = "#FFFFFF").apply { gravity = Gravity.CENTER_VERTICAL }))
        root.addView(label("只用于测速，不影响语音助手：模型不随应用启动加载，测试在独立进程中运行，结束即释放。", 12f, color = "#A1A1AA").apply { setPadding(0, dp(6), 0, 0) })

        val devCard = card()
        devCard.addView(label("设备档位", 15f, bold = true))
        deviceText = label("", 13f, color = "#D4D4D8")
        devCard.addView(deviceText)
        root.addView(devCard)

        val modelCard = card()
        modelCard.addView(label("候选模型（★ = 本机档位默认模型；[实验] 不会被正式功能下载，只在这里测试）", 15f, bold = true))
        modelGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        // Preselect the product default (HIGH model) on every phone so reports stay comparable.
        val recommended = OnDeviceModelCatalog.defaultFor(decision.tier) ?: OnDeviceModelCatalog.defaultFor(DeviceTier.HIGH)
        for (m in OnDeviceModelCatalog.MODELS) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                if (m == recommended) isChecked = true
            }
            modelButtons[rb.id] = m
            modelGroup.addView(rb)
        }
        modelCard.addView(modelGroup)
        modelCard.addView(row(
            button("下载/校验") { startDownload() },
            button("删除") { deleteSelected() },
            button("取消") { cancelAll() }
        ))
        root.addView(modelCard)

        val sweepCard = card()
        sweepCard.addView(label("一键扫描（推荐）", 15f, bold = true))
        sweepCard.addView(label("自动跑 ${SweepPlan.sweep(topology).size} 组配置（${SweepPlan.loadCount(SweepPlan.sweep(topology))} 次加载）：默认（语法快进、不绑核）/ 冷启动对照 / 关语法快进 / 线程 6、8 / 绑最快核 / mmap 加载（S0 方式）/ 关 REPACK / KV f16 / 关 flash-attn / n_ctx 512、2048 / S0 同款。每组 1 次预热 + ${SweepPlan.MEASURED_RUNS} 次测量（4 条不同指令轮换），合并成一份报告。1.5B 模型约 3 分钟，请保持亮屏、勿切后台。", 12f, color = "#A1A1AA"))
        sweepCard.addView(row(button("开始一键扫描") { startBenchmark(sweep = true) }))
        root.addView(sweepCard)

        val runCard = card()
        runCard.addView(label("单项测试（固定前缀 + 短后缀路由提示，紧凑 JSON 语法，最多生成 ${BenchmarkPrompt.MAX_TOKENS} token，首轮为预热）", 13f))
        runCard.addView(label("前缀模式", 13f, color = "#A1A1AA"))
        modeGroup = choiceGroup(listOf("cached", "cold"), 0)
        runCard.addView(modeGroup)
        runCard.addView(label("n_ctx", 13f, color = "#A1A1AA"))
        ctxGroup = choiceGroup(listOf("512", "1024", "2048"), 1)
        runCard.addView(ctxGroup)
        runCard.addView(label("KV 缓存类型（q8_0 需要 flash-attn）", 13f, color = "#A1A1AA"))
        kvGroup = choiceGroup(listOf("q8_0", "f16"), 0)
        runCard.addView(kvGroup)
        runCard.addView(label("flash-attn", 13f, color = "#A1A1AA"))
        faGroup = choiceGroup(listOf("on", "auto", "off"), 0)
        runCard.addView(faGroup)
        runCard.addView(label("权重 REPACK", 13f, color = "#A1A1AA"))
        repackGroup = choiceGroup(listOf("on", "off"), 0)
        runCard.addView(repackGroup)
        runCard.addView(label("mmap 加载权重（off = 权重只驻留一份）", 13f, color = "#A1A1AA"))
        mmapGroup = choiceGroup(listOf("off", "on"), 0)
        runCard.addView(mmapGroup)
        runCard.addView(label("线程数（CPU：${topology.describe()}）", 13f, color = "#A1A1AA"))
        threadsGroup = choiceGroup(listOf("自动(${topology.autoThreads()})", "4", "6", "8"), 0)
        runCard.addView(threadsGroup)
        runCard.addView(label("绑核", 13f, color = "#A1A1AA"))
        pinGroup = choiceGroup(listOf("不绑定", "最快核"), 0)
        runCard.addView(pinGroup)
        runCard.addView(label("语法快进（fast-forward，输出不变）", 13f, color = "#A1A1AA"))
        ffGroup = choiceGroup(listOf("on", "off"), 0)
        runCard.addView(ffGroup)
        runCard.addView(label("测量次数", 13f, color = "#A1A1AA"))
        runsGroup = choiceGroup(listOf("4", "8", "12"), 1)
        runCard.addView(runsGroup)
        runCard.addView(row(button("开始单项测试") { startBenchmark(sweep = false) }))
        root.addView(runCard)

        val evalCard = card()
        evalCard.addView(label("路由评测（S2）", 15f, bold = true))
        evalCard.addView(label(
            "用所选模型和语音服务相同的默认配置，跑 ${com.voicekiosk.app.ondevice.eval.RouteEvalSet.CASES.size} 条离线语句（口语指令、省略追问、必须走云端的反例），" +
                "按正式阈值和超时（${com.voicekiosk.app.ondevice.route.TurnOrchestrator.Config().modelTimeoutMs}ms）统计准确率、误判本地率和延迟。" +
                "不执行任何操作、不联网。模型只在语音服务也会调用它的句子上运行（关键词预筛之后）。", 12f, color = "#A1A1AA"))
        evalCard.addView(label("用例", 13f, color = "#A1A1AA"))
        evalSizeGroup = choiceGroup(listOf("快速(${com.voicekiosk.app.ondevice.eval.RouteEvalSet.quick().size})", "全部(${com.voicekiosk.app.ondevice.eval.RouteEvalSet.CASES.size})"), 0)
        evalCard.addView(evalSizeGroup)
        evalCard.addView(label("降温：每句之间暂停；温控 ≥ moderate 时等它降下来（每句最多等 60s）", 13f, color = "#A1A1AA"))
        evalCoolGroup = choiceGroup(listOf("1s", "0s", "3s"), 0)
        evalCard.addView(evalCoolGroup)
        evalCard.addView(row(button("开始路由评测") { startRouteEval() }))
        root.addView(evalCard)

        statusText = label("", 13f, color = "#FBBF24").apply { setPadding(0, dp(10), 0, 0) }
        root.addView(statusText)

        val reportCard = card()
        reportCard.addView(label("报告", 15f, bold = true))
        reportText = label("尚未运行。", 11f, color = "#D4D4D8").apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        reportCard.addView(reportText)
        reportCard.addView(row(
            button("复制报告") { copyReport() },
            button("分享报告") { shareReport() }
        ))
        root.addView(reportCard)

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            addView(root)
        }
    }

    private fun refreshDeviceText() {
        val p = profile
        deviceText.text = buildString {
            appendLine("${p.manufacturer} ${p.model}  ${p.socModel}")
            appendLine("内存 ${DeviceTierDetector.fmtGiB(p.totalRamBytes)} · 可用存储 ${DeviceTierDetector.fmtGiB(p.freeStorageBytes)} · ${p.cpuCores} 核 · Android SDK ${p.sdkInt}")
            appendLine("dotprod=${p.hasDotProd} i8mm=${p.hasI8mm} fp16=${p.hasFp16Arith} sve2=${p.hasSve2}")
            appendLine("档位：${decision.tier.label}")
            append(decision.reasons.joinToString("\n") { "· $it" })
            if (decision.tier != DeviceTier.HIGH) append("\n（本档正式功能不会下载模型；这里仍允许手动测速以收集数据。）")
        }
    }

    private fun refreshModelStates() {
        for (i in 0 until modelGroup.childCount) {
            val rb = modelGroup.getChildAt(i) as RadioButton
            val m = modelButtons[rb.id] ?: continue
            val ready = downloader.isReadyFast(m)
            val star = if (m == OnDeviceModelCatalog.defaultFor(decision.tier)) "★ " else ""
            val exp = if (m.experimental) "[实验] " else "[默认] "
            val size = String.format(Locale.US, "%.0fMB", m.sizeBytes / 1048576.0)
            rb.text = "$star$exp${m.displayName}  $size  [${m.tier}]  ${if (ready) "已下载✓" else "未下载"}"
        }
    }

    private fun selectedModel(): OnDeviceModel? = modelButtons[modelGroup.checkedRadioButtonId]

    private fun selectedTag(group: RadioGroup): String =
        (group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String) ?: ""

    private fun setStatus(s: String) { main.post { statusText.text = s } }

    // ---------------------------------------------------------------- actions

    private fun startDownload() {
        val m = selectedModel() ?: return toast("请先选择模型")
        if (!busy.compareAndSet(false, true)) return toast("正在执行其他任务")
        setStatus("准备下载 ${m.displayName}…")
        worker.execute {
            val r = downloader.ensure(m, object : OnDeviceModelDownloader.Listener {
                override fun onProgress(model: OnDeviceModel, downloadedBytes: Long, totalBytes: Long) {
                    val pct = if (totalBytes > 0) downloadedBytes * 100 / totalBytes else 0
                    setStatus("下载 ${model.displayName}：$pct%（${downloadedBytes / 1048576}/${totalBytes / 1048576}MB）")
                }
                override fun onStatus(model: OnDeviceModel, message: String) { setStatus(message) }
            })
            busy.set(false)
            setStatus(when (r) {
                is OnDeviceModelDownloader.Result.Ready -> "模型已就绪：${r.file.name}（SHA256 已校验）"
                is OnDeviceModelDownloader.Result.Failed -> "下载失败：${r.reason}"
                OnDeviceModelDownloader.Result.Cancelled -> "已取消（已下载部分会保留，下次续传）"
            })
            main.post { refreshModelStates() }
        }
    }

    private fun deleteSelected() {
        val m = selectedModel() ?: return
        if (busy.get()) return toast("请先取消正在进行的任务")
        downloader.delete(m)
        refreshModelStates()
        setStatus("已删除 ${m.displayName}")
    }

    private fun cancelAll() {
        benchCancelled.set(true)
        downloader.cancel()
        engine?.cancel()
        setStatus("正在取消…")
    }

    private fun singleConfig(): BenchConfig {
        val kv = if (selectedTag(kvGroup) == "f16") LlmEngine.KvType.F16 else LlmEngine.KvType.Q8_0
        val fa = when (selectedTag(faGroup)) { "off" -> LlmEngine.FlashAttn.OFF; "auto" -> LlmEngine.FlashAttn.AUTO; else -> LlmEngine.FlashAttn.ON }
        val load = LlmEngine.LoadConfig(
            contextSize = selectedTag(ctxGroup).toIntOrNull() ?: RoutingDefaults.CONTEXT_SIZE,
            threads = 4,
            kvType = kv,
            flashAttn = fa,
            repack = selectedTag(repackGroup) != "off",
            mmap = selectedTag(mmapGroup) == "on"
        )
        val mode = if (selectedTag(modeGroup) == "cold") LlmEngine.PrefixMode.COLD else LlmEngine.PrefixMode.CACHED
        return BenchConfig(
            name = "single",
            load = load,
            threads = selectedTag(threadsGroup).toIntOrNull() ?: 0,
            pinFastest = selectedTag(pinGroup) == "最快核",
            mode = mode,
            fastForward = selectedTag(ffGroup) != "off"
        )
    }

    private fun startBenchmark(sweep: Boolean) {
        val m = selectedModel() ?: return toast("请先选择模型")
        if (!downloader.isReadyFast(m)) return toast("请先下载并校验该模型")
        val single = if (sweep) null else singleConfig()
        single?.load?.validate()?.let { return toast(it) }
        if (!busy.compareAndSet(false, true)) return toast("正在执行其他任务")
        benchCancelled.set(false)
        val measuredRuns = if (sweep) SweepPlan.MEASURED_RUNS else selectedTag(runsGroup).toIntOrNull() ?: 8
        reportText.text = "运行中…"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            val report = try {
                runBenchmark(m, if (sweep) SweepPlan.sweep(topology) else listOf(single!!), measuredRuns, if (sweep) "sweep" else "single")
            } catch (t: Throwable) {
                "基准测试异常：${t.javaClass.simpleName}: ${t.message}"
            } finally {
                busy.set(false)
            }
            lastReport = report
            val saved = saveReport(m, report, if (sweep) "sweep" else "single")
            main.post {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                reportText.text = report
                statusText.text = saved?.let { "报告已保存：${it.absolutePath}" } ?: "报告保存失败（可直接复制）"
            }
        }
    }

    private fun startRouteEval() {
        val m = selectedModel() ?: return toast("请先选择模型")
        if (!downloader.isReadyFast(m)) return toast("请先下载并校验该模型")
        if (!busy.compareAndSet(false, true)) return toast("正在执行其他任务")
        benchCancelled.set(false)
        val quick = selectedTag(evalSizeGroup).startsWith("快速")
        val cases = if (quick) com.voicekiosk.app.ondevice.eval.RouteEvalSet.quick() else com.voicekiosk.app.ondevice.eval.RouteEvalSet.CASES
        val coolMs = (selectedTag(evalCoolGroup).removeSuffix("s").toIntOrNull() ?: 1) * 1000L
        reportText.text = "路由评测运行中…"
        thermalWaitMs = 0L
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            val t0 = System.nanoTime()
            val report = try {
                val eng = LlmEngine(nativeLibDir = applicationInfo.nativeLibraryDir)
                engine = eng
                try {
                    val thermalStart = thermalStatus()
                    val r = com.voicekiosk.app.ondevice.eval.RouteEvalRunner.run(
                        engine = eng,
                        modelFile = downloader.fileFor(m),
                        model = m,
                        stateDir = File(cacheDir, "llm-prefix"),
                        threads = topology.autoThreads(),
                        cases = cases,
                        cancelled = { benchCancelled.get() },
                        modelAllCases = false,
                        beforeCase = { coolDown(coolMs) },
                        log = { setStatus(it) }
                    )
                    buildString {
                        appendLine("Voice Kiosk 路由评测  ${appVersion()}  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
                        appendLine("${profile.manufacturer} ${profile.model}  ${profile.socModel}  tier=${decision.tier}  cpu=${topology.describe()}")
                        appendLine("prompt=${BenchmarkPrompt.VERSION}  threads=${topology.autoThreads()}  cases=${if (quick) "quick" else "full"}  cooldown=${coolMs}ms  thermal $thermalStart → ${thermalStatus()}  thermal waits ${thermalWaitMs / 1000}s  elapsed ${((System.nanoTime() - t0) / 1e9).toInt()}s${if (benchCancelled.get()) "  (已取消，部分结果)" else ""}")
                        appendLine()
                        append(r.summary())
                        appendLine()
                        append(r.tsv())
                    }
                } finally {
                    engine = null
                    eng.close()
                }
            } catch (t: Throwable) {
                "路由评测异常：${t.javaClass.simpleName}: ${t.message}"
            } finally {
                busy.set(false)
            }
            lastReport = report
            val saved = saveReport(m, report, "route-eval")
            main.post {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                reportText.text = report
                statusText.text = saved?.let { "报告已保存：${it.absolutePath}" } ?: "报告保存失败（可直接复制）"
            }
        }
    }

    @Volatile private var thermalWaitMs = 0L

    /** Before each route-eval case: fixed pause, then wait (≤60 s) while thermal ≥ moderate. Returns the state. */
    private fun coolDown(pauseMs: Long): String {
        if (pauseMs > 0) Thread.sleep(pauseMs)
        val t0 = System.currentTimeMillis()
        while (thermalLevel() >= 2 && System.currentTimeMillis() - t0 < 60_000 && !benchCancelled.get()) {
            setStatus("温控 ${thermalStatus()}，等待降温…")
            Thread.sleep(2000)
        }
        thermalWaitMs += System.currentTimeMillis() - t0
        return thermalStatus()
    }

    /** 0 none, 1 light, 2 moderate, 3+ severe…; -1 unknown. */
    private fun thermalLevel(): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return -1
        return pm.currentThermalStatus
    }

    /** Runs on [worker]. */
    private fun runBenchmark(m: OnDeviceModel, plan: List<BenchConfig>, measuredRuns: Int, mode: String): String {
        val t0 = System.nanoTime()
        profile = DeviceTierDetector.collect(this)
        decision = DeviceTierDetector.classify(profile)
        val thermalStart = thermalStatus()
        val eng = LlmEngine(nativeLibDir = applicationInfo.nativeLibraryDir)
        engine = eng
        try {
            val runner = BenchmarkRunner(
                engine = eng,
                modelFile = downloader.fileFor(m),
                model = m,
                topology = topology,
                memory = ProcMemorySource,
                stateDir = File(cacheDir, "llm-prefix"),
                measuredRuns = measuredRuns,
                runTimeoutMs = RUN_TIMEOUT_MS,
                cancelled = { benchCancelled.get() },
                onStatus = { setStatus(it) }
            )
            val results = runner.run(plan)
            val load = runner.lastLoad
            val report = BenchmarkReport(
                appVersion = appVersion(),
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()),
                device = profile, tier = decision, model = m,
                modelDescription = load?.description ?: "?", backendSummary = load?.backendSummary ?: "?",
                systemInfo = load?.systemInfo ?: "", cpuTopology = topology.describe(),
                mode = mode, results = results, thermalStart = thermalStart, thermalEnd = thermalStatus(),
                batteryTempC = batteryTempC(), elapsedMs = (System.nanoTime() - t0) / 1e6,
                cancelled = benchCancelled.get()
            )
            return report.format()
        } finally {
            engine = null
            eng.close()
        }
    }

    private fun appVersion(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "${pi.versionName} ($code)${if (isDebuggable) " debug" else ""}"
    } catch (_: Exception) { "?" }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "n/a (sdk<29)"
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "?"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun batteryTempC(): Double? = try {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) null else t / 10.0
    } catch (_: Exception) { null }

    /** Saves to <external files>/benchmarks/ (no storage permission needed; visible via USB/file manager under Android/data). */
    private fun saveReport(m: OnDeviceModel, report: String, mode: String): File? = try {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "benchmarks").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, BenchmarkReportFormatter.fileName(stamp, m.id, mode)).apply { writeText(report) }
    } catch (_: Exception) { null }

    private fun copyReport() {
        if (lastReport.isEmpty()) return toast("还没有报告")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vk-llm-bench", lastReport))
        toast("报告已复制")
    }

    private fun shareReport() {
        if (lastReport.isEmpty()) return toast("还没有报告")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Voice Kiosk 端侧模型基准报告")
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }
        try { startActivity(Intent.createChooser(send, "分享报告")) } catch (_: Exception) { toast("没有可分享的应用") }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val RUN_TIMEOUT_MS = 60_000L
    }
}
