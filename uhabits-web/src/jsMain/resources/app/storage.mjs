// One read/write transaction stores the tracking history and pending-upload watermark together.
function emptyState() {
  return {
    history: window.loopHistory("unbound"),
    device: crypto.randomUUID(),
    account: "unbound",
    ack: 0,
    known: {},
    cleanedPurges: [],
  };
}

export function openMemoryStorage() {
  let state = emptyState();
  return {
    async mutate(work) {
      if (!state) throw new Error("This temporary session has ended.");
      const next = work(structuredClone(state));
      state = next;
      return structuredClone(next);
    },
    async read() {
      return structuredClone(state);
    },
    clear() {
      state = null;
    },
  };
}

export async function openStorage(workspace) {
  const db = await new Promise((resolve, reject) => {
    const request = indexedDB.open("loop-owned-device", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("workspaces");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () =>
      reject(new Error("Could not open durable device storage."));
  });
  const mutate = (work) =>
    new Promise((resolve, reject) => {
      const transaction = db.transaction("workspaces", "readwrite");
      const store = transaction.objectStore("workspaces");
      const request = store.get(workspace);
      let next, failure;
      request.onsuccess = () => {
        try {
          const current = request.result || emptyState();
          next = work(current);
          store.put(next, workspace);
        } catch (error) {
          failure = error;
          transaction.abort();
        }
      };
      transaction.oncomplete = () => resolve(next);
      transaction.onabort = transaction.onerror = () =>
        reject(
          failure || new Error("Save failed. This edit was not durably saved."),
        );
    });
  return {
    mutate,
    read: () => mutate((state) => state),
    clear: () => mutate(() => emptyState()),
  };
}
