
## Overview
The **Certificate & mTLS Lifecycle SRE Agent** is a closed-loop control system designed to autonomously manage service-to-service certificate rotations with zero downtime. Built with Java and Spring Boot on Kubernetes, it leverages an agentic orchestration loop to discover expiring certificates, extract and parse X.509 metadata, and flag trust chain violations before they cause production outages.

---

## Current Progress

### Phase 1: Platform Foundation (The "Sense" Loop)
* **SRE Control Plane**: Established a Spring Boot 3.x application with an asynchronous `@Scheduled` orchestrator loop.
* **Kubernetes Integration**: Configured the Fabric8 Kubernetes Client to dynamically detect the cluster config and authenticate seamlessly.
* **Discovery Agent (Initial)**: Implemented real-time polling to scan the Kubernetes API for Secrets of type `kubernetes.io/tls`.
* **Mock CA**: Deployed `smallstep/step-ca` into the cluster to serve as the local Certificate Authority.

### Phase 2: Cryptographic Parsing & Policy (The "Plan" Loop)
* **Domain Model**: Created the immutable `CertificateMetadata` record to transport state securely across agent threads.
* **X.509 Parser Service**: Built a hardened cryptographic parser using Java's `CertificateFactory` and `Base64.getMimeDecoder()` to safely extract expiration dates from raw Kubernetes PEM strings, resilient against malformed data.
* **Policy Agent**: Implemented business logic to evaluate the cryptographic inventory, mathematically calculating Time-To-Live (TTL) and flagging any certificate expiring within 30 days.
* **Centralized Orchestration**: Wired the Discovery and Policy agents into a single, deterministic State Machine governed by the `AgentOrchestrator`.

---

## Environment Setup & Testing Guide

This project is designed to run locally using `kind` (Kubernetes IN Docker) but can be deployed to any compliant Kubernetes cluster (EKS, Minikube, GKE).

### 1. Prerequisites
* **Java 17/21** & Maven
* **Docker**
* **kubectl**
* **kind** (or Minikube)

### 2. Cluster Creation
If you don't have a cluster running, spin up a lightweight `kind` environment:
Code output
README.md created successfully.

```bash
# Create the local cluster
kind create cluster --name sre-cluster

# Verify the connection
kubectl cluster-info
```

### 3. Deploy the Infrastructure (Mock CA)
Create the sre-system namespace and deploy the Step-CA instance.

```bash
# Apply the CA manifest
kubectl apply -f testing-manifests/step-ca-manifest.yaml
```
### 4. Run the Control Plane
Start the Spring Boot application from your terminal. It will automatically bind to the cluster.

```bash
./mvnw clean spring-boot:run
```
## Testing Scenarios & Manifest Files
To validate Phase 1 and Phase 2, we use declarative YAML files to inject specific states into the cluster. The agent should react dynamically on its next polling cycle.

Testing Files Inventory
*(Note: Keep these in a /testing-manifests directory in your repository)*

* `step-ca-manifest.yaml` - **Deploys the Mock CA.**

* `dummy-tls-secret.yaml` - **Injects a valid, short-lived certificate to trigger the Policy Agent.**

* `poison-pill.yaml` - **Injects corrupted Base64 data to test parser fault tolerance.**

### Scenario 1: Policy Violation Detection
Inject a certificate expiring in less than 30 days.

#### 1. Create dummy-tls-secret.yaml:

YAML
```
apiVersion: v1
kind: Secret
metadata:
  name: short-lived-cert
  namespace: sre-system
type: kubernetes.io/tls
data:
  tls.crt: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCi... (Valid Base64 PEM)
  tls.key: LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tCi...
  ```
#### 2. Apply: `kubectl apply -f dummy-tls-secret.yaml`
#### 3. Expected Result: The Spring Boot console logs: ` VIOLATION: Certificate in secret 'short-lived-cert' expires in X days!`

### Scenario 2: Fault Tolerance (The Poison Pill)
Ensure malformed secrets do not crash the SRE Agent.

#### 1. Create poison-pill.yaml:

YAML
```
apiVersion: v1
kind: Secret
metadata:
  name: poison-pill-cert
  namespace: sre-system
type: kubernetes.io/tls
data:
  tls.crt: Tm90IGEgcmVhbCBjZXJ0aWZpY2F0ZQ== # "Not a real certificate"
  tls.key: Tm90IGEgcmVhbCBrZXk=
  ```
#### 2. Apply: `kubectl apply -f poison-pill.yaml`
#### 3. Expected Result: The Java application catches the exception, logs Failed to parse X.509 structure, and continues running without terminating the JVM.
