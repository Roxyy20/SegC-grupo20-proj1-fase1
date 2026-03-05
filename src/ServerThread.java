import java.net.Socket;

class ServerThread extends Thread {
  private Socket socket = null;

  ServerThread(Socket inSoc) {
    socket = inSoc;
    System.out.println("Thread stated for new client");
  }

  public void start() {
  }

}