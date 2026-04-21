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
public class RenewalAgent {

    private final RestTemplate restTemplate;
    private final KubernetesClient kubernetesClient;
    
    @Value("${ca.sign.url}")
    private String caSignUrl;
    
    public RenewalResult executeRenewal(CertificateMetadata metadata) {
        
        // 1. Sanitize the hostname immediately at the boundary
        String cleanHostname = metadata.subjectDns().replaceAll("(?i)^(CN\\s*\\\\?=\\s*)+", "").trim();
        
        log.info("[Renewal] Generating new RSA KeyPair for: {}", cleanHostname);
        
        try {
            // 2. Generate KeyPair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            // 3. Generate PKCS#10 CSR using the perfectly clean hostname
            List<String> sans = List.of(cleanHostname);
            String csrPem = CsrUtility.generatePkcs10Pem(keyPair, cleanHostname, sans);
            
            // 4. Generate the OTT dynamically for the exact same clean hostname
            String dynamicOtt = generateOttDynamically(cleanHostname);
            
            // 5. Request the signed certificate
            String signedCertPem = requestCertificateFromCa(csrPem, dynamicOtt);
            
            // 6. Format the Private Key
            String privateKeyPem = formatPrivateKeyAsPem(keyPair.getPrivate().getEncoded());
            
            log.info("[Renewal] Successfully retrieved signed X.509 certificate for {}", cleanHostname);
            
            // 7. Return the Base64 encoded payload to the Orchestrator
            return new RenewalResult(
                    Base64.getEncoder().encodeToString(signedCertPem.getBytes()),
                    Base64.getEncoder().encodeToString(privateKeyPem.getBytes())
            );
            
        } catch (Exception e) {
            log.error("[Renewal] Failed to negotiate with CA", e);
            throw new RuntimeException("Renewal pipeline failed", e);
        }
    }
    
    private String requestCertificateFromCa(String csrPem, String dynamicOtt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
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

    private String formatPrivateKeyAsPem(byte[] privateKeyBytes) {
        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKeyBytes);
        return "-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----\n";
    }
    
    private String generateOttDynamically(String targetHostname) {
        log.info("[Token-Gen] Requesting fresh OTT for: {}", targetHostname);
        
        try {
            String password = "password123"; 

            String command = String.format(
                "kubectl exec -n sre-system deploy/step-ca -- sh -c \"echo -n '%s' > /tmp/pass.txt && step ca token %s --password-file /tmp/pass.txt\"", 
                password, targetHostname
            );

            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            Process process = processBuilder.start();

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