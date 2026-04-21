package com.sre.agent.sreagent.policy;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sre.agent.sreagent.domain.CertificateMetadata;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PolicyAgent {

    // Threshold for triggering a rotation
    private static final long RENEWAL_THRESHOLD_DAYS = 30;

    public List<CertificateMetadata> evaluateInventory(List<CertificateMetadata> inventory) {
        log.info("[Plan] Policy Agent evaluating {} certificates...", inventory.size());

        List<CertificateMetadata> flaggedCerts = inventory.stream()
                .filter(cert -> cert.getDaysUntilExpiry() <= RENEWAL_THRESHOLD_DAYS)
                .collect(Collectors.toList());

        if (flaggedCerts.isEmpty()) {
            log.info("All certificates are healthy. No action required.");
        } else {
            flaggedCerts.forEach(cert -> 
                log.warn("VIOLATION: Certificate in secret '{}' expires in {} days!", 
                        cert.secretName(), cert.getDaysUntilExpiry())
            );
        }

        return flaggedCerts;
    }
}