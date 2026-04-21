package com.sre.agent.sreagent.discovery;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class DiscoveryAgent {

    private final KubernetesClient kubernetesClient;

    // The Sense phase of the loop
    @Scheduled(fixedRate = 30000)
    public void scanClusterForCertificates() {
        log.info("Scanning kind cluster for TLS secrets...");
        
        try {
            // Querying for Kubernetes Secrets of type 'kubernetes.io/tls'
            SecretList tlsSecrets = kubernetesClient.secrets()
                    .inAnyNamespace()
                    .withField("type", "kubernetes.io/tls")
                    .list();

            tlsSecrets.getItems().forEach(secret -> {
                log.info("Found TLS Secret: {} in namespace: {}", 
                        secret.getMetadata().getName(), 
                        secret.getMetadata().getNamespace());
            });
            
        } catch (Exception e) {
            log.error("Failed to communicate with kind cluster. Check your kubeconfig.", e);
        }
    }
}