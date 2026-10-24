package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pass-5 fixes that have no JVM entry point; checked in the source. */
class Pass5GuardTest {
    private val jni = listOf("src/main/cpp/liveimap_jni.cpp", "app/src/main/cpp/liveimap_jni.cpp")
        .map(::File).first { it.exists() }.readText()

    /** F139: accept-invalid from the IMAP settings never relaxes a different SMTP host. */
    @Test fun smtpAcceptInvalidOnlyForTheImapHost() {
        assertFalse(jni.contains("if (!trust.empty() && session->acceptInvalidCert && certificateVerdict(trust)) trust.clear();"))
        assertFalse(jni.contains("if (!trust.empty() && imapHostToo && session->acceptInvalidCert && certificateVerdict(trust)) trust.clear();"))
        assertTrue(jni.contains("if (!trust.empty() && imapHostToo && acceptInvalidCert && certificateVerdict(trust) && trust != \"certificate changed\") {\n            trust.clear();"))
        assertFalse(jni.contains("if (imapHostToo) pin = session->certPin.c_str();"))
        assertTrue(jni.contains("if (imapHostToo) pin = certPin.c_str();"))
    }

    /** SMTP connects on the owner. nativeSmtp has tcpConnect before mailsmtp_connect, and there is no session mutex. */
    @Test fun smtpConnectsWithoutTheSessionMutex() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSmtp(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val tcp = body.indexOf("tcpConnect(")
        val remember = body.indexOf("rememberSmtpSocket(")
        val sslOpen = body.indexOf("mailstream_ssl_open_with_server_name_callback_timeout(")
        val greet = body.indexOf("mailsmtp_connect(")
        val send = body.indexOf("mailsmtp_data_message(")
        assertTrue(tcp in 0 until greet)
        assertTrue(tcp < remember && remember < sslOpen && sslOpen < greet && greet < send)
        val plain = body.indexOf("mailstream_socket_open_timeout(")
        val plainGreet = body.indexOf("mailsmtp_connect(", plain)
        val plainRemember = body.lastIndexOf("rememberSmtpSocket(", plainGreet)
        assertTrue(plainRemember in 0 until plain && plain < plainGreet)
        val free = jni.indexOf("void freeSmtp(")
        val freeBody = jni.substring(free, jni.indexOf("\n}\n", free))
        val freeClear = freeBody.indexOf("session->smtpFd.exchange(-1);")
        val freeRelease = freeBody.indexOf("mailsmtp_free(")
        assertTrue(freeClear in 0 until freeRelease)
        assertFalse(jni.contains("void smtpTrafficLogger("))
        assertTrue(jni.contains("void smtpTrafficLoggerCopied("))
        val after = body.substring(tcp)
        assertFalse(after.contains("session->smtpHost"))
        assertFalse(after.contains("session->tlsMode"))
        assertFalse(after.contains("session->certPin"))
        assertFalse(after.contains("session->clientP12"))
        assertFalse(after.contains("session->logImapTraffic"))
        assertFalse(after.contains("session->from"))
        assertFalse(after.contains("session->acceptInvalidCert"))
        assertFalse(after.contains("session->allowPlaintextAuth"))
        assertFalse(after.contains("session->allowOldTls"))
        assertFalse(after.contains("session->host"))
        assertFalse(after.contains("session->keyChain"))
        assertTrue(jni.contains("shutdownFd(session->smtpFd.load());"))
        val abort = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAbort(")
        val abortBody = jni.substring(abort, jni.indexOf("\n}\n", abort))
        assertTrue(abortBody.contains("shutdownFd(session->smtpFd.load());"))
        assertFalse(jni.contains("session->mu"))
    }

    /** F141: an expunge ahead of the client sort walk restarts it instead of "sort failed". */
    @Test fun sortChunkPastShrunkenExistsIsDrift() {
        assertTrue(sortChunkDrift(100, 90, false))
        assertTrue(sortChunkDrift(100, 100, true))
        assertFalse(sortChunkDrift(100, 100, false))
        val kt = listOf("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt", "app/src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(kt.contains("if (!sequencesStale) return built"))
    }

    // F50 JMAP copy-first: was a source-text check; now every JMAP send goes through keepCopyUntilSubmitted, whose
    // behaviour is tested in ui/compose/KeepCopyUntilSubmittedTest.

    /** The watch open copies certificate fields and runs on the owner. No session mutex. */
    @Test fun watchSetupConnectsWithoutTheLock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        assertTrue(body.contains("withOwnerCall"))
        val open = body.indexOf("openPlain(")
        val before = body.substring(0, open)
        assertTrue(before.contains("owned.clientP12 = session->clientP12"))
        assertTrue(before.contains("owned.keyChainChain"))
        assertTrue(before.contains("owned.keyChainKey"))
        assertTrue(before.contains("session->allowPlaintextAuth"))
        assertTrue(before.contains("session->allowOldTls"))
        assertFalse(jni.contains("session->mu"))
        val examine = body.indexOf("mailimap_examine(")
        val publish = body.indexOf("session->watch = watch;")
        val checkBeforePublish = body.lastIndexOf("watchSetupSuperseded(", publish)
        assertTrue(examine < checkBeforePublish && checkBeforePublish < publish)
        assertTrue(body.contains("watchSetupSuperseded(session->closed.load(), session->watchGeneration.load(), generation, session->watch != nullptr)"))
        val serial = listOf("src/main/kotlin/org/dlang/liveimap/session/SerialMailSession.kt", "app/src/main/kotlin/org/dlang/liveimap/session/SerialMailSession.kt")
            .map(::File).first { it.exists() }.readText()
        assertFalse(serial.contains("watchMutex"))
        assertFalse(serial.contains("onLane { inner.watch("))
        assertFalse(serial.contains("onLane { inner.stopWatch("))
        assertFalse(serial.contains("onLane { inner.resume("))
        assertTrue(serial.contains("withContext(Dispatchers.IO) { inner.watch(mailbox, onChange) }"))
        assertTrue(serial.contains("withContext(Dispatchers.IO) { inner.stopWatch() }"))
        assertTrue(serial.contains("withContext(Dispatchers.IO) { inner.resume() }"))
    }

    /** The watch open copies certificate fields on the owner before it connects. No session mutex. */
    @Test fun watchOpenCopiesCertificateFieldsBeforeUnlock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        assertTrue(body.contains("withOwnerCall"))
        val open = body.indexOf("openPlain(")
        assertTrue(open > 0)
        val before = body.substring(0, open)
        assertFalse(jni.contains("session->mu"))
        assertTrue(before.contains("session->clientP12"))
        assertTrue(before.contains("session->clientP12Password"))
        assertTrue(before.contains("session->keyChainChain"))
        assertTrue(before.contains("session->keyChainKey"))
        assertTrue(before.contains("session->allowPlaintextAuth"))
        assertTrue(before.contains("session->authMechanism"))
        assertTrue(before.contains("session->allowOldTls"))
        assertTrue(before.contains("session->logImapTraffic"))
        val after = body.substring(open)
        assertFalse(after.contains("session->clientP12"))
        assertFalse(after.contains("session->clientP12Password"))
        assertFalse(after.contains("session->keyChainChain"))
        assertFalse(after.contains("session->keyChainKey"))
        assertFalse(after.contains("session->allowPlaintextAuth"))
        assertFalse(after.contains("session->authMechanism"))
        assertFalse(after.contains("session->allowOldTls"))
        assertFalse(after.contains("session->logImapTraffic"))
        assertTrue(body.contains("&owned"))
        assertTrue(jni.contains("owned->clientP12"))
        assertTrue(jni.contains("owned->allowPlaintextAuth"))
        assertTrue(jni.contains("owned->logImapTraffic"))
        assertTrue(jni.contains("owned->acceptInvalidCert"))
        assertTrue(after.contains("openPlain(env, host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, &owned)"))
        assertTrue(after.contains("openTls(env, tlsMode.c_str(), host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, certPin.c_str(), false, &owned)"))
        assertFalse(after.contains(", session, &error"))
        val plainAt = jni.indexOf("mailimap * openPlain(")
        val plain = jni.substring(plainAt, jni.indexOf("\n}\n", plainAt))
        val plainOwned = plain.indexOf("openTcp(host, port, nullptr, error, &address, logTraffic, \"watch\")")
        val plainLive = plain.indexOf(": openTcp(host, port, live,")
        assertTrue(plainOwned in 0 until plainLive)
        assertFalse(plain.substring(plainOwned, plainLive).contains(", live,"))
        assertFalse(plain.substring(plainOwned, plainLive).contains("imapTrafficLogger"))
        val tlsAt = jni.indexOf("mailimap * openTls(")
        val tls = jni.substring(tlsAt, jni.indexOf("\n}\n", tlsAt))
        assertTrue(tls.contains("openTcp(host, port, nullptr, error, &address, logTraffic, \"watch\")"))
        assertTrue(tls.contains("attachOpenedTrafficLogger(imap, logTraffic, nullptr, \"watch\")"))
        assertFalse(tls.contains("mailimap_set_logger(imap, imapTrafficLogger, live)"))
        val tcpAt = jni.indexOf("mailimap * openTcp(")
        val tcp = jni.substring(tcpAt, jni.indexOf("\n}\n", tcpAt))
        assertTrue(tcp.contains("attachOpenedTrafficLogger(imap, logTraffic, nullptr, copiedLogId)"))
        assertFalse(tcp.contains("mailimap_set_logger(imap, imapTrafficLogger, live)"))
        val copiedAt = jni.indexOf("void imapTrafficLoggerCopied(")
        val copied = jni.substring(copiedAt, jni.indexOf("\n}\n", copiedAt))
        assertFalse(copied.contains("LiveSession"))
        assertFalse(copied.contains("logImapTraffic"))
        assertFalse(copied.contains("->watch"))
        assertFalse(copied.contains("->imap"))
        val attachAt = jni.indexOf("void attachOpenedTrafficLogger(")
        val attach = jni.substring(attachAt, jni.indexOf("\n}\n", attachAt))
        val copiedLogger = attach.indexOf("imapTrafficLoggerCopied")
        val copiedReturn = attach.indexOf("return;", copiedLogger)
        val liveLogger = attach.indexOf("mailimap_set_logger(imap, imapTrafficLogger, live)")
        assertTrue(copiedLogger in 0 until copiedReturn && copiedReturn < liveLogger)
        assertFalse(attach.contains("live->"))
    }

