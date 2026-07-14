import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics.plugin)
}

// ── 签名配置 ──
// 本地：读取 signing.properties；CI：从环境变量获取
val signingProps: Map<String, String> = rootProject.file("signing.properties").let { f ->
    if (f.exists()) {
        f.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim()
            else null
        }.toMap()
    } else emptyMap()
}

android {
    namespace = "com.speakin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speakin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.10"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(signingProps["storeFile"]
                ?: System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            storePassword = signingProps["storePassword"]
                ?: System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = signingProps["keyAlias"]
                ?: System.getenv("KEY_ALIAS") ?: "ci-release"
            keyPassword = signingProps["keyPassword"]
                ?: System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // Play Asset Delivery
    assetPacks += listOf(":speakin_assets")

    // 模型文件不压缩（支持 mmap 直接映射，避免解压开销）
    androidResources {
        noCompress += listOf("pte", "gguf")
    }

}

dependencies {
    implementation(project(":model-service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // ExoPlayer
    implementation(libs.exoplayer.core)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Play In-App Update
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // ExecuTorch
    implementation(libs.executorch.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}

// ============================================================
// 模型下载任务（仅用于本地开发/调试，默认不打包进 APK）
//
// 运行时模型下载优先级：CDN → HuggingFace Mirror → ModelScope
//
// 用法（如需打包模型到 APK）:
//       .\gradlew downloadWhisperModel   (下载 whisper ASR 模型, ~231MB)
//       .\gradlew downloadLlmModel       (下载 Qwen3 润色模型, ~400MB)
//       .\gradlew downloadAllModels      (下载全部模型)
//       .\gradlew copyModelsToBaseAssets (复制模型到 assets, 然后构建 APK)
// ============================================================

val whisperModelDir = rootProject.layout.projectDirectory.dir("whisper_models")
val llmModelDir = rootProject.layout.projectDirectory.dir("llm_models")

// ── 通用下载工具（自动跟踪 301/302/307/308 重定向） ──
fun downloadWithRedirect(urlStr: String, target: File, timeoutMs: Int = 600_000) {
    var url = urlStr
    var redirects = 0
    while (redirects++ < 10) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "SpeakIn-Build/1.0")
        connection.instanceFollowRedirects = false
        connection.connect()

        val code = connection.responseCode
        if (code in 300..399) {
            val newUrl = connection.getHeaderField("Location")
            connection.disconnect()
            if (newUrl == null) throw RuntimeException("Redirect without Location header")
            url = if (newUrl.startsWith("http")) newUrl else URI(url).resolve(newUrl).toString()
            continue
        }
        if (code != 200) throw RuntimeException("HTTP $code: ${connection.responseMessage}")

        target.outputStream().use { output ->
            connection.inputStream.use { input -> input.copyTo(output, bufferSize = 8192) }
        }
        connection.disconnect()
        return
    }
    throw RuntimeException("Too many redirects")
}

val WHISPER_HF_BASE = "https://hf-mirror.com/pingji2025/whisper/resolve/main"
val WHISPER_MS_BASE = "https://www.modelscope.cn/min0max/whisper/resolve/master"
val WHISPER_MODEL_FILES = listOf(
    "whisper_pre_enc.pte"  to "Whisper pre-encoder (raw audio → hidden states)",
    "whisper_decoder.pte"  to "Whisper decoder (autoregressive token generation)",
    "tokenizer.json"       to "BPE tokenizer vocabulary",
)

tasks.register("downloadWhisperModel") {
    group = "SpeakIn"
    description = "下载 Whisper tiny ASR 模型 (~231 MB, HF 优先, ModelScope 兜底)"

    doLast {
        // 下载到项目根目录 whisper_models/
        val modelDir = whisperModelDir.asFile
        modelDir.mkdirs()

        // 下载源：HuggingFace 镜像 → ModelScope（自动降级）
        val downloadSources = listOf(
            "HuggingFace Mirror" to WHISPER_HF_BASE,
            "ModelScope" to WHISPER_MS_BASE
        )

        for ((filename, desc) in WHISPER_MODEL_FILES) {
            val target = File(modelDir, filename)

            if (target.exists() && target.length() > 100_000) {
                println("  ✅ 已存在: $filename (${"%.1f".format(target.length() / 1024.0 / 1024.0)} MB)")
                continue
            }

            println("  ⏳ 下载: $filename ($desc) ...")
            var success = false
            for ((sourceName, baseUrl) in downloadSources) {
                try {
                    val url = "$baseUrl/$filename"
                    downloadWithRedirect(url, target)
                    println("  ✅ 完成 ($sourceName): $filename (${"%.1f".format(target.length() / 1024.0 / 1024.0)} MB)")
                    success = true
                    break
                } catch (e: Exception) {
                    println("  ⚠️  $sourceName 下载失败: ${e.message}, 尝试下一个源...")
                }
            }
            if (!success) {
                throw RuntimeException(
                    "下载 $filename 失败: 所有下载源均不可用\n" +
                    "  请确认 HuggingFace 和 ModelScope 仓库已上传"
                )
            }
        }

        println()
        println("=".repeat(60))
        println("  ✅ Whisper 模型已下载到 whisper_models/")
        println()
        println("  注意：默认 APK 构建不打包模型文件（运行时从网络下载）")
        println("  如需打包进 APK，先执行此任务，再构建：.\\gradlew :app:assembleDebug")
        println("=".repeat(60))
    }
}

tasks.register("downloadLlmModel") {
    group = "SpeakIn"
    description = "下载 Qwen3-0.6B-Q4_K_M GGUF 模型到 llm_models/ 目录"

    doLast {
        val dir = llmModelDir.asFile
        dir.mkdirs()

        val filename = "qwen3-0.6b-q4_k_m.gguf"
        val url = "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
        val target = File(dir, filename)

        if (target.exists() && target.length() > 100_000_000) {
            println("  ✅ 已存在: $filename (${target.length() / 1024 / 1024} MB)")
            return@doLast
        }

        println("  ⏳ 下载: $filename (~400 MB, 可能需要几分钟) ...")
        try {
            downloadWithRedirect(url, target)
            val sizeMb = target.length() / (1024.0 * 1024.0)
            println("  ✅ 完成: $filename (${"%.1f".format(sizeMb)} MB)")
        } catch (e: Exception) {
            throw RuntimeException("下载 $filename 失败: ${e.message}", e)
        }

        println()
        println("=".repeat(60))
        println("  ✅ LLM 模型已下载到 llm_models/（运行时下载，不打包进 APK）")
        println("=".repeat(60))
    }
}

tasks.register("downloadAllModels") {
    group = "SpeakIn"
    description = "下载全部模型（whisper ASR + Qwen3 润色）"
    dependsOn("downloadWhisperModel", "downloadLlmModel")
}

// ── 复制模型到 base assets（仅供本地调试 & CI APK 使用） ──
tasks.register<Copy>("copyModelsToBaseAssets") {
    group = "SpeakIn"
    description = "复制 whisper 模型到 app/src/main/assets/（调试 APK / GitHub Release）"

    val srcDir = whisperModelDir.asFile
    val dstDir = file("src/main/assets/models/whisper")

    from(srcDir) {
        include("whisper_pre_enc.pte", "whisper_decoder.pte", "tokenizer.json")
    }
    into(dstDir)

    // CI 环境没有预下载模型，先下载再复制
    dependsOn("downloadWhisperModel")

    doFirst {
        dstDir.mkdirs()
        println("Copying model files to base assets: ${dstDir.absolutePath}")
    }

    doLast {
        println("Model files copied to base assets (for debug APK / GitHub Release).")
    }
}

// ── 复制模型到 asset pack（用于 AAB 发布构建） ──
tasks.register<Copy>("copyModelsToAssetPack") {
    group = "SpeakIn"
    description = "复制 whisper 模型到 speakin_assets asset pack（AAB 发布 Google Play）"

    val srcDir = whisperModelDir.asFile
    val dstDir = rootProject.layout.projectDirectory.dir("speakin_assets/src/main/assets/models/whisper").asFile

    from(srcDir) {
        include("whisper_pre_enc.pte", "whisper_decoder.pte", "tokenizer.json")
    }
    into(dstDir)

    // CI 环境没有预下载模型，先下载再复制
    dependsOn("downloadWhisperModel")

    doFirst {
        dstDir.mkdirs()
        println("Copying model files to asset pack: ${dstDir.absolutePath}")
    }

    doLast {
        println("Model files copied to asset pack (for AAB / Google Play).")
    }
}

// ── 模型获取策略 ──
// 默认：模型文件不打包进 APK，运行时从网络下载（保持 APK 体积小）。
// 下载优先级：cdn.speakin.app → hf-mirror.com → modelscope.cn
//
// 如需将模型打包进 APK（例如离线发布），执行以下步骤：
//   1. .\gradlew downloadWhisperModel    ← 先下载模型到本地
//   2. .\gradlew copyModelsToBaseAssets   ← 复制到 assets/
//   3. .\gradlew :app:assembleDebug       ← 构建 APK（含模型）
// ============================================================
afterEvaluate {
    // 默认关闭模型打包，保持 APK 体积小。
    // 如需恢复打包，取消下面两行的注释。
    // tasks.matching { it.name in setOf("mergeDebugAssets", "mergeReleaseAssets") }
    //     .configureEach { dependsOn("copyModelsToBaseAssets") }
    // tasks.matching { it.name == "bundleRelease" || it.name == "bundleDebug" }
    //     .configureEach { dependsOn("copyModelsToAssetPack") }
}
