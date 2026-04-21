# Phase 3 Post-Mortem: Troubleshooting & Fixes

## Overview
This document chronicles every issue encountered during the testing of Phase 3 (Zero-Downtime Split-Trust Rollout). It provides an extensive breakdown of why each problem occurred, the various approaches attempted, and the definitive architectural fixes that stabilized the control plane.

---

## Problem 1: Missing Data Carrier (`Undefined` Variables)

### The Symptom
During the hand-off between the `RenewalAgent` and the `RolloutOrchestrator`, the variables `result.signedCertBase64()` and `result.privateKeyBase64()` were flagged as undefined by the IDE.

### The Root Cause
The `RenewalResult` data type did not actually exist in the codebase. The `executeRenewal` method was originally written to just execute logic, but as the pipeline grew, it needed to securely return two distinct Base64-encoded strings (the cert and the key) back to the orchestrator. Because the Java class acting as the carrier was missing, the Orchestrator couldn't unpack the results.

### Attempted Fixes & Final Solution
* **Attempt 1:** Assumed standard Java class syntax and added `.get()` prefixes (e.g., `getSignedCertBase64()`). This failed because the file was entirely absent.
* **Final Fix:** Created a new Java Record (`RenewalResult.java`) to act as a lightweight, immutable data carrier. The `executeRenewal` method was updated to instantiate this record and pass it back to the Orchestrator, establishing a clean boundary between cryptographic generation and Kubernetes patching.

---

## Problem 2: Fabric8 Jackson Serialization Crash (`keySerializer is null`)

### The Symptom
When the application attempted to update the Kubernetes Secret, it threw a fatal exception: `Cannot invoke "com.fasterxml.jackson.databind.JsonSerializer.serialize(...)" because "keySerializer" is null`.

### The Root Cause
This was a flaw in the Fabric8 Kubernetes Client's "Read-Modify-Write" cycle (the `.edit()` method). When Java attempts to pull down the existing Secret, it reads the entire object, including deeply nested and highly complex Kubernetes `managedFields` metadata. The Jackson JSON parser crashed when trying to deserialize and serialize these internal Kubernetes tracking fields, causing the application to fail before any actual updates could be applied.

### Attempted Fixes & Final Solution
* **Final Fix:** Abandoned the "Read-Modify-Write" cycle entirely and implemented **Server-Side Apply (SSA)**. By building a "dummy" Secret object containing *only* the new Base64 strings and using the `.patch()` method with `PatchType.SERVER_SIDE_APPLY`, we bypassed the Jackson serialization bug entirely. The Java client no longer reads the `managedFields`; it simply sends the patch instructions directly to the API server.

---

## Problem 3: Kubernetes Governance Block (409 Conflict)

### The Symptom
Immediately after fixing the Jackson bug, the API server rejected the patch with a `409 Conflict` error: `Apply failed with 2 conflicts: conflicts with "kubectl-create" using v1`.

### The Root Cause
Kubernetes Server-Side Apply relies on strict "Field Management" to track which tool owns which fields in a resource. Because the initial TLS Secret was created manually via the terminal (`kubectl`), Kubernetes assigned ownership of the `.data.tls.crt` and `.data.tls.key` fields to `kubectl`. When the Java application (`fabric8` client) tried to overwrite them, Kubernetes blocked the action to prevent accidental overwrites by conflicting tools.

### Attempted Fixes & Final Solution
* **Attempt 1:** Tried to pass a `force` boolean via `PatchContext.of(PatchType.SERVER_SIDE_APPLY, true)`. This failed due to a missing method signature in the specific Fabric8 version.
* **Attempt 2:** Tried `new PatchContextBuilder()`, which failed because the builder is an inner class.
* **Final Fix:** Used the correct fluent builder syntax: `new PatchContext.Builder().withPatchType(PatchType.SERVER_SIDE_APPLY).withForce(true).build()`. The `force: true` flag tells Kubernetes that the SRE Java Agent is forcefully asserting ownership over those fields, stripping ownership from `kubectl` and allowing the update to proceed.

