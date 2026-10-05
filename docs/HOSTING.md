# Host your own PWA

The PWA is a set of static application files. It does not need a habit database
or Google-token server. Choose a host, configure [Google access](GOOGLE-SETUP.md),
and publish the packaged files. Examples use placeholders, not a maintainer's website.

| Option | What you need |
| --- | --- |
| Cloudflare Pages | Free account and prebuilt files; a free HTTPS `pages.dev` address is supplied |
| Docker | A computer or server running Docker; HTTPS routing for access beyond localhost |
| Kubernetes | An existing cluster, an image registry and a configured HTTPS Gateway |

Cloudflare Pages is the simplest server-free option for this PWA. Static requests
are free and unlimited within the provider's published limits; custom domains are
optional. GitHub Pages and Firebase Hosting also have free tiers with different
limits. See the [research and source links](research/fork-documentation-and-hosting.md)
for the current comparison. [Cloudflare static pricing](https://developers.cloudflare.com/pages/functions/pricing/)

## Prepare the website files

Install [build prerequisites](BUILD.md), then run from the repository root:

```sh
./gradlew --no-configure-on-demand :uhabits-web:prepareDriveGate
python3 scripts/package-pwa.py \
  --hostname habits.example.org \
  --web-client-id YOUR_WEB_CLIENT_ID.apps.googleusercontent.com \
  --image loop-pwa:your-version \
  --output build/hosting/pwa
```

Replace the hostname with your chosen host's real HTTPS address and the client ID
with your own Google registration. Always pass the hostname explicitly. The image
name is used by Docker/Kubernetes examples; static hosting does not run an image.

The result's `site/` folder contains `app/`, `session/`, `update/` and
`loop-core.js`. Keep that layout together. Do not publish only `app/`, or the raw
development integration-gate output. The packaging step sets your configuration
and refreshes the offline cache version. It refuses to overwrite existing output;
use a new output folder for later versions and adjust commands accordingly.

## Cloudflare Pages

1. Sign into Cloudflare and choose **Workers & Pages → Create application →
   Pages → Upload assets**. Choose Direct Upload and a project name. Use the
   actual assigned `<PROJECT>.pages.dev` hostname for packaging and Google setup.
2. Upload the **contents** of the packaged `site/` folder, preserving its folders
   and file layout. Do not upload the APK, Kubernetes files or signing material.
3. Deploy and open `https://<PROJECT>.pages.dev/app/`.
4. Authorize that exact origin in your web OAuth client, connect Google Drive and
   run the checks below. If the assigned hostname differs from the one you
   packaged, package again with the correct hostname.

Future updates upload a newly packaged site as a production deployment. Direct
Upload projects cannot switch to Git integration, so use a new project if you
later choose that workflow. The app's entry point is `/app/`; ordinary static
hosting does not require the container's `/healthz` endpoint.
[Cloudflare Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/)

A Pages deployment is public by default. Google test-user restrictions do not hide
the website itself. Private hosting needs an explicit production access policy,
including any alternative deployment addresses. The preview Access setting only
protects previews. [Cloudflare preview access](https://developers.cloudflare.com/pages/configuration/preview-deployments/)

## Docker

Build an image from the packaged directory and try it locally:

```sh
docker build -t loop-pwa:your-version build/hosting/pwa
docker run --name loop-pwa --restart unless-stopped \
  -p 127.0.0.1:8080:8080 loop-pwa:your-version
```

Open `http://localhost:8080/app/`. Authorize that localhost origin separately for
Google development checks. Nginx serves the files and `/healthz` on port 8080.
For another device or a public website, place an HTTPS reverse proxy in front of
the container: it accepts the browser's HTTPS connection and forwards requests to
port 8080. Configure its hostname and certificate for your own deployment; a bare
HTTP server address is not the supported public PWA setup.

Use a new image tag for each update and recreate the container with that image.
Habit records remain in the browser and Drive; they are not inside this container.
Retain older images for rollback. [Docker container publishing](https://docs.docker.com/engine/network/port-publishing/)

## Kubernetes

Use this option when you already operate a cluster. The generated Deployment runs
Nginx, the Service supplies its internal address, and the HTTPRoute attaches the
website to your existing HTTPS Gateway. A Gateway is the cluster's entry point
for browser traffic; these examples do not install a Gateway controller or TLS
certificate infrastructure.

Package with an image name your cluster can download and your Gateway settings:

```sh
python3 scripts/package-pwa.py \
  --hostname habits.example.org \
  --web-client-id YOUR_WEB_CLIENT_ID.apps.googleusercontent.com \
  --image YOUR_REGISTRY/loop-pwa:your-version \
  --gateway YOUR_GATEWAY_NAME \
  --gateway-namespace YOUR_GATEWAY_NAMESPACE \
  --tls-listener YOUR_HTTPS_LISTENER \
  --output build/hosting/kubernetes-pwa
docker build -t YOUR_REGISTRY/loop-pwa:your-version build/hosting/kubernetes-pwa
docker push YOUR_REGISTRY/loop-pwa:your-version
```

Replace the uppercase values before running. Review the generated namespace labels
and adapt them to your Gateway's namespace access rules. The templates are examples;
the default cluster labels are not a universal configuration. Check the selected
Kubernetes context, then review and apply:

```sh
kubectl config current-context
kubectl diff -k build/hosting/kubernetes-pwa/kubernetes
kubectl apply -k build/hosting/kubernetes-pwa/kubernetes
kubectl -n habits rollout status deployment/loop-pwa
kubectl -n habits get pods,services,httproutes
```

`kubectl diff` returns 1 for proposed changes and 0 when already aligned. The
HTTPRoute must report Accepted and ResolvedRefs; otherwise check the Gateway name,
listener, namespace permissions and Service. A local diagnostic connection is
`kubectl -n habits port-forward service/loop-pwa 8080:8080`.
[Gateway HTTP routing](https://gateway-api.sigs.k8s.io/guides/user-guides/http-routing/)

The optional `deploy/public-tunnel` template is another entry method for an
existing cluster. It requires your own Cloudflare tunnel, hostname configuration
and Secret. It is not necessary for static hosting or the Gateway path. Keep tunnel
credentials and private infrastructure settings in a separate secure location.

## Check a deployment and update safely

Open `/app/`, connect the intended Google account and verify Android-to-browser
and browser-to-Android edits. Install the PWA, reopen offline, make a local edit,
then reconnect and verify it uploads. Check `/session/` separately for temporary
online use. These guides explain the setup; historical live results are in
[release checks](RELEASE-CHECKS.md), not proof of every new provider deployment.

Use `/update/` and Load current Loop version to refresh an older cached shell
without clearing habits. Changing the website address creates a separate browser
storage location: export or synchronize first, then connect the same intended
account at the new address. Hosting and authorization can change independently.
