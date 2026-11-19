# OkHttp ships its own consumer rules; these silence optional-platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit / Play services ship consumer rules; keep text recognition models safe.
-keep class com.google.mlkit.vision.text.** { *; }

# ONNX Runtime (used by Silero VAD) is called from native code.
-keep class ai.onnxruntime.** { *; }
-keep class com.konovalov.vad.** { *; }
-dontwarn ai.onnxruntime.**
