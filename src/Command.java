public enum Command {
  CREATE,
  ADD,
  RD,
  EC,
  RT,
  RH;

  public static Command fromString(String text) {
        try {
            return Command.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // Comando não existe
        }
    }
}