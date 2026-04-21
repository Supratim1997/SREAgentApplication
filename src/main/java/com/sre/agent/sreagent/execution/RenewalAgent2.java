package com.sre.agent.sreagent.execution;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.sre.agent.sreagent.domain.CertificateMetadata;
import com.sre.agent.sreagent.model.RenewalResult;
import com.sre.agent.sreagent.util.CsrUtility;

import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RenewalAgent2 {

    // In a full build, inject a WebClient/RestTemplate to call the Step-CA API
	private final RestTemplate restTemplate;
	private final KubernetesClient kubernetesClient;
	// Inject the URL from application.properties
    @Value("${ca.sign.url}")
    private String caSignUrl;
    
    public RenewalResult executeRenewal(CertificateMetadata metadata) {
    	
    	String cleanHostname = metadata.subjectDns().replaceAll("(?i)^(CN\\s*\\\\?=\\s*)+", "").trim();
    	
    	
        log.info("[Renewal] Generating new RSA KeyPair for: {}", cleanHostname);
        
        try {
        	
            // 1. Generate KeyPair (BouncyCastle or standard java.security)
        	KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        	keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            // 2. Generate PKCS#10 CSR
            // We pass the primary DNS name and inject a few standard SANs	
            List<String> sans = List.of(cleanHostname);
//            String csrPem = CsrUtility.generatePkcs10Pem(keyPair, cleanHostname, sans);
            String csrPem = CsrUtility.generatePkcs10Pem(keyPair, cleanHostname, sans);
            
            // ---> NEW: Generate the token dynamically right before the request <---
            String dynamicOtt = generateOttDynamically(cleanHostname);
            // 3. POST CSR to Step-CA API (http://step-ca.sre-system:9000/1.0/sign)
            // 3. Request the new certificate from the Mock CA
            String signedCertPem = requestCertificateFromCa(csrPem, dynamicOtt);
            
            
            // 4. Format the Private Key as a standard PEM string for Kubernetes
            String privateKeyPem = formatPrivateKeyAsPem(keyPair.getPrivate().getEncoded());
            
            
            log.info("[Renewal] Successfully retrieved signed X.509 certificate for {}", cleanHostname);
            
            // 5. Return the payload to the Orchestrator for injection
            return new RenewalResult(
                    Base64.getEncoder().encodeToString(signedCertPem.getBytes()),
                    Base64.getEncoder().encodeToString(privateKeyPem.getBytes())
            );
            
        } catch (Exception e) {
            log.error("[Renewal] Failed to negotiate with CA", e);
            throw new RuntimeException("Renewal pipeline failed");
        }
    }
    
    /**
     * Executes the REST call to Step-CA to sign the CSR.
     * @param haclathonOtt 
     */
    private String requestCertificateFromCa(String csrPem, String dynamicOtt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // FIX 1: The key MUST be "csr"
        // FIX 2: Pass the raw PEM string directly. Do not Base64 encode it.
        Map<String, String> requestBody = Map.of(
                "csr", csrPem,
                "ott", dynamicOtt
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(caSignUrl, request, Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return (String) response.getBody().get("crt");
        } else {
            throw new RuntimeException("CA responded with error: " + response.getStatusCode());
        }
    }

    /**
     * Wraps the raw private key bytes in standard PKCS#8 PEM headers.
     */
    private String formatPrivateKeyAsPem(byte[] privateKeyBytes) {
        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKeyBytes);
        return "-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----\n";
    }
    
    /**
     * Dynamically asks the cluster's CA pod to generate an OTT for the given hostname.
     */
    private String generateOttDynamically(String targetHostname) {
        log.info("[Token-Gen] Requesting fresh OTT for: {}", targetHostname);
        
        try {
            // Use the exact password you found in the deployment YAML
            String password = "password123"; 

            // We write the password to a temp file INSIDE the pod so 'step' can read it without a TTY
            String command = String.format(
                "kubectl exec -n sre-system deploy/step-ca -- sh -c \"echo -n '%s' > /tmp/pass.txt && step ca token %s --password-file /tmp/pass.txt\"", 
                password, targetHostname
            );

            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            Process process = processBuilder.start();

            // Capture output
            String token = new String(process.getInputStream().readAllBytes()).trim();
            String errorOutput = new String(process.getErrorStream().readAllBytes()).trim();

            int exitCode = process.waitFor();

            if (exitCode != 0 || token.isEmpty()) {
                log.error("[Token-Gen] CLI Error: {}", errorOutput);
                throw new RuntimeException("CA Token generation failed with exit code " + exitCode);
            }

            return token;

        } catch (Exception e) {
            log.error("[Token-Gen] Execution failed", e);
            throw new RuntimeException(e);
        }
    }
    
}