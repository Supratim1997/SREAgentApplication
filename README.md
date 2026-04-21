# Certificate & mTLS Lifecycle SRE Agent

## Overview
The **Certificate & mTLS Lifecycle SRE Agent** is an advanced, closed-loop control plane application designed to autonomously manage service-to-service certificate rotations within Kubernetes clusters with zero downtime. 

Built on Java and Spring Boot, this agentic system operates as a native Kubernetes Operator. It discovers expiring certificates, extracts and parses X.509 metadata, enforces cryptographic compliance, and executes split-trust rollouts using Server-Side Apply (SSA) before trust chain violations can cause production degradation.

---

## System Architecture: The Control Loop

The agent operates on a continuous, deterministic State Machine loop:

1. **Sense (Discovery):** Scans the Kubernetes API for TLS Secrets.
2. **Plan (Policy):** Evaluates cryptographic metadata (TTL, SANs, Algorithms) against compliance thresholds.
3. **Act (Rotation):** Negotiates with the Certificate Authority (CA) to provision new cryptographic materials and force-patches the cluster state.
4. **Verify (Phase 4 - Pending):** Triggers dynamic application context reloads to ensure the new materials are actively utilized without Pod eviction.

---

## Current Progress & Milestones

### Phase 1: Platform Foundation (The "Sense" Loop)
* **SRE Control Plane**: Established a Spring Boot 3.x async `@Scheduled` orchestrator.
* **Kubernetes Integration**: Configured the Fabric8 Client for native cluster authentication and state manipulation.
* **Discovery Agent**: Polling mechanism for `kubernetes.io/tls` Secrets.
* **Mock CA Infrastructure**: Deployed `smallstep/step-ca` into the cluster as the local Certificate Authority.

### Phase 2: Cryptographic Parsing & Policy (The "Plan" Loop)
* **Domain Model**: Implemented the immutable `CertificateMetadata` record.
* **Hardened X.509 Parser**: Utilized `CertificateFactory` to safely extract execution dates from Base64 PEMs, ensuring fault tolerance against malformed data.
* **Policy Agent**: Mathematical evaluation engine calculating precise Time-To-Live (TTL) horizons for rotation flagging.

### Phase 3: Zero-Downtime Split-Trust Rollout (The "Act" Loop)
* **Automated Renewal Pipeline**: Integrated BouncyCastle to dynamically generate 2048-bit RSA KeyPairs and perfectly formatted PKCS#10 CSRs.
* **Cryptographic Boundary Sanitization**: Built an aggressive regex scrubber to align Step-CA CLI One-Time Tokens (OTT) with strict BouncyCastle ASN.1 formatting rules.
* **Server-Side Apply (SSA)**: Bypassed Jackson serialization bugs and K8s `409 Conflict` blocks by claiming field management ownership from `kubectl`.
* **Short-Lived Optimization**: Tuned the Policy Agent TTL math to elegantly handle 24-hour CA certificates (triggering at `< 8 hours`).
* **Payload Hand-Off**: Created the `RenewalResult` carrier to securely transport generated Base64 payloads into the K8s API patch context.

### Phase 4: Hot Reload Verification (Next Steps / Known Issues)
* **Status:** In Development.
* **Challenge:** The TLS Secret successfully updates in K8s, but running services retain the old certificate in active memory. 
* **Goal:** Implement a File System Watcher or memory reload trigger to refresh the JVM SSL context dynamically.

---

## Project Structure

```text
├── src/main/java/com/sre/agent/sreagent/
│   ├── SreAgentApplication.java        # Spring Boot Entry Point
│   ├── config/                         # Client and Bean configurations
│   ├── domain/                         # CertificateMetadata, RenewalResult Records
│   ├── execution/                      # RenewalAgent, RolloutOrchestrator
│   ├── policy/                         # DiscoveryAgent, PolicyAgent
│   ├── util/                           # CsrUtility, X509Parser
│   └── scheduler/                      # Control Loop Trigger
├── scripts/
│   ├── bootstrap-cluster.sh            # Kind cluster creation script
│   └── run-agent.sh                    # Maven compilation and execution script
├── testing-manifests/                  # Declarative test states
│   ├── step-ca-manifest.yaml           # Infrastructure setup
│   ├── dummy-tls-secret.yaml           # Standard test secret
│   ├── short-lived-cert.yaml           # Phase 3 rotation trigger
│   └── poison-pill.yaml                # Fault-tolerance test
└── pom.xml                             # Dependencies (Fabric8, BouncyCastle)
```

