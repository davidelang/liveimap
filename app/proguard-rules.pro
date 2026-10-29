# LiveIMAP release shrinking (F20). R8 cannot see JNI or reflective uses, so they are kept by name here.

# Native methods: keep the names the .so binds by (Java_<class>_<method>) and their argument types.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Classes the native layer looks up with FindClass and calls or constructs (GetMethodID/GetStaticMethodID/
# NewObject, including @JvmStatic companion entry points). Keep the classes, all members, and companions.
# Keep this list in sync with: grep -rhoE '"org/dlang/[^"]+"' app/src/main/cpp
-keep class org.dlang.liveimap.engine.KeyChainSigner** { *; }
-keep class org.dlang.liveimap.engine.LibetpanMailSession** { *; }
-keep class org.dlang.liveimap.engine.MailCharconv** { *; }
-keep class org.dlang.liveimap.engine.MailCharsetsKt { *; }
-keep class org.dlang.liveimap.engine.PeerTrust** { *; }
-keep class org.dlang.liveimap.session.ConnectionLost { *; }
-keep class org.dlang.liveimap.session.FolderEntry { *; }
-keep class org.dlang.liveimap.session.ImapAuthKt { *; }
-keep class org.dlang.liveimap.session.IndexRow { *; }
-keep class org.dlang.liveimap.session.MailFailure { *; }
-keep class org.dlang.liveimap.session.MessageRef { *; }
-keep class org.dlang.liveimap.session.MimePart { *; }
-keep class org.dlang.liveimap.session.Namespace { *; }
-keep enum org.dlang.liveimap.session.NamespaceKind { *; }
-keep class org.dlang.liveimap.session.SelectResult { *; }
-keep class org.dlang.liveimap.session.SortField { *; }
-keep class org.dlang.liveimap.session.ThreadHeader { *; }
-keep class org.dlang.liveimap.engine.SessionOpen { *; }
-keep class org.dlang.liveimap.engine.CopyMoveResult { *; }
-keep class org.dlang.liveimap.session.ClientFetch { *; }
-keep class org.dlang.liveimap.session.ClientHeaders { *; }
-keep class org.dlang.liveimap.session.ThreadFlat { *; }
-keep class org.dlang.liveimap.ui.reader.PartText** { *; }
-keep class org.dlang.liveimap.ui.compose.MimeDecodeException { *; }
-keep class org.dlang.liveimap.ui.reader.MimeTextResult { *; }

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
