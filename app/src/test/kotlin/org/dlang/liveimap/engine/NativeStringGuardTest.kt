package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JNI's NewStringUTF aborts the VM (CheckJNI) or corrupts strings on malformed modified UTF-8, and native strings
 * here carry server and sender bytes (charsets, encodings, traffic lines, TLS text). Every Java string made in
 * native code must go through newString(), which maps invalid sequences to '?'.
 */
class NativeStringGuardTest {
    @Test fun nativeCodeUsesOnlyTheSafeStringHelper() {
        val dir = File("src/main/cpp")
        assertTrue("run from the app module: ${dir.absolutePath}", dir.isDirectory)
        val offenders = dir.walk().filter { it.extension in setOf("cpp", "cc", "c", "h") }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("NewStringUTF")) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
        val raw = File(dir, "liveimap_jni.cpp").readLines().count { it.contains("env->NewString(") }
        assertEquals("only newString() itself calls NewString", 1, raw)
    }
}
