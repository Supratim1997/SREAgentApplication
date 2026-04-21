package com.sre.agent.sreagent.orchestrator;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sre.agent.sreagent.discovery.DiscoveryAgent;
import com.sre.agent.sreagent.domain.CertificateMetadata;
import com.sre.agent.sreagent.execution.RenewalAgent;
import com.sre.agent.sreagent.execution.RolloutOrchestrator;
import com.sre.agent.sreagent.model.RenewalResult;
import com.sre.agent.sreagent.policy.PolicyAgent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final DiscoveryAgent discoveryAgent;
    private final PolicyAgent policyAgent;
    private final RenewalAgent renewalAgent;
    private final RolloutOrchestrator rolloutOrchestrator;

    @Scheduled(fixedRateString = "30000")
    public void executeControlLoop() {
        log.info("--- Initiating SRE Control Loop ---");
        
        // 1. Sense
        List<CertificateMetadata> inventory = discoveryAgent.scanClusterForCertificates();
        
        // 2. Plan
        List<CertificateMetadata> actionRequired = policyAgent.evaluateInventory(inventory);
        
        // 3. Act (To be implemented in Phase 3)
        if (!actionRequired.isEmpty()) {
            log.info("Action required for {} certificates. Triggering Renewal Agent...", actionRequired.size());
            for (CertificateMetadata metadata : actionRequired) {
                log.warn("[Action Required] Commencing automated rotation for: {}", metadata.secretName());
                
                // Generate the new identity
                RenewalResult newMaterials = renewalAgent.executeRenewal(metadata);
                
                // Execute the zero-downtime split-trust rollout
                rolloutOrchestrator.orchestrateSplitTrust(metadata);
            }
        }
    }
}