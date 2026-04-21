package com.sre.agent.sreagent.execution;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.X509ExtendedKeyManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HotSwapKeyManager extends X509ExtendedKeyManager {

    // AtomicReference ensures thread-safe, lock-free swaps in highly concurrent environments
    private final AtomicReference<X509ExtendedKeyManager> delegate = new AtomicReference<>();

    public HotSwapKeyManager(X509ExtendedKeyManager initialManager) {
        this.delegate.set(initialManager);
    }

    public void reload(X509ExtendedKeyManager newManager) {
        log.info("[Hot-Swap] Atomically swapping X509ExtendedKeyManager in memory...");
        this.delegate.set(newManager);
        log.info("[Hot-Swap] JVM memory updated. New connections will use the new certificate.");
    }

    // --- Delegate all standard methods to the underlying manager ---

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return delegate.get().getClientAliases(keyType, issuers);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return delegate.get().chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.get().getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return delegate.get().chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.get().getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.get().getPrivateKey(alias);
    }
}