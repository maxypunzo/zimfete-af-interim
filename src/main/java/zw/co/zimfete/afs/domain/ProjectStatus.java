package zw.co.zimfete.afs.domain;

/** Lifecycle of an asset finance account / project. */
public enum ProjectStatus {
    SAVING("Saving towards deposit"),
    THRESHOLD_MET("Minimum deposit reached"),
    IN_PROGRESS("Project started / loan running"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    ProjectStatus(String label) { this.label = label; }

    public String getLabel() { return label; }
}
