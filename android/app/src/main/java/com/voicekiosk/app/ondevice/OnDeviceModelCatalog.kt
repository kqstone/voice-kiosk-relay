package com.voicekiosk.app.ondevice

/**
 * Candidate GGUF models for the S0 benchmark. Not bundled in the APK; downloaded on
 * demand by [OnDeviceModelDownloader] into the app's private filesDir.
 *
 * Hash provenance (2026-11-09): every SHA256 below equals the Git-LFS oid published by
 * Hugging Face for the pinned revision AND the Sha256 listed by the ModelScope mirror,
 * and was re-computed locally from a full download. See docs/MODELS.md.
 * An entry may only be [HashStatus.VERIFIED] under those conditions; anything else must
 * be [HashStatus.UNVERIFIED] (downloader refuses it outside debug benchmark mode).
 */
enum class HashStatus { VERIFIED, UNVERIFIED }

data class OnDeviceModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val hashStatus: HashStatus,
    val tier: DeviceTier,
    val promptFormat: PromptFormat,
    val license: String,
    val sourceRepo: String,
    val urls: List<String>,
    /**
     * true = the model the product downloads by default for [tier]. false = experimental:
     * only selectable in the benchmark screen (S0.2: everything except Qwen2.5-1.5B).
     */
    val tierDefault: Boolean = false
) {
    val experimental: Boolean get() = !tierDefault
}

object OnDeviceModelCatalog {

    private fun hf(repo: String, rev: String, file: String) =
        "https://huggingface.co/$repo/resolve/$rev/$file"

    private fun ms(repo: String, file: String) =
        "https://www.modelscope.cn/models/$repo/resolve/master/$file"

