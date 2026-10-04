import { config } from "./config.mjs";
import { openStorage, openMemoryStorage } from "./storage.mjs";
import { DriveWorkspace, SCOPE } from "./drive.mjs";

const $ = (id) => document.getElementById(id);
const COLORS = [
  "#d32f2f",
  "#e64a19",
  "#f57c00",
  "#ff8f00",
  "#f9a825",
  "#afb42b",
  "#7cb342",
  "#388e3c",
  "#00897b",
  "#00acc1",
  "#039be5",
  "#1976d2",
  "#303f9f",
  "#5e35b1",
  "#8e24aa",
  "#d81b60",
  "#5d4037",
  "#424242",
  "#757575",
  "#9e9e9e",
];
const COLOR_NAMES = [
  "Red",
  "Deep orange",
  "Orange",
  "Amber",
  "Yellow",
  "Lime",
  "Light green",
  "Green",
  "Teal",
  "Cyan",
  "Light blue",
  "Blue",
  "Indigo",
  "Deep purple",
  "Purple",
  "Pink",
  "Brown",
  "Dark grey",
  "Grey",
  "Light grey",
];
const WEEKDAYS = [
  "Sunday",
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
];
const testRun = new URLSearchParams(location.search).get("testRun");
if (testRun && !/^[a-f0-9-]{36}$/.test(testRun))
  throw new Error("Invalid test workspace.");
const workspace = testRun || config.workspaceId;
const temporary =
  location.pathname.endsWith("/session/") ||
  new URLSearchParams(location.search).get("mode") === "temporary";
const temporaryUrl = new URL(location.href);
temporaryUrl.pathname = temporaryUrl.pathname.replace(/app\/$/, "session/");
temporaryUrl.searchParams.delete("mode");
$("temporary-session").href = temporaryUrl.href;
$("temporary-session").hidden = temporary;
$("end-session").hidden = !temporary;
if (temporary) {
  document.querySelector('link[rel="manifest"]').remove();
  $("storage-description").textContent =
    "This temporary session keeps data only until it ends. New edits require an online authorized connection. Keep it open until pending saves finish.";
}
let ended = false;
// Keep an installed synthetic test in its isolated workspace on every launch.
if (testRun) {
  document.querySelector(".brand").href = location.href;
  if (!temporary)
    document.querySelector("link[rel=manifest]").href =
      `manifest.webmanifest?testRun=${testRun}`;
}
$("connect").disabled = true;
$("new-habit").disabled = true;
let storage,
  state,
  view,
  drive,
  expiresAt = 0,
  syncing = false,
  syncAgain = false,
  retry = 0,
  retryTimer,
  selected,
  page = "habits",
  offset = 0,
  period = 1,
  installPrompt,
  habitBaseline,
  settingsBaseline;
let cleaningTest = false;
const escape = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        character
      ],
  );
