import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.stream.Stream;

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

  // Verifica integridade de todos os ficheiros (não .hash) dentro de um diretório.
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
