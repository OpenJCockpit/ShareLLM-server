# Scaling and Kubernetes

This guide explains why ShareLLM Server can run as several instances, what limits its capacity,
and how to run it on Kubernetes. For a single host with Docker Compose see
[deployment.md](deployment.md).

## Why the server scales horizontally

The server keeps no state of its own. Everything that has to survive a request lives in
PostgreSQL:

| State | Where it lives |
|---|---|
| Pending and leased jobs | `llm_jobs` (the work queue) |
| Results | `llm_results` (keyed by request ID) |
| Registered workers, their models and last heartbeat | `llm_workers` |
| Leases (owner, token, deadline, attempts) | Columns of `llm_jobs` |
| Callers waiting for a result | Nothing shared: the instance polls `llm_results` for its own callers |

Any instance can therefore answer any request, and no sticky sessions are needed:

```text
               ┌──────────────┐
 callers ─────►│              ├──► instance A ──┐
               │ load balancer│                 ├──► PostgreSQL
 workers ─────►│  / ingress   ├──► instance B ──┘
               └──────────────┘
```

- A caller's request can land on instance A while the worker that runs it registers, heartbeats,
  claims and completes through instance B. Instance A sees the result in the database and answers
  the caller.
- An asynchronous caller can submit on one instance and poll `/sharellm/requests/{id}` on another.
- When an instance stops, its waiting synchronous callers are disconnected, but their jobs and
  results stay in the database and can be polled from any other instance.

### Correctness with many instances

Concurrency is handled by the database, not by in-process locks, so it holds across instances:

- **One claim per job.** Workers claim jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so two
  instances never hand the same job to two workers.
- **One lease per worker.** A partial unique index on `llm_jobs(lease_owner) WHERE state='LEASED'`
  rejects a second lease for the same worker, even if two claim requests from that worker race
  on different instances.
- **Fenced results.** Every lease carries a random token. A result or failure with a stale token is
  rejected (`409`), so a worker that lost its lease cannot overwrite a newer attempt.
- **Serialized worker updates.** Registration and heartbeats lock the worker's row, so a laptop
  that reconnects after its heartbeat expired cannot revive old leases.
- **Safe housekeeping.** Every instance runs the lease reclaim and result cleanup on a schedule.
  Both are single idempotent SQL statements, so running them on several instances at once only
  repeats work.

Processing stays at-least-once, as with a single instance.

## What limits capacity

| Resource | What it means | How to scale |
|---|---|---|
| Laptop workers | Inference runs on the laptops; each worker runs one job at a time. This is the real throughput limit. | Add laptops |
| Server instances | Cheap: requests run on virtual threads, and a waiting caller holds a thread but no database connection between polls. | Add replicas for availability, and for CPU when there are many callers |
| PostgreSQL | Shared by all instances; every poll is a query. The usual bottleneck at scale. | Scale up, tune the polling intervals, add a connection pooler |

### Database load

The load comes mostly from polling, and does not depend on the number of instances:

| Source | Rate per client |
|---|---|
| Idle worker waiting for a job | About 2.5 claim queries per second (one per 400 ms during its long poll) |
| Busy worker | One heartbeat every 10 s, plus one result write per job |
| Synchronous caller waiting for a result | About 3 queries per second (`RESULT_POLL_MILLIS`, 350 ms) |
| Asynchronous caller | One query per poll, as often as the caller chooses |

For example, 100 idle laptops and 50 waiting callers produce about 400 small, indexed queries per
second, which a modest PostgreSQL handles easily. Raise `RESULT_POLL_MILLIS` (minimum 50 ms) to
trade a little latency for less load.

### Database connections

Every instance has its own connection pool of `DB_POOL_SIZE` connections (24 by default). Keep

```text
DB_POOL_SIZE × maximum number of instances + headroom ≤ PostgreSQL max_connections
```

PostgreSQL allows 100 connections by default, so lower `DB_POOL_SIZE` when you run more than about
three instances; the Kubernetes example uses 10 per instance with at most 6 instances. A connection
is only held for the duration of one query, so a small pool serves many waiting callers.

