package com.sre.agent.sreagent.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.sre.agent.sreagent.domain.CertificateMetadata;
import com.sre.agent.sreagent.model.RenewalResult;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RolloutOrchestrator {

    private final KubernetesClient kubernetesClient;
    private final RenewalAgent renewalAgent;
    
    // In-memory state tracking for active rollouts
    private final Map<String, RolloutPhase> activeRollouts = new ConcurrentHashMap<>();

    public enum RolloutPhase {
        INITIATED, TRUSTSTORE_UPDATED, LEAF_CERT_UPDATED, VERIFIED, ROLLED_BACK
    }

	/*
	 * public void orchestrateSplitTrust(CertificateMetadata metadata,
	 * RenewalAgent.RenewalResult newCrypto) { String rolloutId =
	 * metadata.secretName(); activeRollouts.put(rolloutId, RolloutPhase.INITIATED);
	 * log.info("[Rollout] Initiating Split-Trust rotation for {}", rolloutId);
	 * 
	 * try { // Step 1: Update Truststores (Client side) // In a real mesh, this
	 * might involve updating a generic CA bundle Secret
	 * log.info("[Rollout] Phase 1: Injecting new CA into Client Truststores...");
	 * Thread.sleep(2000); // Simulate deployment delay
	 * activeRollouts.put(rolloutId, RolloutPhase.TRUSTSTORE_UPDATED);
	 * 
	 * // Step 2: Update Leaf Certificate (Server side)
	 * log.info("[Rollout] Phase 2: Updating Kubernetes TLS Secret {}...",
	 * rolloutId); updateKubernetesSecret(metadata, newCrypto);
	 * activeRollouts.put(rolloutId, RolloutPhase.LEAF_CERT_UPDATED);
	 * 
	 * // Step 3: Trigger JVM Hot-Swap (simulated via API or internal bus)
	 * log.info("[Rollout] Phase 3: Triggering JVM Hot-Swap for active pods...");
	 * 
	 * // If successful, pass to Verification Agent (Phase 4 build)
	 * 
	 * } catch (Exception e) {
	 * log.error("[Rollout] Critical failure during rotation. Triggering Rollback!",
	 * e); activeRollouts.put(rolloutId, RolloutPhase.ROLLED_BACK); // Execute
	 * rollback logic... } }
	 */
    
    public void orchestrateSplitTrust(CertificateMetadata metadata) {
        // 1. Trigger the Renewal Pipeline
        RenewalResult result = renewalAgent.executeRenewal(metadata);

        // 2. Pass the results to the update method
        // NOTE: 'result.signedCertBase64()' and 'result.privateKeyBase64()' 
        // are already Base64 encoded by the RenewalAgent.
        updateKubernetesSecret(
            metadata.secretName(),      // e.g., "service-a-tls"
            metadata.namespace(),       // e.g., "sre-system"
            result.signedCertBase64(),  // The new cert
            result.privateKeyBase64()   // The new private key
        );
        
        log.info("[Orchestrator] Full rotation cycle completed for {}", metadata.subjectDns());
    }

    public void updateKubernetesSecret(String secretName, String namespace, String certBase64, String keyBase64) {
        log.info("[Rollout] Force-applying Server-Side Patch to Secret: {}", secretName);

        try {
            Secret secretPatch = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                .endMetadata()
                .addToData("tls.crt", certBase64)
                .addToData("tls.key", keyBase64)
                .build();
            
            PatchContext patchContext = new PatchContext.Builder()
            	    .withPatchType(PatchType.SERVER_SIDE_APPLY)
            	    .withForce(true)
            	    .build();

            // ADDED: .withForce(true) to resolve the FieldManagerConflict
            kubernetesClient.secrets()
            .inNamespace(namespace)
            .withName(secretName)
            .patch(patchContext, secretPatch);	
                // Note: PatchContext.of(type, force)

            log.info("[Rollout] SUCCESS: Secret {} ownership transferred to Agent and updated.", secretName);
        } catch (Exception e) {
            log.error("[Rollout] Patch failed: {}", e.getMessage());
            throw new RuntimeException("K8s Patch Error", e);
        }
    }
}