package com.sre.agent.sreagent.model;

/**
 * A simple data carrier for the results of a successful certificate renewal.
 */
public record RenewalResult(
    String signedCertBase64,
    String privateKeyBase64
) {}
