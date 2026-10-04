import assert from "node:assert/strict";
import { test } from "node:test";
import { createRequire } from "node:module";
import { openMemoryStorage } from "../../uhabits-web/src/jsMain/resources/app/storage.mjs";
import {
  DriveWorkspace,
  NAMESPACE,
} from "../../uhabits-web/src/jsMain/resources/app/drive.mjs";

globalThis.window = globalThis;
globalThis.self = globalThis;
globalThis.dispatchEvent = () => {};
createRequire(import.meta.url)(
  "../../uhabits-web/build/drive-gate/loop-core.js",
);
const uuid = "0123456789abcdef0123456789abcdef";
const run = "12345678-1234-1234-1234-123456789abc";

async function fixture() {
  const original = loopEdit(
    loopHistory("account"),
    "android",
    "create",
    JSON.stringify({ [`habit:${uuid}:name`]: "Private habit" }),
  );
  const deleted = loopEdit(
    original,
    "android",
    "delete",
    JSON.stringify({ [`habit:${uuid}:deleted`]: true }),
  );
  const purged = loopPurge(deleted, "android", "purge", uuid);
  const storage = openMemoryStorage();
  await storage.mutate((s) => ({
    ...s,
    device: "browser",
    account: "account",
    history: purged,
    known: { old: true },
  }));
  const drive = new DriveWorkspace("unused", "account", run);
  const files = new Map(),
    downloads = [];
  let next = 0,
    failDelete = false;
  function add(id, history, device) {
    const content = loopPack(history, device, run),
      pack = JSON.parse(content);
    files.set(id, {
      content,
      metadata: {
        id,
        name: `${NAMESPACE}-${run}-${device}-${pack.revision}.json`,
        mimeType: "application/json",
        appProperties: {
          namespace: NAMESPACE,
          workspace: run,
          device,
          revision: String(pack.revision),
        },
      },
    });
  }
  add("old", original, "android");
  drive.request = async (url, options = {}) => {
    if (options.method === "POST") {
      const content = options.body
        .split("Content-Type: application/json\r\n\r\n")[2]
        .split("\r\n--")[0];
      const pack = JSON.parse(content);
      add(`replacement-${++next}`, JSON.stringify(pack.history), pack.deviceId);
      return new Response("{}");
    }
    const id = url.split("/files/")[1]?.split("?")[0];
    if (options.method === "DELETE") {
      if (failDelete) throw new Error("Cleanup interrupted");
      files.delete(id);
      return new Response(null, { status: 204 });
    }
    if (url.includes("alt=media")) {
      downloads.push(id);
      return new Response(files.get(id).content);
    }
    return new Response(
      JSON.stringify({ files: [...files.values()].map((f) => f.metadata) }),
    );
  };
  return {
    storage,
    drive,
    files,
    downloads,
    add,
    original,
    setFailure: (value) => {
      failDelete = value;
    },
  };
}

test("completed purge discovery resumes incremental downloads without resurrecting the habit", async () => {
  const f = await fixture();
  await f.drive.synchronize(f.storage);
  assert.equal(f.files.has("old"), false);
  await f.drive.synchronize(f.storage);
  const downloaded = f.downloads.length;
  await f.drive.synchronize(f.storage);
  assert.equal(
    f.downloads.length,
    downloaded,
    "Already inspected clean packages must not download again",
  );
  assert.equal(
    JSON.parse(loopView((await f.storage.read()).history, "2026-10-01", 0))
      .habits.length,
    0,
  );
});

test("interrupted cleanup retries known pre-purge payloads and late stale packages", async () => {
  const f = await fixture();
  f.setFailure(true);
  await assert.rejects(f.drive.synchronize(f.storage), /Cleanup interrupted/);
  assert.equal(f.files.has("old"), true);
  f.setFailure(false);
  await f.drive.synchronize(f.storage);
  assert.equal(f.files.has("old"), false);
  f.add("late-stale", f.original, "android");
  f.setFailure(true);
  await assert.rejects(f.drive.synchronize(f.storage), /Cleanup interrupted/);
  f.setFailure(false);
  await f.drive.synchronize(f.storage);
  assert.equal(f.files.has("late-stale"), false);
  assert.equal(
    JSON.parse(loopView((await f.storage.read()).history, "2026-10-01", 0))
      .habits.length,
    0,
  );
});

test("a new purge rescans packages that were previously accepted as clean", async () => {
  const f = await fixture();
  await f.drive.synchronize(f.storage);
  const other = "1123456789abcdef0123456789abcdef";
  const history = loopEdit(
    loopHistory("account"),
    "other",
    "other-create",
    JSON.stringify({ [`habit:${other}:name`]: "Later private habit" }),
  );
  f.add("previously-clean", history, "other");
  await f.drive.synchronize(f.storage);
  const deleted = loopEdit(
    history,
    "other",
    "other-delete",
    JSON.stringify({ [`habit:${other}:deleted`]: true }),
  );
  const purged = loopPurge(deleted, "other", "other-purge", other);
  await f.storage.mutate((s) => ({
    ...s,
    history: loopMerge(s.history, purged),
  }));
  await f.drive.synchronize(f.storage);
  assert.equal(f.files.has("previously-clean"), false);
  assert.equal(f.downloads.filter((id) => id === "previously-clean").length, 2);
});
