import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
      } else if (mensagem[0].equalsIgnoreCase("ADD")) {
        receiveAddResponseAndSendSectionKey(mensagem[1], mensagem[2], mensagem[3]);
      } else if (mensagem[0].equalsIgnoreCase("EC")) {
        receiveEcResponseAndSendEncryptedValue(mensagem[3]);
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
    if (cmdEnum == Command.EC) {
      try {
        int value = Integer.parseInt(parts[3]);
        if (value < 0 || value > 600) {
          System.out.println("Valor de EC deve estar entre 0 e 600");
          return false;
        }
      } catch (NumberFormatException e) {
        System.out.println("Valor de EC deve ser um inteiro");
        return false;
      }
    }

    outStream.writeUTF(command);
    if (cmdEnum == Command.EC) {
      outStream.writeUTF(parts[1]); // home
      outStream.writeUTF(parts[2]); // device
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

    if (!response.equalsIgnoreCase("OK")) {
      System.out.println(response);
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
   * Depois de um ADD aceite pelo servidor, envia a chave da secção cifrada para
   * o utilizador adicionado.
   */
  private static void receiveAddResponseAndSendSectionKey(String targetUser, String home, String section)
      throws IOException {
    String response = inStream.readUTF();
    if (!response.equalsIgnoreCase(ServerResponse.OK.name())) {
      System.out.println(response);
      return;
    }

    try {
      Path targetCertPath = ensureUserCertificate(targetUser);
      PublicKey targetPublicKey = CryptoUtils.getPublicKeyFromCertificate(targetCertPath);
      PrivateKey ownerPrivateKey = CryptoUtils.getPrivateKeyFromKeystore(
          keystore,
          passwordKeystore,
          userId,
          passwordKeystore
      );

      for (String sectionCode : sectionCodesForAdd(section)) {
        byte[] encryptedForOwner = requestOwnerSectionKey(home, sectionCode);
        byte[] sectionKeyBytes = CryptoUtils.decryptRSA(encryptedForOwner, ownerPrivateKey);
        byte[] encryptedForTarget = CryptoUtils.encryptRSA(sectionKeyBytes, targetPublicKey);
        String storeResponse = storeSectionKeyForUser(targetUser, home, sectionCode, encryptedForTarget);
        if (!storeResponse.equalsIgnoreCase(ServerResponse.OK.name())) {
          System.out.println(storeResponse);
          return;
        }
      }

      System.out.println(ServerResponse.OK.name());
    } catch (Exception e) {
      throw new IOException("Erro ao enviar chave de secção para utilizador: " + e.getMessage(), e);
    }
  }

  private static List<String> sectionCodesForAdd(String section) throws IOException {
    List<String> sectionCodes = new ArrayList<>();
    if (section.equalsIgnoreCase("all")) {
      for (Section value : Section.values()) {
        sectionCodes.add(String.valueOf(value.name().charAt(0)));
      }
      return sectionCodes;
    }

    Section parsedSection = Section.parseSection(section);
    if (parsedSection == null) {
      throw new IOException("Invalid section identifier");
    }
    sectionCodes.add(String.valueOf(parsedSection.name().charAt(0)));
    return sectionCodes;
  }

  private static Path ensureUserCertificate(String targetUser) throws IOException {
    Path certPath = Paths.get("userCerts", targetUser + ".cert");
    if (Files.exists(certPath)) {
      return certPath;
    }

    outStream.writeUTF(Command.GETCERT.name());
    outStream.writeUTF(targetUser);
    outStream.flush();

    String response = inStream.readUTF();
    if (!response.equalsIgnoreCase(ServerResponse.OK.name())) {
      throw new IOException(response);
    }

    long certSize = inStream.readLong();
    if (certSize <= 0 || certSize > Integer.MAX_VALUE) {
      throw new IOException("Invalid certificate size");
    }

    byte[] certBytes = inStream.readNBytes((int) certSize);
    if (certBytes.length != certSize) {
      throw new IOException("Incomplete certificate received");
    }

    Path parent = certPath.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
    }
    Files.write(certPath, certBytes);
    return certPath;
  }

  private static byte[] requestOwnerSectionKey(String home, String section) throws IOException {
    outStream.writeUTF(Command.GETKEY.name());
    outStream.writeUTF(home);
    outStream.writeUTF(section);
    outStream.flush();

    String response = inStream.readUTF();
    if (!response.equalsIgnoreCase(ServerResponse.OK.name())) {
      throw new IOException(response);
    }

    int keySize = inStream.readInt();
    if (keySize <= 0) {
      throw new IOException("Invalid section key size");
    }

    byte[] encryptedKey = inStream.readNBytes(keySize);
    if (encryptedKey.length != keySize) {
      throw new IOException("Incomplete section key received");
    }
    return encryptedKey;
  }

  private static String storeSectionKeyForUser(String targetUser, String home, String section, byte[] encryptedKey)
      throws IOException {
    outStream.writeUTF(Command.STOREKEY.name());
    outStream.writeUTF(targetUser);
    outStream.writeUTF(home);
    outStream.writeUTF(section);
    outStream.writeInt(encryptedKey.length);
    outStream.write(encryptedKey);
    outStream.flush();

    return inStream.readUTF();
  }

  /**
   * Completa o EC: recebe a chave da secção cifrada para o utilizador, decifra-a
   * e envia o valor cifrado com AES.
   */
  private static void receiveEcResponseAndSendEncryptedValue(String value) throws IOException {
    String response = inStream.readUTF();
    if (!response.equalsIgnoreCase(ServerResponse.OK.name())) {
      System.out.println(response);
      return;
    }

    try {
      int encryptedKeySize = inStream.readInt();
      if (encryptedKeySize <= 0) {
        throw new IOException("Invalid section key size");
      }

      byte[] encryptedSectionKey = inStream.readNBytes(encryptedKeySize);
      if (encryptedSectionKey.length != encryptedKeySize) {
        throw new IOException("Incomplete section key received");
      }

      PrivateKey privateKey = CryptoUtils.getPrivateKeyFromKeystore(
          keystore,
          passwordKeystore,
          userId,
          passwordKeystore
      );
      byte[] sectionKeyBytes = CryptoUtils.decryptRSA(encryptedSectionKey, privateKey);
      javax.crypto.SecretKey sectionKey = CryptoUtils.bytesToAESKey(sectionKeyBytes);
      byte[] encryptedValue = CryptoUtils.encryptAES(CryptoUtils.stringToBytes(value), sectionKey);

      outStream.writeInt(encryptedValue.length);
      outStream.write(encryptedValue);
      outStream.flush();

      System.out.println(inStream.readUTF());
    } catch (Exception e) {
      throw new IOException("Erro ao cifrar valor EC: " + e.getMessage(), e);
    }
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
      int sectionCount = inStream.readInt();
      if (sectionCount <= 0) {
        throw new IOException("Invalid number of sections received");
      }

      StringBuilder plaintext = new StringBuilder();
      for (int i = 0; i < sectionCount; i++) {
        String section = inStream.readUTF();
        javax.crypto.SecretKey sectionKey = readSectionKey();
        byte[] encryptedData = readByteBlock();
        String sectionContent = CryptoUtils.bytesToString(encryptedData);
        String decryptedSectionContent = decryptRtSectionContent(sectionContent, sectionKey);
        if (plaintext.length() > 0 && !decryptedSectionContent.isBlank()) {
          plaintext.append(System.lineSeparator());
        }
        plaintext.append(decryptedSectionContent);
        System.out.printf("OK, secção %s recebida.%n", section);
      }

      File outputFile = new File("client/devices_" + home + "_recebido.txt");
      File parent = outputFile.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }
      Files.write(outputFile.toPath(), CryptoUtils.stringToBytes(plaintext.toString()));

      // Mostrar conteúdo recebido no terminal
      System.out.println("Conteúdo recebido:");
      System.out.println(plaintext.toString());

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
      String section = inStream.readUTF();
      javax.crypto.SecretKey sectionKey = readSectionKey();
      byte[] encryptedData = readByteBlock();
      String encryptedContent = CryptoUtils.bytesToString(encryptedData);
      String decryptedContent = decryptRhContent(encryptedContent, sectionKey);
      System.out.printf("OK, secção %s recebida.%n", section);

      // Abrir ficheiro para gravar
      File outputFile = new File("client/" + home + "_" + device + "_log.csv");
      File parent = outputFile.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }
      Files.write(outputFile.toPath(), CryptoUtils.stringToBytes(decryptedContent));

      System.out.println("Conteúdo recebido:");
      System.out.println(decryptedContent);
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

  private static javax.crypto.SecretKey readSectionKey() throws IOException {
    int keySize = inStream.readInt();
    if (keySize <= 0) {
      throw new IOException("Invalid section key size");
    }

    byte[] encryptedSectionKey = inStream.readNBytes(keySize);
    if (encryptedSectionKey.length != keySize) {
      throw new IOException("Incomplete section key received");
    }

    try {
      PrivateKey privateKey = CryptoUtils.getPrivateKeyFromKeystore(
          keystore,
          passwordKeystore,
          userId,
          passwordKeystore
      );
      byte[] sectionKeyBytes = CryptoUtils.decryptRSA(encryptedSectionKey, privateKey);
      return CryptoUtils.bytesToAESKey(sectionKeyBytes);
    } catch (Exception e) {
      throw new IOException("Erro ao decifrar chave da secção: " + e.getMessage(), e);
    }
  }

  private static byte[] readByteBlock() throws IOException {
    long dataSize = inStream.readLong();
    if (dataSize < 0 || dataSize > Integer.MAX_VALUE) {
      throw new IOException("Invalid data size");
    }

    byte[] data = inStream.readNBytes((int) dataSize);
    if (data.length != dataSize) {
      throw new IOException("Incomplete data received");
    }
    return data;
  }

  private static String decryptRtSectionContent(String encryptedContent, javax.crypto.SecretKey sectionKey)
      throws IOException {
    StringBuilder plaintext = new StringBuilder();
    String[] lines = encryptedContent.split("\\R");
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }

      String[] parts = line.split(";", -1);
      if (parts.length >= 3) {
        parts[2] = decryptBase64Value(parts[2], sectionKey);
        line = String.join(";", parts);
      }

      if (plaintext.length() > 0) {
        plaintext.append(System.lineSeparator());
      }
      plaintext.append(line);
    }
    return plaintext.toString();
  }

  private static String decryptRhContent(String encryptedContent, javax.crypto.SecretKey sectionKey)
      throws IOException {
    StringBuilder plaintext = new StringBuilder();
    String[] lines = encryptedContent.split("\\R");
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }

      String[] parts = line.split(",", -1);
      if (parts.length >= 4) {
        parts[3] = decryptBase64Value(parts[3], sectionKey);
        line = String.join(",", parts);
      }

      if (plaintext.length() > 0) {
        plaintext.append(System.lineSeparator());
      }
      plaintext.append(line);
    }
    return plaintext.toString();
  }

  private static String decryptBase64Value(String encryptedValueBase64, javax.crypto.SecretKey sectionKey)
      throws IOException {
    try {
      byte[] encryptedValue = Base64.getDecoder().decode(encryptedValueBase64);
      byte[] plaintextValue = CryptoUtils.decryptAES(encryptedValue, sectionKey);
      return CryptoUtils.bytesToString(plaintextValue);
    } catch (Exception e) {
      throw new IOException("Erro ao decifrar valor: " + e.getMessage(), e);
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
