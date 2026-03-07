import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ServerThread extends Thread {
  private Socket socket = null;
  private String currentUser;
  private static final String HOMES_FILE = "homes.txt";

  ServerThread(Socket inSoc) {
    socket = inSoc;
    System.out.println("Thread started for new client");
  }

  @Override
  public void start() {
    try (Socket clientSocket = socket;
        ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
        ObjectInputStream inStream = new ObjectInputStream(clientSocket.getInputStream());) {
      outStream.flush();

      String user = inStream.readUTF();
      String pass = inStream.readUTF();
      currentUser = user;

      if (user.isBlank() || pass.isBlank() || user.contains(":") || pass.contains(":")) {
        outStream.writeObject(LoginMessage.WrongPassword);
        outStream.flush();
        return;
      }

      FileReader fileReader = new FileReader("users.txt");
      BufferedReader buffReader = new BufferedReader(fileReader);
      String line;
      Boolean found = false;
      while ((line = buffReader.readLine()) != null) {
        String[] values = line.split(":");
        String username = values[0];
        String password = values[1];
        if (username.equals(user)) {
          found = true;
          if (!password.equals(pass)) {
            outStream.writeObject(LoginMessage.WrongPassword);
            outStream.flush();
            buffReader.close();
            readNewPassword(outStream, inStream, password);
          }
          break;
        }
      }
      buffReader.close();
      if (found) {
        outStream.writeObject(LoginMessage.OKUser);
      } else {
        outStream.writeObject(LoginMessage.OKNewUser);
        FileWriter fileWriter = new FileWriter("users.txt",true);
        fileWriter.write(user + ":" + pass + "\n");
        fileWriter.close();
      }
      outStream.flush();

      while (true) {
        String cmdStr;
        try {
          cmdStr = inStream.readUTF();
          System.out.println("Received command: " + cmdStr);
        } catch (IOException e) {
          // client disconnected
          break;
        }
        
        Command cmd = Command.fromString(cmdStr);
        if (cmd == null) {
          outStream.writeUTF("UNKNOWN COMMAND");
          outStream.flush();
          continue;
        }

        switch (cmd) {
          case CREATE:
            createHome(outStream, inStream);
            break;
          case ADD:
            addPermission(outStream, inStream);
            break;
          case RD:
            registerDevice(outStream, inStream);
            break;
          case EC:
            setStateOrTimer(outStream, inStream);
            break;
          case RT:
            requestHomeLog(outStream, inStream);
            break;
          case RH:
            requestDeviceLog(outStream, inStream);
            break;
          default:
            break;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void readNewPassword(ObjectOutputStream outStream, ObjectInputStream inStream, String pass) throws IOException {
    while (true) {
      String newPass = inStream.readUTF();
      System.out.println("Received new password: " + newPass + " password: " + pass);
      System.out.println(newPass.equals(pass));
      if(!newPass.equals(pass)){
        System.out.println("Wrong password. Entrou no if");
        outStream.writeObject(LoginMessage.WrongPassword);
        outStream.flush();
      }
      else if (newPass.equals(pass)){
        System.out.println("Password correct. Entrou no else");
        outStream.writeObject(LoginMessage.OKUser);
        outStream.flush();
        break;
      }
    }
  }

  private void requestDeviceLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    System.out.printf("Entregando log file do dispositivo %s casa\n", device, home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void requestHomeLog(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    System.out.printf("Entregando log file da casa %s\n", home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void setStateOrTimer(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String home = inStream.readUTF();
    String device = inStream.readUTF();
    int value = inStream.readInt();
    System.out.printf("ligando dispositivo %s em %s por %i\n", device, home, value);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void registerDevice(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
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
          String newLine = counterStr.isEmpty() ? (parts[0] + ":" + parts[1]) : (parts[0] + ":" + parts[1] + ":" + counterStr);
          lines.add(newLine);
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
    }

    System.out.printf("Adding device to section %s in home %s%n", section.name(), home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void addPermission(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
    String user = inStream.readUTF();
    String home = inStream.readUTF();
    String permission = inStream.readUTF();
    System.out.println("Usuario " + user + " adicionado com permissão " + permission + " à " + home);
    outStream.writeUTF(ServerResponse.OK.name());
    outStream.flush();
  }

  private void createHome(ObjectOutputStream outStream, ObjectInputStream inStream) throws IOException {
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

  private String findHomeOwner(String homeName) throws IOException {
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

  private Map<String, Integer> countersToMap(String countersStr) {
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

  private String countersToString(Map<String, Integer> counters) {
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
