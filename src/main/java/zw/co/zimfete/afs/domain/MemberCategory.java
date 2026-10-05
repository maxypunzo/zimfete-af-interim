package zw.co.zimfete.afs.domain;

/**
 * Veteran community category. Only the veteran community joins the SACCO as members ($10 joining fee plus
 * $1 a month); anyone else can still be an asset finance client.
 */
public enum MemberCategory {
    WAR_VETERAN("War veteran", "WV"),
    WAR_COLLABORATOR("War collaborator", "WC"),
    EX_DETAINEE("Ex-political prisoner / detainee / restrictee", "EPD"),
    WIDOW("Widow / widower of a veteran", "Widow"),
    DESCENDANT("Child / descendant of a veteran", "Desc"),
    NOT_VETERAN("Not veteran community", "-"),
    /** Brought over from the old register, which did not record categories: to be confirmed. */
    UNKNOWN("Not recorded (to confirm)", "?");

    private final String label;
    private final String shortLabel;

    MemberCategory(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String getLabel() { return label; }
    public String getShortLabel() { return shortLabel; }
    public boolean isVeteranCommunity() { return this != NOT_VETERAN && this != UNKNOWN; }

    /** Lenient lookup for imports: accepts the name, label or the usual abbreviations (WV, WC, widow...). */
    public static MemberCategory parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim().toUpperCase().replaceAll("[^A-Z]", "");
        for (MemberCategory c : values()) {
            if (c.name().replace("_", "").equals(t) || c.shortLabel.equalsIgnoreCase(text.trim())) return c;
        }
        if (t.equals("WARVET") || t.equals("VETERAN") || t.equals("WARVETERAN")) return WAR_VETERAN;
        if (t.contains("COLLAB")) return WAR_COLLABORATOR;
        if (t.contains("DETAIN") || t.contains("PRISON") || t.contains("RESTRICT")) return EX_DETAINEE;
        if (t.contains("WIDOW")) return WIDOW;
        if (t.contains("DESC") || t.contains("CHILD") || t.contains("SON") || t.contains("DAUGHTER")) return DESCENDANT;
        if (t.equals("NONE") || t.equals("NO") || t.contains("NOTVET") || t.contains("NONVET")) return NOT_VETERAN;
        return null;
    }
}