const errorMessage = (error) => error?.message || String(error);
function today() {
  const date = new Date();
  const start = view?.dayStart || 0;
  if (date.getHours() < start) date.setDate(date.getDate() - 1);
  return localDate(date);
}
function localDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
function shift(date, days) {
  const [year, month, day] = date.split("-").map(Number);
  return localDate(new Date(year, month - 1, day + days, 12));
}
function currentView() {
  const assumed = today();
  view = JSON.parse(window.loopView(state.history, assumed, period));
  if (today() !== assumed)
    view = JSON.parse(window.loopView(state.history, today(), period));
  return view;
}
function pending() {
  if (!state) return 0;
  return (JSON.parse(state.history).changes || []).filter(
    (change) => change.deviceId === state.device && change.sequence > state.ack,
  ).length;
}
function cloudStatus(text) {
  $("sync-status").textContent = text;
}
function savedStatus() {
  $("save-status").textContent = temporary
    ? pending()
      ? "Pending save in memory · keep this session open"
      : drive
        ? "Saved in Drive · temporary session"
        : "Temporary session · no offline storage"
    : "Saved on this device";
}
function refreshCloud() {
  if (!state) return;
  $("new-habit").disabled =
    ended ||
    (temporary && (!drive || !navigator.onLine || Date.now() >= expiresAt));
  if (temporary) savedStatus();
  if (!drive)
    cloudStatus(
      state.account === "unbound"
        ? "Local device · connect to synchronize"
        : `Reconnect required${pending() ? ` · ${pending()} pending` : ""}`,
    );
  else if (!navigator.onLine) cloudStatus(`Offline · ${pending()} pending`);
  else if (pending()) cloudStatus(`${pending()} pending changes`);
}
async function mutateHistory(change) {
  try {
    if (ended) throw new Error("This session has ended.");
    if (temporary && (!navigator.onLine || !drive || Date.now() >= expiresAt))
      throw new Error(
        "Temporary sessions can edit only while online and authorized.",
      );
    if (cleaningTest)
      throw new Error("This test workspace is closed for cleanup.");
    state = await storage.mutate((current) => ({
      ...current,
      history: change(current),
    }));
    currentView();
    render();
    savedStatus();
    refreshCloud();
    if (temporary) await sync();
    else scheduleSync();
  } catch (error) {
    $("save-status").textContent = errorMessage(error);
    throw error;
  }
}
async function edit(patch) {
  return mutateHistory((current) =>
    window.loopEdit(
      current.history,
      current.device,
      crypto.randomUUID(),
      JSON.stringify(patch),
    ),
  );
}
function scheduleSync(delay = 400) {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => sync(), delay);
}
async function sync() {
  if (cleaningTest || ended) return;
  if (syncing) {
    syncAgain = true;
    return;
  }
  if (!drive || Date.now() >= expiresAt || !navigator.onLine) {
    if (Date.now() >= expiresAt) drive = undefined;
    refreshCloud();
    return;
  }
  syncing = true;
  $("sync").disabled = true;
  cloudStatus("Synchronizing…");
  try {
    const purging = (JSON.parse(state.history).purgedHabits || []).length > 0;
    const remote = await drive.discover(purging ? {} : state.known, purging);
    state = await storage.mutate((current) => {
      let history = current.history;
      for (const content of remote.incoming)
        history = window.loopMerge(history, content);
      return {
        ...current,
        history,
        known: { ...current.known, ...remote.accepted },
      };
    });
    currentView();
    render();
    if (pending()) {
      const snapshot = state;
      const acknowledged = await drive.publish(snapshot);
      state = await storage.mutate((current) => ({
        ...current,
        ack: Math.max(current.ack, acknowledged),
      }));
    }
    if (purging) await drive.scrubPurged(state.history, remote.packages);
    retry = 0;
    cloudStatus(
      pending() ? `${pending()} pending changes` : "Synchronized with Drive",
    );
    savedStatus();
  } catch (error) {
    const message = errorMessage(error);
    cloudStatus(message);
    if (message.startsWith("Reconnect required")) {
      drive = undefined;
      refreshCloud();
    } else if (drive)
      scheduleSync(Math.min(60000, 1000 * 2 ** Math.min(++retry, 6)));
  } finally {
    syncing = false;
    $("sync").disabled = false;
    if (syncAgain || (pending() && retry === 0)) {
      syncAgain = false;
      if (drive) scheduleSync();
    }
  }
}
$("connect").onclick = () => {
  if (syncing || ended) return;
  if (!window.google?.accounts?.oauth2) {
    cloudStatus("Google sign-in is unavailable. Reconnect when online.");
    return;
  }
  google.accounts.oauth2
    .initTokenClient({
      client_id: config.webClientId,
      scope: SCOPE,
      error_callback: () =>
        cloudStatus(
          "Google sign-in was cancelled. Saved habits remain on this device.",
        ),
      callback: async (response) => {
        try {
          if (
            response.error ||
            !google.accounts.oauth2.hasGrantedAllScopes(response, SCOPE)
          )
            throw new Error("Google Drive permission was not granted.");
          const candidate = new DriveWorkspace(
            response.access_token,
            "",
            workspace,
          );
          const about = await (
            await candidate.request(
              "https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)",
            )
          ).json();
          if (!about.user?.permissionId)
            throw new Error("Google did not return an account identity.");
          candidate.account = about.user.permissionId;
          state = await storage.mutate((current) => ({
            ...current,
            account: candidate.account,
            history: window.loopBindAccount(current.history, candidate.account),
          }));
          drive = candidate;
          expiresAt = Date.now() + Number(response.expires_in) * 1000;
          $("connect").textContent = "Reconnect Google Drive";
          $("connect").title = about.user.emailAddress;
          await sync();
        } catch (error) {
          cloudStatus(errorMessage(error));
        }
      },
    })
    .requestAccessToken({ prompt: "select_account" });
};
function outcome(habit, record) {
  if (!record || record.value === -1)
    return {
      text: record?.computed === 1 ? "✓" : "?",
      description:
        record?.computed === 1
          ? record.recorded
            ? "Unknown · inferred completion"
            : "Inferred completion"
          : "Unknown",
      inferred: record?.computed === 1,
    };
  if (record.value === 3) return { text: "−", description: "Skipped" };
  if (habit.tracking?.type === 1)
    return {
      text: String(record.value / 1000),
      description: `${record.value / 1000} ${habit.tracking.unit}`,
    };
  return record.value === 2
    ? { text: "✓", description: "Completed" }
    : { text: "×", description: "Missed" };
}
function completed(habit) {
  return !!habit.completed;
}
function render() {
  if (!view) return;
  $("new-habit").disabled =
    temporary && (!drive || !navigator.onLine || Date.now() >= expiresAt);
  $("habits-page").hidden = !["habits", "archived"].includes(page);
  $("recovery-page").hidden = page !== "recovery";
  $("detail-page").hidden = page !== "detail";
  $("settings-page").hidden = page !== "settings";
  document
    .querySelectorAll("[data-page]")
    .forEach((button) =>
      button.setAttribute(
        "aria-current",
        button.dataset.page === page ? "page" : "false",
      ),
    );
  $("list-title").textContent =
    page === "archived" ? "Archived habits" : "Habits";
  const query = $("search").value.toLocaleLowerCase();
  let habits = view.habits.filter(
    (habit) =>
      !habit.deleted &&
      habit.archived === (page === "archived") &&
      `${habit.name} ${habit.question} ${habit.description}`
        .toLocaleLowerCase()
        .includes(query) &&
      (!$("hide-completed").checked || !completed(habit)),
  );
  const sort = $("sort").value;
  habits.sort((a, b) =>
    sort === "name"
      ? a.name.localeCompare(b.name)
      : sort === "score"
        ? (b.score || 0) - (a.score || 0)
        : sort === "color"
          ? a.color - b.color
          : sort === "status"
            ? Number(completed(a)) - Number(completed(b))
            : a.position - b.position || a.uuid.localeCompare(b.uuid),
  );
  const days = Array.from({ length: 7 }, (_, index) =>
    shift(today(), -offset - index),
  );
  $("habit-list").innerHTML = habits.length
    ? `<div class="grid-wrap"><table><thead><tr><th class="habit-name">Habit</th><th>Strength</th>${days.map((date) => `<th class="day"><span>${escape(new Date(`${date}T12:00:00`).toLocaleDateString(undefined, { weekday: "short" }))}</span><br>${escape(date.slice(5))}</th>`).join("")}<th>Order</th></tr></thead><tbody>${habits
        .map(
          (habit) =>
            `<tr style="--habit:${COLORS[habit.color]}"><th class="habit-name"><button data-detail="${habit.uuid}" style="color:var(--habit)">${escape(habit.name)}</button><small>${escape(habit.question)}</small></th><td class="strength">${Math.round((habit.score || 0) * 100)}%</td>${days
              .map((date) => {
                const record = habit.history?.find(
                  (entry) => entry.date === date,
                );
                const display = outcome(habit, record);
                const conflict = view.conflicts[`entry:${habit.uuid}:${date}`];
                return `<td><button class="entry ${display.inferred ? "inferred" : ""}" data-entry="${habit.uuid}" data-date="${date}" data-value="${record?.value ?? -1}" aria-label="${escape(`${habit.name}, ${date}: ${conflict ? "Competing entries" : display.description}. ${habit.tracking?.type === 1 ? "Edit amount" : "Toggle completion"}`)}" title="${escape(record?.notes || display.description)}">${conflict ? "!" : escape(display.text)}</button></td>`;
              })
              .join(
                "",
              )}<td class="order"><button data-move="${habit.uuid}" data-direction="-1" aria-label="Move ${escape(habit.name)} up">↑</button><button data-move="${habit.uuid}" data-direction="1" aria-label="Move ${escape(habit.name)} down">↓</button></td></tr>`,
        )
        .join("")}</tbody></table></div>`
    : `<div class="empty"><h2>${page === "archived" ? "No archived habits" : "A small habit, every day"}</h2><p>${query ? "No habits match your search." : page === "archived" ? "Archived habits keep their history here." : "Create your first habit to begin tracking."}</p></div>`;
  const settings = $("settings-form");
  if (!settings.contains(document.activeElement)) {
    settingsBaseline = { dayStart: view.dayStart, weekStart: view.weekStart };
    settings.elements.dayStart.value = view.dayStart;
    settings.elements.weekStart.value = view.weekStart;
  }
  if (page === "detail") renderDetail();
  $("recovery-list").innerHTML =
    view.habits
      .filter((habit) => habit.deleted)
      .map(
        (habit) =>
          `<article class="card"><h2>${escape(habit.name)}</h2><p>${escape(habit.description)}</p><details><summary>Retained history</summary><pre>${escape(
            JSON.stringify(
              (habit.history || []).filter((entry) => entry.recorded),
              null,
              2,
            ),
          )}</pre></details><button data-restore="${habit.uuid}">Restore habit</button><button data-purge="${habit.uuid}">Purge permanently</button></article>`,
      )
      .join("") || "<p>No deleted habits.</p>";
  const conflicts = Object.entries(view.conflicts);
  $("conflicts").hidden = !conflicts.length;
  $("conflicts").innerHTML = conflicts.length
    ? `<h2>Competing revisions preserved</h2><p>Both versions remain saved. Editing an affected value is paused until its conflict is resolved.</p>${conflicts
        .map(
          ([key, revisions]) =>
            `<details><summary>${escape(key)}</summary>${revisions.map((revision, index) => `<pre>${escape(JSON.stringify(revision.value, null, 2))}</pre><button data-resolve="${escape(key)}" data-revision="${index}">Keep this version</button>`).join("")}</details>`,
        )
        .join("")}`
    : "";
}
function chart(values, color, score = false) {
  const recent = values.slice(0, 24).reverse();
  const max = score ? 1 : Math.max(1, ...recent.map((item) => item.value));
  return `<div class="chart" role="img" aria-label="${score ? "Habit strength" : "Recorded totals"} by selected period" style="--habit:${color}">${recent.map((item) => `<div class="chart-bar" style="height:${Math.max(1, (item.value / max) * 100)}%" title="${escape(`${item.date}: ${score ? Math.round(item.value * 100) + "%" : item.value}`)}"></div>`).join("")}</div><details class="chart-legend"><summary>Show graph values</summary><table><tbody>${recent.map((item) => `<tr><td>${item.date}</td><td>${score ? (item.value * 100).toFixed(2) + "%" : item.value}</td></tr>`).join("")}</tbody></table></details>`;
}
function renderDetail() {
  const habit = view.habits.find((item) => item.uuid === selected);
  if (!habit) {
    page = "habits";
    render();
    return;
  }
  const color = COLORS[habit.color];
  const periods = ["Day", "Week", "Month", "Quarter", "Year"];
  const orderedDays = Array.from(
    { length: 7 },
    (_, index) => (view.weekStart - 1 + index) % 7,
  );
  $("detail-page").innerHTML =
    `<button id="back">← Habits</button><div class="heading"><h1 style="color:${color}">${escape(habit.name)}</h1><button data-edit="${habit.uuid}">Edit habit</button></div><p class="detail-meta">${escape(habit.question)}<br>${escape(habit.description)}</p><div class="detail-actions"><button data-record="${habit.uuid}" class="primary">Record a day</button><button data-delete="${habit.uuid}">Delete habit</button><button data-archive="${habit.uuid}">${habit.archived ? "Reactivate" : "Archive"}</button><label>Graph period <select id="period">${periods.map((label, index) => `<option value="${index}" ${index === period ? "selected" : ""}>${label}</option>`).join("")}</select></label></div>${
      habit.tracking
        ? `<div class="details-grid"><section class="card"><h2>Habit strength</h2><p class="metric">${Math.round((habit.score || 0) * 100)}%</p>${chart(habit.scores || [], color, true)}</section><section class="card"><h2>${habit.tracking.type === 1 ? "Amount" : "Completed days"}</h2><p class="muted">${escape(habit.tracking.type === 1 ? `${habit.tracking.targetType === 0 ? "At least" : "At most"} ${habit.tracking.targetValue} ${habit.tracking.unit} per ${habit.tracking.freqDen} days` : `${habit.tracking.freqNum} times in ${habit.tracking.freqDen} days`)}</p>${chart(habit.bars || [], color)}</section>${habit.tracking.type === 1 ? `<section class="card"><h2>Targets</h2>${(habit.targets || []).map((target) => `<div class="target-row"><span>${{ 1: "Today", 7: "This week", 30: "This month", 91: "This quarter", 365: "This year" }[target.days]}</span><span>${target.value} / ${Number(target.target.toFixed(3))} ${escape(habit.tracking.unit)}</span></div>`).join("")}</section>` : ""}<section class="card"><h2>Best streaks</h2>${habit.streaks?.length ? habit.streaks.map((streak) => `<div class="target-row"><span>${streak.start} – ${streak.end}</span><strong>${streak.length} days</strong></div>`).join("") : '<p class="muted">Your first streak starts with a completed day.</p>'}</section></div><section class="card"><h2>Weekday frequency</h2><div class="weekday-table"><table><thead><tr><th>Month</th>${orderedDays.map((day) => `<th>${WEEKDAYS[day].slice(0, 3)}</th>`).join("")}</tr></thead><tbody>${(habit.weekdays || []).map((month) => `<tr><td>${month.month.slice(0, 7)}</td>${orderedDays.map((day) => `<td>${month.values[(day + 1) % 7] / (habit.tracking.type === 1 ? 1000 : 1)}</td>`).join("")}</tr>`).join("")}</tbody></table></div></section><section class="card"><h2>History & entry notes</h2><div class="detail-history"><table><thead><tr><th>Date</th><th>Outcome</th><th>Entry notes</th><th></th></tr></thead><tbody>${(
            habit.history || []
          )
            .filter(
              (entry) =>
                entry.recorded ||
                entry.value !== -1 ||
                entry.notes ||
                entry.computed === 1,
            )
            .map(
              (entry) =>
                `<tr><td>${entry.date}</td><td>${escape(outcome(habit, entry).description)}</td><td>${escape(entry.notes)}</td><td><button data-record="${habit.uuid}" data-date="${entry.date}">Edit</button></td></tr>`,
            )
            .join("")}</tbody></table></div></section>`
        : '<div class="card">Tracking settings have competing versions. All revisions are preserved below.</div>'
    }`;
}
function showHabit(uuid) {
  const form = $("habit-form");
  form.reset();
  form.querySelector(".form-error").textContent = "";
  const habit = view.habits.find((item) => item.uuid === uuid);
  habitBaseline = habit ? structuredClone(habit) : undefined;
  form.elements.uuid.value = uuid || "";
  for (const key of ["name", "question", "description", "color"])
    form.elements[key].value = habit?.[key] ?? (key === "color" ? 8 : "");
  for (const key of [
    "type",
    "freqNum",
    "freqDen",
    "targetValue",
    "targetType",
    "unit",
  ])
    form.elements[key].value =
      habit?.tracking?.[key] ??
      {
        type: 0,
        freqNum: 1,
        freqDen: 1,
        targetValue: 0,
        targetType: 0,
        unit: "",
      }[key];
  form.elements.reminderEnabled.checked = !!habit?.reminder;
  if (habit?.reminder) {
    form.elements.reminderTime.value = `${String(habit.reminder.hour).padStart(2, "0")}:${String(habit.reminder.minute).padStart(2, "0")}`;
    for (const checkbox of form.querySelectorAll('[name="reminderDay"]'))
      checkbox.checked = !!(habit.reminder.days & (1 << Number(checkbox.value)));
  }
  $("habit-dialog-title").textContent = uuid ? "Edit habit" : "New habit";
  $("numeric-settings").hidden = form.elements.type.value === "0";
  $("habit-dialog").showModal();
  form.elements.name.focus();
}
function showEntry(uuid, date = today()) {
  const habit = view.habits.find((item) => item.uuid === uuid),
    form = $("entry-form");
  if (!habit?.tracking) return;
  form.reset();
  form.querySelector(".form-error").textContent = "";
  form.elements.uuid.value = uuid;
  form.elements.date.value = date;
  form.elements.date.max = today();
  const numeric = habit.tracking.type === 1;
  form.elements.outcome.innerHTML = numeric
    ? '<option value="amount">Recorded amount</option><option value="3">Skipped</option><option value="-1">Unknown / undo</option>'
    : '<option value="2">Completed</option><option value="0">Missed</option><option value="3">Skipped</option><option value="-1">Unknown / undo</option>';
  loadEntryDate(habit, form, date);
  $("entry-title").textContent = habit.name;
  $("entry-dialog").showModal();
  form.elements.date.focus();
}
document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  try {
    if (button.dataset.resolve) {
      const key = button.dataset.resolve,
        revisions = view.conflicts[key];
      const chosen = revisions[Number(button.dataset.revision)];
      await mutateHistory((current) =>
        window.loopResolve(
          current.history,
          current.device,
          crypto.randomUUID(),
          key,
          JSON.stringify(chosen.value),
          JSON.stringify(revisions.map((revision) => revision.id)),
        ),
      );
    }
    if (
      button.dataset.delete &&
      confirm("Delete this habit into recovery? Its history will be retained.")
    ) {
      await edit({ [`habit:${button.dataset.delete}:deleted`]: true });
      page = "habits";
      render();
    }
    if (button.dataset.restore)
      await mutateHistory((current) =>
        window.loopRestore(
          current.history,
          current.device,
          crypto.randomUUID(),
          button.dataset.restore,
        ),
      );
    if (
      button.dataset.purge &&
      confirm(
        "Permanently remove this habit and its history? It cannot be restored.",
      )
    )
      await mutateHistory((current) =>
        window.loopPurge(
          current.history,
          current.device,
          crypto.randomUUID(),
          button.dataset.purge,
        ),
      );
    if (button.dataset.page) {
      page = button.dataset.page;
      selected = undefined;
      render();
    }
    if (button.dataset.close) $(button.dataset.close).close();
    if (button.id === "back") {
      page = "habits";
      render();
    }
    if (button.dataset.detail) {
      selected = button.dataset.detail;
      page = "detail";
      render();
    }
    if (button.dataset.edit) showHabit(button.dataset.edit);
    if (button.dataset.record)
      showEntry(button.dataset.record, button.dataset.date);
    if (button.dataset.entry) {
      const habit = view.habits.find(
        (item) => item.uuid === button.dataset.entry,
      );
      const date = button.dataset.date;
      if (
        habit.tracking?.type === 1 ||
        view.conflicts[`entry:${habit.uuid}:${date}`]
      )
        showEntry(habit.uuid, date);
      else {
        const record = habit.history?.find((entry) => entry.date === date);
        const current = record?.value ?? -1;
        await edit({
          [`entry:${habit.uuid}:${date}`]: {
            value: { [-1]: 2, 0: -1, 2: 3, 3: 0 }[current] ?? 2,
            notes: record?.notes || "",
          },
        });
      }
    }
    if (button.dataset.archive) {
      const habit = view.habits.find(
        (item) => item.uuid === button.dataset.archive,
      );
      await edit({ [`habit:${habit.uuid}:archived`]: !habit.archived });
    }
    if (button.dataset.move) {
      const all = view.habits
        .filter((habit) => !habit.deleted)
        .sort(
          (a, b) => a.position - b.position || a.uuid.localeCompare(b.uuid),
        );
      const index = all.findIndex(
          (habit) => habit.uuid === button.dataset.move,
        ),
        to = index + Number(button.dataset.direction);
      if (to >= 0 && to < all.length) {
        [all[index], all[to]] = [all[to], all[index]];
        await edit(
          Object.fromEntries(
            all.map((habit, position) => [
              `habit:${habit.uuid}:position`,
              position,
            ]),
          ),
        );
      }
    }
  } catch (error) {
    $("save-status").textContent = errorMessage(error);
  }
});
$("habit-form").elements.type.onchange = (event) =>
  ($("numeric-settings").hidden = event.target.value === "0");