    /** F27: the extra-IDLE close (reached from onStop on Main) runs on IO. */
    @Test fun watchedFolderCloseIsOffMain() {
        val w = listOf("src/main/kotlin/org/dlang/liveimap/session/WatchedFolders.kt", "app/src/main/kotlin/org/dlang/liveimap/session/WatchedFolders.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(w.contains("withContext(Dispatchers.IO) { session.close() }"))
    }

    /** Device test 2026-10-13: a typed password must count as an unsaved edit before the field loses focus. */
    @Test fun typedPasswordIsADraftAtOnce() {
        val w = listOf("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(w.contains("onDraft = { typed -> passwordDraft = typed.takeIf { it != editor.password } },"))
    }

    /** A watch stop waits until the owner has finished. There is no stop condition variable. */
    @Test fun watchStopJoinsOutsideTheLock() {
        val join = jni.substringAfter("void joinOwner(LiveSession * session) {").substringBefore("\n}\n")
        assertTrue(join.contains("while (!session->ownerFinished.load()) session->ownerFinished.wait(false);"))
        assertFalse(jni.contains("stopCv"))
    }

    /** finishIdleStop closes the wake fd before it frees the watch connection. */
    @Test fun watchStopShutsTheSocketBeforeJoin() {
        val finish = jni.substringAfter("void finishIdleStop(LiveSession * session) {").substringBefore("\n}\n")
        val shut = finish.indexOf("close(session->watchWakeFd);")
        val free = finish.indexOf("mailimap_free(session->watch);")
        assertTrue(shut in 0 until free)
        val sig = jni.indexOf("void signalWatchStop(")
        val sigBody = jni.substring(sig, jni.indexOf("\n}\n", sig))
        assertTrue(sigBody.contains("shutdownWatchSocket(session);"))
        assertTrue(sigBody.contains("write(fd,"))
    }

    /** IDLE moves watchPhase to 2 only after IDLE has started. A failure calls finishIdleStop. */
    @Test fun watchPublishesAfterIdleStartsAndClearsOnFailure() {
        val begin = jni.substringAfter("bool beginWatchIdle(LiveSession * session) {").substringBefore("\n}\n")
        val idle = begin.indexOf("mailimap_idle(session->watch)")
        val phase = begin.indexOf("watchPhase.store(2)")
        val fail = begin.indexOf("return false;", idle)
        assertTrue(idle in 0 until fail && fail < phase)
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val loss = body.indexOf("started < 0 || lost")
        assertTrue(body.indexOf("finishIdleStop(", loss) > loss)
        assertTrue(body.contains("gJni.clearPublishedWatch"))
        val kt = listOf(
            "src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt",
            "app/src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt",
        ).map(::File).first { it.exists() }.readText()
        val watchAt = kt.indexOf("override suspend fun watch(")
        val watch = kt.substring(watchAt, kt.indexOf("override suspend fun stopWatch(", watchAt))
        val rewatchAt = kt.indexOf("override fun rewatch(")
        val rewatch = kt.substring(rewatchAt, kt.indexOf("override fun close()", rewatchAt))
        assertTrue(watch.contains("if (started == 0L)"))
        assertTrue(watch.contains("cur.offer === offer"))
        assertTrue(watch.contains("clearPublishedWatch("))
        assertTrue(watch.contains("requestedWatch"))
        assertTrue(rewatch.contains("nativeWatch("))
        assertTrue(rewatch.contains("runRewatch("))
        assertTrue(rewatch.contains("requestedWatch"))
        assertTrue(rewatch.contains("cur.offer === offer"))
        assertTrue(rewatch.contains("clearPublishedWatch("))
        val runAt = kt.indexOf("internal fun runRewatch(")
        val runBody = kt.substring(runAt, kt.indexOf("\n}\n", runAt))
        assertTrue(runBody.contains("published ?: requested ?: return false"))
        assertTrue(runBody.contains("start(target)"))
        val zero = runBody.indexOf("if (started == 0L)")
        assertTrue(zero > runBody.indexOf("start(target)"))
        assertTrue(runBody.contains("throw ConnectionLost("))
        assertFalse(runBody.contains("noteTraffic"))
    }

    /** emitWatch runs only while watchPhase is the running phase. */
    @Test fun watchThreadDoesNotEmitUntilPublishedOrCleared() {
        val emit = jni.substringAfter("void emitWatch(LiveSession * session, int kind,").substringBefore("\n}\n")
        val gate = emit.indexOf("session->watchPhase.load() != 2")
        val call = emit.indexOf("CallVoidMethod")
        assertTrue(gate in 0 until call)
        val untaggedAt = jni.indexOf("void handleUntagged(")
        val untagged = jni.substring(untaggedAt, jni.indexOf("\n}\n", untaggedAt))
        assertFalse(untagged.contains("emitWatch("))
        assertTrue(untagged.contains("queueWatch("))
        val queueAt = jni.indexOf("void queueWatch(")
        val queue = jni.substring(queueAt, jni.indexOf("\n}\n", queueAt))
        assertTrue(queue.contains("watchGate == 2"))
        assertTrue(queue.contains("watchGate == 0"))
        assertTrue(queue.contains("watchLostBeforePublish"))
        assertTrue(queue.contains("flushQueuedWatch("))
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val publish = body.indexOf("gJni.publishWatch")
        val go = body.indexOf("watchGate = 1")
        val drop = body.indexOf("watchGate = 2")
        assertTrue(publish in 0 until go)
        assertTrue(drop > 0)
        assertTrue(body.contains("watchLostBeforePublish || session->watchEnded"))
        val dead = body.indexOf("watchGate = 2")
        val clear = body.indexOf("clearThisWatch();", dead)
        val back = body.indexOf("return 0;", clear)
        assertTrue(dead in 0 until clear && clear < back)
    }

    /** A deliver wake is read so poll does not spin. A watch loss calls finishIdleStop, not watchThread.join. */
    @Test fun deliverWakeIsReadAndEarlyLossJoins() {
        val step = jni.substringAfter("bool watchStep(LiveSession * session) {").substringBefore("\n}\n")
        val poll = step.indexOf("poll(pfd,")
        val readWake = step.indexOf("read(session->watchWakeFd", poll)
        assertTrue(poll in 0 until readWake)
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val loss = body.indexOf("started < 0 || lost")
        val stop = body.indexOf("finishIdleStop(", loss)
        val cleared = body.indexOf("clearThisWatch();", stop)
        val back = body.indexOf("return 0;", cleared)
        assertTrue(loss in 0 until stop && stop < cleared && cleared < back)
        assertTrue(body.substring(cleared, back).contains("if (started < 0)"))
        assertFalse(jni.contains("watchThread.join"))
    }
}
