const CACHE = "loop-owned-shell-__SHELL_VERSION__";
const SHELL = [
  "./",
  "index.html",
  "app.css",
  "app.mjs",
  "storage.mjs",
  "drive.mjs",
  "config.mjs",
  "sql-wasm.wasm",
  "migrations/09.sql",
  "migrations/10.sql",
  "migrations/11.sql",
  "migrations/12.sql",
  "migrations/13.sql",
  "migrations/14.sql",
  "migrations/15.sql",
  "migrations/16.sql",
  "migrations/17.sql",
  "migrations/18.sql",
  "migrations/19.sql",
  "migrations/20.sql",
  "migrations/21.sql",
  "migrations/22.sql",
  "migrations/23.sql",
  "migrations/24.sql",
  "migrations/25.sql",

  "manifest.webmanifest",
  "icon-192.png",
  "icon-512.png",
  "../loop-core.js",
];
self.addEventListener("install", (event) =>
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL))),
);
// Activate on a normal reopen; avoid replacing the running application's code mid-edit.
self.addEventListener("activate", (event) =>
  event.waitUntil(
    Promise.all([
      self.clients.claim(),
      caches
        .keys()
        .then((keys) =>
          Promise.all(
            keys
              .filter(
                (key) => key.startsWith("loop-owned-shell-") && key !== CACHE,
              )
              .map((key) => caches.delete(key)),
          ),
        ),
    ]),
  ),
);
self.addEventListener("fetch", (event) => {
  if (
    event.request.method !== "GET" ||
    new URL(event.request.url).origin !== self.location.origin
  )
    return;
  const url = new URL(event.request.url);
  const testRun = url.searchParams.get("testRun");
  if (
    url.pathname.endsWith("/app/manifest.webmanifest") &&
    testRun &&
    /^[a-f0-9-]{36}$/.test(testRun)
  ) {
    event.respondWith(
      caches.match("manifest.webmanifest").then(async (cached) => {
        const manifest = await (
          cached || (await fetch("manifest.webmanifest"))
        ).json();
        manifest.id = `./?testRun=${testRun}`;
        manifest.start_url = `./?testRun=${testRun}`;
        return new Response(JSON.stringify(manifest), {
          headers: { "Content-Type": "application/manifest+json" },
        });
      }),
    );
    return;
  }
  // Navigations with a test workspace use the same cached shell offline.
  event.respondWith(
    caches
      .match(event.request)
      .then(
        async (cached) =>
          cached ||
          (event.request.mode === "navigate"
            ? await caches.match("index.html")
            : null) ||
          fetch(event.request),
      ),
  );
});
