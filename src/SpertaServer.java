import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SpertaServer {
  public static void main(String[] args) {
    if (args.length > 1) {
      System.err.println("Wrong argument count. Usage: SpertaServer <port>");
      return;
    }
    int port = 22345;
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.out.format("'%s' id not a valid port format. Using default value of 22345\n", args[0]);
      }
    }
    System.out.format("Starting server at port %s\n", port);
    SpertaServer server = new SpertaServer(port);
    server.start();
  }

  private int port;

  SpertaServer(int port) {
    this.port = port;
  }

  public void start() {
    try (ServerSocket sSoc = new ServerSocket(port)) {
      System.out.println("Server Started");
      while (true) {
        try {
          Socket inSoc = sSoc.accept();
          ServerThread newServerThread = new ServerThread(this, inSoc);
          newServerThread.start();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    } catch (IOException e) {
      System.err.println(e.getMessage());
      System.exit(-1);
    }
  }
}
