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

/**
 * Thread responsável por tratar uma ligação de cliente: atestação,
 * autenticação e encaminhamento de comandos para {@link CommandHandler}.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
class ServerThread extends Thread {
  private Socket socket = null;
  private static final String APP_FILE = "server/apps.txt";

  /**
   * Cria a thread associada a um socket já aceite.
   *
   * @param inSoc socket da ligação do cliente
   */
  ServerThread(Socket inSoc) {
    socket = inSoc;
    System.out.println("Thread started for new client");
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

      Path path = Paths.get("server/users.txt");

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

          if(storedHash.equals(computedHash)){
            authenticated = true;
          }
          else{
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
        System.out.println("Entrou no if found");
        if (!authenticated) {
          // não deve acontecer, mas evitar enviar estado errado
          outStream.writeUTF(LoginMessage.WrongPassword.name());
        } else {
        System.out.println("Entrou no if authenticated");
        outStream.writeUTF(LoginMessage.OKUser.name());
        outStream.flush();
        System.out.println("Enviou OKUser");
        }
      } else {
        outStream.writeUTF(LoginMessage.OKNewUser.name());

        byte[] salt = SecurityUtils.generateSalt();
        String hashBase64 = SecurityUtils.hashPasswordWithSalt(pass, salt);
        String saltBase64 = SecurityUtils.toBase64(salt);

        File usersFile = new File("server/users.txt");
        File parent = usersFile.getParentFile();
        if (parent != null && !parent.exists()) {
          parent.mkdirs();
        }
         try (FileWriter fileWriter = new FileWriter(usersFile, true)) {
          fileWriter.write(user + ":" + hashBase64 + ":" + saltBase64 + "\n");
        } catch (IOException e) {
          e.printStackTrace();
        }
      outStream.flush();
      }

      CommandHandler commandHandler = new CommandHandler(user);

      while (true) {
        String cmdStr;
        try {
          cmdStr = inStream.readUTF();
          System.out.println("Received command: " + cmdStr);
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
  private void readNewPassword(ObjectOutputStream outStream, ObjectInputStream inStream, String storedHash, String storedSalt) throws Exception {
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
   * Realiza a atestação de integridade verificando o tamanho esperado do
   * binário/bytecode do cliente.
   */
  private boolean handleAttestation(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String appName = inStream.readUTF();
    long clientSize = inStream.readLong();
    long expectedSize = readExpectedSize(appName);
    if (expectedSize <= 0 || expectedSize != clientSize) {
      outStream.writeUTF("ATTESTATION FAILED");
      outStream.flush();
      return false;
    }
    outStream.writeUTF("ATTESTATION OK");
    outStream.flush();
    return true;
  }

  /**
   * Obtém o tamanho esperado de uma aplicação registada em {@code apps.txt}.
   */
  private long readExpectedSize(String appName) {
    File file = new File(APP_FILE);
    if (!file.exists()) {
      return -1;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank())
          continue;
        String[] parts = line.split(":");
        if (parts.length != 2)
          continue;
        if (parts[0].equals(appName)) {
          try {
            return Long.parseLong(parts[1]);
          } catch (NumberFormatException e) {
            return -1;
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return -1;
  }

  /**
   * Garante a existência do ficheiro {@code apps.txt} e migra dados legacy
   * quando necessário.
   */
  private void ensureAppFile() {
    File file = new File(APP_FILE);
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    // migrate legacy location if needed
    File legacy = new File("apps.txt");
    if (!file.exists() && legacy.exists() && !legacy.equals(file)) {
      try {
        Files.move(legacy.toPath(), file.toPath());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    long size = new File("src/SpertaClient.java").length();
    try (FileWriter writer = new FileWriter(file, false)) {
      writer.write("SpertaClient:" + size + System.lineSeparator());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
