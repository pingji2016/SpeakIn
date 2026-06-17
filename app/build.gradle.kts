import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.speakin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speakin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // CI / 自动构建用 — 优先从 signing.properties 读取，否则用环境变量
        create("release") {
            val signingFile = rootProject.file("signing.properties")
            val props = mutableMapOf<String, String>()
            if (signingFile.exists()) {
                signingFile.forEachLine { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) props[parts[0].trim()] = parts[1].trim()
                }
            }
            storeFile = file(props["storeFile"] ?: System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            storePassword = props["storePassword"] ?: System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = props["keyAlias"] ?: System.getenv("KEY_ALIAS") ?: "ci-release"
            keyPassword = props["keyPassword"] ?: System.getenv("KEY_PASSWORD") ?: "android"
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    // Play Asset Delivery
    assetPacks += listOf(":speakin_assets")
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

tasks.register("downloadWhisperModel") {
    group = "SpeakIn"
    description = "下载 whisper-tiny ExecuTorch 模型 (.pte) 到 whisper_models/ 目录 (~233 MB)"

    doLast {
        val dir = whisperModelDir.asFile
        dir.mkdirs()

        val files = mapOf(
            "whisper_tiny_xnnpack_fp32.pte" to "https://hf-mirror.com/software-mansion/react-native-executorch-whisper-tiny/resolve/main/xnnpack/whisper_tiny_xnnpack_fp32.pte",
            "tokenizer.json" to "https://hf-mirror.com/software-mansion/react-native-executorch-whisper-small/resolve/main/tokenizer.json"
        )

        files.forEach { (filename, url) ->
            val target = File(dir, filename)
            if (target.exists() && target.length() > 1000) {
                println("  ✅ 已存在，跳过: $filename (${target.length() / 1024 / 1024} MB)")
                return@forEach
            }

            println("  ⏳ 下载: $filename ...")
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.setRequestProperty("User-Agent", "SpeakIn-Build/1.0")
                connection.connect()

                if (connection.responseCode != 200) {
                    throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
                }

                target.outputStream().use { output ->
                    connection.inputStream.use { input ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }

                val sizeMb = target.length() / (1024.0 * 1024.0)
                println("  ✅ 完成: $filename (${"%.1f".format(sizeMb)} MB)")
            } catch (e: Exception) {
                throw RuntimeException("下载 $filename 失败: ${e.message}", e)
            }
        }

        println()
        println("=".repeat(60))
        println("  📁 模型文件: ${dir.absolutePath}")
        println()
        println("  推送到手机:")
        println("    adb shell mkdir -p /data/data/com.speakin.app/files/whisper/")
        println("    adb push ${dir.absolutePath}\\whisper_tiny_xnnpack_fp32.pte /data/data/com.speakin.app/files/whisper/")
        println("    adb push ${dir.absolutePath}\\tokenizer.json /data/data/com.speakin.app/files/whisper/")
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
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 600_000
            connection.setRequestProperty("User-Agent", "SpeakIn-Build/1.0")
            connection.connect()

            if (connection.responseCode != 200) {
                throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }

            target.outputStream().use { output ->
                connection.inputStream.use { input ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            val sizeMb = target.length() / (1024.0 * 1024.0)
            println("  ✅ 完成: $filename (${"%.1f".format(sizeMb)} MB)")
        } catch (e: Exception) {
            throw RuntimeException("下载 $filename 失败: ${e.message}", e)
        }

        println()
        println("=".repeat(60))
        println("  📁 模型文件: ${target.absolutePath}")
        println()
        println("  推送到手机:")
        println("    adb shell mkdir -p /data/data/com.speakin.app/files/models/")
        println("    adb push ${target.absolutePath} /data/data/com.speakin.app/files/models/")
        println("=".repeat(60))
    }
}

tasks.register("downloadAllModels") {
    group = "SpeakIn"
    description = "下载全部模型（whisper ASR + Qwen3 润色）"
    dependsOn("downloadWhisperModel", "downloadLlmModel")
}

// 将 whisper 模型文件复制到 asset pack 中（用于 AAB 构建）
tasks.register<Copy>("copyModelsToAssetPack") {
    group = "SpeakIn"
    description = "复制 whisper 模型到 speakin_assets asset pack"

    val srcDir = rootProject.layout.projectDirectory.dir("whisper_models").asFile
    val dstDir = rootProject.layout.projectDirectory.dir("speakin_assets/src/main/assets").asFile

    from(srcDir) {
        include("whisper_tiny_xnnpack_fp32.pte", "tokenizer.json")
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