$("habit-form").onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target,
    fields = form.elements,
    uuid = fields.uuid.value || crypto.randomUUID().replaceAll("-", ""),
    existing = habitBaseline,
    prefix = `habit:${uuid}:`;
  const patch = {};
  const values = {
    name: fields.name.value.trim(),
    question: fields.question.value,
    description: fields.description.value,
    color: Number(fields.color.value),
    tracking: {
      type: Number(fields.type.value),
      freqNum: Number(fields.freqNum.value),
      freqDen: Number(fields.freqDen.value),
      targetValue: Number(fields.targetValue.value),
      targetType: Number(fields.targetType.value),
      unit: fields.unit.value,
    },
  };
  const time = fields.reminderTime.value.split(":").map(Number);
  values.reminder = fields.reminderEnabled.checked
    ? {
        hour: time[0],
        minute: time[1],
        days: [...form.querySelectorAll('[name="reminderDay"]:checked')].reduce((mask, checkbox) => mask | (1 << Number(checkbox.value)), 0),
      }
    : null;
  if (values.reminder && (!fields.reminderTime.value || values.reminder.days === 0)) {
    form.querySelector(".form-error").textContent = "Choose a reminder time and at least one day.";
    return;
  }
  for (const [key, value] of Object.entries(values))
    if (!existing || JSON.stringify(existing[key]) !== JSON.stringify(value))
      patch[prefix + key] = value;
  if (!existing) {
    patch[prefix + "position"] = view.habits.length;
    patch[prefix + "archived"] = false;
    patch[prefix + "deleted"] = false;
  }
  try {
    if (Object.keys(patch).length) await edit(patch);
    $("habit-dialog").close();
  } catch (error) {
    form.querySelector(".form-error").textContent = errorMessage(error);
  }
};
$("entry-form").elements.outcome.onchange = (event) =>
  ($("amount-label").hidden = event.target.value !== "amount");
