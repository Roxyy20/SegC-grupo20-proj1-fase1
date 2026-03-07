public enum Section {
    MULTIMEDIA,
    LUZES,
    PORTAS,
    GARDEN,
    STORES,
    ELECTROS; 

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