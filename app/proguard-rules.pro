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
-keep class org.dlang.liveimap.engine.TrafficLog** { *; }
-keep class org.dlang.liveimap.session.ConnectionLost { *; }
-keep class org.dlang.liveimap.session.FolderEntry { *; }
-keep class org.dlang.liveimap.session.ImapAuthKt { *; }
-keep class org.dlang.liveimap.session.IndexRow { *; }
-keep class org.dlang.liveimap.session.MailFailure { *; }
-keep class org.dlang.liveimap.session.MailboxUids { *; }
-keep class org.dlang.liveimap.session.MimePart { *; }
-keep class org.dlang.liveimap.session.Namespace { *; }
-keep enum org.dlang.liveimap.session.NamespaceKind { *; }
-keep class org.dlang.liveimap.session.SelectResult { *; }
-keep class org.dlang.liveimap.session.SortField { *; }
-keep class org.dlang.liveimap.session.ThreadHeader { *; }
-keep class org.dlang.liveimap.session.ThreadNode { *; }
-keep class org.dlang.liveimap.ui.reader.PartText** { *; }

# Reflection: BuildConfig.GIT_COMMIT is read with getField by the lab instrumentation (LabSuiteTest).
-keep class org.dlang.liveimap.BuildConfig { *; }

# Readable release stack traces (David reads them from logcat); the mapping file is kept in build/outputs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