If you need more connections than the database allows, put PgBouncer in transaction mode in front
of it. Use PgBouncer 1.21 or newer (which supports prepared statements), or add
`prepareThreshold=0` to `DATABASE_URL`.

### Guidance

- Run **two or three instances** for availability. More instances help only when the server's CPU
  is busy, which takes many concurrent callers.
- Use **`QUEUE_LIMIT`** to reject new jobs with `429` when the backlog grows beyond what the
  workers can process, instead of letting callers time out.
- Prefer the **asynchronous API** (`/sharellm/requests`) for long jobs: callers don't hold a
  connection, and restarts and scale-downs don't disconnect them.
- Use a **managed or replicated PostgreSQL** for high availability. The queue lives in the
  database, so a database failover pauses the service until the instances reconnect.

### Docker Compose

The Compose setup in [deployment.md](deployment.md) runs one instance per host, and the bundled
PostgreSQL cannot be shared between hosts. To run several instances without Kubernetes:

1. Use an external PostgreSQL (see "Using an external PostgreSQL" in deployment.md).
2. Run the Compose stack on several hosts.
3. Put a load balancer in front, or point a single proxy at all hosts.

## Kubernetes

[`deploy/kubernetes/`](../deploy/kubernetes) contains a Kustomize example that runs the server
with several replicas behind an ingress:

| File | Contents |
|---|---|
| `kustomization.yaml` | Namespace, common labels and the image to deploy |
| `namespace.yaml` | Namespace `sharellm`, enforcing the `restricted` Pod Security Standard |
| `configmap.yaml` | Database URL and user, pool size and tuning settings |
| `deployment.yaml` | Two or more replicas: probes, graceful shutdown, hardening, spread across nodes and zones |
| `service.yaml` | ClusterIP service on the API port only |
| `ingress.yaml` | TLS with cert-manager, timeouts and body size for ingress-nginx |
| `pdb.yaml` | Keeps at least one replica during node maintenance |
| `hpa.yaml` | Autoscaling between 2 and 6 replicas on CPU, with a slow scale-down |
| `networkpolicy.yaml` | API traffic only from the ingress controller; egress only to PostgreSQL and DNS |

The manifests are validated against the Kubernetes 1.33 schemas. They are a starting point: adapt
the host name, image, database, ingress controller and resources to your cluster.

### Prerequisites

