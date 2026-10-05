export const SCOPE = "https://www.googleapis.com/auth/drive.appdata";
export const NAMESPACE = "loop-workspace-v1";
const MAX_BYTES = 10000000;

export class DriveWorkspace {
  constructor(token, account, workspace) {
    this.token = token;
    this.account = account;
    this.workspace = workspace;
  }
  async request(url, options = {}) {
    const response = await fetch(url, {
      ...options,
      cache: "no-store",
      signal: AbortSignal.timeout(15000),
      headers: { ...options.headers, Authorization: `Bearer ${this.token}` },
    });
    if (response.status === 401)
      throw new Error("Reconnect required. Your Google authorization expired.");
    if (!response.ok)
      throw new Error(
        `Drive is unavailable (${response.status}). Saved edits remain pending.`,
      );
    return response;
  }
  async synchronize(storage) {
    let state = await storage.read();
    const scannedPurges = JSON.parse(state.history).purgedHabits || [];
    const fullScan = scannedPurges.some(
      (uuid) => !(state.cleanedPurges || []).includes(uuid),
    );
    const remote = await this.discover(
      fullScan ? {} : state.known,
      scannedPurges.length > 0,
    );
    state = await storage.mutate((current) => {
      let history = current.history;
      for (const content of remote.incoming)
        history = window.loopMerge(history, content);
      return { ...current, history };
    });
    if (
      (JSON.parse(state.history).changes || []).some(
        (change) =>
          change.deviceId === state.device && change.sequence > state.ack,
      )
    ) {
      const acknowledged = await this.publish(state);
      state = await storage.mutate((current) => ({
        ...current,
        ack: Math.max(current.ack, acknowledged),
      }));
    }
    if (scannedPurges.length)
      await this.scrubPurged(state.history, remote.packages);
    // A failed cleanup leaves these files unacknowledged so the next scan retries it.
    // Purges received during this scan require their own full scan next time.
    return storage.mutate((current) => ({
      ...current,
      known: { ...current.known, ...remote.accepted },
      cleanedPurges: [
        ...new Set([...(current.cleanedPurges || []), ...scannedPurges]),
      ],
    }));
  }
  async discover(known, includeOldPacks = false) {
    const all = [];
    let pageToken;
    do {
      const params = new URLSearchParams({
        spaces: "appDataFolder",
        pageSize: "1000",
        fields:
          "nextPageToken,incompleteSearch,files(id,name,mimeType,appProperties)",
        q: `trashed = false and appProperties has { key='namespace' and value='${NAMESPACE}' } and appProperties has { key='workspace' and value='${this.workspace}' }`,
      });
      if (pageToken) params.set("pageToken", pageToken);
      const page = await (
        await this.request(
          `https://www.googleapis.com/drive/v3/files?${params}`,
        )
      ).json();
      if (page.incompleteSearch || !Array.isArray(page.files))
        throw new Error("Incomplete discovery. Saved edits remain pending.");
      all.push(...page.files);
      pageToken = page.nextPageToken;
    } while (pageToken);
    const latest = new Map();
    for (const file of all) {
      const properties = file.appProperties;
      if (
        properties?.namespace !== NAMESPACE ||
        properties.workspace !== this.workspace ||
        file.mimeType !== "application/json" ||
        !/^[a-zA-Z0-9_-]{1,128}$/.test(properties.device) ||
        !/^[1-9][0-9]*$/.test(properties.revision) ||
        !Number.isSafeInteger(Number(properties.revision))
      )
        throw new Error("Invalid workspace metadata. No changes were applied.");
      const previous = latest.get(properties.device) || [];
      if (
        !previous.length ||
        Number(properties.revision) > Number(previous[0].appProperties.revision)
      )
        latest.set(properties.device, [file]);
      else if (properties.revision === previous[0].appProperties.revision)
        previous.push(file);
    }
    const packages = [],
      incoming = [],
      accepted = {};
    for (const files of includeOldPacks
      ? all.map((file) => [file])
      : latest.values())
      for (const file of files) {
        if (known[file.id]) continue;
        const response = await this.request(
          `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(file.id)}?alt=media`,
        );
        const content = await response.text();
        if (new TextEncoder().encode(content).length > MAX_BYTES)
          throw new Error("Workspace package exceeds the supported size.");
        const pack = JSON.parse(
          window.loopDecodePack(content, this.account, this.workspace),
        );
        if (
          pack.deviceId !== file.appProperties.device ||
          String(pack.revision) !== file.appProperties.revision ||
          file.name !==
            `${NAMESPACE}-${this.workspace}-${pack.deviceId}-${pack.revision}.json`
        )
          throw new Error("Package metadata does not match its content.");
        incoming.push(JSON.stringify(pack.history));
        packages.push({
          id: file.id,
          device: pack.deviceId,
          payloadHabits: JSON.parse(
            window.loopPayloadHabits(content, this.account, this.workspace),
          ),
        });
        accepted[file.id] = true;
      }
    return { incoming, accepted, packages };
  }
  async scrubPurged(history, packages) {
    const purged = new Set(JSON.parse(history).purgedHabits || []);
    const dirty = packages.filter((pack) =>
      pack.payloadHabits.some((uuid) => purged.has(uuid)),
    );
    // Publish complete redacted device histories before removing any old payload.
    for (const device of new Set(dirty.map((pack) => pack.device)))
      await this.publish({ history, device });
    for (const pack of dirty)
      await this.request(
        `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(pack.id)}`,
        { method: "DELETE" },
      );
  }
  async deleteTestWorkspace(confirmedRun) {
    if (
      confirmedRun !== this.workspace ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(
        confirmedRun,
      )
    )
      throw new Error(
        "Only a confirmed isolated test workspace can be removed.",
      );
    // Validate every revision, account, namespace and workspace before any delete.
    const { accepted } = await this.discover({}, true);
    const ids = Object.keys(accepted);
    for (const id of ids)
      await this.request(
        `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}`,
        { method: "DELETE" },
      );
    return ids.length;
  }
  async publish(state) {
    const content = window.loopPack(
      state.history,
      state.device,
      this.workspace,
    );
    if (!content) return 0;
    if (new TextEncoder().encode(content).length > MAX_BYTES)
      throw new Error(
        "History exceeds the supported package size. Edits remain saved locally.",
      );
    const pack = JSON.parse(content);
    const metadata = {
      name: `${NAMESPACE}-${this.workspace}-${state.device}-${pack.revision}.json`,
      mimeType: "application/json",
      parents: ["appDataFolder"],
      appProperties: {
        namespace: NAMESPACE,
        workspace: this.workspace,
        device: state.device,
        revision: String(pack.revision),
      },
    };
    const boundary = `loop_${crypto.randomUUID()}`;
    const body = `--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(metadata)}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${content}\r\n--${boundary}--\r\n`;
    await this.request(
      "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
      {
        method: "POST",
        headers: { "Content-Type": `multipart/related; boundary=${boundary}` },
        body,
      },
    );
    return pack.revision;
  }
}
