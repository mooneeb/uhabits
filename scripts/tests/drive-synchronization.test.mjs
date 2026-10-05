import assert from "node:assert/strict";
import { test } from "node:test";
import { createRequire } from "node:module";
import { openMemoryStorage } from "../../uhabits-web/src/jsMain/resources/app/storage.mjs";
import { DriveWorkspace } from "../../uhabits-web/src/jsMain/resources/app/drive.mjs";

globalThis.window = globalThis;
globalThis.self = globalThis;
globalThis.dispatchEvent = () => {};
createRequire(import.meta.url)(
  "../../uhabits-web/build/drive-gate/loop-core.js",
);

test("a newly connected empty tracker synchronizes and can upload its first habit", async () => {
  const storage = openMemoryStorage();
  await storage.mutate((current) => ({
    ...current,
    account: "account",
    history: loopBindAccount(current.history, "account"),
  }));
  const drive = new DriveWorkspace("unused", "account", "default");
  drive.request = async (_url, options = {}) =>
    new Response(
      JSON.stringify(options.method === "POST" ? { id: "first" } : { files: [] }),
    );

  const empty = await drive.synchronize(storage);
  assert.equal(
    JSON.parse(loopView(empty.history, "2026-10-05", 0)).habits.length,
    0,
  );

  await storage.mutate((current) => ({
    ...current,
    history: loopEdit(
      current.history,
      current.device,
      "first-habit",
      JSON.stringify({
        "habit:0123456789abcdef0123456789abcdef:name": "First habit",
      }),
    ),
  }));
  const synchronized = await drive.synchronize(storage);
  assert.equal(
    JSON.parse(loopView(synchronized.history, "2026-10-05", 0)).habits[0].name,
    "First habit",
  );
  assert.equal(
    JSON.parse(synchronized.history).changes.filter(
      (change) =>
        change.deviceId === synchronized.device &&
        change.sequence > synchronized.ack,
    ).length,
    0,
    "The first edit must finish acknowledged, rather than stay pending",
  );
});
