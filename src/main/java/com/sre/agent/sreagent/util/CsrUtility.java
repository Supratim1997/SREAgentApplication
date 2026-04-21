package com.sre.agent.sreagent.util;

import java.io.StringWriter;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CsrUtility {

    /**
     * Generates a PKCS#10 Certificate Signing Request (CSR) in PEM format.
     *
     * @param keyPair The newly generated RSA KeyPair for the service.
     * @param commonName The primary domain (e.g., "service-a.sre-system.svc.cluster.local")
     * @param sanDnsNames A list of alternative DNS names (e.g., "localhost", "service-a")
     * @return A Base64 encoded PEM string of the CSR.
     */
	public static String generatePkcs10Pem(KeyPair keyPair, String commonName, List<String> sans) throws Exception {
		// 1. THE NUCLEAR SCRUBBER
		// This regex removes one or more occurrences of "CN=" or "CN\=" or "cn = " at the start of the string
		String finalCleanName = commonName.replaceAll("(?i)^(CN\\s*\\\\?=\\s*)+", "").trim();

		// 2. THE X-RAY (CRITICAL: Do not skip this!)
		System.out.println("=====================================================");
		System.out.println("CRITICAL DEBUG: Raw Input  -> [" + commonName + "]");
		System.out.println("CRITICAL DEBUG: Cleaned up -> [" + finalCleanName + "]");
		System.out.println("=====================================================");

		// 3. Build the Subject
		X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
		nameBuilder.addRDN(BCStyle.CN, finalCleanName); 
		X500Name subject = nameBuilder.build();

	    // 2. Create the SANs (Subject Alternative Names)
	    // Step-CA often requires the CN to also be present in the SAN list as a DNS name
	    // 3. Create the SANs (Use the cleaned name if needed, though SANs are usually clean)
		// 3. Scrub the SANs just to be completely safe
		List<GeneralName> altNames = new ArrayList<>();
		for (String san : sans) {
		    String cleanSan = san.replaceAll("(?i)^CN=", "").trim();
		    altNames.add(new GeneralName(GeneralName.dNSName, cleanSan));
		}
		GeneralNames subjectAltNames = new GeneralNames(altNames.toArray(new GeneralName[0]));
	    // 3. Construct the CSR
	    PKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(
	            subject, 
	            keyPair.getPublic()
	    );

	    // Add the SAN extension (Crucial for modern TLS)
	    ExtensionsGenerator extGen = new ExtensionsGenerator();
	    extGen.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
	    csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extGen.generate());

	    // 4. Sign and Format
	    ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
	    PKCS10CertificationRequest csr = csrBuilder.build(signer);

	    return "-----BEGIN CERTIFICATE REQUEST-----\n" +
	           Base64.getEncoder().encodeToString(csr.getEncoded()) +
	           "\n-----END CERTIFICATE REQUEST-----";
	}

    private static String convertToPem(byte[] encodedData) throws Exception {
        StringWriter stringWriter = new StringWriter();
        try (PemWriter pemWriter = new PemWriter(stringWriter)) {
            pemWriter.writeObject(new PemObject("CERTIFICATE REQUEST", encodedData));
        }
        return stringWriter.toString();
    }
}