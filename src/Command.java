/**
 * Enumeração dos comandos suportados pelo servidor/cliente.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public enum Command {
    CREATE,
    ADD,
    RD,
    EC,
    RT,
    RH;

    /**
     * Converte texto introduzido pelo utilizador no comando correspondente.
     *
     * @param text comando em texto livre
     * @return valor {@link Command} ou {@code null} se não existir
     */
    public static Command fromString(String text) {
        try {
            return Command.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // Comando não existe
        }
    }
}
