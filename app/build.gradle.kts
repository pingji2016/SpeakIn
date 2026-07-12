import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.hilt)
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
        versionCode = 4
        versionName = "1.0.4"

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

    // Firebase (reserved for future use)
    // implementation(platform(libs.firebase.bom))
    // implementation(libs.firebase.analytics)
    // implementation(libs.firebase.crashlytics)

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
// 模型下载任务
// 用法: .\gradlew downloadWhisperModel   (下载 whisper ASR 模型, ~235MB)
//       .\gradlew downloadLlmModel       (下载 Qwen3 润色模型, ~400MB)
//       .\gradlew downloadAllModels      (下载全部模型)
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
val WHISPER_MODEL_FILES = listOf(
    "whisper_pre_enc.pte"  to "Whisper pre-encoder (raw audio → hidden states)",
    "whisper_decoder.pte"  to "Whisper decoder (autoregressive token generation)",
    "tokenizer.json"       to "BPE tokenizer vocabulary",
)

tasks.register("downloadWhisperModel") {
    group = "SpeakIn"
    description = "从 HuggingFace 下载 Whisper tiny ASR 模型 (~231 MB)"

    doLast {
        // 1. 下载到项目根目录 whisper_models/
        val modelDir = whisperModelDir.asFile
        modelDir.mkdirs()

        for ((filename, desc) in WHISPER_MODEL_FILES) {
            val target = File(modelDir, filename)
            val url = "$WHISPER_HF_BASE/$filename"

            if (target.exists() && target.length() > 100_000) {
                println("  ✅ 已存在: $filename (${"%.1f".format(target.length() / 1024.0 / 1024.0)} MB)")
                continue
            }

            println("  ⏳ 下载: $filename ($desc) ...")
            try {
                downloadWithRedirect(url, target)
                println("  ✅ 完成: $filename (${"%.1f".format(target.length() / 1024.0 / 1024.0)} MB)")
            } catch (e: Exception) {
                throw RuntimeException("下载 $filename 失败: ${e.message}\n  请确认 HuggingFace 仓库已上传且 WHISPER_HF_BASE 配置正确", e)
            }
        }

        // 2. 复制到 assets，让模型打包进 APK
        val assetsDir = file("src/main/assets/models/whisper")
        assetsDir.mkdirs()

        for ((filename, _) in WHISPER_MODEL_FILES) {
            val src = File(modelDir, filename)
            if (src.exists()) {
                src.copyTo(File(assetsDir, filename), overwrite = true)
            }
        }

        println()
        println("=".repeat(60))
        println("  ✅ Whisper 模型已就绪，直接构建 APK 即可：")
        println("    .\\gradlew :app:assembleDebug")
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
        println("  📁 模型文件: ${target.absolutePath}")
        println()
        println("  ✅ 模型已下载完成，已复制到 assets/ 目录。")
        println("  现在直接构建 APK 即可（模型已打包在 APK 中）：")
        println("    .\\gradlew :app:assembleDebug")
        println("=".repeat(60))

        // Copy to assets so model is bundled in APK
        val assetsDir = file("src/main/assets/models")
        assetsDir.mkdirs()
        target.copyTo(File(assetsDir, filename), overwrite = true)
    }
}

tasks.register("downloadAllModels") {
    group = "SpeakIn"
    description = "下载全部模型（whisper ASR + Qwen3 润色）"
    dependsOn("downloadWhisperModel", "downloadLlmModel")
}

// 构建 APK 时自动下载 Whisper 模型（已下载则跳过）
afterEvaluate {
    tasks.matching { it.name in setOf("mergeReleaseAssets", "mergeDebugAssets") }
        .configureEach { dependsOn("downloadWhisperModel") }
}

// 将 whisper 模型文件复制到 asset pack 中（用于 AAB 构建）
tasks.register<Copy>("copyModelsToAssetPack") {
    group = "SpeakIn"
    description = "复制 whisper 模型到 speakin_assets asset pack"

    val srcDir = rootProject.layout.projectDirectory.dir("whisper_models").asFile
    val dstDir = rootProject.layout.projectDirectory.dir("speakin_assets/src/main/assets").asFile

    from(srcDir) {
        include("whisper_pre_enc.pte", "whisper_decoder.pte", "tokenizer.json")
    }
    into(dstDir)

    doFirst {
        dstDir.mkdirs()
        println("Copying model files to asset pack: ${dstDir.absolutePath}")
    }

    doLast {
        println("Model files copied to asset pack successfully.")
    }
}
