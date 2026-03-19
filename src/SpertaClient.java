import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URL;
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

      // attestation: send app name and size, wait for response
      String appName = "SpertaClient";
      long appSize = getAppSize();
      outStream.writeUTF(appName);
      outStream.writeLong(appSize);
      outStream.flush();

      String attestResp = inStream.readUTF();
      if ("ATTESTATION FAILED".equalsIgnoreCase(attestResp)) {
        System.out.println("ATTESTATION FAILED");
        return;
      } else if ("ATTESTATION OK".equalsIgnoreCase(attestResp)) {
        System.out.println("ATTESTATION OK");
      } else {
        System.out.println("Unexpected attestation response: " + attestResp);
        return;
      }

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

  private static long getAppSize() {
    File src = new File("src/SpertaClient.java");
    if (src.exists()) {
      return src.length();
    }
    try {
      URL url = SpertaClient.class.getResource("SpertaClient.class");
      if (url != null && "file".equals(url.getProtocol())) {
        File f = new File(url.getFile());
        if (f.exists()) {
          return f.length();
        }
      }
    } catch (Exception e) {
      // ignore and fall through
    }
    return 0L;
  }

  public static void printhelp() {
    System.out.println("");
    System.out.println("Comandos Disponíveis:");
    System.out.println("- CREATE <hm> # Criar casa <hm> - utilizador é Owner ");
    System.out.println("- ADD <user1> <hm> <s> # Adicionar utilizador <user1> à casa <hm>, seção <s>");
    System.out.println("- RD <hm> <s> # Registar um Dispositivo na casa <hm>, na seção <s>");
    System.out.println(
        "- EC  <hm>  <d>  <int>  #  Enviar  valor  <int>  de  estado/temporização,  do dispositivo <d> da casa <hm>, para o servidor");
    System.out.println(
        "- RT <hm># Receber a informação sobre o último comando (estados/temporizações) enviado a cada dispositivo da casa <hm>, desde que o utilizador tenha permissões");
    System.out.println(
        "- RH <hm> <d># Receber o Histórico (ficheiro de log .csv) de comandos enviados ao dispositivo <d> da casa <hm>, desde que o utilizador tenha permissões");
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
      if (mensagem[0].equals("RT")) {
        receive_home_devices_file(mensagem[1]);
      } else if (mensagem[0].equals("RH")) {
        receive_device_log_file(mensagem[1], mensagem[2]);
      } else {
        receiveResponse();
      }
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
    } else if (cmdEnum == Command.ADD && parts.length != 4) {
      System.out.println("Comando ADD requer 3 argumentos: <user1> <hm> <s>");
      return;
    } else if (cmdEnum == Command.EC && parts.length != 4) {
      System.out.println("Comando EC requer 4 argumentos: <hm>  <d>  <int>");
      return;
    } else if (cmdEnum == Command.RT && parts.length != 2) {
      System.out.println("Comando RT requer 1 argumento: <hm>");
      return;
    } else if (cmdEnum == Command.RH && parts.length != 3) {
      System.out.println("Comando RH requer 2 argumentos: <hm> <d>");
      return;
    } else if (cmdEnum == Command.RD && parts.length != 3) {
      System.out.println("Comando RD requer 2 argumentos: <hm> <s>");
      return;
    }
    outStream.writeUTF(command);
    if (cmdEnum == Command.EC) {
      outStream.writeUTF(parts[1]); // home
      outStream.writeUTF(parts[2]); // device
      outStream.writeInt(Integer.parseInt(parts[3])); // value
    } else {
      for (int i = 1; i < parts.length; i++) {
        outStream.writeUTF(parts[i]);
      }
    }
    outStream.flush();
  }

  private static void tryNewPassword() throws IOException {
    try (Scanner sc = new Scanner(System.in)) {
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
    }
    printhelp();
    readCommands();
  }

  private static void receiveResponse() throws IOException {
    String response = inStream.readUTF();
    System.out.println("Resposta do servidor: " + response);

  }

  private static void receive_home_devices_file(String home) throws IOException {
    String response = inStream.readUTF();
    System.out.println("Resposta do servidor: " + response);
    if (response.equalsIgnoreCase(ServerResponse.OK.name())) {
      long fileSize = inStream.readLong();

      // 2. Preparar ficheiro
      FileOutputStream fos = new FileOutputStream("devices_" + home + "_recebido.txt");

      byte[] buffer = new byte[4096];
      long totalRead = 0;

      // 3. Ler exatamente o número de bytes esperado
      while (totalRead < fileSize) {
        int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
        int bytesRead = inStream.read(buffer, 0, bytesToRead);

        if (bytesRead == -1)
          break;

        fos.write(buffer, 0, bytesRead);
        totalRead += bytesRead;
      }

      fos.close();

      System.out.println("Ficheiro recebido com sucesso!");
    } else if (response.equalsIgnoreCase(ServerResponse.NOHM.name())) {
      System.out.println("Casa não existe");
    } else if (response.equalsIgnoreCase(ServerResponse.NODATA.name())) {
      System.out.println("Sem dados");
    }
  }

  private static void receive_device_log_file(String home, String device) throws IOException {
    String response = inStream.readUTF();
    System.out.println("Resposta do servidor: " + response);

    if (response.equals("OK")) {

      // Abrir ficheiro para gravar
      BufferedWriter writer = new BufferedWriter(new FileWriter(home + "_" + device + "_log.csv"));

      try {
        // Ler todas as linhas enviadas pelo servidor
        while (true) {
          String line = inStream.readUTF();
          writer.write(line);
          writer.newLine();
        }
      } catch (EOFException e) {
        // Chegou ao fim do stream → terminado
      }

      writer.close();
      System.out.println("Ficheiro do dispositivo recebido com sucesso!");

    } else {
      // tratar erros
      switch (response) {
        case "NODATA":
          System.out.println("Sem dados disponíveis para este dispositivo.");
          break;
        case "NOHM":
          System.out.println("Casa não existe.");
          break;
        case "NOPERM":
          System.out.println("Sem permissões para este dispositivo.");
          break;
        default:
          System.out.println("Resposta desconhecida do servidor.");
      }
    }
  }
}