---

## Problem 4: Cryptographic Handshake Mismatch (403 Forbidden on Step-CA)

### The Symptom
The scheduler would fail with a 403 error from Step-CA stating: `certificate request does not contain the valid common name - got CN\=service-a..., want [CN=service-a...]` or `want [CN=CN=service-a...]`.

### The Root Cause
This was a complex "Split Brain" data pollution issue driven by ASN.1 formatting rules.
1. When the scheduler read the existing cert from K8s, it parsed the hostname but pulled it out with a prefix: `CN=service-a...`.
2. BouncyCastle's `X500NameBuilder` uses the `BCStyle.CN` tag, which automatically maps to OID 2.5.4.3 (Common Name). When it received the literal string `CN=service-a...`, it assumed the `=` sign was part of the hostname and mathematically escaped it with a backslash (`CN\=`).
3. Meanwhile, the Step-CA CLI command requested a One-Time Token (OTT) using the polluted string. 
This meant Step-CA was locking the token to one strict format, while BouncyCastle was generating a CSR with a completely different, mathematically escaped format. The CA rejected the mismatch.

### Attempted Fixes & Final Solution
* **Attempt 1:** Removed manual `"CN=" +` concatenations. This failed because the pollution was coming dynamically from the parsed K8s secret, not hardcoded strings.
* **Attempt 2:** Added a `.startsWith("CN=")` check to strip it. This failed because it didn't account for whitespace or multiple prefixes (e.g., `CN=CN=`).
* **Attempt 3:** Added a `while` loop to aggressively strip all instances. This revealed via debugging ("X-Ray" logs) that the string *was* being cleaned for the CSR, but the Token generator was still using the dirty string.
* **Final Fix:** Implemented a "Nuclear Scrubber" Regex (`.replaceAll("(?i)^(CN\s*\\?=\s*)+", "")`) at the *very top* of the `executeRenewal` pipeline. This guaranteed that the CLI Token Generator and the BouncyCastle CSR builder received the exact same, mathematically pure string, allowing the CA handshake to perfectly align.

---

## Problem 5: The Infinite Renewal Loop

### The Symptom
The system successfully updated the Secret, but the scheduler kept triggering `[Rollout] SUCCESS` every single time it ran, continually requesting new certificates.

### The Root Cause
Two distinct architectural issues combined to cause this loop:
1.  **TTL Math Mismatch:** Step-CA issues modern, short-lived certificates with a default Time-To-Live (TTL) of 24 hours. The `PolicyAgent` was likely using a standard enterprise threshold (e.g., "Renew if expiring in less than 30 days"). Because a 24-hour cert is *always* expiring in less than 30 days, the loop triggered immediately upon inspecting the newly issued cert.
2.  **Double Execution:** The scheduled control loop was directly calling `renewalAgent.executeRenewal()`, but then immediately passing the data to `orchestrator.orchestrateSplitTrust()`, which *also* internally called the renewal agent. The system was generating two certificates per cycle.

### Attempted Fixes & Final Solution
* **Final Fix 1:** Updated the `PolicyAgent` mathematics to use an hour-based guard clause. The system now converts the remaining MS to hours and only triggers a renewal if `< 8 hours` remain on the 24-hour certificate.
* **Final Fix 2:** Removed the redundant `renewalAgent.executeRenewal` call from the `@Scheduled` control loop. The loop now delegates the entire lifecycle exclusively to the `RolloutOrchestrator`.

---

## Conclusion
Through Phase 3 testing, we successfully transitioned the architecture from a simple procedural script into a highly resilient Kubernetes Operator. The application now properly handles cryptographical ASN.1 edge cases, forcefully manages Kubernetes state via Server-Side Apply, and respects strict, mathematically driven control loops.
