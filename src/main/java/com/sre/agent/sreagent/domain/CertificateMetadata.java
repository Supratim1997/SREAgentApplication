package com.sre.agent.sreagent.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public record CertificateMetadata(
        String secretName,
        String namespace,
        String subjectDns,
        Date expiryDate,
        boolean isExpired
) {
    public long getDaysUntilExpiry() {
        return ChronoUnit.DAYS.between(Instant.now(), expiryDate.toInstant());
    }
}