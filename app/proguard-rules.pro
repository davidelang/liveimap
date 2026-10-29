# LiveIMAP release shrinking (F20). R8 cannot see JNI or reflective uses, so they are kept by name here.

# Native methods: keep the names the .so binds by (Java_<class>_<method>) and their argument types.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# App classes C names are @Keep. JDK classes need no rule.

# Reflection: BuildConfig.GIT_COMMIT is read with getField by the lab instrumentation (LabSuiteTest).
-keep class com.davidlang.liveimap.BuildConfig { *; }

# Readable release stack traces (David reads them from logcat); the mapping file is kept in build/outputs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Toolbar edge-scroll adds the pane delta through these reorderable 3.1.0 members. R8 sees the release suffix.
-keepclassmembers class sh.calvin.reorderable.ReorderableListScope {
    *** access$getState$p(...);
}
-keepclassmembers class sh.calvin.reorderable.ReorderableListState {
    *** getItemOffsets$reorderable_debug();
    *** getItemOffsets$reorderable_release();
    *** getDraggableStates$reorderable_debug();
    *** getDraggableStates$reorderable_release();
}

# SettingsAccessGeneratedKt.with_* stays one method per group (R8 must not inline them together).
-keep,allowobfuscation class org.dlang.liveimap.settings.SettingsAccessGeneratedKt {
    org.dlang.liveimap.settings.AccountSettings with_*(org.dlang.liveimap.settings.AccountSettings,java.lang.String,org.dlang.liveimap.settings.SettingValue);
}
