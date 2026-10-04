import { NAMESPACE, validateRecord, validateRun } from './drive-gate.mjs';

// Development-only cost measurement. Never reads or writes ordinary habit data.
export async function measurePackaging(gate, runId, count = 1000) {
  validateRun(runId);
  if (!Number.isInteger(count) || count < 1 || count > 10000) throw new Error('Invalid sample count.');
  const namespace = `${NAMESPACE}-packaging`;
  const probeId = crypto.randomUUID();
  const uploaded = [];
  const packs = [];
  try {
    for (const producer of ['android', 'browser']) {
      const records = Array.from({ length: count }, (_, index) => {
        const amountMillis = index + 1000;
        const notes = `Packaging cost sample ${index} — آزمائش`;
        return {
          schema: 1, namespace: NAMESPACE, runId, operationId: crypto.randomUUID(), producer,
          accountId: gate.accountId, amountMillis, notes, progress: gate.evaluate(amountMillis, notes),
        };
      });
      const pack = { schema: 1, namespace, runId, probeId, producer, records };
      packs.push(pack);
      const metadata = {
        name: `${namespace}-${probeId}-${producer}.json`, mimeType: 'application/json', parents: ['appDataFolder'],
        appProperties: { namespace, runId, probeId },
      };
      const boundary = `loop_${crypto.randomUUID()}`;
      const body = `--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(metadata)}\r\n` +
        `--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(pack)}\r\n--${boundary}--\r\n`;
      const result = await (await gate.request('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', {
        method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body,
      })).json();
      if (typeof result.id !== 'string') throw new Error('Drive returned no file identity.');
      uploaded.push(result.id);
    }
    const start = performance.now();
    let requests = 0, bytes = 0, records = 0;
    const params = new URLSearchParams({
      spaces: 'appDataFolder', pageSize: '1000', fields: 'nextPageToken,incompleteSearch,files(id,name,mimeType,appProperties)',
      q: `trashed = false and appProperties has { key='namespace' and value='${namespace}' } and appProperties has { key='probeId' and value='${probeId}' }`,
    });
    const page = await (await gate.request(`https://www.googleapis.com/drive/v3/files?${params}`)).json();
    requests++;
    if (page.incompleteSearch || page.nextPageToken || page.files?.length !== uploaded.length) throw new Error('Incomplete package discovery.');
    for (const file of page.files) {
      if (!uploaded.includes(file.id) || file.appProperties?.namespace !== namespace || file.appProperties?.runId !== runId ||
          file.appProperties?.probeId !== probeId || file.mimeType !== 'application/json') throw new Error('Unexpected package metadata.');
      const content = await (await gate.request(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(file.id)}?alt=media`)).text();
      requests++;
      bytes += new TextEncoder().encode(content).length;
      const pack = JSON.parse(content);
      if (pack.schema !== 1 || pack.namespace !== namespace || pack.runId !== runId || pack.probeId !== probeId ||
          !['android', 'browser'].includes(pack.producer) || file.name !== `${namespace}-${probeId}-${pack.producer}.json` ||
          !Array.isArray(pack.records) || pack.records.length !== count ||
          JSON.stringify(pack) !== JSON.stringify(packs.find(candidate => candidate.producer === pack.producer))) throw new Error('Package content changed.');
      for (const record of pack.records) validateRecord(record, runId, gate.accountId, gate.evaluate);
      records += pack.records.length;
    }
    return { files: uploaded.length, records, requests, downloadedBytes: bytes, elapsedMs: Math.round(performance.now() - start) };
  } finally {
    // These identities came from this invocation's successful uploads, never a broad cleanup query.
    for (const id of uploaded) await gate.request(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }
}
