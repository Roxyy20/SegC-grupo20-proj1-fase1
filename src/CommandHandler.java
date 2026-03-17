import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandHandler {

  private static final String HOMES_FILE = "homes.txt";
  private static final Map<String, Home> homes = new LinkedHashMap<>();
  private final String currentUser;

  // static {
  // loadHomesFromDisk();
  // }

  public CommandHandler(String user) {
    currentUser = user;
  }

  // Home
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

    List<String> lines = home.requestDeviceLog(device);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  // Home
  public void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String homeName = inStream.readUTF();

    loadHomesFromDisk();
    if (!findHome(homeName)) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    Home home = getHome(homeName);

    File devicesFile = new File("devices.txt");
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
        if (parts.length < 5)
          continue;
        if (!parts[0].equalsIgnoreCase(homeName))
          continue;
        String device = parts[1];
        ServerResponse perm = home.canAccessDevice(device, currentUser);
        if (perm != ServerResponse.OK)
          continue;
        if (sb.length() > 0)
          sb.append("|");
        // keep full line so client sees home;device;value;res;timestamp
        sb.append(line);
      }
    }

    if (sb.length() == 0) {
      outStream.writeUTF(ServerResponse.NODATA.name());
    } else {
      outStream.writeUTF(ServerResponse.OK.name() + ";" + sb.toString());
    }
    outStream.flush();
  }

  // Home
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
    saveHomesToDisk();

    outStream.writeUTF(res.name());
    outStream.flush();
    if (res == ServerResponse.OK) { 
      File deviceLog = new File(homeName + "_" + device + ".txt");
      try (FileWriter writer = new FileWriter(deviceLog, true)) {
        writer.write(String.format("%s;%d;%s%n", currentUser, value, res.name()));
      } catch (IOException e) {
        e.printStackTrace();
      }
      updateLastStatesFile(homeName, device, value, res);
    }
    System.out.printf("ligando dispositivo %s em %s por %d\n", device, home, value);
  }

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

    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + home);
    outStream.writeUTF(res.name());
    outStream.flush();
  }

  private static Boolean userExists(String user) {
    File usersFile = new File("users.txt");
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
        if (parts[0].equalsIgnoreCase(user)) {
          return true;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  public static Boolean findHome(String home) {
    return getHome(home) != null;
  }

  public void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String name = inStream.readUTF().trim();
    if (name.isBlank()) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    loadHomesFromDisk();
    String key = name.toLowerCase();
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

  public static String findHomeOwner(String homeName) {
    Home record = getHome(homeName);
    return record == null ? null : record.owner;
  }

  /**
   * Recarrega o cache das casas a partir do ficheiro. Chamado uma vez no load
   * inicial; se o ficheiro não existir, o cache fica vazio.
   */
  private static void loadHomesFromDisk() {
    homes.clear();
    homes.putAll(Home.loadHomesFromDisk(HOMES_FILE));
  }

  /**
   * Persiste o cache atual para o ficheiro homes.txt de uma só vez.
   */
  private static void saveHomesToDisk() {
    Home.saveHomesToDisk(homes.values(), HOMES_FILE);
  }

  private static Home getHome(String name) {
    if (name == null)
      return null;
    return homes.get(name.toLowerCase());
  }

  private void updateLastStatesFile(String homeName, String device, int value, ServerResponse res) {
    File file = new File("devices.txt");
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
          latest.put(parts[0].toLowerCase() + ";" + parts[1].toLowerCase(), line);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    String key = homeName.toLowerCase() + ";" + device.toLowerCase();
    String newLine = String.format("%s;%s;%d;%s", homeName, device, value, res.name());
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
