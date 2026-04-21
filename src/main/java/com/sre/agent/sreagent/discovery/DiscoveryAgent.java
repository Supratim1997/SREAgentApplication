package com.sre.agent.sreagent.discovery;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sre.agent.sreagent.domain.CertificateMetadata;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryAgent {

    private final KubernetesClient kubernetesClient;
    
    private final X509ParserService parserService;

    // The Sense phase of the loop
    public List<CertificateMetadata> scanClusterForCertificates() {
    	log.info("[Sense] Scanning kind cluster for TLS secrets...");
    	List<CertificateMetadata> inventory = new ArrayList<>();
        
        try {
            // Querying for Kubernetes Secrets of type 'kubernetes.io/tls'
            SecretList tlsSecrets = kubernetesClient.secrets()
                    .inAnyNamespace()
                    .withField("type", "kubernetes.io/tls")
                    .list();
            
            for (Secret secret : tlsSecrets.getItems()) {
                // Kubernetes TLS secrets always use the key 'tls.crt'
                String base64Cert = secret.getData().get("tls.crt");
                
                if (base64Cert != null) {
                    X509Certificate x509 = parserService.parseKubernetesTls(base64Cert);
                    
                    if (x509 != null) {
                    	CertificateMetadata metadata = new CertificateMetadata(
       						 secret.getMetadata().getName(), secret.getMetadata().getNamespace(),
    						 x509.getSubjectX500Principal().getName(), x509.getNotAfter(), // The expiry date
    						 x509.getNotAfter().before(new java.util.Date()) );
                        inventory.add(metadata);
                        log.info("Parsed: {} | Expiry: {} | Days Left: {}", 
                                metadata.secretName(), 
                                metadata.expiryDate(), 
                                metadata.getDaysUntilExpiry());
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to communicate with kind cluster. Check your kubeconfig.", e);
        }
        return inventory;
    }
}