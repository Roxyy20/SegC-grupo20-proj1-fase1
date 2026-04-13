import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Servidor principal que aceita ligações TCP de clientes {@link SpertaClient}
 * e delega o processamento para {@link ServerThread}.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class SpertaServer {
  /**
   * Ponto de entrada. Lê opcionalmente a porta da linha de comando e inicia o
   * servidor.
   *
   * @param args opcional {@code <port>}
   */
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

  /**
   * Cria uma instância parametrizada com a porta de escuta.
   *
   * @param port porta TCP
   */
  SpertaServer(int port) {
    this.port = port;
  }

  /**
   * Arranca o ciclo de aceitação de clientes e cria uma nova thread por
   * ligação.
   */
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
}
