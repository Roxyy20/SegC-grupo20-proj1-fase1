import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandHandler {

  private static final String HOMES_FILE = "homes.txt";
  private static final Map<String, Home> homes = new LinkedHashMap<>();
  private String currentUser;

  static {
    loadHomesFromDisk();
  }

  public CommandHandler(String user) {
    currentUser = user;
  }

  public void requestDeviceLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    System.out.printf("Entregando log file do dispositivo %s casa\n", device, home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    System.out.printf("Entregando log file da casa %s\n", home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public void setStateOrTimer(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    int value = inStream.readInt();

    if (value < 0 || value > 600 || (value > 0 && value < 1)) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }
    if (getHome(home) == null) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    int deviceCheck = findDeviceAndPermission(device, home);
    if (deviceCheck == 1) {
      outStream.writeUTF(ServerResponse.NOD.name());
      outStream.flush();
      return;
    } else if (deviceCheck == 2) {
      outStream.writeUTF(ServerResponse.NOPERM.name());
      outStream.flush();
      return;
    }
    // TODO implementar o txt
    System.out.printf("ligando dispositivo %s em %s por %i\n", device, home, value);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private int findDeviceAndPermission(String device, String home) {
    Home record = getHome(home);
    if (record == null) {
      return 1;
    }

    if (device == null || device.length() < 2) {
      return 1;
    }
    char sectionChar = device.charAt(0);
    String section = String.valueOf(sectionChar).toUpperCase();
    int num;
    try {
      num = Integer.parseInt(device.substring(1));
    } catch (NumberFormatException e) {
      return 1;
    }

    Integer nextId = record.creationCounters.get(section); // guarda o próximo id livre
    if (nextId == null || num < 1 || num >= nextId) {
      return 1; // dispositivo não existe
    }

    List<String> userPerms = record.permissions.get(currentUser);
    if (userPerms == null) {
      return 2;
    }
    for (String p : userPerms) {
      if (p.equalsIgnoreCase(section)) {
        return 0;
      }
    }
    return 2;
  }

  public void registerDevice(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String sectionCode = inStream.readUTF();

    Home record = getHome(home);
    if (record == null) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    if (!record.owner.equals(currentUser)) {
      outStream.writeUTF(ServerResponse.NOPERM.name());
      outStream.flush();
      return;
    }

    Section section = Section.parseSection(sectionCode);
    if (section == null) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    String key = String.valueOf(section.name().charAt(0));
    // contador representa o próximo id a usar (começa em 1)
    int nextId = record.creationCounters.getOrDefault(key, 1);
    record.creationCounters.put(key, nextId + 1); // incrementa para o próximo pedido
    saveHomesToDisk();

    System.out.printf("Adding device %s%d to section %s in home %s%n", key, nextId, section.name(), home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String user = inStream.readUTF();
    String home = inStream.readUTF();
    String permission = inStream.readUTF();

    int permHomeValid = validatePermissionAndHome(permission, home);
    if (permHomeValid == 1) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    if (permHomeValid == 2) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }
    Home record = getHome(home);
    if (record == null) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    if (!record.owner.equals(currentUser)) {
      outStream.writeUTF(ServerResponse.NOPERM.name());
      outStream.flush();
      return;
    }

    Boolean userCreated = userExists(user);
    if (!userCreated) {
      outStream.writeUTF(ServerResponse.NOUSER.name());
      outStream.flush();
      return;
    }

    String permToAdd = permission;
    if (permission.equalsIgnoreCase("all")) {
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, Integer> entry : record.creationCounters.entrySet()) {
        if (entry.getValue() > 0) {
          if (sb.length() > 0)
            sb.append(",");
          sb.append(entry.getKey());
        }
      }
      permToAdd = sb.toString();
    }

    List<String> existing = record.permissions.getOrDefault(user, new ArrayList<>());
    for (String add : permToAdd.split(",")) {
      if (add.isBlank())
        continue;
      if (!existing.contains(add)) {
        existing.add(add);
      }
    }
    record.permissions.put(user, existing);
    saveHomesToDisk();

    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private int validatePermissionAndHome(String permission, String home) {
    Home record = getHome(home);
    if (record == null) {
      return 1;
    }
    if (permission.equalsIgnoreCase("all")) {
      return record.creationCounters.isEmpty() ? 2 : 0;
    }
    String key = permission.substring(0, 1).toUpperCase();
    if (record.creationCounters.containsKey(key)) {
      return 0;
    }
    return 2;
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
    String name = inStream.readUTF();
    if (name.isBlank()) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    if (homes.containsKey(name.toLowerCase())) {
      System.out.println("Home '" + name + "' already exists.");
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    Home record = new Home(name, currentUser);
    // inicializa todos os contadores de secção a 1 (próximo id disponível)
    for (Section s : Section.values()) {
      String key = String.valueOf(s.name().charAt(0));
      record.creationCounters.put(key, 1);
    }
    homes.put(name.toLowerCase(), record);
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

}
