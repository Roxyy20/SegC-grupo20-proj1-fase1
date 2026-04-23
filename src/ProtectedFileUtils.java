import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Utilitários para abrir ficheiros protegidos em claro, executar uma operação e
 * voltar a cifrar com verificação de integridade.
 */
public final class ProtectedFileUtils {
  /**
   * Metadados do estado de preparação de um ficheiro protegido.
   */
  private static final class PreparedFile {
    private final boolean integrityVerified;
    private final boolean shouldEncryptOnExit;

    /**
     * Constrói o resultado da preparação de ficheiro.
     *
     * @param integrityVerified indica se a versão em claro passou a integridade
     * @param shouldEncryptOnExit indica se deve voltar a cifrar ao sair
     */
    private PreparedFile(boolean integrityVerified, boolean shouldEncryptOnExit) {
      this.integrityVerified = integrityVerified;
      this.shouldEncryptOnExit = shouldEncryptOnExit;
    }
  }

  /**
   * Construtor privado de classe utilitária.
   */
  private ProtectedFileUtils() {
  }

  /**
   * Executa uma operação sobre um ficheiro protegido, garantindo integridade e
   * recifra no final quando aplicável.
   *
   * @param <T> tipo de retorno da operação
   * @param filePath caminho do ficheiro protegido
   * @param pbeKey chave simétrica usada na proteção do ficheiro
   * @param operation operação a executar com o ficheiro em claro
   * @return valor devolvido pela operação
   * @throws Exception se ocorrer erro de preparação, operação ou cifragem final
   */
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

  /**
   * Prepara o ficheiro para acesso em claro, tentando descifrar e validando
   * integridade.
   *
   * @param filePath caminho do ficheiro protegido
   * @param pbeKey chave de cifragem/decifragem
   * @return estado de preparação e política de recifra
   * @throws Exception se ocorrer erro de I/O
   */
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

  /**
   * Cifra o conteúdo completo de um ficheiro com a chave indicada.
   *
   * @param filePath caminho do ficheiro
   * @param pbeKey chave de cifragem
   * @throws Exception se ocorrer erro de I/O ou cifragem
   */
  public static void encryptFile(String filePath, SecretKey pbeKey) throws Exception {
    Path path = Path.of(filePath);
    byte[] plaintext = Files.readAllBytes(path);
    Files.write(path, encrypt(plaintext, pbeKey));
  }

  /**
   * Cifra um bloco em memória com AES/CBC/PKCS5Padding e prefixa o IV.
   *
   * @param plaintext dados em claro
   * @param pbeKey chave de cifragem
   * @return bytes cifrados no formato {@code IV || ciphertext}
   * @throws Exception se a cifra falhar
   */
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

  /**
   * Decifra um bloco no formato {@code IV || ciphertext} com AES/CBC.
   *
   * @param data bytes cifrados incluindo IV inicial
   * @param pbeKey chave de decifragem
   * @return bytes em claro
   * @throws Exception se a decifragem falhar
   */
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
