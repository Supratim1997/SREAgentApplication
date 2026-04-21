package com.sre.agent.sreagent.discovery;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class X509ParserService {

    private final CertificateFactory certificateFactory;

    public X509ParserService() throws CertificateException {
        // Initialize the factory once to save CPU cycles
        this.certificateFactory = CertificateFactory.getInstance("X.509");
    }

    public X509Certificate parseKubernetesTls(String base64EncodedPem) {
        try {
            // Step A: Decode the Base64 string from Kubernetes
            byte[] decodedPem = Base64.getDecoder().decode(base64EncodedPem);
            
            // Step B: Java's CertificateFactory can read PEM formats directly 
            // if provided as an InputStream.
            ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedPem);
            
            return (X509Certificate) certificateFactory.generateCertificate(inputStream);
            
        } catch (IllegalArgumentException e) {
            log.error("Failed to decode Base64 string from secret", e);
            return null;
        } catch (CertificateException e) {
            log.error("Failed to parse X.509 certificate structure", e);
            return null;
        }
    }
}