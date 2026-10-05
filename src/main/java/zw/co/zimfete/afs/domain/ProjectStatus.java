package zw.co.zimfete.afs.domain;

/** Lifecycle of an asset finance project. */
public enum ProjectStatus {
    SAVING("Saving towards deposit"),
    THRESHOLD_MET("Min. deposit reached, awaiting committee"),
    APPROVED("Approved by committee"),
    IN_PROGRESS("Started (funds disbursed)"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    ProjectStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    public boolean isOpen() {
        return this != COMPLETED && this != CANCELLED;
    }

    public boolean isPreStart() {
        return this == SAVING || this == THRESHOLD_MET || this == APPROVED;
    }
}
