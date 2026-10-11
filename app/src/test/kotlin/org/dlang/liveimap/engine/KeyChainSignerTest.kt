package org.dlang.liveimap.engine

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Java half of keystore-backed TLS client-cert signing, with software keys (same JCA calls). */
class KeyChainSignerTest {
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val digest = MessageDigest.getInstance("SHA-256").digest("tls transcript".toByteArray())

    @Test
    fun rsaPkcs1OverDigestInfoMatchesSha256WithRsa() {
        // OpenSSL passes the DER DigestInfo for SHA-256 followed by the digest (RFC 8017 9.2 note 1).
        val prefix = BigInteger("3031300d060960864801650304020105000420", 16).toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
        val sig = KeyChainSigner.sign(rsa.private, KeyChainSigner.RSA_PKCS1, prefix + digest)!!
        val verify = Signature.getInstance("SHA256withRSA").apply { initVerify(rsa.public); update("tls transcript".toByteArray()) }
        assertTrue(verify.verify(sig))
    }

    @Test
    fun rsaRawIsThePrivateOperationAtFullLength() {
        // RSA-PSS: OpenSSL pads, Java does m^d mod n; check s^e mod n == m and the length is the modulus size.
        val pub = rsa.public as RSAPublicKey
        val block = ByteArray(256).also { it[0] = 0x00; it[1] = 0x01; for (i in 2 until 256) it[i] = (i * 7).toByte() }
        val out = KeyChainSigner.sign(rsa.private, KeyChainSigner.RSA_RAW, block)!!
        assertEquals(256, out.size)
        val back = BigInteger(1, out).modPow(pub.publicExponent, pub.modulus)
        assertEquals(BigInteger(1, block), back)
    }

    @Test
    fun rawResultIsLeftPaddedWhenShort() {
        // A tiny input gives a tiny m^d only by chance; instead check padding on a crafted small result via m = 1.
        val one = ByteArray(256).also { it[255] = 1 }
        val out = KeyChainSigner.sign(rsa.private, KeyChainSigner.RSA_RAW, one)!!
        assertEquals(256, out.size)
        assertArrayEquals(one, out)  // 1^d mod n = 1
    }

    @Test
    fun ecdsaOverDigestVerifies() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val sig = KeyChainSigner.sign(ec.private, KeyChainSigner.ECDSA, digest)!!
        val verify = Signature.getInstance("NONEwithECDSA").apply { initVerify(ec.public); update(digest) }
        assertTrue(verify.verify(sig))
    }

    @Test
    fun unknownOpIsNull() {
        assertNull(KeyChainSigner.sign(rsa.private, 9, digest))
    }
}
