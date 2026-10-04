#!/usr/bin/env node
// Drive the real installed Android Chrome page through its public UI.
// First forward chrome_devtools_remote with adb; Node 22+ supplies WebSocket.
const [action, run, name, date, kind, value, notes = ""] =
  process.argv.slice(2);
if (
  !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(
    run || "",
  )
)
  throw new Error("Supply an isolated test UUID");
const targets = await (
  await fetch(`http://127.0.0.1:${process.env.LOOP_CHROME_PORT || 9223}/json`)
).json();
const matching = targets.filter((target) => {
  try {
    const url = new URL(target.url);
    return (
      url.origin === "http://localhost:8080" &&
      url.pathname === "/app/" &&
      url.searchParams.get("testRun") === run
    );
  } catch {
    return false;
  }
});
if (matching.length !== 1)
  throw new Error("Open exactly one installed synthetic test page");
const socket = new WebSocket(matching[0].webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener(
    "error",
    () => reject(new Error("Android Chrome is unavailable")),
    { once: true },
  );
});
let sequence = 0;
const pending = new Map();
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  const operation = pending.get(message.id);
  if (!operation) return;
  pending.delete(message.id);
  clearTimeout(operation.timeout);
  if (message.error) operation.reject(new Error(message.error.message));
  else operation.resolve(message.result);
});
function command(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(
      () => {
        pending.delete(id);
        reject(new Error("Android UI action timed out"));
      },
      action === "cleanup" ? 330000 : 75000,
    );
    pending.set(id, { resolve, reject, timeout });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
try {
  let expression;
  if (action === "inspect") {
    expression = `({status:document.querySelector('#sync-status')?.textContent, saved:document.querySelector('#save-status')?.textContent, cleanupAvailable:!!document.querySelector('#test-tools')&&!document.querySelector('#test-tools').hidden, text:document.body.innerText.slice(0,12000)})`;
  } else if (action === "connect") {
    expression = `(()=>{document.querySelector('#connect').click();return {action:'Google account chooser requested'};})()`;
  } else if (action === "record") {
    if (!name || !date || !["amount", "outcome"].includes(kind))
      throw new Error("record UUID NAME DATE {amount|outcome} VALUE [NOTES]");
    const amountOrOutcome = kind === "amount" ? Number(value) : value;
    if (kind === "amount" && !Number.isFinite(amountOrOutcome))
      throw new Error("Invalid amount");
    expression = `(async()=>{const h=await import('./workflow.mjs?acceptance='+Date.now()); const entry=await h.record(${JSON.stringify(name)},${JSON.stringify(date)},${JSON.stringify(amountOrOutcome)},${JSON.stringify(notes)});return {entry,sync:await h.synchronized()};})()`;
  } else if (action === "cleanup") {
    expression = `(async()=>{const h=await import('./workflow.mjs?acceptance='+Date.now());await h.synchronized();const form=document.querySelector('#test-cleanup-form');if(!form||document.querySelector('#test-tools').hidden)throw new Error('Updated test cleanup UI is unavailable');form.elements.run.value=${JSON.stringify(run)};form.requestSubmit();const end=Date.now()+300000;while(Date.now()<end){const status=document.querySelector('#sync-status').textContent;if(status.startsWith('Removed '))return {status};if(!form.querySelector('button').disabled)throw new Error(status);await new Promise(r=>setTimeout(r,250));}throw new Error('Cleanup did not finish within five minutes');})()`;
  } else {
    socket.close();
    throw new Error(
      "Usage: android-web-workflow.mjs {inspect|connect|record|cleanup} TEST_UUID ...",
    );
  }
  const result = await command("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: action === "connect",
  });
  if (result.exceptionDetails)
    throw new Error(
      result.exceptionDetails.exception?.description ||
        "Android UI action failed",
    );
  console.log(JSON.stringify(result.result.value, null, 2));
} finally {
  socket.close();
}
