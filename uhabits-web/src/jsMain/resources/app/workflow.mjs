// Live acceptance actions use only the app's public forms and visible status.
// Import from the connected synthetic workspace; no access token is returned.
function assert(condition, message) {
  if (!condition) throw new Error(message);
}
function field(form, name, value) {
  form.elements[name].value = String(value);
  form.elements[name].dispatchEvent(new Event("change", { bubbles: true }));
}
function click(selector) {
  const button = document.querySelector(selector);
  assert(button, `Missing UI control: ${selector}`);
  button.click();
}
async function waitFor(predicate, message) {
  const deadline = Date.now() + 60000;
  while (!predicate()) {
    assert(Date.now() < deadline, message);
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}
function requireTestWorkspace() {
  assert(
    /^[a-f0-9-]{36}$/.test(
      new URLSearchParams(location.search).get("testRun") || "",
    ),
    "An isolated testRun is required",
  );
}
export async function record(name, date, value, notes) {
  requireTestWorkspace();
  click("[data-page=habits]");
  const item = [...document.querySelectorAll("[data-detail]")].find(
    (button) => button.textContent === name,
  );
  assert(item, `Habit not discovered: ${name}`);
  click(`[data-detail="${item.dataset.detail}"]`);
  click(`[data-record="${item.dataset.detail}"]:not([data-date])`);
  const form = document.querySelector("#entry-form");
  assert(
    document.querySelector("#entry-dialog").open,
    "Entry editor did not open",
  );
  assert(
    form.elements.uuid.value === item.dataset.detail,
    "Wrong habit entry editor",
  );
  field(form, "date", date);
  field(form, "outcome", typeof value === "number" ? "amount" : value);
  if (typeof value === "number") field(form, "amount", value);
  field(form, "notes", notes);
  form.requestSubmit();
  await waitFor(
    () =>
      !document.querySelector("#entry-dialog").open ||
      form.querySelector(".form-error").textContent,
    "Entry was not saved",
  );
  assert(
    !document.querySelector("#entry-dialog").open,
    form.querySelector(".form-error").textContent,
  );
  assert(
    document.querySelector("#save-status").textContent ===
      "Saved on this device",
    "Save failed",
  );
  const row = document
    .querySelector(
      `[data-record="${item.dataset.detail}"][data-date="${date}"]`,
    )
    ?.closest("tr");
  assert(
    row?.cells[0].textContent === date && row.cells[2].textContent === notes,
    "Saved date or notes differ from the submitted entry",
  );
  const expected =
    typeof value === "number"
      ? String(value)
      : { 2: "Completed", 0: "Missed", 3: "Skipped", "-1": "Unknown" }[value];
  assert(
    row.cells[1].textContent.startsWith(expected),
    "Saved outcome differs from the submitted entry",
  );
  return { name, date, value, notes, saved: true };
}
export async function synchronized() {
  requireTestWorkspace();
  click("#sync");
  await waitFor(
    () =>
      document.querySelector("#sync-status").textContent ===
      "Synchronized with Drive",
    "Live Drive synchronization did not complete",
  );
  return { status: document.querySelector("#sync-status").textContent };
}
export function visibleHabits() {
  requireTestWorkspace();
  click("[data-page=habits]");
  return [...document.querySelectorAll("[data-detail]")].map((button) => ({
    name: button.textContent,
    uuid: button.dataset.detail,
  }));
}
