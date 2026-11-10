package com.privacyguard.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Lidhja ADB e app-it me vete telefonin (Wireless debugging), si LADB/Shizuku.
 * Celesi krijohet nje here dhe ruhet ne dosjen private te app-it; telefoni e
 * mban mend pas ciftimit, keshtu qe lidhjet e mevonshme s'kerkojne kod.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(ctx.filesDir, "adb_key.pk8")
    private val certFile = File(ctx.filesDir, "adb_cert.der")
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=PrivacyGuard")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val x509 = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(x509.encoded)
            key = kp.private
            cert = x509
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "PrivacyGuard"

    companion object {
        @Volatile private var inst: AdbManager? = null
        fun get(ctx: Context): AdbManager = inst ?: synchronized(this) {
            inst ?: AdbManager(ctx.applicationContext).also { inst = it }
        }
    }
}
