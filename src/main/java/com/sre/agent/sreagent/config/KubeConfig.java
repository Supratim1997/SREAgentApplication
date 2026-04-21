package com.sre.agent.sreagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

@Configuration
public class KubeConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        // Automatically picks up ~/.kube/config from WSL
        return new KubernetesClientBuilder().build();
    }
}