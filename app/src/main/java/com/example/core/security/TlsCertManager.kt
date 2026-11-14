package com.example.core.security

import android.content.Context
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages PhotoNest's local X.509 TLS identity and certificate-pinned OkHttp clients.
 * Every server generates its own RSA-2048 X.509 certificate and shares its SHA-256 fingerprint
 * during authenticated pairing so all LAN/VPN traffic is encrypted and MITM-resistant.
 */
class TlsCertManager(private val context: Context) {

    data class ServerTlsMaterial(
        val sslContext: SSLContext,
        val certificate: X509Certificate,
        val fingerprintSha256: String
    )

    private val certFile = File(context.filesDir, "photonest_tls_cert.der")
    private val keyFile = File(context.filesDir, "photonest_tls_key.pkcs8")

    @Synchronized
    fun getOrCreateServerTlsMaterial(): ServerTlsMaterial {
        val (cert, privateKey) = if (certFile.exists() && keyFile.exists()) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                val loadedCert = certFile.inputStream().use { cf.generateCertificate(it) as X509Certificate }
                val keyBytes = keyFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                val loadedKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                loadedCert to loadedKey
            } catch (_: Exception) {
                generateAndPersistIdentity()
            }
        } else {
            generateAndPersistIdentity()
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("photonest", privateKey, KEY_PASS, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEY_PASS)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
        val fingerprint = sha256Hex(cert.encoded)
        return ServerTlsMaterial(sslContext, cert, fingerprint)
    }

    fun buildPinnedClient(expectedFingerprintSha256: String): OkHttpClient {
        val normalizedExpected = expectedFingerprintSha256.replace(":", "").lowercase(Locale.US)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("Empty server certificate chain")
                val actual = sha256Hex(leaf.encoded)
                if (normalizedExpected.isNotBlank() && actual != normalizedExpected) {
                    throw java.security.cert.CertificateException(
                        "TLS certificate pin mismatch! Expected $normalizedExpected, got $actual"
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun generateAndPersistIdentity(): Pair<X509Certificate, PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000)

        val tbsBytes = buildTbsCertificate(
            serial = BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE),
            notBefore = notBefore,
            notAfter = notAfter,
            subjectPublicKeyInfo = pair.public.encoded
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(tbsBytes)
        }.sign()

        val certBytes = derSequence(
            tbsBytes,
            sha256WithRsaAlgId(),
            derBitString(signature)
        )
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        certFile.writeBytes(cert.encoded)
        keyFile.writeBytes(pair.private.encoded)
        return cert to pair.private
    }

    private fun buildTbsCertificate(
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        subjectPublicKeyInfo: ByteArray
    ): ByteArray {
        val versionExplicit = derTagged(0, derInteger(BigInteger.valueOf(2)))
        val serialDer = derInteger(serial)
        val algId = sha256WithRsaAlgId()
        val name = derNameCn("PhotoNest-Vault")
        val validity = derSequence(derUtcTime(notBefore), derUtcTime(notAfter))
        return derSequence(
            versionExplicit,
            serialDer,
            algId,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        )
    }

    private fun sha256WithRsaAlgId(): ByteArray {
        // OID 1.2.840.113549.1.1.11 + NULL
        val oid = byteArrayOf(
            0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
        )
        val derNull = byteArrayOf(0x05, 0x00)
        return derSequence(oid, derNull)
    }

    private fun derNameCn(cn: String): ByteArray {
        // OID 2.5.4.3 (commonName)
        val cnOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val utf8 = cn.toByteArray(Charsets.UTF_8)
        val utf8Str = derWrap(0x0C, utf8)
        val attrTypeVal = derSequence(cnOid, utf8Str)
        val rdnSet = derWrap(0x31, attrTypeVal)
        return derSequence(rdnSet)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derWrap(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun derInteger(value: BigInteger): ByteArray = derWrap(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        val payload = ByteArray(bytes.size + 1)
        payload[0] = 0x00
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        return derWrap(0x03, payload)
    }

    private fun derTagged(tagNum: Int, inner: ByteArray): ByteArray =
        derWrap(0xA0 or tagNum, inner)

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        elements.forEach { out.write(it) }
        return derWrap(0x30, out.toByteArray())
    }

    private fun derWrap(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len < 256 -> {
                out.write(0x81)
                out.write(len)
            }
            else -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    companion object {
        private val KEY_PASS = "photonest".toCharArray()

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
