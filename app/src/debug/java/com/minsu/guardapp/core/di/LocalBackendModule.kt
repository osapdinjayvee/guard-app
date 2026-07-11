package com.minsu.guardapp.core.di

import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.core.network.OkHttpCustomizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Dns
import java.net.InetAddress
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/**
 * Makes the WSL-hosted backend at `https://guard.test` reachable from a debug build.
 *
 * Debug source set only, so none of this can reach a release APK: a shipped build gets stock DNS,
 * the stock trust store, and stock hostname verification.
 *
 * Two things stand between the app and that backend, and neither is the app's fault:
 *
 * 1. **DNS.** `guard.test` is an entry in the *Windows* hosts file pointing at 127.0.0.1, where
 *    WSL's port forwarding picks it up. A device cannot see that file, and its own 127.0.0.1 is
 *    itself. So the name is resolved here instead, to [BuildConfig.BACKEND_HOST] — the host machine
 *    as seen from the device. The hostname still travels in the request, which is what nginx needs
 *    to select the vhost; putting an IP in the URL would land on the default site instead.
 *
 * 2. **Hostname verification.** The certificate for guard.test carries a Common Name but no
 *    Subject Alternative Name, and Android dropped the CN fallback long ago, so the stock verifier
 *    rejects it however thoroughly it is trusted. The verifier below accepts precisely one name.
 *    This is not a blanket "trust everything": the certificate still has to chain to the one pinned
 *    in `network_security_config.xml`, so only the holder of that key can be talked to.
 *
 * Re-issuing the certificate with a SAN would make point 2 unnecessary — the trust anchor in the
 * network security config would then be enough on its own.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalBackendModule {

    @Provides
    @Singleton
    @IntoSet
    fun localBackend(): OkHttpCustomizer = OkHttpCustomizer { builder ->
        val backendHost = BuildConfig.API_BASE_URL.toHostOrNull() ?: return@OkHttpCustomizer

        // Only ever bend the rules for a local development host. `.test` is reserved by RFC 6761
        // and can never be a real domain, which makes it a safe thing to key on: point
        // API_BASE_URL at a genuine server and every override below switches itself off, so a debug
        // build cannot silently redirect production traffic to a loopback tunnel or skip hostname
        // verification against a real certificate.
        if (!backendHost.endsWith(".test", ignoreCase = true)) return@OkHttpCustomizer

        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(backendHost, ignoreCase = true)) {
                    listOf(InetAddress.getByName(BuildConfig.BACKEND_HOST))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        })

        val stock = builder.build().hostnameVerifier
        builder.hostnameVerifier(
            HostnameVerifier { hostname, session: SSLSession ->
                hostname.equals(backendHost, ignoreCase = true) || stock.verify(hostname, session)
            },
        )
    }

    private fun String.toHostOrNull(): String? =
        runCatching { java.net.URI(this).host }.getOrNull()
}
