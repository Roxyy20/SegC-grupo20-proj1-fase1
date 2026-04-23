import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class SecurityUtils {

    private static final int SALT_SIZE = 16;
    private static final int FILE_BUFFER_SIZE = 4096;

    // Gera um salt aleatório de tamanho definido por SALT_SIZE16 bytes.
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    // Calcula o hash SHA-256 de um array de bytes.
    public static byte[] sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data); // Retorna o hash calculado
    }

    // Combina a senha com o salt, calcula o hash SHA-256 do resultado e retorna o
    // hash codificado em Base64.
    public static String hashPasswordWithSalt(String password, byte[] salt) throws Exception {
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8); // Converte a senha para bytes usando UTF-8
        byte[] combined = new byte[passBytes.length + salt.length]; // Cria um array para armazenar a combinação da
                                                                    // senha e do salt

        System.arraycopy(passBytes, 0, combined, 0, passBytes.length);
        System.arraycopy(salt, 0, combined, passBytes.length, salt.length);

        byte[] hash = sha256(combined); // Calcula o hash SHA-256 da combinação da senha e do salt

        // a Base64 é usada para codificar o hash em uma string legível, facilitando o
        // armazenamento e a comparação posterior.
        return Base64.getEncoder().encodeToString(hash);
    }

    // Codifica um array de bytes em uma string Base64.
    public static String toBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    // Decodifica uma string Base64 de volta para um array de bytes.
    public static byte[] fromBase64(String value) {
        return Base64.getDecoder().decode(value);
    }

    // Gera nonce aleatório de 8 bytes (long) para atestação anti-replay.
    public static long generateNonce() {
        return new SecureRandom().nextLong();
    }

    // Calcula SHA-256(nonce || bytes do ficheiro), processando o ficheiro por
    // streaming.
    public static byte[] sha256WithNonceAndFile(long nonce, Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(nonce).array());

        try (InputStream in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[FILE_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw e;
        }
        return digest.digest();
    }
}
