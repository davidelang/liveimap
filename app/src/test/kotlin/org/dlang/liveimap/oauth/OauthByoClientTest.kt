package org.dlang.liveimap.oauth

import org.dlang.liveimap.accounts.decodeBackupRecord
import org.dlang.liveimap.accounts.encodeBackupRecord
import org.dlang.liveimap.accounts.planAccountSave
import org.dlang.liveimap.engine.ServerProbe
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.checkServer
import org.dlang.liveimap.jmap.JMAP_MAIL
import org.dlang.liveimap.jmap.JMAP_SUBMISSION
import org.dlang.liveimap.jmap.JmapHttpExchange
import org.dlang.liveimap.jmap.JmapSession
import org.dlang.liveimap.jmap.jmapAuthorization
import org.dlang.liveimap.jmap.jmapDeliver
import org.dlang.liveimap.jmap.jmapMailboxLevel
import org.dlang.liveimap.jmap.jmapPresented
import org.dlang.liveimap.jmap.jmapUpload
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.chooseSmtpAuthMechanism
import org.dlang.liveimap.session.imapAuthOrder
import org.dlang.liveimap.session.oauthBearerInitial
import org.dlang.liveimap.session.xoauth2Initial
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SieveTargetException
import org.dlang.liveimap.settings.documentSecretKeys
import org.dlang.liveimap.settings.exportSettingsDocument
import org.dlang.liveimap.settings.newAccountSettings
import org.dlang.liveimap.settings.sieveTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class OauthByoClientTest {
    private fun oauthSettings() = newAccountSettings().copy(
        accountAuth = "oauth2",
        oauthIssuer = "https://example.com/issuer1",
        oauthAuthorizationEndpoint = "https://example.com/auth",
        oauthTokenEndpoint = "https://example.com/token",
        oauthRevocationEndpoint = "https://example.com/revoke",
        oauthClientId = "client",
        oauthScopes = "mail",
        imapHost = "imap.example",
        username = "user",
    )

    @Test
    fun payloadsAndIllegalUsernames() {
        val bearer = String(Base64.getDecoder().decode(oauthBearerInitial("user", "imap.example", 993, "tok")), Charsets.UTF_8)
        assertTrue(bearer.startsWith("n,a=user,"))
        assertTrue(bearer.contains("\u0001host=imap.example\u0001"))
        assertTrue(bearer.contains("\u0001port=993\u0001"))
        assertTrue(bearer.contains("\u0001auth=Bearer tok\u0001\u0001"))
        assertEquals(OAUTH_BEARER_ERROR_REPLY, "AQ==")
        val xoauth = String(Base64.getDecoder().decode(xoauth2Initial("user", "tok")), Charsets.UTF_8)
        assertEquals("user=user\u0001auth=Bearer tok\u0001\u0001", xoauth)
        for (name in listOf("a,b", "a\u0001b")) {
            try {
                oauthBearerInitial(name, "h", 143, "t")
                throw AssertionError("username $name")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun pkceAndPendingBeforeTheBrowser() {
        val verifier = pkceVerifier(SecureRandom(), 64)
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
        val challenge = pkceS256(verifier)
        assertFalse(challenge.contains('='))
        assertFalse(challenge.contains('+'))
        assertFalse(challenge.contains('/'))
        var pending: OauthPending? = null
        val kept = OauthTokenState(access = "keep")
        val url = prepareBrowserSignIn(oauthSettings(), { pending = it }, { kept })
        assertEquals("keep", kept.access)
        assertTrue(pending != null && pending!!.verifier.isNotEmpty() && pending!!.state.isNotEmpty())
        assertTrue(url.startsWith("https://example.com/auth?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("scope=mail"))
        val bare = prepareBrowserSignIn(oauthSettings().copy(oauthScopes = ""), { pending = it }, { kept })
        assertFalse(bare.contains("scope="))
        assertTrue(formBody(listOf("a" to "b c")).contains("b+c"))
    }

    @Test
    fun authOrderPasswordAndOauth() {
        val caps = "IMAP4rev1 AUTH=PLAIN AUTH=OAUTHBEARER AUTH=XOAUTH2 SASL-IR"
        val password = imapAuthOrder(caps, tls = true, plaintextOk = true, channelBinding = false, clientMechanisms = "PLAIN")
        assertTrue(password.contains("PLAIN"))
        assertFalse(password.contains("OAUTHBEARER"))
        assertEquals(listOf("OAUTHBEARER"), imapAuthOrder(caps, true, true, false, "", false, "OAUTH:AUTO"))
        assertEquals(listOf("XOAUTH2"), imapAuthOrder(caps, true, true, false, "", false, "OAUTH:XOAUTH2"))
        assertTrue(imapAuthOrder("IMAP4rev1 AUTH=PLAIN", true, true, false, "", false, "OAUTH:AUTO").isEmpty())
        assertEquals("OAUTHBEARER", chooseSmtpAuthMechanism(caps, tls = true, plaintextOk = true, channelBinding = false, clientCert = true, oauthMode = "OAUTH:AUTO"))
        assertNull(chooseSmtpAuthMechanism(caps, tls = false, plaintextOk = true, channelBinding = false, clientCert = false, oauthMode = "OAUTH:AUTO"))
        assertEquals("PLAIN", chooseSmtpAuthMechanism("AUTH=PLAIN", tls = true, plaintextOk = true, channelBinding = false, clientCert = false))
        assertNull(chooseSmtpAuthMechanism("AUTH=PLAIN", tls = true, plaintextOk = true, channelBinding = false, clientCert = false, oauthMode = "OAUTH:OAUTHBEARER"))
    }

    @Test
    fun refreshRotationLockAndSignedIn() {
        val settings = oauthSettings()
        val fresh = OauthTokenState(access = "a", refresh = "r", expiresAtMs = 120_000)
        assertTrue(fresh.signedIn(1_000))
        assertFalse(fresh.needsProactiveRefresh(1_000))
        assertTrue(fresh.copy(expiresAtMs = 1_000).needsProactiveRefresh(1_000))
        val near = OauthTokenState(access = "a", refresh = "r", expiresAtMs = 50_000)
        assertTrue(near.needsProactiveRefresh(0))
        val noExpiry = OauthTokenState(access = "a", refresh = "")
        assertTrue(noExpiry.signedIn(0))
        assertFalse(noExpiry.needsProactiveRefresh(0))
        assertFalse(noExpiry.copy(accessUnusable = true).signedIn(0))
        var posts = 0
        val transport = OauthTransport { request ->
            posts++
            val body = request.body.orEmpty()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=r"))
            assertTrue(body.contains("client_id=client"))
            assertTrue(body.contains("client_secret=sec"))
            OauthHttpResponse(200, """{"access_token":"n","expires_in":30}""")
        }
        val rotated = refreshLocked(fresh, settings, "sec", 0, transport)
        assertEquals("n", rotated.access)
        assertEquals("r", rotated.refresh)
        val replacing = OauthTransport {
            OauthHttpResponse(200, """{"access_token":"n2","refresh_token":"r2","expires_in":30}""")
        }
        assertEquals("r2", refreshLocked(fresh, settings, "sec", 0, replacing).refresh)
        val invalid = OauthTransport { OauthHttpResponse(400, """{"error":"invalid_grant"}""") }
        val dead = onAuthFailure("acct", fresh, settings, "sec", 0, invalid) {}
        assertTrue(dead.accessUnusable && dead.refreshUnusable)
        var saved = fresh.copy(streak = 1)
        val again = onAuthFailure("acct", saved, settings, "sec", 0, OauthTransport {
            throw AssertionError("second refresh")
        }) { saved = it }
        assertTrue(again.accessUnusable && again.refreshUnusable)
        assertEquals(0, onAuthSuccess(fresh.copy(streak = 1, accessUnusable = true)).streak)
        withRefreshLock("same") {
            withRefreshLock("same") { assertTrue(true) }
        }
    }

    @Test
    fun signOutOrderAndFailedRevocation() {
        val steps = ArrayList<String>()
        val trace = signOutOrder(
            OauthTokenState(access = "a", refresh = "r"),
            oauthSettings(),
            "sec",
            OauthTransport { throw OauthFailure("revocation: redirect") },
            dropSession = { steps += "drop" },
            clearToken = { steps += "token" },
            clearPending = { steps += "pending" },
        )
        assertEquals(listOf("drop", "token", "pending"), steps)
        assertEquals(listOf("copy-refresh", "drop-session", "clear", "revoke-failed"), trace.steps)
        assertTrue(trace.stillSignedOut)
        val body = revocationBody(oauthSettings(), "r", "sec")
        assertTrue(body.contains("token_type_hint=refresh_token"))
        assertTrue(body.contains("client_secret=sec"))
    }

    @Test
    fun saveClearsTokenNotSecretAndBackupKeepsV1Empty() {
        val previous = oauthSettings()
        val moved = planAccountSave("id", previous, previous.copy(oauthClientId = "other"), previous.encode(), "slot")
        assertTrue(moved.dropOauthToken && moved.dropOauthPending)
        assertEquals("slot", moved.passwordSlot)
        val host = planAccountSave("id", previous, previous.copy(imapHost = "other.example"), previous.encode(), "slot")
        assertTrue(host.dropOauthToken)
        assertEquals("", host.passwordSlot)
        val withSmtp = previous.copy(smtpHost = "smtp.example")
        val smtp = planAccountSave("id", withSmtp, withSmtp.copy(smtpHost = "other-smtp.example"), withSmtp.encode(), "slot")
        assertFalse(smtp.dropOauthToken)
        assertTrue(smtp.dropSmtpPassword)
        val encoded = encodeBackupRecord(previous, "imap", "smtp", "sieve", "secret", "token")
        assertTrue(encoded.toString(Charsets.UTF_8).startsWith("v=2"))
        val round = decodeBackupRecord(encoded)
        assertEquals("secret", round?.oauthClientSecret)
        assertEquals("token", round?.oauthToken)
        val v1 = """
            v=1
            email=a@b.c
            username=u
            inbound=imap
            host=h
            port=993
            tls=Implicit
            smtpHost=
            smtpPort=587
            imapPassword=p
            smtpPassword=
            sievePassword=
            oauthClientSecret=secret
            oauthToken=token
        """.trimIndent().toByteArray()
        val old = decodeBackupRecord(v1)
        assertNull(old?.oauthClientSecret)
        assertNull(old?.oauthToken)
        assertEquals("p", old?.imapPassword)
    }

    @Test
    fun redirectsAreErrorsAndDiscoveryFallsBackOnlyWithoutADocument() {
        val redirected = OauthTransport { OauthHttpResponse(302, "") }
        try {
            oauthCall(OauthHttpRequest("https://example.com/token", "POST"), redirected, "token exchange")
            throw AssertionError("redirect")
        } catch (error: OauthFailure) {
            assertTrue(error.message!!.contains("token exchange"))
            assertTrue(error.message!!.contains("redirect"))
        }
        assertFalse(oauthUrlAllowed("http://127.0.0.1/token"))
        OauthLoopback.allow("http://127.0.0.1:9")
        assertTrue(oauthUrlAllowed("http://127.0.0.1:9/token"))
        OauthLoopback.clear()
        assertEquals(
            "https://example.com/.well-known/oauth-authorization-server/issuer1",
            rfc8414DiscoveryUrl("https://example.com/issuer1/"),
        )
        var calls = ArrayList<String>()
        val missing = OauthTransport { request ->
            calls += request.url
            OauthHttpResponse(200, """{"issuer":"https://example.com/issuer1"}""")
        }
        try {
            discoverOauth("https://example.com/issuer1", missing)
            throw AssertionError("missing endpoint")
        } catch (error: OauthFailure) {
            assertTrue(error.message!!.startsWith("discovery:"))
        }
        assertEquals(1, calls.size)
        calls = ArrayList()
        val fallback = OauthTransport { request ->
            calls += request.url
            if (request.url.contains("oauth-authorization-server")) {
                OauthHttpResponse(404, "no")
            } else {
                OauthHttpResponse(
                    200,
                    """{"authorization_endpoint":"https://example.com/a","token_endpoint":"https://example.com/t","scopes_supported":["mail"],"revocation_endpoint":"https://example.com/r"}""",
                )
            }
        }
        val found = discoverOauth("https://example.com/issuer1", fallback)
        assertEquals(2, calls.size)
        assertTrue(calls[1].endsWith("/.well-known/openid-configuration"))
        assertEquals("https://example.com/a", found.authorization)
        assertEquals("https://example.com/r", found.revocation)
        calls = ArrayList()
        val array = OauthTransport { request ->
            calls += request.url
            if (calls.size == 1) OauthHttpResponse(200, "[]") else OauthHttpResponse(200, """{"authorization_endpoint":"https://example.com/a","token_endpoint":"https://example.com/t"}""")
        }
        assertEquals("https://example.com/a", discoverOauth("https://example.com/issuer1", array).authorization)
        assertEquals(2, calls.size)
    }

    @Test
    fun redirectStateAndTokenExchange() {
        val settings = oauthSettings()
        var pending: OauthPending? = OauthPending("verifier", "state-1")
        var token: OauthTokenState? = OauthTokenState(access = "old", refresh = "old-refresh")
        val transport = OauthTransport { request ->
            val body = request.body.orEmpty()
            assertTrue(body.contains("grant_type=authorization_code"))
            assertTrue(body.contains("code=the-code"))
            assertTrue(body.contains("code_verifier=verifier"))
            assertTrue(body.contains("redirect_uri="))
            assertTrue(body.contains("client_secret=sec"))
            OauthHttpResponse(200, """{"access_token":"new","refresh_token":"rotated","expires_in":9}""")
        }
        val ok = completeRedirect(
            listOf("one"),
            mapOf("state" to "state-1", "code" to "the-code"),
            { pending },
            { token },
            { _, next -> pending = next },
            { _, next -> token = next },
            { settings },
            { "sec" },
            1_000,
            transport,
        )
        assertTrue(ok.wroteToken)
        assertNull(pending)
        assertEquals("new", token?.access)
        assertEquals("rotated", token?.refresh)
        pending = OauthPending("verifier", "state-1")
        val before = token
        val mismatch = completeRedirect(
            listOf("one"),
            mapOf("state" to "other", "code" to "the-code"),
            { pending },
            { token },
            { _, next -> pending = next },
            { _, next -> token = next },
            { settings },
            { "sec" },
            1_000,
            transport,
        )
        assertFalse(mismatch.wroteToken)
        assertNull(pending)
        assertEquals(before, token)
        assertEquals("openWebPage", browserOpenKind(null))
        assertEquals("customTabs", browserOpenKind("com.android.chrome"))
    }

    @Test
    fun checkServerSieveExportAndBearer() {
        val settings = oauthSettings().copy(tlsMode = org.dlang.liveimap.settings.TlsMode.Implicit)
        val probe = ServerProbe(post = "IMAP4rev1 AUTH=OAUTHBEARER AUTH=XOAUTH2")
        val signedOut = checkServer(settings, probe, oauthSignedIn = false)
        assertTrue(signedOut.notes.any { it == "Not signed in" })
        assertTrue(signedOut.notes.any { it.contains("AUTH=OAUTHBEARER") })
        assertTrue(signedOut.conflicts.isEmpty())
        val neither = checkServer(settings, ServerProbe(post = "IMAP4rev1 AUTH=PLAIN"), oauthSignedIn = true)
        assertTrue(neither.conflicts.any { it.contains("OAUTHBEARER") })
        val passwordMode = checkServer(settings.copy(accountAuth = "password"), probe)
        assertFalse(passwordMode.notes.any { it == "Not signed in" })
        val target = sieveTarget(settings.copy(sieveSameAsInbound = true), "inbound", "sieve-row")
        assertEquals("sieve-row", target.password)
        assertEquals(settings.imapHost, target.host)
        try {
            sieveTarget(settings.copy(sieveSameAsInbound = true), "inbound", null)
            throw AssertionError("password")
        } catch (error: SieveTargetException) {
            assertEquals("password", error.message)
        }
        assertEquals("inbound", sieveTarget(settings.copy(accountAuth = "password", sieveSameAsInbound = true), "inbound", "row").password)
        val encoded = settings.encode()
        assertFalse(encoded.contains("\"oauthToken\""))
        assertTrue(encoded.contains("\"oauthTokenEndpoint\""))
        assertTrue("oauthClientSecret" in documentSecretKeys)
        val exported = exportSettingsDocument(encoded, true, "i", "s", "v", "secret", "blob")
        assertTrue(exported.contains("\"oauthToken\""))
        assertFalse(exportSettingsDocument(encoded, false, "i", "s", "v", "secret", "blob").contains("\"oauthToken\""))
        val off = settings.copy(smtpSameAsImap = false)
        val (secret, bearer) = jmapPresented(off.accountAuth, "imap-password", "access")
        assertTrue(bearer)
        assertEquals("access", secret)
        assertEquals("Bearer access", jmapAuthorization("user", secret, bearer))
        try {
            jmapPresented("password", null, null)
            throw AssertionError("password missing")
        } catch (error: MailFailure) {
            assertEquals("password missing", error.text)
        }
        assertTrue(jmapAuthorization("user", "pw", false).startsWith("Basic "))
        val seen = ArrayList<String>()
        val session = JmapSession(
            "user",
            "https://example.com/jmap",
            "https://example.com/download",
            "https://example.com/upload",
            "https://example.com/events",
            "state",
            setOf(JMAP_MAIL),
            "A1",
        )
        val body = """{"methodResponses":[["Mailbox/query",{"ids":["mb1"]},"0"],["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":1,"unreadEmails":0}]},"1"]]}"""
        jmapMailboxLevel(session, null, "user", "access", "", { _, _, auth ->
            seen += auth
            object : JmapHttpExchange {
                override val status = 200
                override fun peerDer() = emptyList<ByteArray>()
                override fun header(name: String) = null
                override fun body() = body
                override fun close() {}
            }
        }, trust = { _, _, _ -> "" }, bearer = true)
        assertEquals(listOf("Bearer access"), seen)
        val uploaded = ArrayList<String>()
        jmapUpload(session, "rfc822".toByteArray(), "user", "access", "", { _, _, _, auth ->
            uploaded += auth
            blobExchange("""{"blobId":"b1"}""")
        }, trust = { _, _, _ -> "" }, policy = TlsPolicy(), bearer = true)
        assertEquals(listOf("Bearer access"), uploaded)
        val basic = ArrayList<String>()
        jmapUpload(session, "rfc822".toByteArray(), "user", "pw", "", { _, _, _, auth ->
            basic += auth
            blobExchange("""{"blobId":"b1"}""")
        }, trust = { _, _, _ -> "" }, policy = TlsPolicy(), bearer = false)
        assertTrue(basic.single().startsWith("Basic "))
        val submission = session.copy(capabilityIds = setOf(JMAP_MAIL, JMAP_SUBMISSION))
        val delivered = ArrayList<String>()
        try {
            jmapDeliver(
                submission,
                "From: a@b.c\r\n\r\nhi".toByteArray(),
                "mb",
                "a@b.c",
                listOf("c@d.e"),
                "user",
                "access",
                "",
                { _, _, _, auth ->
                    delivered += "upload:$auth"
                    blobExchange("""{"blobId":"b1"}""")
                },
                { _, _, auth ->
                    delivered += "post:$auth"
                    blobExchange("""{"methodResponses":[]}""")
                },
                trust = { _, _, _ -> "" },
                bearer = true,
            )
        } catch (_: Exception) {
        }
        assertTrue(delivered.contains("upload:Bearer access"))
        assertTrue(delivered.contains("post:Bearer access"))
        val tree = listOf(File("src/main/assets/settings-tree.json"), File("app/src/main/assets/settings-tree.json")).first { it.isFile }.readText()
        assertTrue(tree.contains("\"oauth2\": \"OAuth 2 (bring your own client ID)\""))
        assertTrue(tree.contains("\"title\": \"Same as IMAP login\""))
        assertTrue(tree.contains("com.davidlang.liveimap:/oauth2redirect"))
        val auth = tree.substringAfter("\"setting\": \"authMechanism\"")
        assertTrue(auth.contains("\"equals\": \"password\""))
        assertFalse(tree.contains("\"pinerc\": \"oauth"))
    }

    @Test
    fun codeBodyOmitsEmptyScopeAndKeepsRedirect() {
        val body = authorizationCodeBody(oauthSettings().copy(oauthScopes = " "), "c", "v", null)
        assertFalse(body.contains("scope="))
        assertFalse(body.contains("client_secret"))
        assertTrue(body.contains("redirect_uri=com.davidlang.liveimap%3A%2Foauth2redirect"))
        val confidential = authorizationCodeBody(oauthSettings(), "c", "v", "sec")
        assertTrue(confidential.contains("client_secret=sec"))
        assertTrue(confidential.contains("scope=mail"))
    }

    private fun blobExchange(text: String) = object : JmapHttpExchange {
        override val status = 200
        override fun peerDer() = emptyList<ByteArray>()
        override fun header(name: String) = null
        override fun body() = text
        override fun close() {}
    }
}
