import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Scanner;

public class SpertaClient {
  // streams used for sending commands to the server
  private static ObjectOutputStream outStream;
  private static ObjectInputStream inStream;

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
      // assign to static fields so other methods can use them
      outStream = new ObjectOutputStream(clientSocket.getOutputStream());
      outStream.flush();
      inStream = new ObjectInputStream(clientSocket.getInputStream());

      // send credentials
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
          tryNewPassword();
          break;
        case OKNewUser:
          System.out.println("Ok. You are a new user");
          printhelp();
          readCommands();
          break;
        case OKUser:
          System.out.println("OK, Welcome back");
          printhelp();
          readCommands();
          break;

        default:
          break;
      }

      // cleanup streams after command loop returns
      outStream.close();
      inStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void printhelp() {
    System.out.println("");
    System.out.println("Comandos Disponíveis:");
    System.out.println("CREATE <hm> # Criar casa <hm> - utilizador é Owner ");
    System.out.println("ADD <user1> <hm> <s> # Adicionar utilizador <user1> à casa <hm>, seção <s>");
    System.out.println("RD <hm> <s> # Registar um Dispositivo na casa <hm>, na seção <s>");
    System.out.println("EC  <hm>  <d>  <int>  #  Enviar  valor  <int>  de  estado/temporização,  do dispositivo <d> da casa <hm>, para o servidor");
    System.out.println("RT <hm># Receber a informação sobre o último comando (estados/temporizações) enviado a cada dispositivo da casa <hm>, desde que o utilizador tenha permissões");
    System.out.println("RH <hm> <d># Receber o Histórico (ficheiro de log .csv) de comandos enviados ao dispositivo <d> da casa <hm>, desde que o utilizador tenha permissões");
    System.out.println("");
  }

  public static void readCommands() throws IOException {
    Scanner sc = new Scanner(System.in);
    boolean running = true;
    while (running) {
      System.out.println("");
      System.out.print("Ação pretendida: ");
      String[] mensagem = sc.nextLine().split(" ");
      sendCommand(mensagem);
      reciveResponse();
    }
  sc.close();
  }

    private static void sendCommand(String[] parts) throws IOException {
      String command = parts[0];
      Command cmdEnum = Command.fromString(command);
      if (cmdEnum == null) {
        System.out.println("Comando não existe");
        return;
      }
      if (cmdEnum == Command.CREATE && parts.length != 2) {
        System.out.println("Comando CREATE requer 1 argumento: <hm> ");
        return;
      }
      else if (cmdEnum == Command.ADD && parts.length != 4) {
        System.out.println("Comando ADD requer 3 argumentos: <user1> <hm> <s>");
        return;
      }
      else if (cmdEnum == Command.EC && parts.length != 4) {
        System.out.println("Comando EC requer 4 argumentos: <hm>  <d>  <int>");
        return;
      }
      else if (cmdEnum == Command.RT && parts.length != 2) {
        System.out.println("Comando RT requer 1 argumento: <hm>");
        return;
      }
      else if (cmdEnum == Command.RH && parts.length != 3) {
        System.out.println("Comando RH requer 2 argumentos: <hm> <d>");
        return;
      }
      else if (cmdEnum == Command.RD && parts.length != 3) {
        System.out.println("Comando RD requer 2 argumentos: <hm> <s>");
        return;
      }
      if(cmdEnum == Command.EC){
        String hm = parts[1];
        String d = parts[2];
        int intValue = Integer.parseInt(parts[3]);
        outStream.writeUTF(hm);
        outStream.writeUTF(d);
        outStream.writeInt(intValue);
      }
      else {
        for (String part : parts) {
          outStream.writeUTF(part);
        }
      }
      outStream.flush();
    }

    private static void tryNewPassword() throws IOException {
      Scanner sc = new Scanner(System.in);
      while (true) {
        System.out.print("Enter new password: ");
        String newPassword = sc.nextLine();
        outStream.writeUTF(newPassword);
        outStream.flush();
        LoginMessage msg;
        try {
          msg = (LoginMessage) inStream.readObject();
        } catch (ClassNotFoundException e) {
          e.printStackTrace();
          return;
        }
        if (msg == LoginMessage.WrongPassword) {
          System.out.println("Wrong password.");
        } else {
          break;
        }
     }
      printhelp();
      readCommands();
    }

    private static void reciveResponse() throws IOException {
      String response = inStream.readUTF();
      System.out.println("Resposta do servidor: " + response);
    }
  
}
