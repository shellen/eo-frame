package com.aphex3k.eo1;

import android.util.Log;

import okhttp3.OkHttpClient;

/**
 * Modern TLS for clients that talk to today's servers from Android 4.4: the bundled Conscrypt
 * provider (AES-GCM, current SNI) with the app's merged trust store (current roots, including the
 * ones KitKat predates, such as Let's Encrypt's). Used by the Spotify client and by the kiosk
 * browser's fallback for pages the platform's TLS stack cannot open.
 */
final class ModernTls {
    private static final String TAG = "EO1";

    private ModernTls() {
    }

    /**
     * Puts Conscrypt and the merged trust store on {@code b}. Capped at TLS 1.2: under TLS 1.3
     * Conscrypt reports the key exchange as "GENERIC", which the merged trust manager rejects.
     *
     * @return false when Conscrypt could not load; {@code b} is then unchanged
     */
    static boolean apply(OkHttpClient.Builder b) {
        try {
            java.security.Provider provider = org.conscrypt.Conscrypt.newProvider();
            javax.net.ssl.X509TrustManager tm = com.aphex3k.eo1.trust.MergedTrust.create(true).trustManager;
            if (tm == null) {
                tm = org.conscrypt.Conscrypt.getDefaultX509TrustManager();
            }
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS", provider);
            sc.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
            b.sslSocketFactory(new MusicService.HostnameSocketFactory(sc.getSocketFactory()), tm);
            b.connectionSpecs(java.util.Arrays.asList(
                    new okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                            .tlsVersions(okhttp3.TlsVersion.TLS_1_2).build(),
                    okhttp3.ConnectionSpec.CLEARTEXT));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Conscrypt unavailable: " + t);
            return false;
        }
    }
}
