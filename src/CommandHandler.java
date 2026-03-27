import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trata comandos recebidos de um cliente autenticado, garantindo validações de
 * permissões e persistindo a informação necessária em disco.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class CommandHandler {

  private static final String HOMES_FILE = "server/homes.txt";
  private static final Map<String, Home> homes = new LinkedHashMap<>();
  private final String currentUser;

  /**
   * Associa o handler a um utilizador autenticado.
   */
  public CommandHandler(String user) {
    currentUser = user;
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
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }

    Home home = getHome(homeName);

    ServerResponse perm = home.canAccessDevice(device, currentUser);
    if (!ServerResponse.OK.equals(perm)) {
      outStream.writeUTF(perm.name());
      outStream.flush();
      return;
    }

    File deviceLogFile = home.requestDeviceLog(device);
    if (!deviceLogFile.exists()) {
      outStream.writeUTF(ServerResponse.NODATA.name());
      outStream.flush();
      return;
    }
    outStream.writeUTF(ServerResponse.OK.name());
    byte[] data = java.nio.file.Files.readAllBytes(deviceLogFile.toPath());
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
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    Home home = getHome(homeName);

    ServerResponse housePerm = home.canAccessHome(currentUser);
    if (ServerResponse.NOPERM.equals(housePerm)) {
      outStream.writeUTF(housePerm.name());
      outStream.flush();
      return;
    }

    File devicesFile = new File("server/devices.txt");
    File parent = devicesFile.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    if (!devicesFile.exists()) {
      outStream.writeUTF(ServerResponse.NODATA.name());
      outStream.flush();
      return;
    }

    StringBuilder sb = new StringBuilder();
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
        String device = parts[1];
        ServerResponse perm = home.canAccessDevice(device, currentUser);
        if (perm != ServerResponse.OK)
          continue;
        if (sb.length() > 0)
          sb.append(System.lineSeparator());
        // keep full line so client sees home;device;value;res;timestamp
        sb.append(line);
      }
    }

    if (sb.length() == 0) {
      outStream.writeUTF(ServerResponse.NODATA.name());
    } else {
      outStream.writeUTF(ServerResponse.OK.name());

      // converter string para bytes
      byte[] data = sb.toString().getBytes();

      // enviar tamanho (LONG)
      outStream.writeLong(data.length);

      // enviar conteúdo
      outStream.write(data);
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
    int value = inStream.readInt();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.setStateOrTimer(device, value, currentUser);

    outStream.writeUTF(res.name());
    outStream.flush();
    if (res == ServerResponse.OK) {
      File deviceLog = new File("server/" + homeName + "_" + device + ".csv");
      File parent = deviceLog.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }
      try (FileWriter writer = new FileWriter(deviceLog, true)) {
        Date currentUserData = new Date();
        String data = new SimpleDateFormat("yyyy-MM-dd").format(currentUserData);
        String time = new SimpleDateFormat("HH:mm:ss").format(currentUserData);
        writer.write(String.format("%s,%s,%s,%d", data, time, currentUser, value));
        writer.write(System.lineSeparator());
      } catch (IOException e) {
        e.printStackTrace();
      }
      updateLastStatesFile(homeName, device, value, currentUser);
    }
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
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.registerDevice(sectionCode, currentUser);
    saveHomesToDisk();

    outStream.writeUTF(res.name());
    outStream.flush();
  }

  /**
   * Concede permissões de acesso a um utilizador para uma casa e secção.
   */
  public synchronized void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String user = inStream.readUTF();
    String homeName = inStream.readUTF();
    String permission = inStream.readUTF();

    if (!userExists(user)) {
      outStream.writeUTF(ServerResponse.NOUSER.name());
      outStream.flush();
      return;
    }

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    Home home = getHome(homeName);

    ServerResponse res = home.addPermission(user, permission, currentUser);
    saveHomesToDisk();

    // System.out.println("Usuario " + user + " adicionado com permissão " +
    // permission + " à " + home);
    outStream.writeUTF(res.name());
    outStream.flush();
  }

  /**
   * Verifica se um utilizador está registado no ficheiro de utilizadores.
   */
  private static Boolean userExists(String user) {
    File usersFile = new File("server/users.txt");
    File parent = usersFile.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    if (!usersFile.exists()) {
      return false;
    }
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
  public static Boolean findHome(String home) {
    return getHome(home) != null;
  }

  /**
   * Cria uma nova casa e associa o utilizador atual como owner.
   */
  public void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String name = inStream.readUTF().trim();
    if (name.isBlank()) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    loadHomesFromDisk();
    String key = name;
    if (homes.containsKey(key)) {
      System.out.println("Home '" + name + "' already exists.");
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    Home record = new Home(name, currentUser);
    // inicializa todos os contadores de secção a 1 (próximo id disponível)
    homes.put(key, record);
    saveHomesToDisk();

    System.out.println("Creating home '" + name + "'");
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
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
  private static void loadHomesFromDisk() {
    ensureHomesFile();
    homes.clear();
    homes.putAll(Home.loadHomesFromDisk(HOMES_FILE));
  }

  /**
   * Persiste o cache atual para o ficheiro homes.txt de uma só vez.
   */
  private static void saveHomesToDisk() {
    ensureHomesFile();
    Home.saveHomesToDisk(homes.values(), HOMES_FILE);
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
    File parent = homesFile.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    if (homesFile.exists()) {
      return;
    }

    File legacy = new File("homes.txt");
    if (!legacy.equals(homesFile) && legacy.exists()) {
      try {
        Files.move(legacy.toPath(), homesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (IOException e) {
        // fall through to create empty file
        e.printStackTrace();
      }
    }

    try {
      homesFile.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Atualiza o ficheiro que guarda o último estado conhecido de cada dispositivo.
   */
  private void updateLastStatesFile(String homeName, String device, int value, String user) {
    File file = new File("server/devices.txt");
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    Map<String, String> latest = new LinkedHashMap<>();
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
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    String key = homeName + ";" + device;
    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    String newLine = String.format("%s;%s;%d;%s;%s", homeName, device, value, timestamp, user);
    latest.put(key, newLine);

    try (FileWriter writer = new FileWriter(file, false)) {
      for (String line : latest.values()) {
        writer.write(line);
        writer.write(System.lineSeparator());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}
