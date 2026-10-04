import { DriveGate, SCOPE, validateRun } from './drive-gate.mjs';

const element = id => document.getElementById(id);
let gate;
let busy = false;
let expiresAt = 0;
const evaluate = (amount, notes) => JSON.parse(window.loopProbe(amount, notes));
const status = text => { element('status').textContent = text; };
function updateButtons() {
  for (const id of ['connect', 'new-run']) element(id).disabled = busy;
  for (const id of ['publish', 'discover', 'cleanup']) element(id).disabled = busy || !gate || Date.now() >= expiresAt;
}
async function action(work) {
  if (busy) return;
  busy = true;
  updateButtons();
  try { await work(); } catch (error) { status(error.message); }
  finally { busy = false; updateButtons(); }
}
element('new-run').onclick = () => { element('run-id').value = crypto.randomUUID(); };
element('connect').onclick = () => {
  if (!window.google?.accounts?.oauth2 || !window.loopProbe) {
    status('Google authorization or the Kotlin core is unavailable. Check connectivity and the web build.');
    return;
  }
  // Obtain each token through a user gesture. Never persist or export it.
  google.accounts.oauth2.initTokenClient({
    client_id: element('client-id').value.trim(), scope: SCOPE,
    error_callback: () => status('Authorization was cancelled or blocked. Connect again.'),
    callback: response => action(async () => {
      gate = undefined;
      if (response.error || !google.accounts.oauth2.hasGrantedAllScopes(response, SCOPE)) {
        throw new Error('Drive app-data permission was not granted.');
      }
      const candidate = new DriveGate(response.access_token, '', evaluate);
      const about = await (await candidate.request('https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)')).json();
      if (!about.user?.permissionId) throw new Error('Drive did not return an account identity.');
      candidate.accountId = about.user.permissionId;
      expiresAt = Date.now() + Number(response.expires_in) * 1000;
      gate = candidate;
      status(`Connected as ${about.user.emailAddress}. Ready for this synthetic test run.`);
    }),
  }).requestAccessToken({ prompt: 'select_account' });
};
element('publish').onclick = () => action(async () => {
  const runId = validateRun(element('run-id').value.trim());
  const amount = Number(element('amount').value);
  const amountMillis = Math.round(amount * 1000);
  if (!Number.isFinite(amount) || Math.abs(amount * 1000 - amountMillis) > 1e-6) {
    throw new Error('Enter a finite numeric amount with at most three decimal places.');
  }
  status('Uploading synthetic browser sample…');
  const record = await gate.publish(runId, amountMillis, element('notes').value);
  element('results').textContent = JSON.stringify(record, null, 2);
  status('Browser sample accepted by Drive. Discover this run on Android.');
});
element('discover').onclick = () => action(async () => {
  status('Discovering synthetic records from Drive…');
  const files = await gate.discover(element('run-id').value.trim());
  const producers = new Set(files.map(file => file.record.producer));
  element('results').textContent = JSON.stringify({ metrics: gate.metrics, records: files.map(file => file.record) }, null, 2);
  status(producers.has('android') && producers.has('browser')
    ? 'Browser verified samples from both clients with matching shared-core progress. Verify reverse discovery on Android too.'
    : 'Gate pending: publish samples on both Android and browser using this run UUID and Google account.');
});
element('cleanup').onclick = () => action(async () => {
  const runId = validateRun(element('run-id').value.trim());
  if (!window.confirm(`Permanently delete only synthetic records for test run ${runId}?`)) return;
  const deleted = await gate.cleanup(runId);
  element('results').textContent = '';
  status(`Deleted ${deleted} verified synthetic records. Ordinary habit data was not selected.`);
});
setInterval(() => {
  if (gate && Date.now() >= expiresAt) {
    gate = undefined;
    updateButtons();
    status('Reconnect required: the Google access token expired.');
  }
}, 1000);
updateButtons();
