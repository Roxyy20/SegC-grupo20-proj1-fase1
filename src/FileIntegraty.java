import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

public class FileIntegraty {
  // Calcula e guarda o hash SHA-256 do ficheiro.
  public static void updateFileHash(String filePath) throws Exception {
    Path path = Path.of(filePath);
    byte[] data = Files.readAllBytes(path);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(data);
    String hashString = Base64.getEncoder().encodeToString(hash);
    Files.writeString(Path.of(filePath + ".hash"), hashString);
  }

  // Verifica se o hash do ficheiro bate certo.
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

  // Garante existência do ficheiro e do respetivo .hash (bootstrap/migração).
  public static void ensureFileAndHash(String filePath) throws Exception {
    Path path = Path.of(filePath);
    Path parent = path.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
    }
    if (!Files.exists(path)) {
      Files.createFile(path);
    }

    Path hashPath = Path.of(filePath + ".hash");
    if (!Files.exists(hashPath)) {
      updateFileHash(filePath);
    }
  }

  // Verifica integridade; lança exceção em caso de falha.
  public static void assertIntegrity(String filePath) throws Exception {
    if (!checkFileIntegrity(filePath)) {
      throw new IllegalStateException("Integrity check failed: " + filePath);
    }
  }

  // Garante ficheiro e hash; em falha termina com NOK-INTEGRITY.
  public static void ensureFileAndHashOrExit(String filePath) {
    try {
      ensureFileAndHash(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  // Verifica integridade; em falha termina com NOK-INTEGRITY.
  public static void verifyIntegrityOrExit(String filePath) {
    try {
      assertIntegrity(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  // Atualiza hash após escrita; em falha termina com NOK-INTEGRITY.
  public static void updateHashOrExit(String filePath) {
    try {
      updateFileHash(filePath);
    } catch (Exception e) {
      failIntegrityAndExit();
    }
  }

  public static void failIntegrityAndExit() {
    System.err.println("NOK-INTEGRITY");
    System.exit(1);
  }
}
