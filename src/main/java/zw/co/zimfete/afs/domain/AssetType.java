package zw.co.zimfete.afs.domain;

public enum AssetType {
    BOREHOLE("Borehole drilling"),
    SOLAR("Solar installation"),
    FARMING_INPUTS("Farming inputs"),
    FENCING("Fencing"),
    IRRIGATION("Irrigation equipment"),
    LIVESTOCK("Livestock / housing"),
    EQUIPMENT("Machinery / equipment"),
    OTHER("Other");

    private final String label;

    AssetType(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Lenient lookup used by the Excel import: accepts the enum name or the label. */
    public static AssetType parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        for (AssetType a : values()) {
            if (a.name().equalsIgnoreCase(t.replace(' ', '_')) || a.label.equalsIgnoreCase(t)) return a;
        }
        String lower = t.toLowerCase();
        if (lower.contains("bore")) return BOREHOLE;
        if (lower.contains("solar")) return SOLAR;
        if (lower.contains("fenc")) return FENCING;
        if (lower.contains("input") || lower.contains("seed") || lower.contains("fert")) return FARMING_INPUTS;
        if (lower.contains("irrig")) return IRRIGATION;
        return OTHER;
    }
}
