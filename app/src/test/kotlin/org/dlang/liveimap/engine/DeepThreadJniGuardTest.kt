package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F114 (withdrawn; guard kept): THREAD replies can nest one level per reply, so the JNI tree builder must stay
 * iterative and release each finished node's local refs (live refs = depth + ~2). The on-device depth test is
 * part of the emulator checks; host C++ tests are not wired into the build.
 */
class DeepThreadJniGuardTest {
    private val body = File("src/main/cpp/liveimap_jni.cpp").readText()
        .substringAfter("jobject threadObject(JNIEnv * env, LiveThread * node) {")
        .substringBefore("\nLiveThread * detachThread(")

    @Test
    fun threadObjectIsIterative() {
        assertTrue(body.contains("std::vector<Frame> stack;"))
        assertEquals("no recursive call", 0, Regex("""\bthreadObject\(""").findAll(body).count())
    }

    @Test
    fun finishedNodesReleaseTheirLocalRefs() {
        assertTrue(body.contains("env->DeleteLocalRef(uid);"))
        assertTrue(body.contains("env->DeleteLocalRef(top.list);"))
        assertTrue(body.contains("env->DeleteLocalRef(obj);"))
        assertFalse("no frame push that could be skipped on an early return", body.contains("PushLocalFrame"))
    }

    @Test
    fun parseStaysBoundedByNodeCount() {
        assertTrue(File("src/main/cpp/liveimap_jni.cpp").readText().contains("kMaxThreadNodes"))
    }
}
