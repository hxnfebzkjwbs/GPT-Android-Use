// SPDX-License-Identifier: Apache-2.0
// Adapted from MuntashirAkon/libadb-android test app (Apache-2.0 OR GPL-3.0-or-later).
package com.hxnfebzkjwbs.gptandroiduse;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

public final class AdbConnectionManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager INSTANCE;
    private PrivateKey privateKey;
    private Certificate certificate;

    public static synchronized AbsAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new AdbConnectionManager(context.getApplicationContext());
        }
        return INSTANCE;
    }

    private AdbConnectionManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        privateKey = readPrivateKeyFromFile(context);
        certificate = readCertificateFromFile(context);
        if (privateKey == null || certificate == null) {
            generateAndPersistIdentity(context);
        }
    }

    private void generateAndPersistIdentity(@NonNull Context context) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair pair = generator.generateKeyPair();
        PublicKey publicKey = pair.getPublic();
        privateKey = pair.getPrivate();

        String algorithmName = "SHA512withRSA";
        long now = System.currentTimeMillis();
        long expiry = now + (3650L * 24L * 60L * 60L * 1000L);
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
        X500Name name = new X500Name("CN=GPT Android Use");
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(expiry);
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithmName);
        certificate = cert;

        writePrivateKeyToFile(context, privateKey);
        writeCertificateToFile(context, certificate);
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "GPT-Android-Use"; }

    @Nullable
    private static Certificate readCertificateFromFile(@NonNull Context context)
            throws IOException, CertificateException {
        File file = new File(context.getFilesDir(), "adb-cert.pem");
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void writeCertificateToFile(@NonNull Context context, @NonNull Certificate cert)
            throws CertificateEncodingException, IOException {
        File file = new File(context.getFilesDir(), "adb-cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            encoder.encode(cert.getEncoded(), out);
            out.write('\n');
            out.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKeyFromFile(@NonNull Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File file = new File(context.getFilesDir(), "adb-private.key");
        if (!file.exists()) return null;
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        return factory.generatePrivate(spec);
    }

    private static void writePrivateKeyToFile(@NonNull Context context, @NonNull PrivateKey key)
            throws IOException {
        File file = new File(context.getFilesDir(), "adb-private.key");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(key.getEncoded());
        }
    }
}
