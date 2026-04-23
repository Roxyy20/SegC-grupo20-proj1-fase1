import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Servidor principal que aceita ligações TLS de clientes {@link SpertaClient}
 * e delega o processamento para {@link ServerThread}.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public class SpertaServer {
  private static final String SALT_FILE = "server/server.salt";

  /**
   * Ponto de entrada.
   *
   * @param args {@code [<port>] <password-cifra> <keystore> <password-keystore>}
   */
  public static void main(String[] args) {
    if (args.length != 3 && args.length != 4) {
      System.err.println(
          "Wrong argument count. Usage: SpertaServer [<port>] <password-cifra> <keystore> <password-keystore>");
      return;
    }

    int port = 22345;
    String passwordCifra;
    String keystorePath;
    String passwordKeystore;

    if (args.length == 4) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.out.format("'%s' is not a valid port format. Using default value of 22345%n", args[0]);
      }
      passwordCifra = args[1];
      keystorePath = args[2];
      passwordKeystore = args[3];
    } else {
      passwordCifra = args[0];
      keystorePath = args[1];
      passwordKeystore = args[2];
    }

    System.out.format("Starting server at port %s%n", port);
    SpertaServer server = new SpertaServer(port, passwordCifra, keystorePath, passwordKeystore);
    server.start();
  }

  private final int port;
  private final SecretKey pbeKey;
  // private final String passwordCifra;
  private final String keystorePath;
  private final String passwordKeystore;

  /**
   * Cria uma instância parametrizada com a porta de escuta e configuração TLS.
   *
   * @param port             porta TCP
   * @param passwordCifra    senha para cifrar os dados da aplicação
   * @param keystorePath     caminho da keystore do servidor
   * @param passwordKeystore password da keystore
   */
  SpertaServer(int port, String passwordCifra, String keystorePath, String passwordKeystore) {
    this.port = port;
    this.pbeKey = deriveKey(passwordCifra, loadOrCreateSalt());
    this.keystorePath = keystorePath;
    this.passwordKeystore = passwordKeystore;
  }

  private static SecretKey deriveKey(String password, byte[] salt) {
    PBEKeySpec pbeSpec = new PBEKeySpec(
        password.toCharArray(),
        salt,
        65536,
        128);
    SecretKey tmp;
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      tmp = factory.generateSecret(pbeSpec);
    } catch (Exception ex) {
      throw new RuntimeException(ex.getMessage());
    }

    return new SecretKeySpec(tmp.getEncoded(), "AES");
  }

  private static byte[] loadOrCreateSalt() {
    File saltFile = new File(SALT_FILE);

    if (saltFile.exists()) {
      try {
        FileIntegraty.ensureFileAndHashOrExit(SALT_FILE);
        FileIntegraty.verifyIntegrityOrExit(SALT_FILE);
        return Files.readAllBytes(saltFile.toPath());
      } catch (Exception ex) {
        System.err.println("Salt file could not be open");
        throw new RuntimeException(ex.getMessage());
      }
    }

    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);

    saltFile.getParentFile().mkdirs();
    try {
      Files.write(saltFile.toPath(), salt);
      FileIntegraty.updateHashOrExit(SALT_FILE);
    } catch (Exception ex) {
      System.err.println("Could not write salt to file");
    }

    return salt;
  }

  /**
   * Arranca o ciclo de aceitação de clientes e cria uma nova thread por ligação.
   */
  public void start() {
    try {
      SSLServerSocketFactory factory = buildTlsFactory(keystorePath, passwordKeystore);
      try (SSLServerSocket sSoc = (SSLServerSocket) factory.createServerSocket(port)) {
        sSoc.setNeedClientAuth(false); // autenticação unilateral do servidor
        verifyServerStorageOrExit();
        System.out.println("Server Started");
        printReachableAddresses();
        while (true) {
          try {
            Socket inSoc = sSoc.accept();
            // O socket já é TLS; a thread só precisa do canal + dados da aplicação.
            ServerThread newServerThread = new ServerThread(inSoc, pbeKey);
            newServerThread.start();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
    } catch (IOException | GeneralSecurityException e) {
      System.err.println(e.getMessage());
      System.exit(-1);
    }
  }

  private SSLServerSocketFactory buildTlsFactory(String keyStorePath, String keyStorePassword)
      throws IOException, GeneralSecurityException {
    KeyStore keyStore = loadKeyStore(keyStorePath, keyStorePassword);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, keyStorePassword.toCharArray());

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, new SecureRandom());
    return context.getServerSocketFactory();
  }

  private KeyStore loadKeyStore(String keyStorePath, String keyStorePassword)
      throws IOException, GeneralSecurityException {
    Path path = Paths.get(keyStorePath);
    String[] candidateTypes = new String[] { KeyStore.getDefaultType(), "JKS", "PKCS12" };
    GeneralSecurityException lastSecurityException = null;

    for (String type : candidateTypes) {
      try (InputStream in = Files.newInputStream(path)) {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(in, keyStorePassword.toCharArray());
        return keyStore;
      } catch (GeneralSecurityException e) {
        lastSecurityException = e;
      }
    }

    if (lastSecurityException != null) {
      throw lastSecurityException;
    }
    throw new GeneralSecurityException("Unable to load keystore.");
  }

  private void verifyServerStorageOrExit() {
    Path dir = Path.of("server");
    if (!Files.exists(dir)) {
      return;
    }

    // Por decisão de implementação deste projeto:
    // a verificação global de integridade cobre ficheiros de dados do servidor
    // e exclui material de certificados/chaves partilhadas por utilizadores.
    Path sectionKeysDir = dir.resolve("sectionKeys").normalize();
    Path certsDir = dir.resolve("certs").normalize();
    Path saltPath = dir.resolve("server.salt").normalize();

    try (Stream<Path> stream = Files.walk(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> !path.toString().endsWith(".hash"))
          .filter(path -> !path.normalize().startsWith(sectionKeysDir))
          .filter(path -> !path.normalize().startsWith(certsDir))
          .filter(path -> !path.normalize().equals(saltPath))
          .forEach(path -> verifyServerFileOrExit(path));
    } catch (IOException e) {
      FileIntegraty.failIntegrityAndExit();
    }
  }

  private void verifyServerFileOrExit(Path path) {
    try {
      if (isProtectedServerFile(path)) {
        ProtectedFileUtils.withDecryptedFile(path.toString(), pbeKey, () -> null);
      } else {
        FileIntegraty.verifyIntegrityOrExit(path.toString());
      }
    } catch (Exception e) {
      FileIntegraty.failIntegrityAndExit();
    }
  }

  private boolean isProtectedServerFile(Path path) {
    String normalizedPath = path.normalize().toString().replace(File.separatorChar, '/');
    return normalizedPath.equals("server/apps.txt")
        || normalizedPath.equals("server/homes.txt");
  }

  private void printReachableAddresses() {
    List<String> addresses = new ArrayList<>();

    try {
      for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!networkInterface.isUp() || networkInterface.isLoopback()) {
          continue;
        }

        for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
          if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
            addresses.add(address.getHostAddress());
          }
        }
      }
    } catch (SocketException e) {
      System.out.println("Could not determine local network addresses automatically.");
      return;
    }

    if (addresses.isEmpty()) {
      System.out.println("No non-loopback IPv4 addresses detected. Remote clients may not be able to connect.");
      return;
    }

    System.out.println("Remote clients should connect to one of these addresses:");
    for (String address : addresses) {
      System.out.format("- %s:%d%n", address, port);
    }
  }
}
