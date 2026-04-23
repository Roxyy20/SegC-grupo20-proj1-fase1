import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.Callable;

import javax.crypto.SecretKey;

/**
 * Thread responsável por tratar uma ligação de cliente: atestação,
 * autenticação e encaminhamento de comandos para {@link CommandHandler}.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
class ServerThread extends Thread {
  private Socket socket = null;
  private SecretKey pbeKey;
  private static final String APP_FILE = "server/apps.txt";
  private static final String USERS_FILE = "server/users.txt";
  private static final String REFERENCE_APP_ID = "SpertaClient";
  private static final String DEFAULT_REFERENCE_APP_PATH = "build/SpertaClient.jar";

  /**
   * Cria a thread associada a um socket já aceite.
   *
   * @param inSoc socket da ligação do cliente
   */
  ServerThread(Socket inSoc, SecretKey pbeKey) {
    socket = inSoc;
    this.pbeKey = pbeKey;
  }

  /**
   * Loop principal da thread: realiza atestação, autenticação e despacha
   * comandos recebidos do cliente até este encerrar a ligação.
   */
  @Override
  public void run() {
    try (Socket clientSocket = socket;
        ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
        ObjectInputStream inStream = new ObjectInputStream(clientSocket.getInputStream());) {
      outStream.flush();

      ensureAppFile();
      FileIntegraty.ensureFileAndHashOrExit(USERS_FILE);
      FileIntegraty.verifyIntegrityOrExit(USERS_FILE);
      if (!handleAttestation(outStream, inStream)) {
        return;
      }

      String user = inStream.readUTF();
      String pass = inStream.readUTF();

      if (user.isBlank() || pass.isBlank() || user.contains(":") || pass.contains(":")) {
        outStream.writeUTF(LoginMessage.WrongPassword.name());
        outStream.flush();
        return;
      }

      Path path = Paths.get(USERS_FILE);

      Files.createDirectories(path.getParent());

      if (Files.notExists(path)) {
        Files.createFile(path);
      }

      BufferedReader buffReader = Files.newBufferedReader(path);
      String line;
      Boolean found = false;
      boolean authenticated = false;
      while ((line = buffReader.readLine()) != null) {
        String[] values = line.split(":");
        String username = values[0];
        String storedHash = values[1];
        String storedSalt = values[2];
        if (username.equals(user)) {
          found = true;

          byte[] saltBytes = SecurityUtils.fromBase64(storedSalt);
          String computedHash = SecurityUtils.hashPasswordWithSalt(pass, saltBytes);

          if (storedHash.equals(computedHash)) {
            authenticated = true;
          } else {
            outStream.writeUTF(LoginMessage.WrongPassword.name());
            outStream.flush();
            readNewPassword(outStream, inStream, storedHash, storedSalt);
            authenticated = true;
          }
          break;
        }
      }
      buffReader.close();
      if (found) {
        if (!authenticated) {
          // não deve acontecer, mas evitar enviar estado errado
          outStream.writeUTF(LoginMessage.WrongPassword.name());
        } else {
          outStream.writeUTF(LoginMessage.OKUser.name());
          outStream.flush();
        }
      } else {
        outStream.writeUTF(LoginMessage.OKNewUser.name());

        byte[] salt = SecurityUtils.generateSalt();
        String hashBase64 = SecurityUtils.hashPasswordWithSalt(pass, salt);
        String saltBase64 = SecurityUtils.toBase64(salt);

        File usersFile = new File(USERS_FILE);
        ensureParentDir(usersFile);
        try (FileWriter fileWriter = new FileWriter(usersFile, true)) {
          fileWriter.write(user + ":" + hashBase64 + ":" + saltBase64 + "\n");
        } catch (IOException e) {
          e.printStackTrace();
        }
        FileIntegraty.updateHashOrExit(USERS_FILE);
        outStream.flush();
        receiveAndStoreCertificate(user, outStream, inStream);
      }

      CommandHandler commandHandler = new CommandHandler(user, pbeKey);

      while (true) {
        String cmdStr;
        try {
          cmdStr = inStream.readUTF();
        } catch (IOException e) {
          // client disconnected
          break;
        }

        Command cmd = Command.fromString(cmdStr);
        if (cmd == null) {
          outStream.writeUTF("UNKNOWN COMMAND");
          outStream.flush();
          continue;
        }

        switch (cmd) {
          case CREATE:
            commandHandler.createHome(outStream, inStream);
            break;
          case ADD:
            commandHandler.addPermission(outStream, inStream);
            break;
          case RD:
            commandHandler.registerDevice(outStream, inStream);
            break;
          case EC:
            commandHandler.setStateOrTimer(outStream, inStream);
            break;
          case RT:
            commandHandler.requestHomeLog(outStream, inStream);
            break;
          case RH:
            commandHandler.requestDeviceLog(outStream, inStream);
            break;
          case GETCERT:
            commandHandler.requestUserCertificate(outStream, inStream);
            break;
          case GETKEY:
            commandHandler.requestOwnerSectionKey(outStream, inStream);
            break;
          case STOREKEY:
            commandHandler.storeSectionKeyForUser(outStream, inStream);
            break;
          default:
            break;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Lê passwords adicionais enviadas pelo cliente até que coincida com a
   * registada.
   */
  private void readNewPassword(ObjectOutputStream outStream, ObjectInputStream inStream, String storedHash,
      String storedSalt) throws Exception {
    while (true) {
      String newPass = inStream.readUTF();

      byte[] saltBytes = SecurityUtils.fromBase64(storedSalt);
      String computedHash = SecurityUtils.hashPasswordWithSalt(newPass, saltBytes);

      if (!computedHash.equals(storedHash)) {
        outStream.writeUTF(LoginMessage.WrongPassword.name());
        outStream.flush();
      } else {
        break;
      }
    }
  }

  /**
   * Realiza atestação anti-replay com nonce:
   * cliente envia SHA256(nonce || SpertaClient.jar).
   */
  private boolean handleAttestation(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    try {
      return withDecryptedAppFile(() -> {
        FileIntegraty.verifyIntegrityOrExit(APP_FILE);
        long nonce = SecurityUtils.generateNonce();
        outStream.writeLong(nonce);
        outStream.flush();

        int hashSize = inStream.readInt();
        if (hashSize != 32) {
          outStream.writeUTF("HASHSIZE != 32");
          outStream.flush();
          return false;
        }

        byte[] clientHash = inStream.readNBytes(hashSize);
        if (clientHash.length != hashSize) {
          outStream.writeUTF("CLIENTHASH != HASHSIZE");
          outStream.flush();
          return false;
        }

        Path referenceAppPath = readReferenceAppPath(REFERENCE_APP_ID);
        if (referenceAppPath == null || !Files.exists(referenceAppPath)) {
          outStream.writeUTF("REFERENCE PAHT NOT FOUND");
          outStream.flush();
          return false;
        }

        byte[] expectedHash = SecurityUtils.sha256WithNonceAndFile(nonce, referenceAppPath);
        if (!MessageDigest.isEqual(clientHash, expectedHash)) {
          outStream.writeUTF("HASH MISMATCH");
          outStream.flush();
          return false;
        }

        outStream.writeUTF("OKATTEST");
        outStream.flush();
        return true;
      });
    } catch (Exception e) {
      try {
        outStream.writeUTF("NOKATTEST");
        outStream.flush();
      } catch (IOException ioException) {
        // já estamos a falhar a atestação; não há recuperação útil aqui
      }
      return false;
    }
  }

  /**
   * Obtém o caminho da aplicação de referência registada em {@code apps.txt}.
   */
  private Path readReferenceAppPath(String appName) {
    File file = new File(APP_FILE);
    if (!file.exists()) {
      return null;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank())
          continue;
        String[] parts = line.split(":", 2);
        if (parts.length != 2)
          continue;
        if (parts[0].equals(appName)) {
          String pathValue = parts[1].trim();
          if (pathValue.isBlank()) {
            return null;
          }
          return Paths.get(pathValue);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Garante a existência do ficheiro {@code apps.txt} e migra dados legacy
   * quando necessário.
   */
  private void ensureAppFile() {
    File file = new File(APP_FILE);
    ensureParentDir(file);

    // migrate legacy location if needed
    File legacy = new File("apps.txt");
    if (!file.exists() && legacy.exists() && !legacy.equals(file)) {
      try {
        Files.move(legacy.toPath(), file.toPath());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    FileIntegraty.ensureFileAndHashOrExit(APP_FILE);

    try {
      withDecryptedAppFile(() -> {
        FileIntegraty.verifyIntegrityOrExit(APP_FILE);

        // Migração simples de formato legacy (SpertaClient:<size>) para caminho de jar.
        Path appPath = readReferenceAppPath(REFERENCE_APP_ID);
        if (appPath == null || !Files.exists(appPath)) {
          try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(REFERENCE_APP_ID + ":" + DEFAULT_REFERENCE_APP_PATH + System.lineSeparator());
          }
          FileIntegraty.updateHashOrExit(APP_FILE);
        }
        return null;
      });
    } catch (Exception e) {
      FileIntegraty.failIntegrityAndExit();
    }
  }

  private <T> T withDecryptedAppFile(Callable<T> operation) throws Exception {
    return ProtectedFileUtils.withDecryptedFile(APP_FILE, pbeKey, operation);
  }

  private void receiveAndStoreCertificate(String user, ObjectOutputStream outStream, ObjectInputStream inStream)
      throws IOException {

    long certSize = inStream.readLong();
    if (certSize <= 0) {
      throw new IOException("Invalid certificate size");
    }

    byte[] certBytes = inStream.readNBytes((int) certSize);
    if (certBytes.length != certSize) {
      throw new IOException("Incomplete certificate received");
    }

    File certDir = new File("server/certs");
    if (!certDir.exists()) {
      certDir.mkdirs();
    }

    File certFile = new File(certDir, user + ".cert");
    Files.write(certFile.toPath(), certBytes);
  }

  private static void ensureParentDir(File file) {
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
  }
}