function loadEntryDate(habit, form, date) {
  const record = habit.history?.find((entry) => entry.date === date);
  const numeric = habit.tracking.type === 1;
  form.elements.notes.value = record?.notes || "";
  form.elements.outcome.value = numeric
    ? record?.value === 3
      ? "3"
      : record?.value === -1 && record.recorded
        ? "-1"
        : "amount"
    : String(record?.recorded ? record.value : 2);
  form.elements.amount.value =
    record && record.value >= 0 && record.value !== 3
      ? record.value / 1000
      : "";
  $("amount-label").hidden =
    !numeric || form.elements.outcome.value !== "amount";
}
$("entry-form").elements.date.onchange = (event) => {
  const form = $("entry-form");
  const habit = view.habits.find(
    (item) => item.uuid === form.elements.uuid.value,
  );
  loadEntryDate(habit, form, event.target.value);
};
$("entry-form").onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target,
    fields = form.elements;
  try {
    let value = Number(fields.outcome.value);
    if (fields.outcome.value === "amount") {
      if (fields.amount.value === "") throw new Error("Enter an amount.");
      const amount = Number(fields.amount.value);
      value = Math.round(amount * 1000);
      if (value === 3)
        throw new Error(
          "0.003 is reserved for skipped entries in Loop. Choose another amount.",
        );
      if (!Number.isFinite(amount) || Math.abs(amount * 1000 - value) > 1e-6)
        throw new Error("Use at most three decimal places.");
    }
    await edit({
      [`entry:${fields.uuid.value}:${fields.date.value}`]: {
        value,
        notes: fields.notes.value,
      },
    });
    $("entry-dialog").close();
  } catch (error) {
    form.querySelector(".form-error").textContent = errorMessage(error);
  }
};
$("settings-form").onsubmit = async (event) => {
  event.preventDefault();
  try {
    const patch = {};
    for (const key of ["dayStart", "weekStart"]) {
      const value = Number(event.target.elements[key].value);
      if (value !== settingsBaseline[key]) patch[`setting:${key}`] = value;
    }
    if (Object.keys(patch).length) await edit(patch);
  } catch {}
};
$("new-habit").onclick = () => showHabit();
$("sync").onclick = () => sync();
$("disconnect").onclick = () => {
  if (syncing) {
    cloudStatus("Wait for synchronization to finish before disconnecting.");
    return;
  }
  if (
    pending() &&
    !confirm(
      "Disconnect with pending edits? They will remain on this device, bound to this Google account. Reconnect that same account to upload them.",
    )
  )
    return;
  drive = undefined;
  expiresAt = 0;
  clearTimeout(retryTimer);
  refreshCloud();
};
$("update-app").hidden = temporary;
if (testRun) $("update-app").href += `?testRun=${testRun}`;
$("clear-local").hidden = temporary;
$("disconnect").hidden = temporary;
$("clear-local").onclick = async () => {
  if (syncing) {
    cloudStatus(
      "Wait for synchronization to finish before clearing local data.",
    );
    return;
  }
  if (
    !confirm(
      pending()
        ? "Discard unsynchronized edits and all local habit data? These pending changes will be lost permanently."
        : "Clear this device's habit data? Drive data is retained.",
    )
  )
    return;
  try {
    state = await storage.clear();
    drive = undefined;
    expiresAt = 0;
    clearTimeout(retryTimer);
    currentView();
    render();
    savedStatus();
    refreshCloud();
  } catch (error) {
    $("save-status").textContent = errorMessage(error);
  }
};
function endSession() {
  ended = true;
  storage?.clear();
  drive = undefined;
  state = undefined;
  view = undefined;
  habitBaseline = undefined;
  settingsBaseline = undefined;
  selected = undefined;
  $("connect").title = "";
  expiresAt = 0;
  clearTimeout(retryTimer);
  document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
  $("habit-form").reset();
  $("entry-form").reset();
  $("habit-list").replaceChildren();
  $("detail-page").replaceChildren();
  $("recovery-list").replaceChildren();
  $("conflicts").replaceChildren();
  $("save-status").textContent = "Temporary session ended · memory cleared";
  cloudStatus("Session ended");
  $("connect").disabled = true;
  $("new-habit").disabled = true;
}
$("end-session").onclick = () => {
  if (syncing) {
    cloudStatus("A save is in progress. Wait before ending this session.");
    return;
  }
  if (
    pending() &&
    !confirm(
      "Pending saves exist only in memory. End the session and discard them?",
    )
  )
    return;
  endSession();
};
window.addEventListener("beforeunload", (event) => {
  if (temporary && pending()) {
    event.preventDefault();
    event.returnValue = "Pending saves exist only in this session.";
  }
});
window.addEventListener("pagehide", () => {
  if (temporary) endSession();
});
window.addEventListener("pageshow", (event) => {
  if (temporary && event.persisted) location.reload();
});
$("test-cleanup-form").onsubmit = async (event) => {
  event.preventDefault();
  if (cleaningTest) return;
  const button = event.target.querySelector("button");
  try {
    if (!testRun || event.target.elements.run.value !== testRun)
      throw new Error("Enter this test workspace's exact UUID.");
    if (!drive || syncing || pending())
      throw new Error(
        "Connect and finish synchronizing before removing test data.",
      );
    cleaningTest = true;
    button.disabled = true;
    clearTimeout(retryTimer);
    const count = await drive.deleteTestWorkspace(testRun);
    cloudStatus(
      `Removed ${count} validated test files. Close this test workspace.`,
    );
  } catch (error) {
    cleaningTest = false;
    button.disabled = false;
    cloudStatus(errorMessage(error));
  }
};
$("search").oninput = render;
$("sort").onchange = render;
$("hide-completed").onchange = render;
$("earlier").onclick = () => {
  offset += 7;
  render();
};
$("today").onclick = () => {
  offset = 0;
  render();
};
document.addEventListener("change", (event) => {
  if (event.target.id === "period") {
    period = Number(event.target.value);
    currentView();
    render();
  }
});
const systemTheme = matchMedia("(prefers-color-scheme: dark)");
function applyTheme() {
  document.body.dataset.theme =
    $("theme").value === "system"
      ? systemTheme.matches
        ? "dark"
        : "light"
      : $("theme").value;
}
$("theme").onchange = () => {
  try {
    if (!temporary) localStorage.setItem("loop-device-theme", $("theme").value);
  } catch {}
  applyTheme();
};
systemTheme.onchange = applyTheme;
window.addEventListener("beforeinstallprompt", (event) => {
  if (temporary) return;
  event.preventDefault();
  installPrompt = event;
  $("install").hidden = false;
});
$("install").onclick = async () => {
  await installPrompt?.prompt();
  $("install").hidden = true;
};
window.addEventListener("online", () => scheduleSync(0));
window.addEventListener("offline", refreshCloud);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state) {
    currentView();
    render();
    scheduleSync(0);
  }
});
setInterval(() => {
  if (document.visibilityState === "visible") scheduleSync(0);
}, 30000);
try {
  if (!window.loopView)
    await new Promise((resolve) =>
      window.addEventListener("loop-core-ready", resolve, { once: true }),
    );
  // Check for shell updates even when an older bundle cannot read this device's history.
  if (!temporary && "serviceWorker" in navigator) {
    const registration = await navigator.serviceWorker.register("sw.js");
    registration.update().catch(() => {});
  }
  storage = temporary ? openMemoryStorage() : await openStorage(workspace);
  state = await storage.read();
  currentView();
  render();
  savedStatus();
  refreshCloud();
  $("connect").disabled = false;
  $("new-habit").disabled = temporary;
  $("habit-form").elements.color.innerHTML = COLOR_NAMES.map(
    (name, index) => `<option value="${index}">${name}</option>`,
  ).join("");
  try {
    if (!temporary)
      $("theme").value = localStorage.getItem("loop-device-theme") || "system";
  } catch {}
  applyTheme();
  if (testRun) {
    $("test-tools").hidden = false;
    $("test-workspace").hidden = false;
    $("test-workspace").textContent = `Isolated workflow test: ${testRun}`;
  }
} catch (error) {
  $("save-status").textContent = errorMessage(error);
  $("new-habit").disabled = true;
}

function download(content, name, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
$("export-backup").onclick = () => {
  try { download(window.loopBackup(state.history), "Loop Backup.loop.json", "application/json"); }
  catch (error) { $("import-status").textContent = errorMessage(error); }
};
$("export-csv").onclick = async () => {
  try { download(await window.loopExportCSV(state.history), "Loop CSV.zip", "application/zip"); }
  catch (error) { $("import-status").textContent = errorMessage(error); }
};
$("import-form").onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target;
  const button = form.querySelector("button");
  button.disabled = true;
  try {
    const file = $("import-file").files[0];
    if (!file || !$("confirm-import").checked) throw new Error("Choose and confirm an import file.");
    if (file.size > 10000000) throw new Error("Import is too large (maximum 10 MB).");
    $("import-status").textContent = "Validating import…";
    const id = crypto.randomUUID();
    const backup = await window.loopReadImport(new Uint8Array(await file.arrayBuffer()), id);
    await mutateHistory((current) => window.loopImportBackup(current.history, backup, current.device, id));
    $("import-status").textContent = "Import applied. Review any competing revisions below; synchronization status shows upload progress.";
    form.reset();
  } catch (error) { $("import-status").textContent = errorMessage(error); }
  finally { button.disabled = false; }
};
