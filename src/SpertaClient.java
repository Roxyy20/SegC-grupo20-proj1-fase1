import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.Scanner;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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

  // dados necessários para crypto / keystore
  private static String keystore;
  private static String passwordKeystore;
  private static String userId;

  /**
   * Ponto de entrada do cliente. Efetua a ligação ao servidor, trata a
   * atestação de integridade, autentica o utilizador e inicia o ciclo de
   * leitura de comandos da consola.
   *
   * @param args argumentos da linha de comando: {@code <serverAddress>
   *             <user-id> <pass>}
   */
  public static void main(String[] args) {
      try {
          if (args.length != 7) {
              System.err.println("Wrong argument count. Usage: SpertaClient <serverAddress> <truststore> <password-truststore> <keystore> <password-keystore> <user-id> <password>");
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
          String truststore = args[1];
          String passwordTruststore = args[2];
          keystore = args[3];
          passwordKeystore = args[4];
          userId = args[5];
          String password = args[6];

          System.setProperty("javax.net.ssl.trustStore", truststore);
          System.setProperty("javax.net.ssl.trustStorePassword", passwordTruststore);
          System.setProperty("javax.net.ssl.keyStore", keystore);
          System.setProperty("javax.net.ssl.keyStorePassword", passwordKeystore);
          SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
          SSLSocket clientSocket = (SSLSocket) factory.createSocket(address, port);
          System.out.println("Connected");
          // assign to static fields so other methods can use them
          outStream = new ObjectOutputStream(clientSocket.getOutputStream());
          outStream.flush();
          inStream = new ObjectInputStream(clientSocket.getInputStream());
          
          // attestation anti-replay: SHA256(nonce || SpertaClient.jar)
          long nonce = inStream.readLong();
          byte[] hash;
          try {
            Path clientJarPath = resolveClientJarPath();
            hash = SecurityUtils.sha256WithNonceAndFile(nonce, clientJarPath);
          } catch (Exception e) {
            System.err.println("Erro a calcular atestação: " + e.getMessage());
            return;
          }

          outStream.writeInt(hash.length);
          outStream.write(hash);
          outStream.flush();

          String attestResp = inStream.readUTF();
          if ("NOKATTEST".equalsIgnoreCase(attestResp)) {
              System.out.println("NOKATTEST");
              return;
          } else if ("OKATTEST".equalsIgnoreCase(attestResp)) {
              System.out.println("OKATTEST");
          } else {
              System.out.println("Unexpected attestation response: " + attestResp);
              return;
          }
          
          // send credentials
          outStream.writeUTF(userId);
          outStream.writeUTF(password);
          outStream.flush();
          
          LoginMessage msg = LoginMessage.valueOf(inStream.readUTF());
          switch (msg) {
              case WrongPassword:
                  System.out.println("Wrong password");
                  tryNewPassword();
                  break;
              case OKNewUser:
                  System.out.println("Ok. You are a new user");
                  sendCertificate(outStream, userId);
                  System.out.println("Certificate sent");
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
          System.err.println("Error: " + e.getMessage());
      }
  }

  /**
   * Resolve o caminho do jar em execução para usar na atestação remota.
   */
  private static Path resolveClientJarPath() throws IOException {
    try {
      Path codeSourcePath = Paths.get(SpertaClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isRegularFile(codeSourcePath)) {
        return codeSourcePath;
      }
    } catch (Exception e) {
      // fallback abaixo
    }

    Path fallback = Paths.get("build", "SpertaClient.jar");
    if (Files.isRegularFile(fallback)) {
      return fallback;
    }
    throw new IOException("Não foi possível localizar SpertaClient.jar para atestação.");
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

      
      if(mensagem[0].equalsIgnoreCase("CREATE")) {
        receiveCreateResponseAndSendSectionKeys(mensagem[1]);
      } else if (mensagem[0].equalsIgnoreCase("RT")) {
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
   * Trata a resposta ao CREATE. Se a casa for criada com sucesso,
   * gera e envia as chaves de secção cifradas para o owner.
   */
  private static void receiveCreateResponseAndSendSectionKeys(String home) throws IOException {
    String response = inStream.readUTF();
    System.out.println(response);

    if (!response.equalsIgnoreCase("OK")) {
      return;
    }

    try {
      sendSectionKeysForCreate(home);
      String finalResponse = inStream.readUTF();
      System.out.println(finalResponse);
    } catch (Exception e) {
      throw new IOException("Erro ao gerar/enviar chaves de secção: " + e.getMessage(), e);
    }
  }

  /**
   * Gera uma chave AES por secção, cifra-a com a chave pública do owner
   * e envia-a ao servidor.
   */
  private static void sendSectionKeysForCreate(String home) throws Exception {
    PublicKey ownerPublicKey = CryptoUtils.getPublicKeyFromKeystore(
        keystore,
        passwordKeystore,
        userId
    );

    outStream.writeInt(Section.values().length);

    for (Section section : Section.values()) {
      javax.crypto.SecretKey sectionKey = CryptoUtils.generateAESKey();
      byte[] sectionKeyBytes = CryptoUtils.aesKeyToBytes(sectionKey);
      byte[] encryptedSectionKey = CryptoUtils.encryptRSA(sectionKeyBytes, ownerPublicKey);

      outStream.writeUTF(section.name());
      outStream.writeInt(encryptedSectionKey.length);
      outStream.write(encryptedSectionKey);
    }

    outStream.flush();
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
      LoginMessage msg = LoginMessage.valueOf(inStream.readUTF());
      if (msg == LoginMessage.WrongPassword) {
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
    if (response.equalsIgnoreCase("OK")) {
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
    } else if (response.equalsIgnoreCase("NOHM")) {
      System.out.println("Casa não existe");
    } else if (response.equalsIgnoreCase("NODATA")) {
      System.out.println("Sem dados");
    } else if (response.equalsIgnoreCase("NOPERM")) {
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

  private static void sendCertificate(ObjectOutputStream outStream, String user) throws IOException {
    Path certPath = Paths.get("userCerts", user + ".cert");
    byte[] certBytes = Files.readAllBytes(certPath);

    outStream.writeLong(certBytes.length);
    outStream.write(certBytes);
    outStream.flush();
  }
}
