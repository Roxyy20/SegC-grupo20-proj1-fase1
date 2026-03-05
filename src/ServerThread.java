import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

class ServerThread extends Thread {
  private Socket socket = null;

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
            return;
          }
          break;
        }
      }
      buffReader.close();
      if (found) {
        outStream.writeObject(LoginMessage.OKUser);
      } else {
        outStream.writeObject(LoginMessage.OKNewUser);
        FileWriter fileWriter = new FileWriter("users.txt");
        fileWriter.write(user + ":" + pass + "\n");
        fileWriter.close();
      }
      outStream.flush();

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
            createHome(outStream, inStream);
            break;
          case ADD:
            addPermission(outStream, inStream);
            break;
          case RD:
            registerDevice(outStream, inStream);
            break;
          case EC:
            setStateOrTimer(outStream, inStream);
            break;
          case RT:
            requestHomeLog(outStream, inStream);
            break;
          case RH:
            requestDeviceLog(outStream, inStream);
            break;
          default:
            break;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void requestDeviceLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    System.out.printf("Entregando log file do dispositivo %s casa\n", device, home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    System.out.printf("Entregando log file da casa %s\n", home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void setStateOrTimer(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    int value = inStream.readInt();
    System.out.printf("ligando dispositivo %s em %s por %i\n", device, home, value);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void registerDevice(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String deviceType = inStream.readUTF();
    System.out.printf("Adicionando dispositivo %s à casa %s\n", deviceType, home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String user = inStream.readUTF();
    String home = inStream.readUTF();
    String permission = inStream.readUTF();
    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String name = inStream.readUTF();
    System.out.println("Creating home '" + name + "'");
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

}