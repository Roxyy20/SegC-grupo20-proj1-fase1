import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

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
        FileIntegraty.verifyDirectoryIntegrityOrExit("server");
        System.out.println("Server Started");
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
}
