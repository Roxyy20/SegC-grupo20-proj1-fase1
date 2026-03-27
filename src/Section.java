/**
 * Enumeração das secções possíveis numa casa inteligente.
 *
 * @author Alice Correia 61844, Marta Rufino 61778, Franscisco Lima 61864
 */
public enum Section {
  MULTIMEDIA,
  LUZES,
  PORTAS,
  GARDEN,
  STORES,
  ELECTROS;

  /**
   * Converte o primeiro caracter de um código textual na secção correspondente.
   *
   * @param code texto introduzido pelo utilizador (ex.: "m" para MULTIMEDIA)
   * @return secção correspondente ou {@code null} se não existir
   */
  public static Section parseSection(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    char c = Character.toUpperCase(code.charAt(0));
    for (Section s : Section.values()) {
      if (s.name().charAt(0) == c) {
        return s;
      }
    }
    return null;
  }
}
