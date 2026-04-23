import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modelo de dados de uma casa, com respetivo owner, contadores de dispositivos
 * por secção e permissões por utilizador.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class Home {
  final String name;
  final String owner;
  final Map<String, Integer> creationCounters = new HashMap<String, Integer>();
  final Map<String, List<String>> permissions = new HashMap<String, List<String>>();

  /**
   * Cria uma nova casa, inicializando o contador de dispositivos por secção.
   */
  Home(String name, String owner) {
    this.name = name;
    this.owner = owner;

    for (Section s : Section.values()) {
      String key = String.valueOf(s.name().charAt(0));
      this.creationCounters.putIfAbsent(key, 1);
    }
  }

  /**
   * Construtor utilizado na leitura a partir de ficheiro persistido.
   */
  private Home(String name, String owner, String counters, String... permissions) {
    this.name = name;
    this.owner = owner;
    this.creationCounters.putAll(countersToMap(counters));

    for (String perm : permissions) {
      int idx = perm.indexOf("=");
      if (idx <= 0)
        return;
      String user = perm.substring(0, idx);
      String permList = perm.substring(idx + 1);
      List<String> sections = new ArrayList<>();
      if (!permList.isBlank()) {
        for (String p : permList.split(",")) {
          if (!p.isBlank())
            sections.add(p);
        }
      }
      this.permissions.put(user, sections);
    }
  }

  // returns list with lines
  /**
   * Devolve o ficheiro de log associado a um dispositivo.
   */
  public File requestDeviceLog(String device) {
    File deviceLogFile = new File("server/" + name + "_" + device + ".csv");
    ensureParentDir(deviceLogFile);
    return deviceLogFile;
  }

  // returns list of commands
  /**
   * Placeholder para entrega de log completo da casa.
   */
  public List<String> requestHomeLog() {
    System.out.printf("Entregando log file da casa %s\n", name);
    return null;
  }

  /**
   * Verifica se o utilizador tem acesso ao dispositivo.
   */
  public ServerResponse canAccessDevice(String device, String user) {
    return findDeviceAndPermission(device, user);
  }

  /**
   * Valida e aplica alteração de estado/temporização a um dispositivo.
   */
  public ServerResponse setStateOrTimer(String device, int value, String user) {
    if (value < 0 || value > 600)
      return ServerResponse.NOK;

    ServerResponse perm = findDeviceAndPermission(device, user);
    if (!ServerResponse.OK.equals(perm))
      return perm;

    return ServerResponse.OK;
  }

  /**
   * Regista um novo dispositivo numa secção, apenas permitido ao owner.
   */
  public ServerResponse registerDevice(String sectionCode, String currentUser) {
    if (!owner.equals(currentUser))
      return ServerResponse.NOPERM;

    Section section = Section.parseSection(sectionCode);
    if (section == null)
      return ServerResponse.NOK;

    String key = String.valueOf(section.name().charAt(0));
    int nextId = creationCounters.getOrDefault(key, 1);
    creationCounters.put(key, nextId + 1);
    System.out.printf("Adding device %s%d to section %s in home %s%n", key, nextId, section.name(), name);
    return ServerResponse.OK;
  }

  /**
   * Atribui permissões de acesso a secções de uma casa a um utilizador.
   */
  public ServerResponse addPermission(String user, String permission, String currentUser) {
    if (!owner.equals(currentUser))
      return ServerResponse.NOPERM;
    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + name);

    if (permission.equals("all")) {
      List<String> perms = new ArrayList<>();
      for (Section s : Section.values()) {
        String code = String.valueOf(s.name().charAt(0));
        if (!perms.contains(code))
          perms.add(code);
      }
      permissions.put(user, perms);
    } else {
      Section section = Section.parseSection(permission);
      if (section == null)
        return ServerResponse.NOK; // secção inexistente

      List<String> perms = permissions.getOrDefault(user, new ArrayList<>());

      // Se o utilizador tinha ALL (ou lista completa), substitui pelas novas
      // permissões
      if (perms.size() == Section.values().length) {
        perms = new ArrayList<>();
      }

      String code = String.valueOf(section.name().charAt(0));
      if (!perms.contains(code))
        perms.add(code);
      permissions.put(user, perms);
    }
    return ServerResponse.OK;
  }

  /**
   * Verifica se o utilizador tem acesso global à casa (apenas owner neste
   * momento).
   */
  public ServerResponse canAccessHome(String user) {
    if (owner.equals(user))
      return ServerResponse.OK;

    List<String> userPerms = permissions.get(user);
    if (userPerms == null)
      return ServerResponse.NOPERM;

    return ServerResponse.OK;
  }

  /**
   * Verifica a existência do dispositivo e se o utilizador tem permissão para
   * o controlar.
   */
  private ServerResponse findDeviceAndPermission(String device, String user) {
    if (device == null || device.length() < 2)
      return ServerResponse.NOD;
    String section = String.valueOf(device.charAt(0)).toUpperCase();
    int num;
    try {
      num = Integer.parseInt(device.substring(1));
    } catch (NumberFormatException e) {
      return ServerResponse.NOD;
    }

    Integer nextId = creationCounters.get(section);
    if (nextId == null || num < 1 || num >= nextId)
      return ServerResponse.NOD;

    // owner can always control existing devices
    if (owner.equals(user))
      return ServerResponse.OK;

    List<String> userPerms = permissions.get(user);
    if (userPerms == null)
      return ServerResponse.NOPERM;

    for (String p : userPerms)
      if (p.equals(section) || p.equals("all"))
        return ServerResponse.OK;

    return ServerResponse.NOPERM;

  }

  /**
   * Converte a string persistida dos contadores para um mapa.
   */
  private static Map<String, Integer> countersToMap(String countersStr) {
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

  /**
   * Converte o mapa de contadores para formato string persistente.
   */
  private static String countersToString(Map<String, Integer> counters) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Section s : Section.values()) {
      String key = String.valueOf(s.name().charAt(0));
      int val = counters.getOrDefault(key, 0);
      if (val <= 0) {
        continue;
      }
      if (!first) {
        sb.append(",");
      }
      sb.append(key).append("=").append(val);
      first = false;
    }
    return sb.toString();
  }

  /**
   * Guarda em disco a coleção de casas fornecida.
   */
  public synchronized static void saveHomesToDisk(Collection<Home> homes, String filename) {
    File homesFile = new File(filename);
    ensureParentDir(homesFile);
    try (FileWriter writer = new FileWriter(homesFile, false)) {
      for (Home home : homes) {
        StringBuilder sb = new StringBuilder();
        sb.append(home.name).append(":").append(home.owner);
        String countersStr = countersToString(home.creationCounters);
        boolean hasCounters = !countersStr.isEmpty();
        if (hasCounters) {
          sb.append(":").append(countersStr);
        }
        boolean firstPerm = true;
        for (Map.Entry<String, List<String>> entry : home.permissions.entrySet()) {
          if (firstPerm) {
            sb.append(":");
            firstPerm = false;
          } else {
            sb.append(":");
          }
          sb.append(entry.getKey()).append("=");
          sb.append(String.join(",", entry.getValue()));
        }
        writer.write(sb.toString());
        writer.write(System.lineSeparator());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Lê de disco a lista de casas e reconstrói os objetos em memória.
   */
  public synchronized static Map<String, Home> loadHomesFromDisk(String filename) {
    File homesFile = new File(filename);
    Map<String, Home> homes = new HashMap<>();
    if (!homesFile.exists())
      return homes;
    try (BufferedReader reader = new BufferedReader(new FileReader(homesFile))) {
      String line;

      while ((line = reader.readLine()) != null) {
        if (line.isBlank())
          continue;
        String[] parts = line.split(":");
        if (parts.length < 2)
          continue;
        String homeName = parts[0];
        Home record;
        if (parts.length >= 3 && !parts[2].isEmpty()) {
          record = new Home(homeName, parts[1], parts[2], Arrays.copyOfRange(parts, 3, parts.length));
        } else {
          record = new Home(homeName, parts[1]);
        }
        homes.put(homeName, record);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return homes;
  }

  /**
   * Garante que a diretoria-mãe do ficheiro existe antes de escrever.
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
