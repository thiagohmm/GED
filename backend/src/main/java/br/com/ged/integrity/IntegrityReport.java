package br.com.ged.integrity;

import java.util.List;

public record IntegrityReport(long totalVersions, long ok, List<IntegrityIssue> issues) {

    public boolean allOk() {
        return issues.isEmpty();
    }
}
