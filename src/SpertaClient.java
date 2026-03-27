import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Scanner;

/**
 * Cliente de linha de comando que autentica no {@link SpertaServer}, envia
 * comandos e guarda os ficheiros devolvidos pelo servidor.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class SpertaClient {
  // streams used for sending commands to the server
  private static ObjectOutputStream outStream;
  private static ObjectInputStream inStream;
  // scanner partilhado para evitar fechar System.in
  private static final Scanner STDIN = new Scanner(System.in);

  /**
   * Ponto de entrada do cliente. Efetua a ligação ao servidor, trata a
   * atestação de integridade, autentica o utilizador e inicia o ciclo de
   * leitura de comandos da consola.
   *
   * @param args argumentos da linha de comando: {@code <serverAddress>
   *             <user-id> <pass>}
   */
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

      String msg = inStream.readUTF();
      switch (msg) {
        case "WRONG-PWD":
          System.out.println("Wrong password");
          tryNewPassword();
          break;
        case "OK-NEW-USER":
          System.out.println("Ok. You are a new user");
          printhelp();
          readCommands();
          break;
        case "OK-USER":
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

  /**
   * Obtém o tamanho do ficheiro fonte ou do bytecode do cliente. Este valor é
   * usado no processo de atestação realizado pelo servidor.
   *
   * @return tamanho do executável ou 0 se não for possível determinar
   */
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

  /**
   * Mostra os comandos disponíveis para o utilizador.
   */
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

  /**
   * Ciclo principal de leitura de comandos a partir da consola do utilizador.
   * Após cada comando, aciona o respetivo método de envio e trata a resposta.
   *
   * @throws IOException se ocorrer erro de I/O na comunicação com o servidor
   */
  public static void readCommands() throws IOException {
    boolean running = true;
    while (running) {
      System.out.println("");
      System.out.print("Ação pretendida: ");
      String[] mensagem = STDIN.nextLine().trim().split("\\s+");
      if (mensagem.length == 0 || mensagem[0].isBlank())
        continue;

      boolean sent = sendCommand(mensagem);
      if (!sent)
        continue; // erro nos argumentos → voltar a pedir comando

      if (mensagem[0].equalsIgnoreCase("RT")) {
        receive_home_devices_file(mensagem[1]);
      } else if (mensagem[0].equalsIgnoreCase("RH")) {
        receive_device_log_file(mensagem[1], mensagem[2]);
      } else {
        receiveResponse();
      }
    }
  }

  /**
   * Valida e envia um comando para o servidor, escrevendo os argumentos
   * necessários na stream de saída.
   *
   * @param parts tokens do comando introduzido pelo utilizador
   * @return {@code true} se o comando foi enviado, {@code false} em caso de
   *         erro de validação
   * @throws IOException se falhar a escrita na stream
   */
  private static boolean sendCommand(String[] parts) throws IOException {
    String command = parts[0];
    Command cmdEnum = Command.fromString(command);
    if (cmdEnum == null) {
      System.out.println("Comando não existe");
      return false;
    }
    if (cmdEnum == Command.CREATE && parts.length != 2) {
      System.out.println("Comando CREATE requer 1 argumento: <hm> ");
      return false;
    } else if (cmdEnum == Command.ADD && parts.length != 4) {
      System.out.println("Comando ADD requer 3 argumentos: <user1> <hm> <s>");
      return false;
    } else if (cmdEnum == Command.EC && parts.length != 4) {
      System.out.println("Comando EC requer 4 argumentos: <hm>  <d>  <int>");
      return false;
    } else if (cmdEnum == Command.RT && parts.length != 2) {
      System.out.println("Comando RT requer 1 argumento: <hm>");
      return false;
    } else if (cmdEnum == Command.RH && parts.length != 3) {
      System.out.println("Comando RH requer 2 argumentos: <hm> <d>");
      return false;
    } else if (cmdEnum == Command.RD && parts.length != 3) {
      System.out.println("Comando RD requer 2 argumentos: <hm> <s>");
      return false;
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
    return true;
  }

  /**
   * Solicita ao utilizador uma nova password até que corresponda ao valor
   * esperado pelo servidor.
   *
   * @throws IOException se falhar a comunicação com o servidor
   */
  private static void tryNewPassword() throws IOException {
    while (true) {
      System.out.print("Enter new password: ");
      String newPassword = STDIN.nextLine();
      outStream.writeUTF(newPassword);
      outStream.flush();
      String msg = inStream.readUTF();
      if ("WRONG-PWD".equals(msg)) {
        System.out.println("Wrong password.");
      } else {
        break;
      }
    }
    printhelp();
    readCommands();
  }

  /**
   * Lê e apresenta a resposta textual simples devolvida pelo servidor.
   *
   * @throws IOException se falhar a leitura da stream
   */
  private static void receiveResponse() throws IOException {
    String response = inStream.readUTF();
    // Mostrar apenas a resposta crua (ex.: NOHM)
    System.out.println(response);
  }

  /**
   * Recebe do servidor a listagem de estados dos dispositivos de uma casa,
   * gravando-a num ficheiro local e mostrando o conteúdo no terminal.
   *
   * @param home nome da casa solicitada
   * @throws IOException se ocorrer erro de leitura ou escrita de ficheiro
   */
  private static void receive_home_devices_file(String home) throws IOException {
    String response = inStream.readUTF();
    if (response.equalsIgnoreCase(ServerResponse.OK.name())) {
      long fileSize = inStream.readLong();
      System.out.printf("OK, %d (long).%n", fileSize);

      // 2. Preparar ficheiro
      File outputFile = new File("client/devices_" + home + "_recebido.txt");
      File parent = outputFile.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }

      FileOutputStream fos = new FileOutputStream(outputFile);
      java.io.ByteArrayOutputStream memoryCopy = new java.io.ByteArrayOutputStream((int) fileSize);

      byte[] buffer = new byte[4096];
      long totalRead = 0;

      // 3. Ler exatamente o número de bytes esperado
      while (totalRead < fileSize) {
        int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
        int bytesRead = inStream.read(buffer, 0, bytesToRead);

        if (bytesRead == -1)
          break;

        fos.write(buffer, 0, bytesRead);
        memoryCopy.write(buffer, 0, bytesRead);
        totalRead += bytesRead;
      }

      fos.close();

      // Mostrar conteúdo recebido no terminal
      String content = memoryCopy.toString();
      System.out.println("Conteúdo recebido:");
      System.out.println(content);

      System.out.println("Ficheiro recebido com sucesso!");
    } else if (response.equalsIgnoreCase(ServerResponse.NOHM.name())) {
      System.out.println("Casa não existe");
    } else if (response.equalsIgnoreCase(ServerResponse.NODATA.name())) {
      System.out.println("Sem dados");
    } else if (response.equalsIgnoreCase(ServerResponse.NOPERM.name())) {
      System.out.println("Sem permissões para esta casa.");
    } else {
      System.out.println("Resposta desconhecida do servidor.");
    }
  }

  /**
   * Recebe do servidor o ficheiro de histórico (CSV) para um dispositivo
   * específico, guarda-o em {@code client/<home>_<device>_log.csv} e mostra-o
   * na consola.
   *
   * @param home   casa a que pertence o dispositivo
   * @param device identificador do dispositivo
   * @throws IOException se falhar a transferência ou gravação do ficheiro
   */
  private static void receive_device_log_file(String home, String device) throws IOException {
    String response = inStream.readUTF();
    if (response.equals("OK")) {
      long fileSize = inStream.readLong();
      System.out.printf("OK, %d (long).%n", fileSize);

      // Abrir ficheiro para gravar
      File outputFile = new File("client/" + home + "_" + device + "_log.csv");
      File parent = outputFile.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }
      FileOutputStream fos = new FileOutputStream(outputFile);
      java.io.ByteArrayOutputStream memoryCopy = new java.io.ByteArrayOutputStream((int) fileSize);

      byte[] buffer = new byte[4096];
      long totalRead = 0;
      while (totalRead < fileSize) {
        int toRead = (int) Math.min(buffer.length, fileSize - totalRead);
        int read = inStream.read(buffer, 0, toRead);
        if (read == -1)
          break;
        fos.write(buffer, 0, read);
        memoryCopy.write(buffer, 0, read);
        totalRead += read;
      }

      fos.close();

      System.out.println("Conteúdo recebido:");
      System.out.println(memoryCopy.toString());
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
        case "NOD":
          System.out.println("O dispositivo não existe");
          break;
        default:
          System.out.println("Resposta desconhecida do servidor.");
      }
    }
  }
}