- Kubernetes 1.27 or newer, with `kubectl`.
- An ingress controller. The example uses [ingress-nginx](https://kubernetes.github.io/ingress-nginx/);
  other controllers need their own annotations for the timeout and body size.
- [cert-manager](https://cert-manager.io/) with a `ClusterIssuer` named `letsencrypt`, or a TLS
  secret named `sharellm-tls` that you manage yourself.
- metrics-server, for the HorizontalPodAutoscaler.
- PostgreSQL 14 or newer, reachable from the cluster: a managed service, or an operator such as
  [CloudNativePG](https://cloudnative-pg.io/). The example does not run PostgreSQL itself.
- A registry with the image built from the `Dockerfile`.

### Deploy

1. **Build and push the image**, after `mvn -B verify` passed in CI:

   ```bash
   docker build -t registry.example.org/sharellm-server:1.0.0 .
   docker push registry.example.org/sharellm-server:1.0.0
   ```

   Set the same name and tag under `images` in `kustomization.yaml`.

2. **Create the database and its user** on your PostgreSQL. The server creates its tables on
   startup, so the user needs the `CREATE` privilege on the schema.

3. **Edit the example:** the database URL and user in `configmap.yaml`, the host name in
   `ingress.yaml`, and the PostgreSQL address in `networkpolicy.yaml` if you want to narrow it.

4. **Create the namespace and the secrets.** Each key of the secret becomes a file in
   `/run/secrets`, which the server reads as a setting with the same name:

   ```bash
   kubectl apply -f deploy/kubernetes/namespace.yaml
   ./deploy/generate-secrets.sh             # or use your own values
   kubectl -n sharellm create secret generic sharellm-secrets \
     --from-file=SHARELLM_PUBLIC_KEYS=secrets/sharellm_public_keys \
     --from-file=SHARELLM_WORKER_KEY=secrets/sharellm_worker_key \
     --from-file=DATABASE_PASSWORD=secrets/database_password
   ```

   Put your database user's password in `secrets/database_password` first. In a GitOps setup,
   create the same secret with External Secrets Operator, Sealed Secrets or your cloud's secret
   store instead; never commit it.

5. **Apply and check:**

   ```bash
   kubectl apply -k deploy/kubernetes
   kubectl -n sharellm rollout status deployment/sharellm-server
   kubectl -n sharellm get pods,ingress,hpa
   curl -s https://sharellm.example.org/health          # {"status":"UP"}
   ```

   On the very first start against an empty database, two replicas may create the tables at the
   same moment and one of them can fail with a duplicate-object error. Kubernetes restarts it and it
   then starts normally.

Workers and callers use the ingress host, exactly as with the Compose deployment.

### How the example handles scaling and restarts

- **Probes.** Liveness and readiness are on the management port 8081, which is not part of the
  Service, so they are not reachable through the ingress. Readiness includes the database: a pod
  that loses its database stops receiving traffic but is not restarted. A startup probe gives the
  JVM up to 150 s to start.
- **Graceful shutdown.** When a pod stops, the `preStop` hook waits 5 s so the ingress stops
  routing to it. Spring then finishes running requests for up to `SHUTDOWN_TIMEOUT` (30 s), within
  `terminationGracePeriodSeconds` (45 s). Callers still waiting after that are disconnected, and can
  poll their result on another pod.
- **Rolling updates** add one new pod before removing an old one (`maxSurge: 1`,
  `maxUnavailable: 0`), so capacity never drops. Workers reconnect on their own, and results
  survive because they are in the database.
- **Autoscaling.** The HPA adds pods above 70% CPU. Most time is spent waiting on workers and the
  database, so CPU rises only with many callers; treat the HPA as a safety net, and set
  `minReplicas` for the availability you need. Scale-down removes one pod at a time after 10
  minutes, because every removed pod disconnects its waiting synchronous callers.
- **Placement.** Topology spread constraints place the replicas on different nodes and zones where
  possible, and the PodDisruptionBudget keeps at least one replica during node drains.
- **Resources.** The memory limit is 1 GiB and the JVM uses 75% of it as heap. There is no CPU
  limit, to avoid throttling; the CPU request decides placement and autoscaling.
- **Hardening.** The pods run as UID 10001 with a read-only root filesystem, a 64 MiB `emptyDir` at
  `/tmp`, all capabilities dropped, the default seccomp profile and no service account token.

### Changing settings and keys

- **Settings.** Edit `configmap.yaml`, apply it, then run
  `kubectl -n sharellm rollout restart deployment/sharellm-server`. The pods read the settings only
  at startup.
- **Keys.** Update the `sharellm-secrets` secret and restart the deployment in the same way. Owner
  keys can be rotated without downtime by adding the new key first, as described in
  [deployment.md](deployment.md#rotating-keys).
- **More replicas.** Raise `maxReplicas` in `hpa.yaml`, and lower `DB_POOL_SIZE` so that the
  connection budget still fits the database.

### Rate limiting

ingress-nginx can limit requests per client IP, for example:

```yaml
nginx.ingress.kubernetes.io/limit-rps: "20"
nginx.ingress.kubernetes.io/limit-burst-multiplier: "5"
```

The limit applies per ingress-nginx pod. Workers share one IP when they are behind the same NAT, so
leave room for their long polls and heartbeats.

### Monitoring

- Ship the ECS JSON logs from the pods with your usual log collector.
- Watch the readiness of the pods, the HPA, and the database: connections, query latency and CPU.
- The number of pending jobs is a useful queue metric. Until the server exports metrics, query it
  directly: `SELECT state, count(*) FROM llm_jobs GROUP BY state;`

## Known limits

- **PostgreSQL is a single point of failure.** Use a highly available database for an available
  service.
- **No schema migrations.** Every instance runs `schema.sql` at startup, which only creates missing
  tables and indexes. Read the release notes before an upgrade that changes the schema.
- **No metrics endpoint.** Only the health probes are exposed; there is no Prometheus endpoint yet.
- **One shared worker key.** All workers authenticate with the same key, independent of the number
  of instances.
