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
const key = `habit:${uuid}:name`;
const run = "12345678-1234-1234-1234-123456789abc";

test("browser JSON round-trips preserve native operation identities and equivalent tracking values", () => {
  const tracking = `habit:${uuid}:tracking`;
  const original = JSON.stringify({
    accountId: "account",
    changes: [
      {
        id: "native-create",
        deviceId: "android",
        sequence: 1,
        observed: {},
        edits: {
          [tracking]: {
            type: 1,
            freqNum: 1,
            freqDen: 1,
            targetValue: 1,
            targetType: 0,
            unit: "km",
          },
        },
      },
    ],
  }).replace('"targetValue":1', '"targetValue":1.0');
  const roundTrip = JSON.stringify(JSON.parse(original));
  const merged = loopMerge(original, roundTrip);
  assert.equal(JSON.parse(merged).changes.length, 1);
  assert.deepEqual(JSON.parse(loopView(merged, "2026-10-01", 0)).conflicts, {});
  assert.throws(
    () => loopMerge(original, roundTrip.replace('"targetValue":1', '"targetValue":2')),
    /different content/,
  );
});

test("temporary sessions isolate owned data and clear all pending work on end", async () => {
  const first = openMemoryStorage(),
    second = openMemoryStorage();
  await first.mutate((state) => ({
    ...state,
    history: loopEdit(
      state.history,
      state.device,
      "create",
      JSON.stringify({ [key]: "Private session habit" }),
    ),
  }));
  assert.equal(
    JSON.parse(loopView((await second.read()).history, "2026-10-01", 0)).habits
      .length,
    0,
  );
  const copy = await first.read();
  copy.history = loopHistory("other");
  assert.equal(
    JSON.parse(loopView((await first.read()).history, "2026-10-01", 0))
      .habits[0].name,
    "Private session habit",
  );
  first.clear();
  assert.equal(await first.read(), null);
  await assert.rejects(
    first.mutate((state) => state),
    /ended/,
  );
});

test("interrupted purge upload retains old Drive payload until redacted replacement is accepted", async () => {
  const base = loopEdit(
    loopHistory("account"),
    "android",
    "create",
    JSON.stringify({ [key]: "Private habit" }),
  );
  const deleted = loopEdit(
    base,
    "android",
    "delete",
    JSON.stringify({ [`habit:${uuid}:deleted`]: true }),
  );
  const purged = loopPurge(deleted, "android", "purge", uuid);
  const file = { id: "old", device: "android", payloadHabits: [uuid] };
  const drive = new DriveWorkspace("unused", "account", run);
  const original = globalThis.fetch;
  let fail = true,
    accepted,
    deletedFile = false;
  globalThis.fetch = async (url, options) => {
    if (options.method === "POST") {
      if (fail) throw new Error("Response interrupted");
      accepted = options.body;
      return new Response("{}");
    }
    assert.equal(options.method, "DELETE");
    assert.ok(accepted);
    deletedFile = true;
    return new Response(null, { status: 204 });
  };
  try {
    await assert.rejects(drive.scrubPurged(purged, [file]), /interrupted/);
    assert.equal(deletedFile, false);
    fail = false;
    await drive.scrubPurged(purged, [file]);
    assert.equal(deletedFile, true);
    assert.equal(accepted.includes("Private habit"), false);
    assert.ok(accepted.includes(uuid));
  } finally {
    globalThis.fetch = original;
  }
});

test("an accepted upload with a lost reply retries without duplicating the habit", async () => {
  const history = loopEdit(
    loopHistory("account"),
    "android",
    "stable-create",
    JSON.stringify({ [key]: "Once" }),
  );
  const drive = new DriveWorkspace("unused", "account", run);
  const original = globalThis.fetch;
  const stored = [];
  globalThis.fetch = async (_url, options) => {
    const content = options.body
      .split("Content-Type: application/json\r\n\r\n")[2]
      .split("\r\n--")[0];
    stored.push(content);
    if (stored.length === 1) throw new Error("Reply lost after acceptance");
    return new Response("{}");
  };
  try {
    await assert.rejects(
      drive.publish({ history, device: "android" }),
      /Reply lost/,
    );
    assert.equal(await drive.publish({ history, device: "android" }), 1);
    const merged = stored.reduce(
      (local, content) =>
        loopMerge(
          local,
          JSON.stringify(
            JSON.parse(loopDecodePack(content, "account", run)).history,
          ),
        ),
      loopHistory("account"),
    );
    const view = JSON.parse(loopView(merged, "2026-10-01", 0));
    assert.deepEqual(
      view.habits.map((habit) => habit.name),
      ["Once"],
    );
    assert.deepEqual(view.conflicts, {});
    assert.equal(JSON.parse(merged).changes.length, 1);
  } finally {
    globalThis.fetch = original;
  }
});

test("wall-clock skew cannot choose between incompatible concurrent names", () => {
  const now = Date.now;
  let merged;
  try {
    const base = loopEdit(
      loopHistory("account"),
      "android",
      "create",
      JSON.stringify({ [key]: "Walk" }),
    );
    Date.now = () => Date.UTC(2099, 0, 1);
    const future = loopEdit(
      base,
      "android",
      "future",
      JSON.stringify({ [key]: "Morning" }),
    );
    Date.now = () => Date.UTC(1901, 0, 1);
    const past = loopEdit(
      base,
      "browser",
      "past",
      JSON.stringify({ [key]: "Evening" }),
    );
    merged = loopMerge(future, past);
  } finally {
    Date.now = now;
  }
  const view = JSON.parse(loopView(merged, "2026-10-01", 0));
  assert.deepEqual(
    new Set(view.conflicts[key].map((revision) => revision.value)),
    new Set(["Morning", "Evening"]),
  );
});
