import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.crypto.SecretKey;


/**
 * Trata comandos recebidos de um cliente autenticado, garantindo validações de
 * permissões e persistindo a informação necessária em disco.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class CommandHandler {

  private static final String HOMES_FILE = "server/homes.txt";
  private static final String USERS_FILE = "server/users.txt";
  private static final String DEVICES_FILE = "server/devices.txt";
  private static final Map<String, Home> homes = new LinkedHashMap<>();
  private final String currentUser;
  private final SecretKey pbeKey;
  private static final String SECTION_KEYS_DIR = "server/sectionKeys";

  /**
   * Associa o handler a um utilizador autenticado.
   */
  public CommandHandler(String user, SecretKey pbeKey) {
    currentUser = user;
    this.pbeKey = pbeKey;
  }

  // Home
  /**
   * Envia para o cliente o log CSV de um dispositivo, validando permissões.
   */
  public void requestDeviceLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String homeName = inStream.readUTF();
    String device = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }

    Home home = getHome(homeName);

    ServerResponse perm = home.canAccessDevice(device, currentUser);
    if (!ServerResponse.OK.equals(perm)) {
      sendResponse(outStream, perm);
      return;
    }

    File deviceLogFile = home.requestDeviceLog(device);
    if (!deviceLogFile.exists()) {
      sendResponse(outStream, ServerResponse.NODATA);
      return;
    }

    String section = deviceSectionCode(device);
    File keyFile = findEncryptedSectionKey(homeName, section, currentUser);
    if (keyFile == null || !keyFile.exists()) {
      sendResponse(outStream, ServerResponse.NODATA);
      return;
    }

    byte[] encryptedSectionKey = Files.readAllBytes(keyFile.toPath());
    byte[] data = withDecryptedProtectedFile(deviceLogFile.getPath(), () -> Files.readAllBytes(deviceLogFile.toPath()));

    outStream.writeUTF(ServerResponse.OK.name());
    outStream.writeUTF(section);
    outStream.writeInt(encryptedSectionKey.length);
    outStream.write(encryptedSectionKey);
    outStream.writeLong(data.length);
    outStream.write(data);
    outStream.flush();
  }

  // Home
  /**
   * Envia para o cliente os últimos estados/temporizações dos dispositivos da
   * casa a que o utilizador tem acesso.
   */
  public void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String homeName = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }
    Home home = getHome(homeName);

    ServerResponse housePerm = home.canAccessHome(currentUser);
    if (ServerResponse.NOPERM.equals(housePerm)) {
      sendResponse(outStream, housePerm);
      return;
    }

    File devicesFile = new File(DEVICES_FILE);
    ensureParentDir(devicesFile);
    Map<String, StringBuilder> dataBySection = new LinkedHashMap<>();
    final boolean[] hasDataForHome = new boolean[1];
    withDecryptedProtectedFile(DEVICES_FILE, () -> {
      try (BufferedReader reader = new BufferedReader(new FileReader(devicesFile))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank())
            continue;
          String[] parts = line.split(";");
          if (parts.length < 3)
            continue;
          if (!parts[0].equals(homeName))
            continue;
          hasDataForHome[0] = true;
          String device = parts[1];
          ServerResponse perm = home.canAccessDevice(device, currentUser);
          if (perm != ServerResponse.OK)
            continue;
          String section = deviceSectionCode(device);
          StringBuilder sectionData = dataBySection.computeIfAbsent(section, key -> new StringBuilder());
          if (sectionData.length() > 0)
            sectionData.append(System.lineSeparator());
          sectionData.append(line);
        }
      }
      return null;
    });

    if (dataBySection.isEmpty()) {
      if (hasDataForHome[0]) {
        outStream.writeUTF(ServerResponse.NOPERM.name());
      } else {
        outStream.writeUTF(ServerResponse.NODATA.name());
      }
    } else {
      for (String section : dataBySection.keySet()) {
        File keyFile = findEncryptedSectionKey(homeName, section, currentUser);
        if (keyFile == null || !keyFile.exists()) {
          sendResponse(outStream, ServerResponse.NODATA);
          return;
        }
      }

      outStream.writeUTF(ServerResponse.OK.name());
      outStream.writeInt(dataBySection.size());

      for (Map.Entry<String, StringBuilder> entry : dataBySection.entrySet()) {
        String section = entry.getKey();
        File keyFile = findEncryptedSectionKey(homeName, section, currentUser);
        byte[] encryptedSectionKey = Files.readAllBytes(keyFile.toPath());
        byte[] sectionData = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);

        outStream.writeUTF(section);
        outStream.writeInt(encryptedSectionKey.length);
        outStream.write(encryptedSectionKey);
        outStream.writeLong(sectionData.length);
        outStream.write(sectionData);
      }
    }
    outStream.flush();
  }

  // Home
  /**
   * Define estado ou temporização de um dispositivo, regista no log e atualiza
   * o ficheiro de últimos estados.
   */
  public synchronized void setStateOrTimer(ObjectOutputStream outStream, ObjectInputStream inStream)
      throws IOException {
    String homeName = inStream.readUTF();
    String device = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.canAccessDevice(device, currentUser);
    if (res != ServerResponse.OK) {
      sendResponse(outStream, res);
      return;
    }

    File keyFile = findEncryptedSectionKey(homeName, deviceSectionCode(device), currentUser);
    if (keyFile == null || !keyFile.exists()) {
      sendResponse(outStream, ServerResponse.NODATA);
      return;
    }

    byte[] encryptedSectionKey = Files.readAllBytes(keyFile.toPath());
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.writeInt(encryptedSectionKey.length);
    outStream.write(encryptedSectionKey);
    outStream.flush();

    int encryptedValueSize = inStream.readInt();
    if (encryptedValueSize <= 0) {
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    byte[] encryptedValue = inStream.readNBytes(encryptedValueSize);
    if (encryptedValue.length != encryptedValueSize) {
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    String encryptedValueBase64 = Base64.getEncoder().encodeToString(encryptedValue);
    File deviceLog = new File("server/" + homeName + "_" + device + ".csv");
    ensureParentDir(deviceLog);
    withDecryptedProtectedFile(deviceLog.getPath(), () -> {
      try (FileWriter writer = new FileWriter(deviceLog, true)) {
        Date currentUserData = new Date();
        String data = new SimpleDateFormat("yyyy-MM-dd").format(currentUserData);
        String time = new SimpleDateFormat("HH:mm:ss").format(currentUserData);
        writer.write(String.format("%s,%s,%s,%s", data, time, currentUser, encryptedValueBase64));
        writer.write(System.lineSeparator());
      }
      FileIntegraty.updateHashOrExit(deviceLog.getPath());
      return null;
    });
    updateLastStatesFile(homeName, device, encryptedValueBase64, currentUser);

    sendResponse(outStream, ServerResponse.OK);
  }

  /**
   * Regista um novo dispositivo numa secção, validando que o utilizador é o
   * proprietário da casa.
   */
  public synchronized void registerDevice(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String homeName = inStream.readUTF();
    String sectionCode = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.registerDevice(sectionCode, currentUser);
    saveHomesToDisk();

    sendResponse(outStream, res);
  }

  /**
   * Concede permissões de acesso a um utilizador para uma casa e secção.
   */
  public synchronized void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String user = inStream.readUTF();
    String homeName = inStream.readUTF();
    String permission = inStream.readUTF();

    if (!userExists(user)) {
      sendResponse(outStream, ServerResponse.NOUSER);
      return;
    }

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.addPermission(user, permission, currentUser);
    saveHomesToDisk();

    // System.out.println("Usuario " + user + " adicionado com permissão " +
    // permission + " à " + home);
    sendResponse(outStream, res);
  }

  /**
   * Envia ao owner autenticado o certificado de outro utilizador.
   */
  public synchronized void requestUserCertificate(ObjectOutputStream outStream, ObjectInputStream inStream)
      throws IOException {
    String user = inStream.readUTF();
    if (!userExists(user)) {
      sendResponse(outStream, ServerResponse.NOUSER);
      return;
    }

    File certFile = new File("server/certs", user + ".cert");
    if (!certFile.exists()) {
      sendResponse(outStream, ServerResponse.NODATA);
      return;
    }

    byte[] certBytes = Files.readAllBytes(certFile.toPath());
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.writeLong(certBytes.length);
    outStream.write(certBytes);
    outStream.flush();
  }

  /**
   * Envia ao owner a chave da secção cifrada com a chave pública do próprio owner.
   */
  public synchronized void requestOwnerSectionKey(ObjectOutputStream outStream, ObjectInputStream inStream)
      throws IOException {
    String homeName = inStream.readUTF();
    String section = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }

    Home home = getHome(homeName);
    if (!home.owner.equals(currentUser)) {
      sendResponse(outStream, ServerResponse.NOPERM);
      return;
    }

    File keyFile = findEncryptedSectionKey(homeName, section, currentUser);
    if (keyFile == null || !keyFile.exists()) {
      sendResponse(outStream, ServerResponse.NODATA);
      return;
    }

    byte[] encryptedKey = Files.readAllBytes(keyFile.toPath());
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.writeInt(encryptedKey.length);
    outStream.write(encryptedKey);
    outStream.flush();
  }

  /**
   * Guarda a chave de secção recebida do owner, cifrada com a chave pública do utilizador alvo.
   */
  public synchronized void storeSectionKeyForUser(ObjectOutputStream outStream, ObjectInputStream inStream)
      throws IOException {
    String user = inStream.readUTF();
    String homeName = inStream.readUTF();
    String section = inStream.readUTF();
    int keySize = inStream.readInt();

    if (keySize <= 0) {
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    byte[] encryptedKey = inStream.readNBytes(keySize);
    if (encryptedKey.length != keySize) {
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    if (!userExists(user)) {
      sendResponse(outStream, ServerResponse.NOUSER);
      return;
    }

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      sendResponse(outStream, ServerResponse.NOHM);
      return;
    }

    Home home = getHome(homeName);
    if (!home.owner.equals(currentUser)) {
      sendResponse(outStream, ServerResponse.NOPERM);
      return;
    }

    storeEncryptedSectionKey(homeName, section, user, encryptedKey);
    sendResponse(outStream, ServerResponse.OK);
  }

  /**
   * Verifica se um utilizador está registado no ficheiro de utilizadores.
   */
  private static boolean userExists(String user) {
    File usersFile = new File(USERS_FILE);
    ensureParentDir(usersFile);
    FileIntegraty.ensureFileAndHashOrExit(USERS_FILE);
    FileIntegraty.verifyIntegrityOrExit(USERS_FILE);
    try (BufferedReader reader = new BufferedReader(new FileReader(usersFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
          continue;
        }
        if (parts[0].equals(user)) {
          return true;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  /**
   * Indica se uma casa existe no cache em memória.
   */
  public static boolean findHome(String home) {
    return getHome(home) != null;
  }

  /**
   * Cria uma nova casa e associa o utilizador atual como owner.
   */
  public void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String name = inStream.readUTF().trim();
    if (name.isBlank()) {
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    loadHomesFromDisk();
    String key = name;
    if (homes.containsKey(key)) {
      System.out.println("Home '" + name + "' already exists.");
      sendResponse(outStream, ServerResponse.NOK);
      return;
    }

    Home record = new Home(name, currentUser);
    // inicializa todos os contadores de secção a 1 (próximo id disponível)
    homes.put(key, record);
    saveHomesToDisk();

    System.out.println("Creating home '" + name + "'");
    sendResponse(outStream, ServerResponse.OK);

    try {
      receiveAndStoreSectionKeysForCreate(inStream, name, currentUser);
      outStream.writeUTF(ServerResponse.OK.name());
    } catch (IOException e) {
      outStream.writeUTF(ServerResponse.NOK.name());
      throw e;
    }

    outStream.flush();
  }

  /**
   * Guarda no disco uma chave de secção cifrada para um utilizador.
   *
   * @param homeName nome da casa
   * @param section secção lógica (W/K/L/B ou nome equivalente)
   * @param owner utilizador a quem a chave pertence
   * @param encryptedKey bytes da chave já cifrada
   * @throws IOException se ocorrer erro de escrita
   */
  private void storeEncryptedSectionKey(String homeName, String section, String owner, byte[] encryptedKey)
      throws IOException {

    File dir = new File(SECTION_KEYS_DIR);
    if (!dir.exists()) {
      dir.mkdirs();
    }

    String sectionCode = sectionKeyCode(section);
    File keyFile = new File(dir, "key." + homeName + "." + sectionCode + "." + owner);
    Files.write(keyFile.toPath(), encryptedKey);

  }

  /**
   * Converte identificador de secção para o código de um carácter usado em disco.
   *
   * @param section identificador textual da secção
   * @return código curto da secção
   * @throws IOException se o identificador for inválido
   */
  private String sectionKeyCode(String section) throws IOException {
    Section parsedSection = Section.parseSection(section);
    if (parsedSection == null) {
      throw new IOException("Invalid section identifier");
    }
    return String.valueOf(parsedSection.name().charAt(0));
  }

  /**
   * Localiza no disco a chave cifrada de uma secção para um utilizador.
   *
   * @param homeName casa alvo
   * @param section secção pedida
   * @param user utilizador dono da chave
   * @return ficheiro da chave cifrada, ou {@code null} se não existir
   * @throws IOException se ocorrer erro de validação de secção
   */
  private File findEncryptedSectionKey(String homeName, String section, String user) throws IOException {
    Section parsedSection = Section.parseSection(section);
    if (parsedSection == null) {
      return null;
    }

    String sectionCode = String.valueOf(parsedSection.name().charAt(0));
    File shortNameFile = new File(SECTION_KEYS_DIR, "key." + homeName + "." + sectionCode + "." + user);
    if (shortNameFile.exists()) {
      return shortNameFile;
    }

    File legacyNameFile = new File(SECTION_KEYS_DIR, "key." + homeName + "." + parsedSection.name() + "." + user);
    if (legacyNameFile.exists()) {
      return legacyNameFile;
    }

    return null;
  }

  /**
   * Extrai o código de secção a partir do identificador do dispositivo.
   *
   * @param device identificador do dispositivo
   * @return primeira letra maiúscula do dispositivo
   * @throws IOException se o identificador for inválido
   */
  private String deviceSectionCode(String device) throws IOException {
    if (device == null || device.isBlank()) {
      throw new IOException("Invalid device identifier");
    }
    return String.valueOf(Character.toUpperCase(device.charAt(0)));
  }

  /**
   * Recebe do cliente as chaves de secção iniciais de uma casa recém-criada e
   * persiste cada uma em disco.
   *
   * @param inStream stream de entrada do cliente
   * @param homeName casa criada
   * @param owner owner da casa
   * @throws IOException se o payload recebido for inválido ou incompleto
   */
  private void receiveAndStoreSectionKeysForCreate(ObjectInputStream inStream, String homeName, String owner)
      throws IOException {

  int sectionCount = inStream.readInt();
  if (sectionCount <= 0) {
    throw new IOException("Invalid number of sections received");
  }

  for (int i = 0; i < sectionCount; i++) {
    String section = inStream.readUTF().trim();
    if (section.isBlank()) {
      throw new IOException("Invalid section identifier");
    }

    int keySize = inStream.readInt();
    if (keySize <= 0) {
      throw new IOException("Invalid encrypted section key size");
    }

    byte[] encryptedKey = inStream.readNBytes(keySize);
    if (encryptedKey.length != keySize) {
      throw new IOException("Incomplete encrypted section key received");
    }

    storeEncryptedSectionKey(homeName, section, owner, encryptedKey);
  }
}

  /**
   * Devolve o owner associado a uma casa.
   */
  public static String findHomeOwner(String homeName) {
    Home record = getHome(homeName);
    return record == null ? null : record.owner;
  }

  /**
   * Recarrega o cache das casas a partir do ficheiro. Chamado uma vez no load
   * inicial; se o ficheiro não existir, o cache fica vazio.
   */
  private void loadHomesFromDisk() throws IOException {
    ensureHomesFile();
    withDecryptedProtectedFile(HOMES_FILE, () -> {
      homes.clear();
      homes.putAll(Home.loadHomesFromDisk(HOMES_FILE));
      return null;
    });
  }

  /**
   * Persiste o cache atual para o ficheiro homes.txt de uma só vez.
   */
  private void saveHomesToDisk() throws IOException {
    ensureHomesFile();
    withDecryptedProtectedFile(HOMES_FILE, () -> {
      Home.saveHomesToDisk(homes.values(), HOMES_FILE);
      FileIntegraty.updateHashOrExit(HOMES_FILE);
      return null;
    });
  }


  /**
   * Obtém uma casa do cache, ignorando maiúsculas/minúsculas.
   */
  private static Home getHome(String name) {
    if (name == null)
      return null;
    return homes.get(name);
  }

  /**
   * Garante que o ficheiro homes.txt está na pasta do servidor e que a pasta
   * existe.
   * Se existir um ficheiro legacy na raiz, tenta movê-lo para a localização
   * correta.
   */
  private static void ensureHomesFile() {
    File homesFile = new File(HOMES_FILE);
    ensureParentDir(homesFile);

    if (homesFile.exists()) {
      return;
    }

    File legacy = new File("homes.txt");
    if (!legacy.equals(homesFile) && legacy.exists()) {
      try {
        Files.move(legacy.toPath(), homesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        // fallback: o bootstrap do ficheiro/hash é feito em ensureFileAndHashOrExit
        e.printStackTrace();
      }
    }
  }

  /**
   * Atualiza o ficheiro que guarda o último estado conhecido de cada dispositivo.
   */
  private void updateLastStatesFile(String homeName, String device, String encryptedValue, String user) {
    File file = new File(DEVICES_FILE);
    ensureParentDir(file);
    Map<String, String> latest = new LinkedHashMap<>();
    try {
      withDecryptedProtectedFile(DEVICES_FILE, () -> {
        if (file.exists()) {
          try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.isBlank())
                continue;
              String[] parts = line.split(";");
              if (parts.length < 2)
                continue;
              latest.put(parts[0] + ";" + parts[1], line);
            }
          }
        }
        String key = homeName + ";" + device;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String newLine = String.format("%s;%s;%s;%s;%s", homeName, device, encryptedValue, timestamp, user);
        latest.put(key, newLine);

        try (FileWriter writer = new FileWriter(file, false)) {
          for (String line : latest.values()) {
            writer.write(line);
            writer.write(System.lineSeparator());
          }
        }
        FileIntegraty.updateHashOrExit(DEVICES_FILE);
        return null;
      });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executa uma operação sobre um ficheiro protegido do servidor com gestão de
   * integridade e cifragem.
   *
   * @param <T> tipo de retorno da operação
   * @param filePath caminho do ficheiro protegido
   * @param operation operação a executar
   * @return resultado da operação
   * @throws IOException se ocorrer erro durante a operação protegida
   */
  private <T> T withDecryptedProtectedFile(String filePath, Callable<T> operation) throws IOException {
    FileIntegraty.ensureFileAndHashOrExit(filePath);
    try {
      return ProtectedFileUtils.withDecryptedFile(filePath, pbeKey, operation);
    } catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new IOException(e);
    }
  }

  /**
   * Envia resposta simples ao cliente e força flush da stream.
   *
   * @param outStream stream de saída
   * @param response resposta a enviar
   * @throws IOException se falhar a escrita
   */
  private static void sendResponse(ObjectOutputStream outStream, ServerResponse response) throws IOException {
    outStream.writeUTF(response.name());
    outStream.flush();
  }

  /**
   * Garante que a diretoria-mãe de um ficheiro existe.
   *
   * @param file ficheiro alvo
   */
  private static void ensureParentDir(File file) {
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
  }

}
