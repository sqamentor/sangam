<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-kube

The one Kubernetes REST client of Sangam (owner decision 155). It is plain Java: the JDK HTTP client and Jackson,
no Kubernetes client library.

Users:

- `deploy/operator` (M-090): reconciles `SangamInstallation` and `SangamCell` (field manager `sangam-operator`).
- `sangam-backup` (M-102): the backup console lists the chart's backup CronJobs, starts a Job from one, and reads
  the Jobs as run history.

## API

- `Kube`: `get` (null on 404), `list` (the `items`), `create` (null on 409, so a deterministic name makes a create
  idempotent), `apply` (server-side apply, forced, with the client's field manager), `patchStatus` (merge patch of
  `/status`), and `Kube.labelSelector(selector)` for list paths. Other answers throw `Kube.KubeException` with the
  HTTP status and the API server's `Status.message` (at most 500 characters).
- `KubeHttp.inCluster(fieldManager)`: the pod's own service account. The API server is
  `https://$KUBERNETES_SERVICE_HOST:$KUBERNETES_SERVICE_PORT` (IPv6 hosts bracketed), trusted only through the
  service-account `ca.crt`. The token file is read again for every request, because the kubelet rotates bound tokens.
  The token is never logged and never part of an error.
- `KubeHttp.podNamespace()`: the namespace of the service account.

Paths must be absolute API paths; a relative path or one containing `..` is refused.

## Tests

`KubeHttpTest` (written, not run in the coding phase) covers 404, 409, server-side apply, the status subresource,
error messages without the token, path checks and label selectors.
