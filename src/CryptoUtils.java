import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES";
    private static final int AES_KEY_SIZE = 128;

    private static final String RSA_TRANSFORMATION = "RSA";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private CryptoUtils() {
        // utility class
    }

    // =========================================================
    // AES
    // =========================================================

    /**
     * Gera uma chave AES de 128 bits.
     */
    public static SecretKey generateAESKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES_ALGORITHM);
        keyGen.init(AES_KEY_SIZE, new SecureRandom());
        return keyGen.generateKey();
    }

    /**
     * Cifra dados com AES.
     */
    public static byte[] encryptAES(byte[] data, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    /**
     * Decifra dados com AES.
     */
    public static byte[] decryptAES(byte[] encryptedData, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(encryptedData);
    }

    /**
     * Reconstrói uma SecretKey AES a partir dos bytes da chave.
     */
    public static SecretKey bytesToAESKey(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    /**
     * Obtém os bytes de uma chave AES.
     */
    public static byte[] aesKeyToBytes(SecretKey key) {
        return key.getEncoded();
    }

    // =========================================================
    // RSA
    // =========================================================

    /**
     * Cifra dados com RSA usando a chave pública.
     */
    public static byte[] encryptRSA(byte[] data, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(data);
    }

    /**
     * Decifra dados com RSA usando a chave privada.
     */
    public static byte[] decryptRSA(byte[] encryptedData, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encryptedData);
    }

    // =========================================================
    // CERTIFICADOS
    // =========================================================

    /**
     * Lê um certificado X.509 a partir de um ficheiro .cert.
     */
    public static X509Certificate readCertificate(Path certPath) throws Exception {
        try (InputStream in = Files.newInputStream(certPath)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    /**
     * Extrai a chave pública de um ficheiro .cert.
     */
    public static PublicKey getPublicKeyFromCertificate(Path certPath) throws Exception {
        X509Certificate cert = readCertificate(certPath);
        return cert.getPublicKey();
    }

    /**
     * Lê o certificado guardado numa keystore, por alias.
     */
    public static Certificate getCertificateFromKeystore(
            String keystorePath,
            String keystorePassword,
            String alias) throws Exception {

        KeyStore keystore = loadKeystore(keystorePath, keystorePassword);
        return keystore.getCertificate(alias);
    }

    /**
     * Extrai a chave pública do certificado guardado na keystore.
     */
    public static PublicKey getPublicKeyFromKeystore(
            String keystorePath,
            String keystorePassword,
            String alias) throws Exception {

        Certificate cert = getCertificateFromKeystore(keystorePath, keystorePassword, alias);
        if (cert == null) {
            throw new IllegalArgumentException("Certificate not found for alias: " + alias);
        }
        return cert.getPublicKey();
    }

    // =========================================================
    // KEYSTORE / PRIVATE KEY
    // =========================================================

    /**
     * Carrega uma keystore PKCS12 do disco.
     */
    public static KeyStore loadKeystore(String keystorePath, String keystorePassword) throws Exception {
        KeyStore keystore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keystore.load(fis, keystorePassword.toCharArray());
        }
        return keystore;
    }

    /**
     * Obtém a chave privada de uma keystore.
     *
     * Normalmente:
     * - keystorePassword = password da keystore
     * - keyPassword = password da chave
     *
     * Se no vosso caso forem iguais, passam a mesma string nas duas.
     */
    public static PrivateKey getPrivateKeyFromKeystore(
            String keystorePath,
            String keystorePassword,
            String alias,
            String keyPassword) throws Exception {

        KeyStore keystore = loadKeystore(keystorePath, keystorePassword);
        KeyStore.ProtectionParameter protection =
                new KeyStore.PasswordProtection(keyPassword.toCharArray());

        KeyStore.PrivateKeyEntry pkEntry =
                (KeyStore.PrivateKeyEntry) keystore.getEntry(alias, protection);

        if (pkEntry == null) {
            throw new IllegalArgumentException("Private key not found for alias: " + alias);
        }

        return pkEntry.getPrivateKey();
    }

    // =========================================================
    // BASE64
    // =========================================================

    public static String toBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] fromBase64(String value) {
        return Base64.getDecoder().decode(value);
    }

    // =========================================================
    // STRINGS / BYTES
    // =========================================================

    public static byte[] stringToBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String bytesToString(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}