---

## Environment Setup & Execution

### 1. Prerequisites
Ensure the following are installed and configured:
* **Java 17+** & Maven
* **Docker**
* **kubectl**
* **kind** (Kubernetes IN Docker)

### 2. Infrastructure Bootstrapping

Start by spinning up the local cluster and deploying the required CA infrastructure.

```bash
# 1. Create the local Kubernetes cluster
kind create cluster --name sre-cluster

# 2. Verify connection
kubectl cluster-info

# 3. Create the operational namespace
kubectl create namespace sre-system

# 4. Deploy the Step-CA Mock Authority
kubectl apply -f testing-manifests/step-ca-manifest.yaml

# 5. Wait for CA Pods to be ready
kubectl wait --for=condition=ready pod -l app=step-ca -n sre-system --timeout=90s
```

### 3. Running the Control Plane

Execute the Spring Boot application locally. It will automatically detect your `~/.kube/config` and begin polling.

```bash
# Clean, compile, and run the agent (skipping tests for rapid iteration)
./mvnw clean compile spring-boot:run -Dspring-boot.run.arguments="--skipTests"
```

---

## Comprehensive Testing Guide

To validate the Agent's capabilities, apply these specific manifests to the cluster and monitor the Spring Boot logs for the correct automated response.

### Scenario 1: Cryptographic Parsing & Fault Tolerance
**Objective:** Verify the parser safely handles malformed or missing certificate data without crashing the JVM.

1. **Apply the Manifest:**
   ```bash
   kubectl apply -f testing-manifests/poison-pill.yaml
   ```
2. **Expected Log Output:**
   ```text
   [Discovery] Found secret 'poison-pill-cert'
   [Parser] ERROR: Failed to parse X.509 structure for 'poison-pill-cert'
   [ControlLoop] Continuing execution loop...
   ```

### Scenario 2: Policy Violation Detection
**Objective:** Verify the Policy Agent correctly identifies a certificate nearing its expiration threshold.

1. **Apply the Manifest:**
   ```bash
   kubectl apply -f testing-manifests/dummy-tls-secret.yaml
   ```
2. **Expected Log Output:**
   ```text
   [PolicyAgent] VIOLATION: Certificate in secret 'dummy-tls-cert' expires in 4 days!
   ```

### Scenario 3: Phase 3 Automated Rotation (Split-Trust)
**Objective:** Trigger the full end-to-end rotation pipeline, including CA negotiation and Server-Side patching.

1. **Apply the Manifest:** Create a secret representing a Step-CA 24-hour certificate (expiring in less than 8 hours).
   ```bash
   kubectl apply -f testing-manifests/short-lived-cert.yaml
   ```
2. **Monitor the Agent Logs:**
   ```text
   [PolicyAgent] VIOLATION: Certificate in secret 'short-lived-cert' expires in 6 hours!
   [Action Required] Commencing automated rotation for: short-lived-cert
   [Renewal] Generating new RSA KeyPair for: service-a.sre-system.svc.cluster.local
   [Token-Gen] Requesting fresh OTT for: service-a.sre-system.svc.cluster.local
   [Renewal] Successfully retrieved signed X.509 certificate
   [Rollout] Force-applying Server-Side Patch to Secret: short-lived-cert
   [Rollout] SUCCESS: Secret short-lived-cert ownership transferred to Agent and updated.
   ```
3. **Verify Cluster State:**
   Ensure the K8s API reflects the updated fields managed by the Fabric8 client.
   ```bash
   kubectl get secret short-lived-cert -n sre-system -o yaml
   ```
   *Look for `manager: fabric8` under `managedFields`.*