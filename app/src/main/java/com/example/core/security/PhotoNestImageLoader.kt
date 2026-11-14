package com.example.core.security

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.example.core.data.PairedServerEntity
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Provides a Coil ImageLoader capable of loading:
 * 1. Local files and content:// URIs
 * 2. Video frame thumbnails via VideoFrameDecoder
 * 3. Authenticated, certificate-pinned HTTPS thumbnails/originals from paired PhotoNest servers
 * 4. Standard public HTTPS URLs (e.g., Google Sign-In profile avatars) without pin mismatch or header leakage.
 */
object PhotoNestImageLoader {

    private data class ServerCreds(
        val token: String,
        val ownerUid: String,
        val fingerprint: String
    )

    private val serverCredsByHostPort = ConcurrentHashMap<String, ServerCreds>()
    private val pinnedClientCache = ConcurrentHashMap<String, OkHttpClient>()

    @Volatile
    private var primaryCreds: ServerCreds? = null

    fun updateServers(servers: List<PairedServerEntity>) {
        serverCredsByHostPort.clear()
        servers.forEach { server ->
            val fp = server.certFingerprint.replace(":", "").lowercase(Locale.US)
            if (server.hostAddress.isNotBlank() && server.port > 0) {
                serverCredsByHostPort["${server.hostAddress}:${server.port}"] = ServerCreds(
                    token = server.authToken,
                    ownerUid = server.ownerUid,
                    fingerprint = fp
                )
            }
        }
        primaryCreds = servers.firstOrNull()?.let { first ->
            ServerCreds(
                token = first.authToken,
                ownerUid = first.ownerUid,
                fingerprint = first.certFingerprint.replace(":", "").lowercase(Locale.US)
            )
        }
    }

    fun updateServerCredentials(
        token: String,
        ownerUid: String,
        certFingerprint: String,
        hostAddress: String = "",
        port: Int = 0
    ) {
        val creds = ServerCreds(
            token = token,
            ownerUid = ownerUid,
            fingerprint = certFingerprint.replace(":", "").lowercase(Locale.US)
        )
        primaryCreds = if (token.isNotBlank() || creds.fingerprint.isNotBlank()) creds else null
        if (hostAddress.isNotBlank() && port > 0) {
            serverCredsByHostPort["$hostAddress:$port"] = creds
        }
    }

    private fun getOrCreatePinnedClient(fingerprint: String): OkHttpClient {
        return pinnedClientCache.getOrPut(fingerprint) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull()
                        ?: throw java.security.cert.CertificateException("Empty server certificate chain")
                    if (fingerprint.isNotBlank()) {
                        val actual = TlsCertManager.sha256Hex(leaf.encoded)
                        if (actual != fingerprint) {
                            throw java.security.cert.CertificateException(
                                "Server certificate pin mismatch for PhotoNest server"
                            )
                        }
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    fun build(context: Context): ImageLoader {
        val standardOkHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val dispatchingCallFactory = Call.Factory { request: Request ->
            val hostPort = "${request.url.host}:${request.url.port}"
            val matchedCreds = serverCredsByHostPort[hostPort]
                ?: if (request.url.encodedPath.startsWith("/api/v1/")) primaryCreds else null

            if (matchedCreds != null) {
                val authedReq = if (matchedCreds.token.isNotBlank()) {
                    request.newBuilder()
                        .header("Authorization", "Bearer ${matchedCreds.token}")
                        .header("X-Owner-Uid", matchedCreds.ownerUid)
                        .build()
                } else {
                    request
                }
                getOrCreatePinnedClient(matchedCreds.fingerprint).newCall(authedReq)
            } else {
                // External HTTPS (e.g., Google Sign-In avatars): standard TLS trust, zero credential leakage
                standardOkHttp.newCall(request)
            }
        }

        return ImageLoader.Builder(context.applicationContext)
            .callFactory(dispatchingCallFactory)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
