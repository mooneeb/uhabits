import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DriveWorkspace,
  NAMESPACE,
} from "../../uhabits-web/src/jsMain/resources/app/drive.mjs";

const run = "12345678-1234-1234-1234-123456789abc";

test("cleanup refuses the ordinary workspace without making a request", async () => {
  const drive = new DriveWorkspace("unused", "account", "default");
  drive.request = () => assert.fail("No request may be made for ordinary data");
  await assert.rejects(drive.deleteTestWorkspace("default"));
  await assert.rejects(drive.deleteTestWorkspace(run));
});

test("a malformed later test pack prevents every deletion", async () => {
  const drive = new DriveWorkspace("unused", "account", run);
  const files = [1, 2].map((revision) => ({
    id: `file${revision}`,
    name: `${NAMESPACE}-${run}-device-${revision}.json`,
    mimeType: "application/json",
    appProperties: {
      namespace: NAMESPACE,
      workspace: run,
      device: "device",
      revision: String(revision),
    },
  }));
  const requests = [];
  drive.request = async (url, options) => {
    requests.push({ url, method: options?.method });
    if (url.includes("?alt=media"))
      return {
        text: async () =>
          url.includes("file1") ? JSON.stringify({ history: {} }) : "malformed",
      };
    return { json: async () => ({ files }) };
  };
  // The real core separately validates packs. This fault isolates the requirement
  // that all validation completes before any destructive request starts.
  globalThis.window = {
    loopPayloadHabits() {
      return "[]";
    },
    loopDecodePack(content) {
      if (content === "malformed") throw new Error("Unsupported pack");
      return JSON.stringify({ deviceId: "device", revision: 1, history: {} });
    },
  };
  try {
    await assert.rejects(drive.deleteTestWorkspace(run), /Unsupported pack/);
    assert.equal(
      requests.filter((request) => request.method === "DELETE").length,
      0,
    );
    assert.equal(
      requests.filter((request) => request.url.includes("?alt=media")).length,
      2,
    );
  } finally {
    delete globalThis.window;
  }
});
