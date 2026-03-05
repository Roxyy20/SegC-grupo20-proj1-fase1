import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class SpertaClient {
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

      outStream.writeUTF(args[1]);
      outStream.writeUTF(args[2]);
      outStream.flush();

      LoginMessage msg;
      try {
        msg = (LoginMessage) inStream.readObject();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
        return;
      }
      switch (msg) {
        case WrongPassword:
          System.out.println("Wrong password");
          break;
        case OKNewUser:
          System.out.println("Ok. You are a new user");
          printhelp();
          break;
        case OKUser:
          System.out.println("OK, Welcome back");
          printhelp();
          break;

        default:
          break;
      }

      outStream.close();
      inStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void printhelp() {
    System.out.println("Comandos Disponíveis:");
    System.out.println("CREATE <hm> # Criar casa <hm> - utilizador é Owner ");
    System.out.println("ADD <user1> <hm> <s> # Adicionar utilizador <user1> à casa <hm>, seção <s>");
    System.out.println("RD <hm> <s> # Registar um Dispositivo na casa <hm>, na seção <s>");
    System.out.println("EC  <hm>  <d>  <int>  #  Enviar  valor  <int>  de  estado/temporização,  do dispositivo <d> da casa <hm>, para o servidor");
    System.out.println("RT <hm># Receber a informação sobre o último comando (estados/temporizações) enviado a cada dispositivo da casa <hm>, desde que o utilizador tenha permissões");
    System.out.println("RH <hm> <d># Receber o Histórico (ficheiro de log .csv) de comandos enviados ao dispositivo <d> da casa <hm>, desde que o utilizador tenha permissões");
  }

}
