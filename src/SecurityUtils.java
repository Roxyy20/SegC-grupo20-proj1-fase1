import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class SecurityUtils {

    private static final int SALT_SIZE = 16;

    // Gera um salt aleatório de tamanho definido por SALT_SIZE16 bytes.
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    // Calcula o hash SHA-256 de um array de bytes.
    public static byte[] sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    // Combina a senha com o salt, calcula o hash SHA-256 do resultado e retorna o hash codificado em Base64.
    public static String hashPasswordWithSalt(String password, byte[] salt) throws Exception {
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[passBytes.length + salt.length];

        System.arraycopy(passBytes, 0, combined, 0, passBytes.length);
        System.arraycopy(salt, 0, combined, passBytes.length, salt.length);

        byte[] hash = sha256(combined);
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
} 
    

