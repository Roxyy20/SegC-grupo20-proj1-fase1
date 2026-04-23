import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.stream.Stream;

/**
 * Utilitários de integridade de ficheiros baseados em SHA-256 com ficheiros
 * auxiliares {@code .hash}.
 */
public class FileIntegraty {
  /**
   * Calcula e guarda o hash SHA-256 de um ficheiro em {@code <ficheiro>.hash}.
   *
   * @param filePath caminho do ficheiro alvo
   * @throws Exception se ocorrer erro de leitura, hashing ou escrita
   */
  public static void updateFileHash(String filePath) throws Exception {
    Path path = Path.of(filePath);
    byte[] data = Files.readAllBytes(path);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(data);
    String hashString = Base64.getEncoder().encodeToString(hash);
    Files.writeString(Path.of(filePath + ".hash"), hashString);
  }

  /**
   * Verifica a integridade de um ficheiro comparando-o com o seu {@code .hash}.
   *
   * @param filePath caminho do ficheiro alvo
   * @return {@code true} se a integridade for válida, {@code false} caso
   *         contrário
   */
  public static boolean checkFileIntegrity(String filePath) {
    try {
      Path path = Path.of(filePath);
      Path hashPath = Path.of(filePath + ".hash");
      if (!Files.exists(path) || !Files.exists(hashPath)) {
        return false;
      }
      byte[] data = Files.readAllBytes(path);
      String storedHash = Files.readString(hashPath).trim();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      String hashString = Base64.getEncoder().encodeToString(hash);
      return hashString.equals(storedHash);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Garante que o ficheiro e o respetivo {@code .hash} existem.
   *
   * @param filePath caminho do ficheiro alvo
   * @throws Exception se existir ficheiro sem hash, ou se falhar a criação
   */
  public static void ensureFileAndHash(String filePath) throws Exception {
    Path path = Path.of(filePath);
    Path parent = path.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
    }
    boolean createdNow = false;
    if (!Files.exists(path)) {
      Files.createFile(path);
      createdNow = true;
    }

    Path hashPath = Path.of(filePath + ".hash");
    if (!Files.exists(hashPath)) {
      if (createdNow) {
        updateFileHash(filePath);
      } else {
        throw new IllegalStateException("Missing .hash for existing file: " + filePath);
      }
    }
  }

  /**
   * Valida a integridade e lança exceção em caso de falha.
   *
   * @param filePath caminho do ficheiro alvo
   * @throws Exception se a integridade falhar
   */
  public static void assertIntegrity(String filePath) throws Exception {
    if (!checkFileIntegrity(filePath)) {
      throw new IllegalStateException("Integrity check failed: " + filePath);
    }
  }

  /**
   * Garante existência de ficheiro e hash; termina o processo em caso de falha.
   *
   * @param filePath caminho do ficheiro alvo
   */
  public static void ensureFileAndHashOrExit(String filePath) {
    try {
      ensureFileAndHash(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  /**
   * Verifica integridade; termina o processo em caso de falha.
   *
   * @param filePath caminho do ficheiro alvo
   */
  public static void verifyIntegrityOrExit(String filePath) {
    try {
      assertIntegrity(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  /**
   * Atualiza o hash do ficheiro; termina o processo em caso de falha.
   *
   * @param filePath caminho do ficheiro alvo
   */
  public static void updateHashOrExit(String filePath) {
    try {
      updateFileHash(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  /**
   * Escreve {@code NOK-INTEGRITY} e termina imediatamente o processo.
   */
  public static void failIntegrityAndExit() {
    System.err.println("NOK-INTEGRITY");
    System.exit(1);
  }

  /**
   * Verifica integridade de todos os ficheiros regulares de um diretório,
   * excluindo ficheiros {@code .hash}, {@code server/sectionKeys} e
   * {@code server/certs}.
   *
   * @param directoryPath caminho da diretoria a verificar
   */
  public static void verifyDirectoryIntegrityOrExit(String directoryPath) {
    Path dir = Path.of(directoryPath);
    if (!Files.exists(dir)) {
      return;
    }
    Path sectionKeysDir = dir.resolve("sectionKeys").normalize();
    Path certsDir = dir.resolve("certs").normalize();

    try (Stream<Path> stream = Files.walk(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> !path.toString().endsWith(".hash"))
          .filter(path -> !path.normalize().startsWith(sectionKeysDir))
          .filter(path -> !path.normalize().startsWith(certsDir))
          .forEach(path -> verifyIntegrityOrExit(path.toString()));
    } catch (IOException e) {
      failIntegrityAndExit();
    }
  }
}
