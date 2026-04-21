import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public final class ProtectedFileUtils {
  private static final class PreparedFile {
    private final boolean integrityVerified;
    private final boolean shouldEncryptOnExit;

    private PreparedFile(boolean integrityVerified, boolean shouldEncryptOnExit) {
      this.integrityVerified = integrityVerified;
      this.shouldEncryptOnExit = shouldEncryptOnExit;
    }
  }

  private ProtectedFileUtils() {
  }

  public static <T> T withDecryptedFile(String filePath, SecretKey pbeKey, Callable<T> operation) throws Exception {
    PreparedFile preparedFile = prepareFileForPlaintextAccess(filePath, pbeKey);
    if (!preparedFile.integrityVerified) {
      FileIntegraty.failIntegrityAndExit();
    }
    try {
      T result = operation.call();
      FileIntegraty.verifyIntegrityOrExit(filePath);
      return result;
    } finally {
      if (preparedFile.shouldEncryptOnExit) {
        encryptFile(filePath, pbeKey);
      }
    }
  }

  private static PreparedFile prepareFileForPlaintextAccess(String filePath, SecretKey pbeKey) throws Exception {
    Path path = Path.of(filePath);
    byte[] fileData = Files.readAllBytes(path);
    if (fileData.length == 0) {
      boolean plaintextIntegrityOk = FileIntegraty.checkFileIntegrity(filePath);
      return new PreparedFile(plaintextIntegrityOk, plaintextIntegrityOk);
    }

    try {
      Files.write(path, decrypt(fileData, pbeKey));
      return new PreparedFile(FileIntegraty.checkFileIntegrity(filePath), true);
    } catch (Exception e) {
      boolean plaintextIntegrityOk = FileIntegraty.checkFileIntegrity(filePath);
      return new PreparedFile(plaintextIntegrityOk, plaintextIntegrityOk);
    }
  }

  public static void encryptFile(String filePath, SecretKey pbeKey) throws Exception {
    Path path = Path.of(filePath);
    byte[] plaintext = Files.readAllBytes(path);
    Files.write(path, encrypt(plaintext, pbeKey));
  }

  public static byte[] encrypt(byte[] plaintext, SecretKey pbeKey) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, pbeKey);

    byte[] iv = cipher.getIV();
    byte[] ciphertext = cipher.doFinal(plaintext);
    byte[] result = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, result, 0, iv.length);
    System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
    return result;
  }

  public static byte[] decrypt(byte[] data, SecretKey pbeKey) throws Exception {
    if (data.length < 16) {
      throw new IllegalArgumentException("Encrypted file is too short");
    }

    byte[] iv = Arrays.copyOfRange(data, 0, 16);
    byte[] ciphertext = Arrays.copyOfRange(data, 16, data.length);

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, pbeKey, new IvParameterSpec(iv));
    return cipher.doFinal(ciphertext);
  }
}
