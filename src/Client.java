import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class Client {
  public static void main(String[] args) {
    if (args.length != 3) {
      System.err.println("Wrong argument count. Usage: SpertaClient <serverAddress> <user-id> <pass>");
      return;
    }

    String[] values = args[0].split(":");

    String address = values[0];

    int port = 22345;
    if (values.length > 1) {
      try {
        port = Integer.parseInt(values[1]);
      } catch (NumberFormatException e) {
        System.out.format("'%s' id not a valid port format. Using default value of 22345\n", values[1]);
      }
    }

    try (Socket clientSocket = new Socket(address, port)) {
      System.out.println("Connected");
      ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
      outStream.flush();
      ObjectInputStream inStream = new ObjectInputStream(clientSocket.getInputStream());

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
