import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
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
    Server server = new Server(port);
    server.start();
  }

  private int port;

  Server(int port) {
    this.port = port;
  }

  public void start() {
    try (ServerSocket sSoc = new ServerSocket(port)) {
      System.out.println("Server Started");
      while (true) {
        try {
          Socket inSoc = sSoc.accept();
          ServerThread newServerThread = new ServerThread(inSoc);
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

  static class ServerThread extends Thread {
    private Socket socket = null;

    ServerThread(Socket inSoc) {
      socket = inSoc;
      System.out.println("Thread stated for new client");
    }

    public void start() {
    }

  }
}