    val MODELS: List<OnDeviceModel> = listOf(
        OnDeviceModel(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            displayName = "Qwen2.5-0.5B-Instruct Q4_K_M",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            hashStatus = HashStatus.VERIFIED,
            tier = DeviceTier.MID,
            promptFormat = PromptFormat.CHATML,
            license = "Apache-2.0",
            sourceRepo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            urls = listOf(
                ms("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
                hf("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q4_k_m.gguf")
            )
        ),
        OnDeviceModel(
            id = "qwen3-0.6b-q4_k_m",
            displayName = "Qwen3-0.6B Q4_K_M（unsloth 量化，关闭思考）",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            sizeBytes = 396_705_472L,
            sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
            hashStatus = HashStatus.VERIFIED,
            tier = DeviceTier.MID,
            promptFormat = PromptFormat.CHATML_QWEN3_NO_THINK,
            license = "Apache-2.0",
            sourceRepo = "unsloth/Qwen3-0.6B-GGUF",
            urls = listOf(
                ms("unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
                hf("unsloth/Qwen3-0.6B-GGUF", "50968a4468ef4233ed78cd7c3de230dd1d61a56b", "Qwen3-0.6B-Q4_K_M.gguf")
            )
        ),
        OnDeviceModel(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            displayName = "Qwen2.5-1.5B-Instruct Q4_K_M",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            hashStatus = HashStatus.VERIFIED,
            tier = DeviceTier.HIGH,
            promptFormat = PromptFormat.CHATML,
            license = "Apache-2.0",
            sourceRepo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            urls = listOf(
                ms("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
                hf("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "91cad51170dc346986eccefdc2dd33a9da36ead9", "qwen2.5-1.5b-instruct-q4_k_m.gguf")
            ),
            // S0.1 phone sweep (SM8650): 4/4 correct, route p50 1084 / p90 1310 ms.
            tierDefault = true
        ),
        OnDeviceModel(
            id = "qwen3-1.7b-q4_k_m",
            displayName = "Qwen3-1.7B Q4_K_M（unsloth 量化，关闭思考）",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1_107_409_472L,
            sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
            hashStatus = HashStatus.VERIFIED,
            tier = DeviceTier.HIGH,
            promptFormat = PromptFormat.CHATML_QWEN3_NO_THINK,
            license = "Apache-2.0",
            sourceRepo = "unsloth/Qwen3-1.7B-GGUF",
            urls = listOf(
                ms("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
                hf("unsloth/Qwen3-1.7B-GGUF", "d7f544eead698dbd1f15126ef60b45a1e1933222", "Qwen3-1.7B-Q4_K_M.gguf")
            )
        )
    )

    /** Only these hosts may appear in download URLs. */
    val ALLOWED_HOSTS = setOf("www.modelscope.cn", "huggingface.co")

    private val SHA256_RE = Regex("^[0-9a-f]{64}$")
    private val FILE_NAME_RE = Regex("^[A-Za-z0-9._-]+\\.gguf$")

    fun byId(id: String): OnDeviceModel? = MODELS.firstOrNull { it.id == id }

    /** Models of a size class ([OnDeviceModel.tier]), default or experimental. */
    fun forTier(tier: DeviceTier): List<OnDeviceModel> = MODELS.filter { it.tier == tier }

    /**
     * The model the product uses by default on a device of [tier]. S0.2 policy: only HIGH
     * gets a model (Qwen2.5-1.5B Q4_K_M); MID and LOW use rules + cloud only, because the
     * 0.5–0.6B candidates failed the routing cases (Qwen3-0.6B 1/4 on the phone).
     */
    fun defaultFor(tier: DeviceTier): OnDeviceModel? = MODELS.firstOrNull { it.tierDefault && it.tier == tier }

    /** Download size the tier's default model needs (0 when the tier has no model). */
    fun largestForTier(tier: DeviceTier): Long = defaultFor(tier)?.sizeBytes ?: 0L

    fun isWellFormedSha256(s: String?): Boolean = s != null && SHA256_RE.matches(s)

    /**
     * Returns null when [model] may be downloaded/used, otherwise the refusal reason.
     * Unverified hashes are only tolerated when [allowUnverified] (debug benchmark mode),
     * and even then a well-formed hash must exist when one is provided.
     */
    fun usabilityError(model: OnDeviceModel, allowUnverified: Boolean): String? {
        if (!FILE_NAME_RE.matches(model.fileName)) return "非法文件名：${model.fileName}"
        if (model.urls.isEmpty()) return "没有下载地址"
        for (u in model.urls) {
            val host = hostOf(u) ?: return "非法下载地址：$u"
            if (!u.startsWith("https://")) return "下载地址必须是 HTTPS：$u"
            if (host !in ALLOWED_HOSTS) return "下载地址主机不在白名单：$host"
        }
        return when (model.hashStatus) {
            HashStatus.VERIFIED ->
                if (isWellFormedSha256(model.sha256)) null else "已标记 VERIFIED 但 SHA256 格式非法"
            HashStatus.UNVERIFIED -> when {
                !allowUnverified -> "模型 SHA256 未核实（UNVERIFIED），仅调试基准模式可用"
                model.sha256 != null && !isWellFormedSha256(model.sha256) -> "SHA256 格式非法"
                else -> null
            }
        }
    }

    fun validateCatalog(models: List<OnDeviceModel> = MODELS): List<String> {
        val problems = mutableListOf<String>()
        if (models.map { it.id }.toSet().size != models.size) problems += "duplicate id"
        if (models.map { it.fileName }.toSet().size != models.size) problems += "duplicate fileName"
        for (m in models) {
            if (m.sizeBytes <= 0) problems += "${m.id}: sizeBytes must be > 0"
            if (m.tier == DeviceTier.LOW) problems += "${m.id}: LOW tier has no model"
            usabilityError(m, allowUnverified = true)?.let { problems += "${m.id}: $it" }
            if (m.hashStatus == HashStatus.VERIFIED && !isWellFormedSha256(m.sha256)) {
                problems += "${m.id}: VERIFIED without valid sha256"
            }
        }
        return problems
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }
}
