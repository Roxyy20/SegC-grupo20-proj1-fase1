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
    System.out.println("Thread stated for new client");
  }

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
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}