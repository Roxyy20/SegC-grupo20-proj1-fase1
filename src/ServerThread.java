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
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}