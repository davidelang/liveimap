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
        assertTrue(jni.contains("if (!trust.empty() && imapHostToo && acceptInvalidCert && certificateVerdict(trust)) trust.clear();"))
        assertFalse(jni.contains("if (imapHostToo) pin = session->certPin.c_str();"))
        assertTrue(jni.contains("if (imapHostToo) pin = certPin.c_str();"))
    }

    /** SMTP copies the fields it needs, then drops the session mutex before the connect and the send. */
    @Test fun smtpConnectsWithoutTheSessionMutex() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSmtp(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val unlock = body.indexOf("session->mu.unlock();")
        val tcp = body.indexOf("tcpConnect(")
        val remember = body.indexOf("rememberSmtpSocket(")
        val sslOpen = body.indexOf("mailstream_ssl_open_with_server_name_callback_timeout(")
        val greet = body.indexOf("mailsmtp_connect(")
        val send = body.indexOf("mailsmtp_data_message(")
        assertTrue(unlock in 0 until tcp && tcp < remember && remember < sslOpen && sslOpen < greet && greet < send)
        val plain = body.indexOf("mailstream_socket_open_timeout(")
        val plainGreet = body.indexOf("mailsmtp_connect(", plain)
        val plainRemember = body.lastIndexOf("rememberSmtpSocket(", plainGreet)
        assertTrue(plainRemember in 0 until plain && plain < plainGreet)
        val free = jni.indexOf("void freeSmtp(")
        val freeBody = jni.substring(free, jni.indexOf("\n}\n", free))
        val freeLock = freeBody.indexOf("session->mu.lock();")
        val freeClear = freeBody.indexOf("session->smtpFd.store(-1);")
        val freeUnlock = freeBody.indexOf("session->mu.unlock();")
        val freeRelease = freeBody.indexOf("mailsmtp_free(")
        assertTrue(freeLock in 0 until freeClear && freeClear < freeUnlock && freeUnlock < freeRelease)
        assertFalse(jni.contains("void smtpTrafficLogger("))
        assertTrue(jni.contains("void smtpTrafficLoggerCopied("))
        val after = body.substring(unlock)
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
        val readFd = abortBody.indexOf("session->smtpFd.load()")
        val abortLock = abortBody.lastIndexOf("session->mu.lock();", readFd)
        val abortUnlock = abortBody.indexOf("session->mu.unlock();", readFd)
        assertTrue(abortLock in 0 until readFd && readFd < abortUnlock)
    }

    /** F140: the JMAP long-poll's model callbacks run under the screen gate. */
    @Test fun jmapPushUpdatesTakeTheGate() {
        val screen = listOf("src/main/kotlin/org/dlang/liveimap/ui/folder/JmapFolderScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/folder/JmapFolderScreen.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(screen.contains("gate.withLock { model.applyPush(state, model.pushOpen()) }"))
        assertTrue(screen.contains("gate.withLock { model.messages(id) }"))
        assertFalse(screen.contains("reload = { id -> model.messages(id) }"))
    }

    /** F141: an expunge ahead of the client sort walk restarts it instead of "sort failed". */
    @Test fun sortChunkPastShrunkenExistsIsDrift() {
        assertTrue(sortChunkDrift(100, 90, false))
        assertTrue(sortChunkDrift(100, 100, true))
        assertFalse(sortChunkDrift(100, 100, false))
        val kt = listOf("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt", "app/src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(kt.contains("if (sortChunkDrift(exists, selectedExists(), sequencesStale)) { drift = true; break }"))
        assertFalse(kt.contains("nativeFetchSortFieldsRange(requireHandle(), wireField, first, last) ?: throw"))
    }

    // F50 JMAP copy-first: was a source-text check; now every JMAP send goes through keepCopyUntilSubmitted, whose
    // behaviour is tested in ui/compose/KeepCopyUntilSubmittedTest.

    /** F13/F136/F261: connect, EXAMINE, and the IDLE-start wait run without the session lock. Publish checks the generation. */
    @Test fun watchSetupConnectsWithoutTheLock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val unlock = body.indexOf("session->mu.unlock();")
        val open = body.indexOf("openTls(")
        val relock = body.indexOf("session->mu.lock();")
        assertTrue(unlock in 0 until open && open < relock)
        val examine = body.indexOf("mailimap_examine(")
        val examineUnlock = body.lastIndexOf("session->mu.unlock();", examine)
        val examineLock = body.indexOf("session->mu.lock();", examine)
        assertTrue(examineUnlock in 0 until examine && examine < examineLock)
        val wait = body.indexOf("session->startCv.wait(")
        val waitUnlock = body.lastIndexOf("session->mu.unlock();", wait)
        val waitLock = body.indexOf("session->mu.lock();", wait)
        assertTrue(waitUnlock in 0 until wait && wait < waitLock)
        val publish = body.indexOf("session->watch = watch;")
        val checkBeforePublish = body.lastIndexOf("watchSetupSuperseded(", publish)
        assertTrue(examine < checkBeforePublish && checkBeforePublish < publish)
        val afterWait = body.substring(wait)
        val checkAfterWait = afterWait.indexOf("watchSetupSuperseded(")
        val freeAfterWait = afterWait.indexOf("mailimap_free(session->watch)")
        assertTrue(checkAfterWait in 0 until freeAfterWait)
        assertTrue(body.contains("watchSetupSuperseded(session->closed, session->watchGeneration, generation, session->watch != nullptr)"))
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

    /** F267: the watch open copies certificate fields while the session mutex is still held. */
    @Test fun watchOpenCopiesCertificateFieldsBeforeUnlock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val unlock = body.indexOf("session->mu.unlock();")
        assertTrue(unlock > 0)
        val before = body.substring(0, unlock)
        assertTrue(before.contains("session->clientP12"))
        assertTrue(before.contains("session->clientP12Password"))
        assertTrue(before.contains("session->keyChainChain"))
        assertTrue(before.contains("session->keyChainKey"))
        assertTrue(before.contains("session->allowPlaintextAuth"))
        assertTrue(before.contains("session->authMechanism"))
        assertTrue(before.contains("session->allowOldTls"))
        assertTrue(before.contains("session->logImapTraffic"))
        val after = body.substring(unlock)
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

    /** F27: a watch stop joins the watch thread without the session lock; other stops wait for it. */
    @Test fun watchStopJoinsOutsideTheLock() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStopWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val unlock = body.indexOf("session->mu.unlock();")
        val join = body.indexOf("thread.join();")
        val relock = body.indexOf("session->mu.lock();")
        assertTrue(unlock in 0 until join && join < relock)
        assertTrue(jni.contains("while (session->watchStopping) session->stopCv.wait(session->mu);"))
    }

    /** Stop shuts the watch socket down before join, and the stop path does not call mailimap_idle_done. */
    @Test fun watchStopShutsTheSocketBeforeJoin() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStopWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val shut = body.indexOf("shutdownWatchSocket(session);")
        val join = body.indexOf("thread.join();")
        val unlock = body.indexOf("session->mu.unlock();")
        val relock = body.indexOf("session->mu.lock();")
        assertTrue(shut in 0 until join)
        assertTrue(unlock in 0 until join && join < relock)
        val stop = jni.indexOf("void stopWatchLocked(")
        val stopBody = jni.substring(stop, jni.indexOf("\n}\n", stop))
        val stopShut = stopBody.indexOf("shutdownWatchSocket(session);")
        val stopJoin = stopBody.indexOf("watchThread.join();")
        assertTrue(stopShut in 0 until stopJoin)
        val main = jni.indexOf("void watchMain(")
        val mainBody = jni.substring(main, jni.indexOf("\n}\n", main))
        assertTrue(mainBody.contains("if (!watchLost && !session->watchStop.load())"))
        assertFalse(mainBody.contains("if (!watchLost) {"))
        val sig = jni.indexOf("void signalWatchStop(")
        val sigBody = jni.substring(sig, jni.indexOf("\n}\n", sig))
        assertTrue(sigBody.contains("shutdownWatchSocket(session);"))
        assertTrue(sigBody.contains("write(fd,"))
    }

    /** Publish the watch callback only after IDLE has started. A 0 return clears that generation. */
    @Test fun watchPublishesAfterIdleStartsAndClearsOnFailure() {
        val n = jni.indexOf("Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(")
        val body = jni.substring(n, jni.indexOf("\n}\n", n))
        val wait = body.indexOf("session->startCv.wait(")
        val beforeWait = body.substring(0, wait)
        assertFalse(beforeWait.contains("publishWatch"))
        val tail = body.substring(wait)
        val started = tail.indexOf("started > 0")
        val publish = tail.indexOf("gJni.publishWatch")
        assertTrue(started in 0 until publish)
        val firstReturn = tail.indexOf("return 0;")
        val clearBeforeReturn = tail.lastIndexOf("clearThisWatch();", firstReturn)
        assertTrue(clearBeforeReturn in 0 until firstReturn)
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
        assertTrue(rewatch.contains("if (started == 0L)"))
        assertTrue(rewatch.contains("cur.offer === offer"))
        assertTrue(rewatch.contains("clearPublishedWatch("))
    }

    /** The watch thread queues until nativeWatch publishes that generation or clears it. */
    @Test fun watchThreadDoesNotEmitUntilPublishedOrCleared() {
        val main = jni.indexOf("void watchMain(")
        val mainBody = jni.substring(main, jni.indexOf("\n}\n", main))
        assertFalse(mainBody.contains("emitWatch("))
        assertTrue(mainBody.contains("drainWatchQueue("))
        assertTrue(mainBody.contains("queueWatch("))
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
}
