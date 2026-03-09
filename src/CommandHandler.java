import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandHandler {

    private static final String HOMES_FILE = "homes.txt";
    private static String currentUser;

    public CommandHandler(String user) {
        currentUser = user;
    }

    public static void requestDeviceLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    System.out.printf("Entregando log file do dispositivo %s casa\n", device, home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public static void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    System.out.printf("Entregando log file da casa %s\n", home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public static void setStateOrTimer(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    int value = inStream.readInt();

    if(value < 0 || value > 600 || (value > 0 && value <1)){
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }
    if(!findHome(home)){
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
      //TODO implementar o txt
    System.out.printf("ligando dispositivo %s em %s por %i\n", device, home, value);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private static int findDeviceAndPermission(String device, String home) {
    File homesFile = new File(HOMES_FILE);
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2 || !parts[0].equalsIgnoreCase(home)) {
          continue;
        }
        Map<String, Integer> counters = countersToMap(parts.length > 2 ? parts[2] : "");
        if (device.length() < 2) {
          return 1;
        }
        char sectionChar = device.charAt(0);
        String section = String.valueOf(sectionChar);
        int num;
        try {
          num = Integer.parseInt(device.substring(1));
        } catch (NumberFormatException e) {
          return 1;
        }
        if (num < 1 || !counters.containsKey(section) || counters.get(section) < num) {
          return 1;
        }
        String userPerms = null;
        for (int i = 3; i < parts.length; i++) {
          if (parts[i].startsWith(currentUser + "=")) {
            userPerms = parts[i].substring((currentUser + "=").length());
            break;
          }
        }
        if (userPerms == null) {
          return 2;
        }
        String[] perms = userPerms.split(",");
        for (String p : perms) {
          if (p.equals(section)) {
            return 0;
          }
        }
        return 2;
      }
    } catch (IOException e) {
      e.printStackTrace();
      return 1;
    }
    return 1;
  }

  public static void registerDevice(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String sectionCode = inStream.readUTF();

    String owner = findHomeOwner(home);
    File homesFile = new File(HOMES_FILE);
    if (owner == null || !homesFile.exists()) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }
    if (!owner.equals(currentUser)) {
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
    
    //atualiza a linha da casa
    List<String> lines = new ArrayList<>();
    boolean updated = false;
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
          lines.add(line);
          continue;
        }
        if (!parts[0].equalsIgnoreCase(home)) {
          lines.add(line);
        } else {
          Map<String, Integer> counters = countersToMap(parts.length > 2 ? parts[2] : "");
          String key = String.valueOf(section.name().charAt(0));
          int next = counters.getOrDefault(key, 0) + 1;
          counters.put(key, next);
          String counterStr = countersToString(counters);
          StringBuilder newLine = new StringBuilder(parts[0]).append(":").append(parts[1]);
          if (!counterStr.isEmpty()) {
            newLine.append(":").append(counterStr);
          }
          if (parts.length > 3) {
            for (int i = 3; i < parts.length; i++) {
              newLine.append(":").append(parts[i]);
            }
          }
          lines.add(newLine.toString());
          updated = true;
        }
      }
    }

    if (!updated) {
      outStream.writeUTF(ServerResponse.NOHM.name());
      outStream.flush();
      return;
    }

    try (FileWriter writer = new FileWriter(homesFile, false)) {
      for (String l : lines) {
        writer.write(l + System.lineSeparator());
      }
        writer.close();
    }

    System.out.printf("Adding device to section %s in home %s%n", section.name(), home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public static void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
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
    String owner = findHomeOwner(home);
    if (!owner.equals(currentUser)) {
      outStream.writeUTF(ServerResponse.NOPERM.name());
      outStream.flush();
      return;
    }
    
    Boolean userCreated = userExists(user);
    if(!userCreated){
        outStream.writeUTF(ServerResponse.NOUSER.name());
        outStream.flush();
        return;
    }

    List<String> lines = new ArrayList<>();
    File homesFile = new File(HOMES_FILE);
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (!parts[0].equalsIgnoreCase(home)) {
          lines.add(line);
        } else {
          //caso a permissão seja "all"
          String permToAdd = permission;
          if (permission.equalsIgnoreCase("all")) {
            String sectionList = "";
            if (parts.length >= 3 && !parts[2].isEmpty()) {
              String[] counters = parts[2].split(",");
              StringBuilder sb = new StringBuilder();
              for (String cnt : counters) {
                String[] kv = cnt.split("=");
                if (kv.length == 2 && !kv[1].equals("0")) {
                  if (sb.length() > 0) sb.append(",");
                  sb.append(kv[0]);
                }
              }
              sectionList = sb.toString();
            }
            permToAdd = sectionList;
          }
          //verifica se já existe este user com permissoes
          String prefix = user + "=";
          if (line.contains(":" + prefix) || line.startsWith(prefix)) {
            String[] segments = line.split(":");
            StringBuilder rebuilt = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
              if (i > 0) {
                rebuilt.append(":");
              }
              if (segments[i].startsWith(prefix)) {
                String perms = segments[i].substring(prefix.length());
                // merge existing with new permissions
                String[] existing = perms.isEmpty() ? new String[0] : perms.split(",");
                List<String> merged = new ArrayList<>();
                for (String e : existing) {
                  if (!e.isBlank()) merged.add(e);
                }
                for (String add : permToAdd.split(",")) {
                  if (!merged.contains(add) && !add.isBlank()) {
                    merged.add(add);
                  }
                }
                String joined = String.join(",", merged);
                rebuilt.append(user).append("=").append(joined);
              } else {
                rebuilt.append(segments[i]);
              }
            }
            lines.add(rebuilt.toString());
          } else {
            lines.add(line + ":" + user + "=" + permToAdd);
          }
        }
      }
    }

    try (FileWriter writer = new FileWriter(homesFile, false)) {
      for (String l : lines) {
        writer.write(l + System.lineSeparator());
      }
      writer.close();
    }

    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private static int validatePermissionAndHome(String permission, String home) {
    int valid = 1;
    File homesFile = new File(HOMES_FILE);
    if (!homesFile.exists()) {
      return valid;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
          continue;
        }
        if (parts[0].equalsIgnoreCase(home)) {
          if (parts.length < 3 || parts[2].isEmpty()) {
            return 2;
          }
          if (permission.equalsIgnoreCase("all")) {
              return 0;
          }
          valid = 2;
          String perms = parts[2];
          String[] permParts = perms.split(",");
          for (String p : permParts) {
            String[] kv = p.split("=");
            if (kv.length != 2) {
              continue;
            }
            if (kv[0].equalsIgnoreCase(permission)) {
              valid = 0;
              break;
            }
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return valid;
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
    File homesFile = new File(HOMES_FILE);
    if (!homesFile.exists()) {
      return false;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
          continue;
        }
        if (parts[0].equalsIgnoreCase(home)) {
          return true;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  public static void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String name = inStream.readUTF();
    if (name.isBlank()) {
      outStream.writeUTF(ServerResponse.NOK.name());
      outStream.flush();
      return;
    }

    File homesFile = new File(HOMES_FILE);
    if (!homesFile.exists()) {
      homesFile.createNewFile();
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      //verifica se a casa já existe
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length > 0 && parts[0].equalsIgnoreCase(name)) {
          System.out.println("Home '" + name + "' already exists.");
          outStream.writeUTF(ServerResponse.NOK.name());
          outStream.flush();
          return;
        }
      }
    }

    try (FileWriter writer = new FileWriter(homesFile, true)) {
      writer.write(name + ":" + currentUser + System.lineSeparator());
      writer.close();
    }

    System.out.println("Creating home '" + name + "'");
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  public static String findHomeOwner(String homeName) throws IOException {
    File homesFile = new File(HOMES_FILE);
    if (!homesFile.exists()) {
      return null;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(":");
        if (parts.length < 2) {
          continue;
        }
        if (parts[0].equalsIgnoreCase(homeName)) {
          return parts[1];
        }
      }
    }
    return null;
  }

  public static Map<String, Integer> countersToMap(String countersStr) {
    if (countersStr == null) {
      return new HashMap<>();
    }
    Map<String, Integer> counters = new HashMap<>();
    String[] parts = countersStr.split(",");
    for (String p : parts) {
      String[] kv = p.split("=");
      if (kv.length != 2) {
        continue;
      }
      try {
        counters.put(kv[0], Integer.valueOf(kv[1]));
      } catch (NumberFormatException e) {
        counters.put(kv[0], 0);
      }
    }
    return counters;
  }

  public static String countersToString(Map<String, Integer> counters) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Section s : Section.values()) {
      String key = String.valueOf(s.name().charAt(0));
      int val = counters.getOrDefault(key, 0);
      if (val <= 0) {
        continue; // só guardamos secções com dispositivos
      }
      if (!first) {
        sb.append(",");
      }
      sb.append(key).append("=").append(val);
      first = false;
    }
    return sb.toString();
  }

}
