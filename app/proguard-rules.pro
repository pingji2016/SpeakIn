# SpeakIn ProGuard Rules

# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes SourceFile, LineNumberTable

# Keep Room entities
-keep class com.speakin.app.data.local.entity.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep ExecuTorch native methods
-keep class org.pytorch.executorch.** { *; }
-keep class com.speakin.modelservice.ExecuTorchWhisperEngine { *; }

# Keep Facebook JNI (ExecuTorch depends on fbjni + SoLoader)
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }

# Keep llama.cpp JNI
-keep class com.speakin.app.domain.llm.LocalLlmEngine { *; }
-keep class com.speakin.modelservice.LocalLlmEngine { *; }

# Keep AIDL interfaces
-keep class com.speakin.modelservice.IModelService** { *; }
-keep class com.speakin.modelservice.IModelServiceCallback** { *; }

# Keep Coil
-keep class coil.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
