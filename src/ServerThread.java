import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

class ServerThread extends Thread {
  private Socket socket = null;
  private static final String APP_FILE = "apps.txt";

  ServerThread(Socket inSoc) {
    socket = inSoc;
    System.out.println("Thread started for new client");
  }

  @Override
  public void start() {
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
        outStream.writeObject(LoginMessage.WrongPassword);
        outStream.flush();
        return;
      }

      FileReader fileReader = new FileReader("users.txt");
      BufferedReader buffReader = new BufferedReader(fileReader);
      String line;
      Boolean found = false;
      while ((line = buffReader.readLine()) != null) {
        String[] values = line.split(":");
        String username = values[0];
        String password = values[1];
        if (username.equals(user)) {
          found = true;
          if (!password.equals(pass)) {
            outStream.writeObject(LoginMessage.WrongPassword);
            outStream.flush();
            buffReader.close();
            readNewPassword(outStream, inStream, password);
          }
          break;
        }
      }
      buffReader.close();
      if (found) {
        outStream.writeObject(LoginMessage.OKUser);
      } else {
        outStream.writeObject(LoginMessage.OKNewUser);
        FileWriter fileWriter = new FileWriter("users.txt", true);
        fileWriter.write(user + ":" + pass + "\n");
        fileWriter.close();
      }
      outStream.flush();

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
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void readNewPassword(ObjectOutputStream outStream, ObjectInputStream inStream, String pass)
      throws IOException {
    while (true) {
      String newPass = inStream.readUTF();
      System.out.println("Received new password: " + newPass + " password: " + pass);
      System.out.println(newPass.equals(pass));
      if (!newPass.equals(pass)) {
        System.out.println("Wrong password. Entrou no if");
        outStream.writeObject(LoginMessage.WrongPassword);
        outStream.flush();
      } else if (newPass.equals(pass)) {
        System.out.println("Password correct. Entrou no else");
        outStream.writeObject(LoginMessage.OKUser);
        outStream.flush();
        break;
      }
    }
  }

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
        if (parts[0].equalsIgnoreCase(appName)) {
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

  private void ensureAppFile() {
    File file = new File(APP_FILE);
    long size = new File("src/SpertaClient.java").length();
    try (FileWriter writer = new FileWriter(file, false)) {
      writer.write("SpertaClient:" + size + System.lineSeparator());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
