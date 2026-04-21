package com.sre.agent.sreagent;

import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableScheduling
public class SreagentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SreagentApplication.class, args);
	}
	
	// By placing this here, we GUARANTEE Spring uses this specific instance
    @Bean
    public RestTemplate restTemplate() throws Exception {
        System.out.println("\n⚠️ WARNING: INITIATING INSECURE REST TEMPLATE FOR WSL TESTING ⚠️\n");

        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                if (connection instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(sc.getSocketFactory());
                    ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) -> true);
                }
                super.prepareConnection(connection, httpMethod);
            }
        };

        return new RestTemplate(factory);
    }

}
