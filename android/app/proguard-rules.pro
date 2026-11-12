# Voice Kiosk ProGuard Rules

# Sherpa-ONNX native JNI bindings
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# JPinyin offline pinyin library
-keep class com.github.stuxuhai.jpinyin.** { *; }
