// Hovanki admin (docs/adr/0008-admin.md): a page without a build step or libraries, over /api/v1/admin.
// Safety rules of this file:
// - everything from the server (report texts, nicknames, reasons) goes into the page as text: el() makes text nodes,
//   nothing here ever sets innerHTML;
// - every request carries the X-Hovanki-Admin header (CSRF) and the session cookie, which scripts can't read.

import { ZoneMap, areaSquareMeters } from "./map.js";
import { createFieldTab } from "./field.js";

const API = "/api/v1/admin";
const ROLE = { PLAYER: "игрок", MODERATOR: "модератор", ADMIN: "админ" };
const PHASE = { LOBBY: "лобби", HIDING: "прячутся", SEEKING: "поиск", FINISHED: "закончена" };
const ACTION = {
  LOGIN: "вход", ENROLL_TOTP: "подключил аутентификатор", RESOLVE_REPORT: "разобрал жалобу", BAN: "бан",
  UNBAN: "снял бан", MUTE: "запрет чата", UNMUTE: "снял запрет чата", RENAME: "сменил ник",
  LOGOUT_DEVICES: "выход на всех устройствах", DELETE_ACCOUNT: "удалил аккаунт", SHOW_EMAIL: "показал email",
  FIND_BY_EMAIL: "искал по email", END_GAME: "завершил игру", SET_ROLE: "сменил роль", RESET_TOTP: "сбросил аутентификатор",
  SET_FEATURE: "переключил возможность",
  BIG_GAME_CREATE: "создал большую игру", BIG_GAME_UPDATE: "изменил большую игру", BIG_GAME_START: "запустил большую игру",
  BIG_GAME_CANCEL: "отменил большую игру",
  WATCH_GAME: "смотрел игру вживую",
  LAB_RUN_CREATE: "создал прогон радиолабы", LAB_RUN_CONTROL: "управлял прогоном радиолабы",
  LAB_RUN_DOWNLOAD: "скачал журналы прогона", LAB_RUN_DELETE: "удалил прогон радиолабы",
  FIELD_EXPORT: "выгрузил отчёт полевой игры", FIELD_MARK: "поставил отметку в полевой игре",
};
const MODERATOR_MAX_DAYS = 30;

let me = null;

// DOM

/** An element; attrs: properties or on* handlers; children: nodes or strings (as text), null/false skipped. */
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else if (key in node && key !== "list") node[key] = value;
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  append(node, children);
  return node;
}

function append(node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

function show(...children) {
  const main = document.getElementById("main");
  main.replaceChildren();
  append(main, children);
}

function notice(text, kind = "error") {
  const box = document.getElementById("notice");
  box.className = `notice ${kind}`;
  box.textContent = text;
  box.hidden = false;
  clearTimeout(notice.timer);
  notice.timer = setTimeout(() => { box.hidden = true; }, kind === "error" ? 8000 : 4000);
}

const fmt = {
  time: (ms) => (ms ? new Date(ms).toLocaleString("ru-RU", { dateStyle: "short", timeStyle: "short" }) : "—"),
  ago(ms) {
    if (!ms) return "—";
    const minutes = Math.round((Date.now() - ms) / 60000);
    if (minutes < 1) return "только что";
    if (minutes < 60) return `${minutes} мин назад`;
    const hours = Math.round(minutes / 60);
    if (hours < 48) return `${hours} ч назад`;
    return `${Math.round(hours / 24)} дн назад`;
  },
  until: (ms) => (ms ? `до ${fmt.time(ms)}` : "навсегда"),
  // The server leaves out empty fields (explicitNulls = false): null and undefined are both "none".
  number: (value, digits = 0) => (value == null ? "—" : value.toLocaleString("ru-RU", { maximumFractionDigits: digits })),
  /** 1 жалоба, 2 жалобы, 5 жалоб. */
  plural(n, one, few, many) {
    const tens = n % 100;
    const ones = n % 10;
    const word = ones === 1 && tens !== 11 ? one : ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? few : many;
    return `${n} ${word}`;
  },
};

// API

class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

/** The request with the admin header and the session cookie; a failed answer becomes an ApiError. */
async function send(method, path, body) {
  const response = await fetch(API + path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: { "X-Hovanki-Admin": "1", ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const text = await response.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      // Not our JSON (a proxy's page): the status says enough.
    }
    throw new ApiError(response.status, json);
  }
  return response;
}

async function api(method, path, body) {
  const response = await send(method, path, body);
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

const get = (path) => api("GET", path);
const post = (path, body = {}) => api("POST", path, body);
const del = (path, body) => api("DELETE", path, body);

/** Runs an action; errors become a notice, a lost session goes back to the login. */
async function run(action, done) {
  try {
    const result = await action();
    if (done) notice(done, "ok");
    return result;
  } catch (e) {
    if (e instanceof ApiError && e.status === 401 && me) {
      me = null;
      notice("Сессия закончилась: войдите снова.");
      route();
    } else if (e instanceof ApiError) {
      notice(errorText(e));
    } else {
      notice(`Сеть: ${e.message}`);
    }
    throw e;
  }
}

function errorText(e) {
  const reason = e.body?.reason;
  if (reason === "WRONG_CREDENTIALS") return "Неверный логин или пароль (или это не аккаунт сотрудника).";
  if (reason === "EMAIL_NOT_VERIFIED") return "Сначала подтвердите email в приложении.";
  if (reason === "SESSION_EXPIRED") return "Время вышло: войдите снова.";
  if (reason === "TOO_MANY_REQUESTS") return "Слишком много попыток, подождите.";
  if (reason === "LAB_RUN_CLOSED") return "Прогон закрыт: он завершён или старше суток.";
  if (e.body?.code === "INVALID_CODE") return "Неверный код.";
  if (e.status === 404 && e.body?.message === "The admin is off") return "Админка выключена на этом сервере.";
  if (e.status === 403) return `Нельзя: ${e.body?.message ?? "нет прав"}.`;
  return e.body?.message ?? e.message;
}

// Dialogs

/**
 * A form in a modal dialog; fields: [{name, label, type, value, min, max}]. Resolves with the values, or null if
 * cancelled. A reason field is always there: every action goes to the audit log with it.
 */
function ask(title, { text, fields = [], confirm = "Готово", danger = false } = {}) {
  return new Promise((resolve) => {
    const inputs = {};
    const form = el("form", { method: "dialog" },
      el("h3", {}, title),
      text ? el("p", { class: "muted" }, text) : null,
      [...fields, { name: "reason", label: "Причина (попадёт в журнал)", required: true }].map((field) => {
        const input = field.options
          ? el("select", { name: field.name }, field.options.map(([value, label]) => el("option", { value }, label)))
          : el("input", {
            name: field.name, type: field.type ?? "text", value: field.value ?? "", required: field.required ?? false,
            min: field.min, max: field.max, maxLength: field.type ? null : 500, class: field.type === "number" ? "days" : null,
          });
        if (field.type === "checkbox") input.checked = Boolean(field.value);
        inputs[field.name] = input;
        return el("label", {}, field.label, field.type === "checkbox" ? " " : el("br"), input);
      }),
      el("div", { class: "actions" },
        el("button", { type: "button", class: "secondary", onclick: () => close(null) }, "Отмена"),
        el("button", { type: "submit", class: danger ? "danger" : null }, confirm)));
    const dialog = el("dialog", {}, form);
    function close(value) {
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const values = {};
      for (const [name, input] of Object.entries(inputs)) {
        values[name] = input.type === "checkbox" ? input.checked : input.value.trim();
      }
      close(values);
    });
    dialog.addEventListener("cancel", () => close(null));
    document.body.append(dialog);
    dialog.showModal();
    inputs.reason.focus();
  });
}

/** Days for a ban or a chat ban: moderators 1..30, admins also forever. */
function sanctionFields(kind) {
  const fields = [{ name: "days", label: "Дней", type: "number", value: kind === "BAN" ? 7 : 3, min: 1,
    max: isAdmin() ? 3650 : MODERATOR_MAX_DAYS }];
  if (kind === "BAN" && isAdmin()) fields.push({ name: "forever", label: "Навсегда", type: "checkbox" });
  return fields;
}

const days = (values) => (values.forever ? null : Number(values.days));
const isAdmin = () => me?.role === "ADMIN";

// Login

function loginView() {
  document.getElementById("nav").hidden = true;
  document.getElementById("who").hidden = true;
  const login = el("input", { name: "login", autocomplete: "username", required: true });
  const password = el("input", { name: "password", type: "password", autocomplete: "current-password", required: true });
  show(el("form", {
    class: "card narrow",
    async onsubmit(event) {
      event.preventDefault();
      const answer = await run(() => post("/login", { login: login.value.trim(), password: password.value }));
      password.value = "";
      if (answer.next === "TOTP") codeView(answer.challenge);
      else emailCodeView(answer);
    },
  },
  el("h1", {}, "Вход для сотрудников"),
  el("label", {}, "Email или ник", login),
  el("label", {}, "Пароль", password),
  el("p", {}, el("button", { type: "submit" }, "Дальше")),
  el("p", { class: "muted small" }, "Дальше — код из приложения-аутентификатора. Нет прав сотрудника — ответ такой же, как на неверный пароль.")));
  login.focus();
}

function codeInput() {
  return el("input", { class: "code", inputMode: "numeric", autocomplete: "one-time-code", maxLength: 6, required: true,
    pattern: "[0-9 ]{6,7}" });
}

function codeView(challenge) {
  const code = codeInput();
  show(el("form", {
    class: "card narrow",
    async onsubmit(event) {
      event.preventDefault();
      me = await run(() => post("/login/totp", { challenge, code: code.value }));
      go("#/reports");
    },
  },
  el("h1", {}, "Код из приложения"),
  el("p", { class: "muted" }, "6 цифр из Google Authenticator, 1Password или другого приложения-аутентификатора."),
  code,
  el("p", {}, el("button", { type: "submit" }, "Войти"))));
  code.focus();
}

function emailCodeView(answer) {
  const code = el("input", { class: "code", inputMode: "numeric", autocomplete: "one-time-code", maxLength: 6, required: true });
  show(el("form", {
    class: "card narrow",
    async onsubmit(event) {
      event.preventDefault();
      const enrollment = await run(() => post("/enroll", { challenge: answer.challenge, emailCode: code.value }));
      enrollView(answer.challenge, enrollment);
    },
  },
  el("h1", {}, "Подключение аутентификатора"),
  el("p", {}, "Первый вход: на ", el("b", {}, answer.emailHint ?? "ваш email"), " ушёл код. Введите его, и появится QR для приложения."),
  code,
  el("p", {}, el("button", { type: "submit" }, "Дальше"))));
  code.focus();
}

function enrollView(challenge, enrollment) {
  const code = codeInput();
  show(el("form", {
    class: "card narrow",
    async onsubmit(event) {
      event.preventDefault();
      me = await run(() => post("/enroll/confirm", { challenge, code: code.value }), "Аутентификатор подключён.");
      go("#/reports");
    },
  },
  el("h1", {}, "Отсканируйте QR"),
  el("p", { class: "muted" }, "В приложении-аутентификаторе: «Добавить» → «Сканировать QR». Или введите ключ вручную."),
  qrSvg(enrollment.qr),
  el("p", { class: "mono secret" }, enrollment.secret),
  el("label", {}, "Первый код из приложения", code),
  el("p", {}, el("button", { type: "submit" }, "Подключить и войти"))));
  code.focus();
}

/** The QR from its rows of modules ("1" dark), with the quiet zone of 4 modules; drawn, not parsed. */
function qrSvg(rows) {
  const ns = "http://www.w3.org/2000/svg";
  const size = rows.length + 8;
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${size} ${size}`);
  svg.setAttribute("class", "qr");
  svg.setAttribute("shape-rendering", "crispEdges");
  let path = "";
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) if (row[x] === "1") path += `M${x + 4} ${y + 4}h1v1h-1z`;
  });
  const background = document.createElementNS(ns, "rect");
  background.setAttribute("width", size);
  background.setAttribute("height", size);
  background.setAttribute("fill", "#fff");
  const modules = document.createElementNS(ns, "path");
  modules.setAttribute("d", path);
  modules.setAttribute("fill", "#000");
  svg.append(background, modules);
  return svg;
}

// Frame

const PAGES = [
  ["reports", "Жалобы"], ["users", "Пользователи"], ["games", "Игры"], ["features", "Возможности"], ["stats", "Цифры"],
  ["big", "Большие игры", true], ["lab", "Радиолаба", true], ["field", "Полевые тесты", true], ["staff", "Сотрудники", true],
  ["audit", "Журнал", true],
];

function frame(page, openReports) {
  const nav = document.getElementById("nav");
  nav.replaceChildren(...PAGES.filter(([, , adminOnly]) => !adminOnly || isAdmin()).map(([id, title]) =>
    el("a", { href: `#/${id}`, class: id === page ? "active" : null }, title,
      id === "reports" && openReports ? el("span", { class: "badge" }, openReports) : null)));
  nav.hidden = false;
  const who = document.getElementById("who");
  who.replaceChildren(
    el("span", {}, `${me.nickname} · ${ROLE[me.role]}`),
    el("button", {
      class: "secondary",
      async onclick() {
        await run(() => post("/logout"));
        me = null;
        route();
      },
    }, "Выйти"));
  who.hidden = false;
}

// The field tests' tab (field.js): the helpers it needs, handed over.
const fieldTab = createFieldTab({
  el, get, post, del, send, run, ask, fmt, frame, show, go, notice, ApiError, errorText,
  sessionLost() {
    me = null;
    route();
  },
});

async function route() {
  stopLive();
  stopLab();
  fieldTab.stop();
  if (!me) {
    try {
      me = await get("/me");
    } catch (e) {
      if (e instanceof ApiError && e.status === 404 && e.body?.message === "The admin is off") {
        show(el("div", { class: "card narrow" }, el("h1", {}, "Админка выключена"),
          el("p", {}, "На сервере не задан HOVANKI_ADMIN_SECRET_KEY (docs/deploy.md).")));
        return;
      }
      loginView();
      return;
    }
  }
  const [page, id, sub] = location.hash.replace(/^#\/?/, "").split("/");
  try {
    if (page === "users" && id) await userView(decodeURIComponent(id));
    else if (page === "users") await usersView();
    else if (page === "games" && id && isAdmin()) await liveView(decodeURIComponent(id));
    else if (page === "games") await gamesView();
    else if (page === "features") await featuresView();
    else if (page === "stats") await statsView();
    else if (page === "staff" && isAdmin()) await staffView();
    else if (page === "audit" && isAdmin()) await auditView();
    else if (page === "big" && isAdmin() && id) await bigGameEditor(decodeURIComponent(id));
    else if (page === "big" && isAdmin()) await bigGamesView();
    else if (page === "lab" && isAdmin() && id && sub === "report") await labReportView(decodeURIComponent(id));
    else if (page === "lab" && isAdmin() && id) await labRunView(decodeURIComponent(id));
    else if (page === "lab" && isAdmin()) await labRunsView();
    else if (page === "field" && isAdmin() && id && sub === "report") await fieldTab.reportView(decodeURIComponent(id));
    else if (page === "field" && isAdmin() && id) await fieldTab.gameView(decodeURIComponent(id));
    else if (page === "field" && isAdmin()) await fieldTab.listView();
    else await reportsView(id === "all");
  } catch {
    // run() showed it.
  }
}

// Reports

async function reportsView(all) {
  const page = await run(() => get(`/reports?open=${!all}`));
  frame("reports", page.open);
  const list = el("div");
  let last = null;
  function add(reports) {
    for (const report of reports) list.append(reportCard(report, () => reportsView(all)));
    last = reports.length ? reports[reports.length - 1].id : last;
    more.hidden = reports.length < 50;
  }
  const more = el("button", {
    class: "secondary",
    async onclick() { add((await run(() => get(`/reports?open=${!all}&before=${last}`))).reports); },
  }, "Показать ещё");
  show(
    el("div", { class: "row spread" }, el("h1", {}, all ? "Все жалобы" : `Открытые жалобы: ${page.open}`),
      el("a", { href: all ? "#/reports" : "#/reports/all" }, all ? "Только открытые" : "Все за 90 дней")),
    list,
    page.reports.length ? null : el("p", { class: "muted" }, "Жалоб нет."),
    more);
  add(page.reports);
}

function reportCard(report, reload) {
  const author = report.authorId
    ? el("a", { href: `#/users/${encodeURIComponent(report.authorId)}` }, report.authorName)
    : el("span", {}, report.authorName, " ", el("span", { class: "tag" }, "гость"));
  const resolved = report.resolvedAtMillis
    ? el("p", { class: "small" }, el("span", { class: "tag ok" }, "разобрана"), " ", report.resolvedByName, ", ",
      fmt.time(report.resolvedAtMillis), ": ", report.resolution)
    : null;
  return el("div", { class: "card" },
    el("div", { class: "row spread" },
      el("div", {}, "От ", author, " · ", el("span", { class: "muted" }, fmt.time(report.createdAtMillis))),
      el("div", { class: "muted small" }, "пожаловался: ", report.reporterName ?? "гость")),
    el("blockquote", {}, report.text),
    report.authorId
      ? el("p", { class: "small muted" }, "На сообщения автора за 90 дней: ",
        fmt.plural(report.authorReports, "жалоба", "жалобы", "жалоб"), " от ",
        fmt.plural(report.authorReporters, "игрока", "игроков", "игроков"))
      : null,
    resolved,
    report.resolvedAtMillis ? null : el("div", { class: "row" },
      resolveButton(report, "DISMISS", "Отклонить", reload, "secondary"),
      report.authorId ? resolveButton(report, "MUTE", "Запрет чата", reload) : null,
      report.authorId ? resolveButton(report, "BAN", "Бан", reload, "danger") : null,
      report.authorId ? resolveButton(report, "RENAME", "Сменить ник", reload) : null));
}

function resolveButton(report, action, title, reload, kind) {
  return el("button", {
    class: kind,
    async onclick() {
      const fields = action === "BAN" || action === "MUTE" ? sanctionFields(action) : [];
      const values = await ask(`${title}: ${report.authorName}`, {
        text: action === "DISMISS" ? "Нарушения нет, жалоба закрывается." : "Все открытые жалобы на это сообщение закроются.",
        fields, confirm: title, danger: action === "BAN",
      });
      if (!values) return;
      await run(() => post(`/reports/${report.id}/resolve`, {
        action, reason: values.reason, days: fields.length ? days(values) : null,
      }), "Жалоба разобрана.");
      reload();
    },
  }, title);
}

// Users

async function usersView() {
  frame("users");
  const results = el("div");
  const query = el("input", { placeholder: "Ник (начало) или id", autocomplete: "off" });
  async function search(event) {
    event.preventDefault();
    const found = await run(() => get(`/users?q=${encodeURIComponent(query.value)}`));
    results.replaceChildren(usersTable(found.users));
  }
  const byEmail = isAdmin() ? el("form", {
    class: "card",
    async onsubmit(event) {
      event.preventDefault();
      const email = event.target.elements.email.value.trim();
      const values = await ask("Поиск по email", { text: "Точный адрес, с которого написали в поддержку. Попадёт в журнал (скрытым)." });
      if (!values) return;
      const found = await run(() => post("/users/find-by-email", { email, reason: values.reason }));
      results.replaceChildren(found.users.length ? usersTable(found.users) : el("p", { class: "muted" }, "Нет такого аккаунта."));
    },
  }, el("label", {}, "Email (только админы)", el("input", { name: "email", type: "email", required: true })),
  el("p", {}, el("button", { type: "submit", class: "secondary" }, "Найти по email"))) : null;
  show(el("h1", {}, "Пользователи"),
    el("form", { class: "card", onsubmit: search }, el("label", {}, "Поиск", query),
      el("p", {}, el("button", { type: "submit" }, "Найти"))),
    byEmail,
    results);
  query.focus();
}

function usersTable(users) {
  if (!users.length) return el("p", { class: "muted" }, "Никого.");
  return el("table", {},
    el("tr", {}, el("th", {}, "Ник"), el("th", {}, "Роль"), el("th", {}, "С нами"), el("th", {}, "")),
    users.map((user) => el("tr", {},
      el("td", {}, el("a", { href: `#/users/${encodeURIComponent(user.id)}` }, user.nickname)),
      el("td", {}, ROLE[user.role]),
      el("td", {}, fmt.time(user.createdAtMillis)),
      el("td", {}, user.banned ? el("span", { class: "tag ban" }, "бан") : null, " ",
        user.muted ? el("span", { class: "tag mute" }, "без чата") : null))));
}

async function userView(userId) {
  const card = await run(() => get(`/users/${encodeURIComponent(userId)}`));
  frame("users");
  const reload = () => userView(userId);
  const now = Date.now();
  const active = (kind) => card.sanctions.find((s) => s.kind === kind && !s.liftedAtMillis && (!s.untilMillis || s.untilMillis > now));
  const ban = active("BAN");
  const mute = active("MUTE");
  const email = el("span", {}, card.emailMasked);
  const isStaff = card.role !== "PLAYER";
  const self = card.id === me.userId;

  const action = (title, path, { fields = [], text, danger, done, after } = {}) => el("button", {
    class: danger ? "danger" : "secondary",
    async onclick() {
      const values = await ask(`${title}: ${card.nickname}`, { fields, text, confirm: title, danger });
      if (!values) return;
      const result = await run(() => post(`/users/${encodeURIComponent(card.id)}/${path}`, bodyFor(path, values)), done);
      if (after) after(result);
      else reload();
    },
  }, title);

  show(
    el("div", { class: "row spread" },
      el("h1", {}, card.nickname, " ", isStaff ? el("span", { class: "tag staff" }, ROLE[card.role]) : null,
        ban ? el("span", { class: "tag ban" }, `бан ${fmt.until(ban.untilMillis)}`) : null, " ",
        mute ? el("span", { class: "tag mute" }, `без чата ${fmt.until(mute.untilMillis)}`) : null),
      el("a", { href: "#/users" }, "← к поиску")),
    el("div", { class: "card" }, el("dl", { class: "grid" },
      field("id", el("span", { class: "mono" }, card.id)),
      field("Email", email, " ", card.emailVerified ? el("span", { class: "tag ok" }, "подтверждён") : el("span", { class: "tag" }, "не подтверждён")),
      field("Регистрация", fmt.time(card.createdAtMillis)),
      field("Язык писем", card.language),
      field("Игр", card.games, card.lastGameAtMillis ? ` · последняя ${fmt.ago(card.lastGameAtMillis)}` : ""),
      field("Устройств", card.devices, card.lastSeenAtMillis ? ` · был ${fmt.ago(card.lastSeenAtMillis)}` : ""),
      field("Друзей", card.friends),
      field("Заблокировали его", card.blockedBy),
      field("Жалоб на него / от него", `${card.reportsAgainst} / ${card.reportsBy}`),
      field("Сохранять маршруты", card.saveRoutes ? "включено" : "выключено"),
      isStaff ? field("Аутентификатор", card.totpEnrolled ? "подключён" : "не подключён") : null)),
    self ? el("p", { class: "muted" }, "Это вы: над собой действий нет.") : el("div", { class: "row" },
      isStaff ? null : [
        ban ? action("Снять бан", "unban", { done: "Бан снят." })
          : action("Бан", "ban", { fields: sanctionFields("BAN"), danger: true, done: "Забанен.",
            text: "Выход со всех устройств, вход и игра с аккаунтом закрыты." }),
        mute ? action("Разрешить чат", "unmute", { done: "Запрет снят." })
          : action("Запрет чата", "mute", { fields: sanctionFields("MUTE"), done: "Чат запрещён." }),
        action("Сменить ник", "rename", { text: "Ник станет случайным player-…", done: "Ник сменён." }),
      ],
      isAdmin() ? action("Показать email", "email", {
        text: "Email целиком — только для ответа на обращение. Попадёт в журнал.",
        after: (result) => { email.textContent = result.email; },
      }) : null,
      isAdmin() && !isStaff ? action("Выйти на всех устройствах", "logout", { done: "Все сессии закрыты." }) : null,
      isAdmin() && card.role === "PLAYER" ? action("Сделать модератором", "role", { done: "Теперь модератор." }) : null,
      isAdmin() && card.role === "MODERATOR" ? action("Снять модератора", "role", { done: "Теперь игрок." }) : null,
      isAdmin() && card.role === "MODERATOR" ? action("Сбросить аутентификатор", "reset-totp", {
        text: "При следующем входе модератор подключит его заново (с кодом из письма).", done: "Сброшен.",
      }) : null,
      isAdmin() && !isStaff ? el("button", {
        class: "danger",
        async onclick() {
          const values = await ask(`Удалить аккаунт ${card.nickname}`, {
            text: "Навсегда: аккаунт, друзья, история, маршруты. Только по письменному запросу владельца.",
            fields: [{ name: "confirm", label: `Введите ник «${card.nickname}» для подтверждения`, required: true }],
            confirm: "Удалить", danger: true,
          });
          if (!values) return;
          if (values.confirm !== card.nickname) { notice("Ник не совпал: ничего не удалено."); return; }
          await run(() => post(`/users/${encodeURIComponent(card.id)}/delete`, { reason: values.reason }), "Аккаунт удалён.");
          go("#/users");
        },
      }, "Удалить аккаунт") : null),
    el("h2", {}, "Баны и запреты чата"),
    card.sanctions.length ? el("table", {},
      el("tr", {}, el("th", {}, "Что"), el("th", {}, "Когда, кто"), el("th", {}, "Срок"), el("th", {}, "Причина")),
      card.sanctions.map((s) => el("tr", {},
        el("td", {}, el("span", { class: `tag ${s.kind === "BAN" ? "ban" : "mute"}` }, s.kind === "BAN" ? "бан" : "без чата")),
        el("td", {}, fmt.time(s.createdAtMillis), ", ", s.byName),
        el("td", {}, s.liftedAtMillis ? `снят ${fmt.time(s.liftedAtMillis)} (${s.liftedByName})` : fmt.until(s.untilMillis)),
        el("td", {}, s.reason)))) : el("p", { class: "muted" }, "Не было."));

  function bodyFor(path, values) {
    if (path === "ban" || path === "mute") return { days: days(values), reason: values.reason };
    if (path === "role") return { role: card.role === "PLAYER" ? "MODERATOR" : "PLAYER", reason: values.reason };
    return { reason: values.reason };
  }
}

function field(title, ...value) {
  return el("div", {}, el("dt", {}, title), el("dd", {}, ...value));
}

// Games

async function gamesView() {
  const { games } = await run(() => get("/games"));
  frame("games");
  show(el("h1", {}, `Игры сейчас: ${games.length}`),
    el("p", { class: "muted small" }, "Без центра зоны, позиций и чата: только числа. Открытые игры (хост разрешил зрителей) " +
      "админы могут смотреть вживую, с причиной в журнале."),
    games.length ? el("table", {},
      el("tr", {}, ["Игра", "Фаза", "Хост", "Игроки (гости, ищущие)", "Создана", "В фазе с", "Активность", "Зона", "Карта", "Чат",
        "Зрители", ""]
        .map((t) => el("th", {}, t))),
      games.map((game) => el("tr", {},
        el("td", { class: "mono" }, game.gameId),
        el("td", {}, PHASE[game.phase]),
        el("td", {}, game.hostName),
        el("td", {}, `${game.players} (${game.guests}, ${game.seekers})`),
        el("td", {}, fmt.ago(game.createdAtMillis)),
        el("td", {}, fmt.ago(game.phaseStartedAtMillis)),
        el("td", {}, fmt.ago(game.lastActivityMillis)),
        el("td", {}, `${Math.round(game.zoneRadiusMeters)} м`, capacitySummary(game)),
        el("td", {}, mapSummary(game)),
        el("td", {}, game.chatMessages),
        el("td", {}, game.openGame ? `открыта · ${game.spectators}` : "—"),
        el("td", { class: "row" }, isAdmin() && game.openGame ? el("button", {
          class: "secondary",
          onclick: () => watchGame(game.gameId),
        }, "Смотреть") : null, isAdmin() && game.phase !== "FINISHED" ? el("button", {
          class: "danger",
          async onclick() {
            const values = await ask(`Завершить игру ${game.gameId}`, {
              text: "Идущая игра закончится сейчас, игроки увидят итоги; игра в лобби исчезнет.", confirm: "Завершить", danger: true,
            });
            if (!values) return;
            await run(() => post(`/games/${encodeURIComponent(game.gameId)}/end`, { reason: values.reason }), "Игра завершена.");
            gamesView();
          },
        }, "Завершить") : null)))) : el("p", { class: "muted" }, "Сейчас никто не играет."));
}

/** How many players the zone fits (docs/adr/0010-big-games.md), and whether the host played anyway in a crowded one. */
function capacitySummary(game) {
  if (game.capacity == null) return null;
  const crowded = game.players > game.capacity;
  return el("div", { class: "small muted" }, `до ${fmt.plural(game.capacity, "игрока", "игроков", "игроков")}`,
    crowded ? [" ", el("span", { class: "tag mute" }, "тесно")] : null,
    game.crowdingAccepted ? [" ", el("span", { class: "tag" }, "играют всё равно")] : null);
}

// Watching an open game live (docs/adr/0011-spectators-and-recordings.md): admins only, after a reason that goes to the
// audit log; the server then serves the game for half an hour. The players' map (map.js, tiles through the server) with
// the zone, everybody's position and the last two minutes of their way drawn over it; names are canvas text.

const LIVE_POLL_MS = 3000;
const LIVE_ROLE = { HIDER: "прячется", SEEKER: "ищет" };
const LIVE_STATUS = { ACTIVE: "в игре", CAUGHT: "пойман", ELIMINATED: "выбыл" };
const LIVE_COLOR = { SEEKER: "#b00060", HIDER: "#6b4bff", OUT: "#6b6b78" };
let liveTimer = null;

function stopLive() {
  clearTimeout(liveTimer);
  liveTimer = null;
}

/** Asks for the reason, then shows open game [gameId] live. */
async function watchGame(gameId) {
  const values = await ask(`Смотреть игру ${gameId}`, {
    text: "Открытая игра вживую: где сейчас каждый игрок и его путь за последние 2 минуты. Причина попадёт в журнал; " +
      "смотреть можно 30 минут, потом — снова с причиной.",
    confirm: "Смотреть",
  });
  if (!values) return;
  await run(() => post(`/games/${encodeURIComponent(gameId)}/watch`, { reason: values.reason }));
  go(`#/games/${encodeURIComponent(gameId)}`);
}

async function liveView(gameId) {
  frame("games");
  const hash = `#/games/${encodeURIComponent(gameId)}`;
  const path = `/games/${encodeURIComponent(gameId)}/live`;
  let live;
  try {
    live = await get(path);
  } catch (e) {
    await liveEnded(gameId, e);
    return;
  }
  const header = el("div");
  const players = el("div");
  const canvas = el("canvas", { class: "map live" });
  const map = new ZoneMap(canvas, {
    loadTile: (z, x, y) => get(`/tiles/${z}/${x}/${y}`),
    center: live.settings.zone.initial.center,
    zoom: 15,
    editable: false,
    overlay: (g, screen) => drawLive(g, screen, live),
  });
  const render = () => {
    header.replaceChildren(liveHeader(live));
    players.replaceChildren(livePlayers(live));
    map.redraw();
  };
  show(el("a", { href: "#/games" }, "← Все игры"), header, canvas, players);
  map.fit(circleBounds(live.settings.zone.initial));
  render();
  async function tick() {
    try {
      live = await get(path);
    } catch (e) {
      if (location.hash === hash) await liveEnded(gameId, e);
      return;
    }
    if (location.hash !== hash) return;
    render();
    liveTimer = setTimeout(tick, LIVE_POLL_MS);
  }
  liveTimer = setTimeout(tick, LIVE_POLL_MS);
}

/** Watching ended: the reason's half hour is over, the host closed the game, or it is gone. */
async function liveEnded(gameId, e) {
  const back = el("a", { href: "#/games" }, "← Все игры");
  if (e instanceof ApiError && e.status === 403) {
    const closed = e.body?.reason === "GAME_NOT_OPEN";
    show(back, el("div", { class: "card narrow" }, el("h1", {}, closed ? "Игра закрыта" : "Нужна причина"),
      el("p", {}, closed
        ? "Хост закрыл игру для зрителей: смотреть её больше нельзя."
        : "Доступ к этой игре закончился (30 минут после причины) или ещё не открыт."),
      closed ? null : el("button", { onclick: () => watchGame(gameId) }, "Смотреть с причиной")));
  } else if (e instanceof ApiError && e.status === 404) {
    show(back, el("div", { class: "card narrow" }, el("h1", {}, "Игры больше нет"),
      el("p", {}, "Она закончилась и удалена с сервера.")));
  } else {
    await run(() => Promise.reject(e)).catch(() => {});
  }
}

function liveHeader(live) {
  const left = live.phaseEndsAtMillis ? Math.max(0, Math.round((live.phaseEndsAtMillis - live.serverTimeMillis) / 1000)) : null;
  const clock = left == null ? "" : ` · ещё ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  return el("div", {},
    el("h1", {}, `Игра ${live.gameId} · ${PHASE[live.phase]}${clock}`),
    el("p", { class: "muted small" }, `Вживую, обновляется каждые 3 секунды. Зрителей: ${live.spectators}. ` +
      "Путь каждого — за последние 2 минуты. Каждое открытие записано в журнал."));
}

/** The corners of the square around [circle]: the map fits them. */
function circleBounds(circle) {
  const dLat = circle.radiusMeters / 110540;
  const dLon = circle.radiusMeters / (111320 * Math.cos((circle.center.lat * Math.PI) / 180));
  const { lat, lon } = circle.center;
  return [{ lat: lat - dLat, lon: lon - dLon }, { lat: lat + dLat, lon: lon + dLon }];
}

/** Over the map: the zone (dashed: the next one), the rule's buildings, everybody's way and where they are. */
function drawLive(g, screen, live) {
  const path = (points, close) => {
    g.beginPath();
    points.forEach((point, i) => {
      const p = screen(point);
      if (i === 0) g.moveTo(p.x, p.y); else g.lineTo(p.x, p.y);
    });
    if (close) g.closePath();
  };
  const circle = (zone) => {
    const [south, north] = circleBounds(zone);
    const center = screen(zone.center);
    g.beginPath();
    g.arc(center.x, center.y, Math.abs(screen(north).y - screen(south).y) / 2, 0, 2 * Math.PI);
  };
  const color = (player) => (player.status !== "ACTIVE" ? LIVE_COLOR.OUT : LIVE_COLOR[player.role]);

  g.fillStyle = "rgba(176, 0, 96, 0.2)";
  for (const building of live.buildings) {
    path(building.outline, true);
    g.fill();
  }
  const streets = live.streetZone?.length ? live.streetZone : null;
  const stage = Math.min(live.zoneStage ?? 0, (streets?.length ?? 1) - 1);
  g.strokeStyle = "#0e0e12";
  g.lineWidth = 3;
  if (streets) path(streets[stage].outline, true); else circle(live.zoneNow ?? live.settings.zone.initial);
  g.stroke();
  const next = streets ? streets[stage + 1] : live.nextZone;
  if (next) {
    g.setLineDash([8, 6]);
    g.lineWidth = 1.5;
    if (streets) path(next.outline, true); else circle(next);
    g.stroke();
    g.setLineDash([]);
  }
  g.lineCap = "round";
  g.lineJoin = "round";
  for (const player of live.players) {
    if (!(player.trail?.length > 1)) continue;
    path(player.trail, false);
    g.strokeStyle = color(player);
    g.globalAlpha = 0.6;
    g.lineWidth = 3;
    g.stroke();
    g.globalAlpha = 1;
  }
  g.font = "600 12px system-ui, sans-serif";
  for (const player of live.players) {
    if (!player.location) continue;
    const p = screen(player.location);
    g.beginPath();
    g.arc(p.x, p.y, 6, 0, 2 * Math.PI);
    g.fillStyle = color(player);
    g.fill();
    g.strokeStyle = "#fff";
    g.lineWidth = 2;
    g.stroke();
    g.lineWidth = 3;
    g.strokeText(player.name, p.x + 10, p.y + 4);
    g.fillStyle = "#0e0e12";
    g.fillText(player.name, p.x + 10, p.y + 4);
  }
}

function livePlayers(live) {
  const seen = (p) => (p.location
    ? `${Math.max(0, Math.round((live.serverTimeMillis - p.location.atMillis) / 1000))} с назад`
    : "нет точек");
  return el("table", {},
    el("tr", {}, ["Игрок", "Роль", "Статус", "Последняя точка"].map((t) => el("th", {}, t))),
    live.players.map((p) => el("tr", {},
      el("td", {}, p.name),
      el("td", {}, LIVE_ROLE[p.role]),
      el("td", {}, LIVE_STATUS[p.status]),
      el("td", {}, seen(p)))));
}

const BUILDINGS = { LOADING: "грузятся", READY: "есть", UNAVAILABLE: "нет данных, правило выключено" };
const STREET_ZONE = { LOADING: "строится", READY: "по улицам", UNAVAILABLE: "улиц нет, круг" };

/** The game's map data: whether the building rule is on (and on how many buildings), and the zone's shape. */
function mapSummary(game) {
  const buildings = game.buildings === "READY"
    ? `здания: ${game.buildingCount ?? 0}`
    : `здания: ${BUILDINGS[game.buildings] ?? "—"}`;
  const zone = game.zoneShape === "STREETS" ? `зона: ${STREET_ZONE[game.streetZone] ?? "по улицам"}` : "зона: круг";
  return `${buildings} · ${zone}`;
}

// Server features (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-sensors.md)

/** Every ServerFeature the page knows, in the enum's order: a name and one line of what it does. */
const FEATURES = {
  RADAR: { title: "Радар по Bluetooth", about: "ищущие чувствуют «тепло, горячо, горит», когда прячущийся рядом" },
  HIDER_SENSE: { title: "Чутьё прячущегося", about: "пульс из кармана, когда ищущий рядом" },
  PRECISION_RADAR: { title: "Точный радар (UWB)", about: "метры и стрелка между одинаковыми телефонами (пока заглушка в приложении)" },
  PROXIMITY_CATCH: { title: "Находка только вплотную", about: "заявка проходит, только если радар слышал телефоны рядом" },
  QUESTS: { title: "Задания", about: "искры за задания в раунде" },
  PERKS: { title: "Бонусы за искры", about: "стереть след, обманка, невидимость, прожектор…" },
  CHECKPOINTS: { title: "Контрольные точки", about: "по GPS или по QR-коду, который хост вешает на месте" },
  PICKUPS: { title: "Бонусы на карте", about: "лежат там, где положил хост" },
  ACTIVITY: { title: "Датчик бега", about: "телефон сообщает, бежит ли игрок" },
  POCKET_STEALTH: { title: "Карман прячет", about: "телефон в кармане читается ищущим на ступень холоднее" },
  LIVE_SOCKET: { title: "Живой канал (WebSocket)", about: "приложения синхронизируются через сокет, события приходят сразу; выключили — опрос, как раньше" },
  RADIO_LAB: { title: "Радиолаба", about: "телефоны debug-сборки входят в прогон по коду и шлют журналы радио на сервер; отчёт — во вкладке «Радиолаба»" },
  FIELD_LOG: { title: "Полевой журнал", about: "тестовая сборка (preview) с согласия тестера пишет журнал игры с координатами и шлёт его на сервер; только на staging, на проде выключен" },
};

/** A feature this page doesn't know (a newer server) goes by its enum name. */
const featureTitle = (name) => FEATURES[name]?.title ?? name;

async function featuresView() {
  featuresPage(await run(() => get("/features")));
}

/** The list; a switch answers with the new list, so the page re-renders from the answer without another request. */
function featuresPage({ features }) {
  frame("features");
  show(el("h1", {}, "Возможности"),
    el("p", { class: "muted small" },
      "Всё выключено по умолчанию. Включённое хост может выбрать в настройках новой игры; идущие игры не меняются. " +
      "Живой канал — не выбор хоста: переключается сразу для всех игр, приложения сами переходят на сокет или опрос."),
    el("table", {},
      el("tr", {}, ["Возможность", "Состояние", "Кто и когда переключил", ""].map((t) => el("th", {}, t))),
      features.map((feature) => el("tr", {},
        el("td", {}, featureTitle(feature.feature), " ", el("span", { class: "mono muted" }, feature.feature),
          FEATURES[feature.feature] ? el("div", { class: "small muted" }, FEATURES[feature.feature].about) : null),
        el("td", {}, feature.enabled ? el("span", { class: "tag ok" }, "вкл") : el("span", { class: "tag" }, "выкл")),
        el("td", {}, feature.updatedAtMillis ? `${fmt.time(feature.updatedAtMillis)}, ${feature.updatedByName}` : "—"),
        el("td", {}, isAdmin() ? featureSwitch(feature) : null)))));
}

/** Admins only: asks for the reason, switches the feature for everybody and shows the list the server answers with. */
function featureSwitch(feature) {
  const title = feature.enabled ? "Выключить" : "Включить";
  return el("button", {
    class: feature.enabled ? "secondary" : null,
    async onclick() {
      const values = await ask(`${title}: ${featureTitle(feature.feature)}`, {
        text: feature.enabled
          ? "Хосты новых игр больше не смогут её выбрать. Идущие игры не меняются."
          : "Хосты новых игр смогут выбрать её в настройках. Идущие игры не меняются.",
        confirm: title,
      });
      if (!values) return;
      featuresPage(await run(
        () => post(`/features/${encodeURIComponent(feature.feature)}`, { enabled: !feature.enabled, reason: values.reason }),
        feature.enabled ? "Возможность выключена." : "Возможность включена."));
    },
  }, title);
}

// Numbers

async function statsView() {
  const s = await run(() => get("/stats"));
  frame("stats", s.reportsOpen);
  const tile = (title, value, note) => el("div", { class: "tile" }, el("span", { class: "muted small" }, title),
    el("b", {}, value), note ? el("span", { class: "muted small" }, note) : null);
  const percent = (value) => (value == null ? "—" : `${Math.round(value * 100)} %`);
  show(el("h1", {}, "Цифры"),
    el("p", { class: "muted small" }, `На ${fmt.time(s.generatedAtMillis)}. Средние — только по 5 играм и больше.`),
    el("h2", {}, "Игроки"),
    el("div", { class: "tiles" },
      tile("Аккаунтов", fmt.number(s.users), `+${s.usersNew7d} за 7 дней, +${s.usersNew30d} за 30`),
      tile("Играли за сутки", fmt.number(s.activePlayers1d), "с аккаунтом"),
      tile("Играли за 7 дней", fmt.number(s.activePlayers7d), "с аккаунтом")),
    el("h2", {}, "Игры"),
    el("div", { class: "tiles" },
      tile("За сутки", fmt.number(s.games1d)), tile("За 7 дней", fmt.number(s.games7d)), tile("За 30 дней", fmt.number(s.games30d)),
      tile("Игроков в игре", fmt.number(s.avgPlayers30d, 1), "в среднем, 30 дней"),
      tile("Поиск", s.avgSearchMinutes30d == null ? "—" : `${fmt.number(s.avgSearchMinutes30d, 1)} мин`, "в среднем, 30 дней"),
      tile("Ищущие нашли всех", percent(s.seekersWinRate30d), "доля игр, 30 дней"),
      tile("Споров", fmt.number(s.disputes7d), "за 7 дней")),
    el("h2", {}, "Модерация"),
    el("div", { class: "tiles" },
      tile("Открытых жалоб", fmt.number(s.reportsOpen)), tile("Жалоб за 7 дней", fmt.number(s.reports7d)),
      tile("Банов", fmt.number(s.activeBans), "действуют"), tile("Запретов чата", fmt.number(s.activeMutes), "действуют")),
    el("h2", {}, "Сервер"),
    el("div", { class: "tiles" },
      tile("Игр сейчас", fmt.number(s.gamesLive)), tile("Игроков сейчас", fmt.number(s.playersLive)),
      tile("Память", `${s.heapUsedMb} / ${s.heapMaxMb} МБ`),
      tile("Работает", `${fmt.number(s.uptimeSeconds / 3600, 1)} ч`),
      tile("Версия", s.serverVersion)));
}

// Staff and the audit log (admins)

async function staffView() {
  const { staff } = await run(() => get("/staff"));
  frame("staff");
  show(el("h1", {}, "Сотрудники"),
    el("p", { class: "muted small" }, "Модератора назначают в карточке пользователя. Админа — только на сервере (docs/deploy.md)."),
    el("table", {},
      el("tr", {}, ["Ник", "Роль", "Аутентификатор", "Последний вход"].map((t) => el("th", {}, t))),
      staff.map((member) => el("tr", {},
        el("td", {}, el("a", { href: `#/users/${encodeURIComponent(member.id)}` }, member.nickname)),
        el("td", {}, ROLE[member.role]),
        el("td", {}, member.totpEnrolled ? el("span", { class: "tag ok" }, "подключён") : el("span", { class: "tag" }, "нет")),
        el("td", {}, fmt.time(member.lastLoginAtMillis))))));
}

async function auditView() {
  const first = await run(() => get("/audit"));
  frame("audit");
  const body = el("tbody");
  let last = null;
  function add(entries) {
    for (const entry of entries) {
      const feature = featureSwitched(entry);
      body.append(el("tr", {},
        el("td", {}, fmt.time(entry.atMillis)),
        el("td", {}, entry.actorName),
        el("td", {}, feature ? (feature.enabled ? "включил возможность" : "выключил возможность") : ACTION[entry.action] ?? entry.action),
        el("td", {}, entry.targetUserId ? el("a", { href: `#/users/${encodeURIComponent(entry.targetUserId)}`, class: "mono" }, entry.targetUserId) : null,
          feature ? el("div", { class: "small" }, featureTitle(feature.name), " ", el("span", { class: "mono muted" }, feature.name))
            : entry.target ? el("div", { class: "small muted" }, entry.target) : null),
        el("td", {}, entry.reason ?? "")));
    }
    last = entries.length ? entries[entries.length - 1].id : last;
    more.hidden = entries.length < 50;
  }
  const more = el("button", { class: "secondary", async onclick() { add((await run(() => get(`/audit?before=${last}`))).entries); } },
    "Показать ещё");
  show(el("h1", {}, "Журнал"),
    el("p", { class: "muted small" }, "Каждое действие сотрудников и каждый показ email. Хранится год."),
    el("table", {}, el("thead", {}, el("tr", {}, ["Когда", "Кто", "Что", "Над кем", "Причина"].map((t) => el("th", {}, t)))), body),
    more);
  add(first.entries);
}

/** A SET_FEATURE entry's feature and the way it went, from the target the server writes ("RADAR on"); null otherwise. */
function featureSwitched(entry) {
  const match = entry.action === "SET_FEATURE" ? /^(\w+) (on|off)$/.exec(entry.target ?? "") : null;
  return match ? { name: match[1], enabled: match[2] === "on" } : null;
}

// Big games (docs/adr/0010-big-games.md): admins only. The zone is drawn on the map with the pencil; next to it the
// area and how many players the zone fits by its ground (the server's estimate). Every change needs a reason.

const BIG_STATUS = {
  SCHEDULED: "запись", LOBBY: "лобби открыто", RUNNING: "идёт", FINISHED: "закончилась", CANCELLED: "отменена",
  INTERRUPTED: "прервана",
};
const BIG_EDITABLE = ["SCHEDULED", "LOBBY", "INTERRUPTED"];
const MAP_CENTER_KEY = "hovanki.admin.mapCenter";

async function bigGamesView() {
  const page = await run(() => get("/big-games"));
  frame("big");
  show(
    el("div", { class: "row spread" }, el("h1", {}, "Большие игры"), el("a", { class: "button", href: "#/big/new" }, "Новая игра")),
    el("p", { class: "muted small" }, `Запись заранее, лобби открывается за 30 минут до старта, старт по времени или кнопкой. До ${fmt.number(page.maxPlayers)} игроков. Хост — сервер.`),
    page.games.length ? el("table", {},
      el("tr", {}, ["Игра", "Статус", "Старт (время места)", "Записались", "Вмещает", "В игре", ""].map((t) => el("th", {}, t))),
      page.games.map((game) => el("tr", {},
        el("td", {}, el("a", { href: `#/big/${encodeURIComponent(game.id)}` }, game.title)),
        el("td", {}, el("span", { class: `tag ${game.status === "RUNNING" || game.status === "LOBBY" ? "ok" : ""}` }, BIG_STATUS[game.status] ?? game.status)),
        el("td", {}, game.startsAtLocal.replace("T", " "), el("div", { class: "small muted" }, game.timeZone)),
        el("td", {}, `${fmt.number(game.signedUp)} / ${fmt.number(game.playerLimit)}`),
        el("td", {}, game.capacity == null ? "—" : fmt.number(game.capacity),
          game.capacity != null && game.playerLimit > game.capacity ? el("div", { class: "small" }, el("span", { class: "tag mute" }, "лимит выше")) : null),
        el("td", {}, game.players == null ? "—" : fmt.number(game.players)),
        el("td", {}, bigGameActions(game, bigGamesView))))) : el("p", { class: "muted" }, "Больших игр ещё не было."));
}

/** Start (the lobby is open) and cancel, each with a reason. */
function bigGameActions(game, reload) {
  const action = (title, path, text, danger) => el("button", {
    class: danger ? "danger" : "secondary",
    async onclick() {
      const values = await ask(`${title}: ${game.title}`, { text, confirm: title, danger });
      if (!values) return;
      await run(() => post(`/big-games/${encodeURIComponent(game.id)}/${path}`, { reason: values.reason }), "Готово.");
      reload();
    },
  }, title);
  return el("div", { class: "row" },
    game.status === "LOBBY" ? action("Старт", "start", "Раунд начнётся сейчас с теми, кто в лобби; ищущих выберет сервер.") : null,
    [...BIG_EDITABLE, "RUNNING"].includes(game.status)
      ? action("Отменить", "cancel", "Записавшиеся увидят, что игра отменена; идущий раунд закончится, лобби закроется.", true)
      : null);
}

async function bigGameEditor(id) {
  const page = await run(() => get("/big-games"));
  frame("big");
  const game = id === "new" ? null : page.games.find((g) => g.id === id);
  if (id !== "new" && !game) {
    show(el("p", {}, "Нет такой игры. ", el("a", { href: "#/big" }, "К списку")));
    return;
  }
  const editable = !game || BIG_EDITABLE.includes(game.status);
  const corners = game ? game.zone.outline.map((p) => ({ lat: p.lat, lon: p.lon })) : [];
  if (corners.length > 1 && corners[0].lat === corners.at(-1).lat && corners[0].lon === corners.at(-1).lon) corners.pop();
  const setup = game?.setup ?? { hidingMinutes: 10, seekingMinutes: 60, shrinks: true, glowEveryMinutes: 5, glowForSeconds: 10, seekers: 10 };
  const norms = game?.norms ?? page.norms;

  // The form
  const input = (name, value, attrs = {}) => el("input", { name, value: value ?? "", ...attrs });
  const number = (name, value, min, max) => input(name, value, { type: "number", min, max, class: "days" });
  const zones = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];
  const ownZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const zoneValue = game?.timeZone ?? ownZone;
  const timeZone = zones.length
    ? el("select", { name: "timeZone" }, (zones.includes(zoneValue) ? zones : [zoneValue, ...zones]).map((z) => el("option", { value: z, selected: z === zoneValue }, z)))
    : input("timeZone", zoneValue);
  const fields = {
    title: input("title", game?.title, { maxLength: 80, required: true }),
    start: input("start", game?.startsAtLocal, { type: "datetime-local", required: true }),
    timeZone,
    hiding: number("hiding", setup.hidingMinutes, 1, 60),
    seeking: number("seeking", setup.seekingMinutes, 10, 240),
    shrinks: el("input", { type: "checkbox", name: "shrinks", checked: setup.shrinks }),
    glowEvery: number("glowEvery", setup.glowEveryMinutes, 0, 60),
    glowFor: number("glowFor", setup.glowForSeconds, 2, 600),
    seekers: number("seekers", setup.seekers, 1, page.maxPlayers - 1),
    limit: input("limit", game ? game.playerLimit : "", { type: "number", min: 2, max: page.maxPlayers, class: "days", placeholder: "авто" }),
    dense: number("dense", norms.denseSquareMeters, 10, 1000000),
    forest: number("forest", norms.forestSquareMeters, 10, 1000000),
    mixed: number("mixed", norms.mixedSquareMeters, 10, 1000000),
    open: number("open", norms.openSquareMeters, 10, 1000000),
    reason: input("reason", "", { maxLength: 500, required: true }),
  };
  if (!editable) for (const field of Object.values(fields)) field.disabled = true;
  const currentNorms = () => ({
    denseSquareMeters: Number(fields.dense.value), forestSquareMeters: Number(fields.forest.value),
    mixedSquareMeters: Number(fields.mixed.value), openSquareMeters: Number(fields.open.value),
  });

  // The estimate
  const estimate = el("div", { class: "estimate" });
  let estimateTimer = null;
  let estimated = game ? { capacity: game.capacity, areas: game.areas, fewCovers: game.fewCovers, state: game.capacity == null ? "UNAVAILABLE" : "READY" } : null;
  function showEstimate() {
    const area = areaSquareMeters(corners);
    const hectares = (m2) => (m2 >= 1_000_000 ? `${fmt.number(m2 / 1_000_000, 2)} км²` : `${fmt.number(m2 / 10_000, 1)} га`);
    const rows = [el("div", {}, el("span", { class: "muted small" }, "Площадь"), el("b", {}, corners.length > 2 ? hectares(area) : "—"))];
    if (estimated?.state === "READY" && corners.length > 2) {
      rows.push(el("div", {}, el("span", { class: "muted small" }, "Помещается"), el("b", {}, fmt.plural(estimated.capacity, "игрок", "игрока", "игроков"))));
      const a = estimated.areas;
      rows.push(el("div", { class: "small muted" },
        `застройка ${hectares(a.denseSquareMeters)} · лес ${hectares(a.forestSquareMeters)} · парк и смешанная ${hectares(a.mixedSquareMeters)} · открытое ${hectares(a.openSquareMeters)} · дома и вода ${hectares(a.blockedSquareMeters)}`));
      if (estimated.fewCovers) rows.push(el("p", { class: "tag mute" }, "Мало укрытий: почти вся зона — открытое место."));
      const limit = Number(fields.limit.value);
      if (limit && limit > estimated.capacity) rows.push(el("p", { class: "tag mute" }, `Лимит ${limit} выше оценки: причина попадёт в журнал.`));
    } else if (estimated?.state === "UNAVAILABLE" && corners.length > 2) {
      rows.push(el("p", { class: "small muted" }, "Нет данных карты для оценки: задайте лимит сами."));
    } else if (corners.length > 2) {
      rows.push(el("p", { class: "small muted" }, "Считаем…"));
    }
    estimate.replaceChildren(...rows);
  }
  function askEstimate() {
    clearTimeout(estimateTimer);
    estimated = null;
    showEstimate();
    if (corners.length < 3) return;
    estimateTimer = setTimeout(async () => {
      try {
        estimated = await post("/zone-estimate", { zone: { outline: corners }, norms: currentNorms() });
      } catch (e) {
        estimated = { state: "ERROR" };
        if (e instanceof ApiError) notice(errorText(e));
      }
      showEstimate();
    }, 600);
  }
  for (const name of ["dense", "forest", "mixed", "open"]) fields[name].addEventListener("change", askEstimate);
  fields.limit.addEventListener("input", showEstimate);

  // The map
  let savedCenter = null;
  try { savedCenter = JSON.parse(localStorage.getItem(MAP_CENTER_KEY)); } catch { /* no storage: the default */ }
  const canvas = el("canvas", { class: "map" });
  const map = new ZoneMap(canvas, {
    loadTile: (z, x, y) => get(`/tiles/${z}/${x}/${y}`),
    center: savedCenter?.lat != null ? savedCenter : { lat: 50.4501, lon: 30.5234 },
    zoom: 15,
    points: corners,
    editable,
    onChange: askEstimate,
  });
  const pencil = el("button", {
    type: "button", class: "secondary",
    onclick() { map.setPencil(!map.pencil); pencil.textContent = map.pencil ? "Карандаш: вкл" : "Карандаш: выкл"; },
  }, map.pencil ? "Карандаш: вкл" : "Карандаш: выкл");
  map.setPencil(map.pencil);
  const place = el("input", { placeholder: "Широта, долгота: 50.4501, 30.5234", class: "place" });
  const tools = el("div", { class: "row map-tools" },
    el("button", { type: "button", class: "secondary", onclick: () => map.zoomAt(map.zoom + 1) }, "+"),
    el("button", { type: "button", class: "secondary", onclick: () => map.zoomAt(map.zoom - 1) }, "−"),
    editable ? pencil : null,
    editable ? el("button", { type: "button", class: "secondary", onclick() { corners.pop(); map.changed(); } }, "Убрать точку") : null,
    editable ? el("button", { type: "button", class: "secondary", onclick() { map.setPoints([]); map.setPencil(true); pencil.textContent = "Карандаш: вкл"; } }, "Очистить") : null,
    el("button", { type: "button", class: "secondary", onclick: () => map.fit() }, "Вся зона"),
    el("form", {
      class: "row",
      onsubmit(event) {
        event.preventDefault();
        const [lat, lon] = place.value.split(/[,;\s]+/).map(Number);
        if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 85 && Math.abs(lon) <= 180) {
          map.goTo({ lat, lon }, 15);
          try { localStorage.setItem(MAP_CENTER_KEY, JSON.stringify({ lat, lon })); } catch { /* fine */ }
        } else {
          notice("Координаты: широта, долгота, например 50.4501, 30.5234.");
        }
      },
    }, place, el("button", { type: "submit", class: "secondary" }, "Перейти")),
    navigator.geolocation ? el("button", {
      type: "button", class: "secondary",
      onclick() {
        navigator.geolocation.getCurrentPosition(
          (position) => map.goTo({ lat: position.coords.latitude, lon: position.coords.longitude }, 15),
          () => notice("Браузер не дал место."));
      },
    }, "Где я") : null);

  const label = (text, field, note) => el("label", {}, text, el("br"), field, note ? el("span", { class: "small muted" }, " ", note) : null);
  const form = el("form", {
    class: "card",
    async onsubmit(event) {
      event.preventDefault();
      if (corners.length < 3) { notice("Нарисуйте зону: хотя бы три точки."); return; }
      const limit = fields.limit.value.trim();
      const body = {
        title: fields.title.value.trim(),
        timeZone: fields.timeZone.value,
        startsAtLocal: fields.start.value,
        zone: { outline: corners },
        setup: {
          hidingMinutes: Number(fields.hiding.value), seekingMinutes: Number(fields.seeking.value), shrinks: fields.shrinks.checked,
          glowEveryMinutes: Number(fields.glowEvery.value), glowForSeconds: Number(fields.glowFor.value), seekers: Number(fields.seekers.value),
        },
        norms: currentNorms(),
        playerLimit: limit ? Number(limit) : null,
        reason: fields.reason.value.trim(),
      };
      const saved = await run(() => (game ? post(`/big-games/${encodeURIComponent(game.id)}/update`, body) : post("/big-games", body)),
        game ? "Изменено: записавшиеся увидят." : "Игра создана: она в списке у игроков.");
      go(`#/big/${encodeURIComponent(saved.id)}`);
    },
  },
  el("h2", {}, "Игра"),
  label("Название", fields.title),
  el("div", { class: "row" }, label("Старт, время места", fields.start), label("Часовой пояс места", fields.timeZone)),
  el("div", { class: "row" }, label("Прятки, мин", fields.hiding), label("Поиск, мин", fields.seeking), label("Ищущих", fields.seekers)),
  el("label", {}, fields.shrinks, " Зона сужается к центру (до конца поиска)"),
  el("div", { class: "row" }, label("Свечение раз в, мин (0 — нет)", fields.glowEvery), label("на, с", fields.glowFor)),
  label("Лимит игроков", fields.limit, `пусто — сколько помещается, не больше ${fmt.number(page.maxPlayers)}`),
  el("h2", {}, "Норма, м² на игрока"),
  el("div", { class: "row" }, label("Застройка", fields.dense), label("Лес", fields.forest), label("Парк", fields.mixed), label("Открытое", fields.open)),
  label("Причина (попадёт в журнал)", fields.reason),
  editable ? el("p", {}, el("button", { type: "submit" }, game ? "Сохранить" : "Создать")) : el("p", { class: "muted" }, "Раунд начался или игра закончилась: менять нельзя."));

  show(
    el("div", { class: "row spread" },
      el("h1", {}, game ? game.title : "Новая большая игра",
        game ? el("span", { class: "tag" }, " ", BIG_STATUS[game.status] ?? game.status) : null),
      el("a", { href: "#/big" }, "← к списку")),
    game ? el("div", { class: "row" },
      el("span", { class: "muted" }, `Записались ${fmt.number(game.signedUp)} из ${fmt.number(game.playerLimit)}`,
        game.players != null ? ` · в игре ${fmt.number(game.players)}` : ""),
      bigGameActions(game, () => bigGameEditor(id))) : null,
    el("div", { class: "map-layout" },
      el("div", {}, tools, canvas,
        el("p", { class: "small muted" }, editable ? "Карандаш: клик ставит точку. Точку можно тащить, серую точку посреди стороны — тоже (появится новая), правый клик убирает. " : "",
          "Карта: © OpenMapTiles © участники OpenStreetMap, тайлы OpenFreeMap через наш сервер."),
        estimate),
      form));
  showEstimate();
  if (corners.length > 2) map.fit();
  if (!game) fields.title.focus();
}

// The radio lab (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md step 1): admins only, whether the
// RADIO_LAB feature is on or off (old reports stay readable). Test phones of the debug build join a run by its code or
// QR, follow its plan by the server's clock and upload their lab logs. Here: the list and a new run, the run's console
// (every button with a reason), its devices and the live view from the chunks received so far, refreshed every 2
// seconds, and the report. Labels, phone models and numbers only: no coordinates, no players.

const LAB_POLL_MS = 2000;
const LAB_STATUS = { CREATED: "ждёт старта", RUNNING: "идёт", PAUSED: "пауза", FINISHED: "завершён" };
const LAB_BLUETOOTH = { ON: "BT вкл", OFF: "BT выкл", DENIED: "BT запрещён", OFF_BY_PLAYER: "BT выкл вручную", UNSUPPORTED: "нет BT" };
let labTimers = [];
// Grows with every stopLab(): an answer that arrives for an older page is dropped.
let labGeneration = 0;

function stopLab() {
  for (const timer of labTimers) clearInterval(timer);
  labTimers = [];
  labGeneration++;
}

const labPath = (id, action) => `/lab/runs/${encodeURIComponent(id)}${action ? `/${action}` : ""}`;
const labHash = (id, sub) => `#/lab/${encodeURIComponent(id)}${sub ? `/${sub}` : ""}`;

function labStatusTag(status) {
  const kind = status === "RUNNING" ? "ok" : status === "PAUSED" ? "mute" : "";
  return el("span", { class: `tag ${kind}` }, LAB_STATUS[status] ?? status);
}

/** 1536000 → «1,5 МБ». */
function labBytes(bytes) {
  if (bytes == null) return "—";
  if (bytes < 1024) return `${fmt.number(bytes)} Б`;
  if (bytes < 1024 * 1024) return `${fmt.number(bytes / 1024, 1)} КБ`;
  return `${fmt.number(bytes / 1024 / 1024, 1)} МБ`;
}

/** Milliseconds as «1:05»; null as «—». */
function labClock(ms) {
  if (ms == null) return "—";
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** How long ago [ms] of the server's clock was; [offset] = the server's clock − this computer's. */
function labAgo(ms, offset) {
  if (ms == null) return "—";
  const seconds = Math.max(0, Math.round((Date.now() + offset - ms) / 1000));
  return seconds < 120 ? `${seconds} с назад` : fmt.ago(ms - offset);
}

/** «Радио · radio v2 · метки A, mac · 8 шагов · 12:00 по таймеру». */
function labScenarioLine(scenario) {
  return [
    scenario.title, `${scenario.id} v${scenario.version}`, `метки ${(scenario.labels ?? []).join(", ")}`,
    fmt.plural(scenario.steps, "шаг", "шага", "шагов"),
    scenario.totalSeconds == null ? "есть шаги до кнопки" : `${labClock(scenario.totalSeconds * 1000)} по таймеру`,
  ].join(" · ");
}

/** What the phone said it can do, in one line. */
function labCapabilities(c = {}) {
  const parts = [c.platform ?? "OTHER", LAB_BLUETOOTH[c.bluetooth] ?? c.bluetooth ?? "нет BT"];
  if (c.uwb) parts.push("UWB");
  if (c.advertisingSets != null) parts.push(`наборов рекламы: ${c.advertisingSets}`);
  const flag = (value, name) => {
    if (value != null) parts.push(`${name}: ${value ? "да" : "нет"}`);
  };
  flag(c.leCoded, "LE Coded");
  flag(c.wifiAware, "Wi-Fi Aware");
  flag(c.locationPermission, "геолокация");
  flag(c.notifications, "уведомления");
  return parts.join(" · ");
}

/** The run is gone (deleted) or the answer failed: a card instead of the page. */
function labGone(e) {
  const back = el("a", { href: "#/lab" }, "← Все прогоны");
  if (e instanceof ApiError && e.status === 404) {
    frame("lab");
    show(back, el("div", { class: "card narrow" }, el("h1", {}, "Прогона нет"), el("p", {}, "Его удалили, или ссылка неверна.")));
  } else {
    run(() => Promise.reject(e)).catch(() => {});
  }
}

async function labRunsView() {
  const page = await run(() => get("/lab/runs"));
  frame("lab");
  const runs = page.runs ?? [];
  const scenarios = page.scenarios ?? [];
  const scenarioOf = (labRun) => scenarios.find((s) => s.id === labRun.scenarioId);

  const title = el("input", { name: "title", required: true, maxLength: 100, placeholder: "Например: двор, айфон в кармане" });
  const scenario = el("select", { name: "scenario" },
    scenarios.map((s) => el("option", { value: s.id }, `${s.title} (${s.id} v${s.version})`)));
  const about = el("p", { class: "small muted" });
  const showAbout = () => {
    const chosen = scenarios.find((s) => s.id === scenario.value);
    about.textContent = chosen ? labScenarioLine(chosen) : "";
  };
  scenario.addEventListener("change", showAbout);
  const reason = el("input", { name: "reason", required: true, maxLength: 500 });
  const form = el("form", {
    class: "card",
    async onsubmit(event) {
      event.preventDefault();
      const created = await run(() => post("/lab/runs", {
        title: title.value.trim(), scenarioId: scenario.value, reason: reason.value.trim(),
      }), "Прогон создан.");
      go(labHash(created.id));
    },
  },
  el("h2", { class: "first" }, "Новый прогон"),
  el("label", {}, "Название", title),
  el("label", {}, "Сценарий", el("br"), scenario),
  about,
  el("label", {}, "Причина (попадёт в журнал)", reason),
  el("p", {}, el("button", { type: "submit", disabled: !scenarios.length }, "Создать")));

  show(el("h1", {}, "Радиолаба"),
    el("p", { class: "muted small" },
      "Прогон — один замер радио на тестовых телефонах debug-сборки: они входят по коду или QR, идут по плану по часам " +
      "сервера и шлют журналы. Без игроков и координат. Телефоны войдут, только пока включена возможность «Радиолаба»; " +
      "журналы хранятся 90 дней после прогона, отчёты — дольше."),
    runs.length ? el("table", {},
      el("tr", {}, ["Прогон", "Код", "Сценарий", "Статус", "Телефоны", "Журналы", "Создал", "Отчёт"].map((t) => el("th", {}, t))),
      runs.map((r) => el("tr", {},
        el("td", {}, el("a", { href: labHash(r.id) }, r.title)),
        el("td", { class: "mono" }, r.code),
        el("td", {}, scenarioOf(r)?.title ?? r.scenarioId, el("div", { class: "small muted mono" }, `${r.scenarioId} v${r.scenarioVersion}`)),
        el("td", {}, labStatusTag(r.status)),
        el("td", {}, fmt.number(r.devices ?? 0)),
        el("td", {}, labBytes(r.bytes ?? 0)),
        el("td", {}, r.createdByName || "—", el("div", { class: "small muted" }, fmt.time(r.createdAtMillis))),
        el("td", {}, r.reportReady ? el("a", { href: labHash(r.id, "report") }, "✓ открыть") : "—")))) :
      el("p", { class: "muted" }, "Прогонов ещё не было."),
    form);
  showAbout();
}

/** The time left in the current timed step by the server's clock; null for a button step or a run not going. */
function labLeftMillis(view, offset) {
  const { state } = view;
  const step = view.steps?.[state.stepIndex];
  if (!step || step.seconds == null || state.stepStartedAtMillis == null) return null;
  if (state.status === "PAUSED") return state.stepStartedAtMillis + step.seconds * 1000 - (state.pausedAtMillis ?? Date.now() + offset);
  if (state.status !== "RUNNING") return null;
  return state.stepStartedAtMillis + step.seconds * 1000 - (Date.now() + offset);
}

async function labRunView(id) {
  const generation = labGeneration;
  let view;
  try {
    view = await get(labPath(id));
  } catch (e) {
    if (generation === labGeneration) labGone(e);
    return;
  }
  if (generation !== labGeneration) return;
  // A game's field log is no lab run: its own tab shows it (an old link lands here).
  if (view.run.kind === "GAME") {
    go(`#/field/${encodeURIComponent(id)}`);
    return;
  }
  frame("lab");
  let offset = view.state.serverTimeMillis - Date.now();
  const head = el("div");
  const consoleBox = el("div", { class: "card" });
  const devices = el("div");
  const live = el("div");
  const status = el("p", { class: "small muted" });
  const countdown = el("b", { class: "mono" });

  // The whole plan, built once; the current step's row is marked on every render.
  const steps = view.steps ?? [];
  const planRows = steps.map((step) => el("tr", {},
    el("td", {}, step.index + 1), el("td", { class: "mono" }, step.id), el("td", {}, step.title),
    el("td", {}, step.seconds == null ? "до кнопки" : `${step.seconds} с`)));
  const plan = el("details", {}, el("summary", {}, `Весь план: ${fmt.plural(steps.length, "шаг", "шага", "шагов")}`),
    el("table", {}, el("tr", {}, ["№", "Шаг", "Что", "Длится"].map((t) => el("th", {}, t))), planRows));

  function render() {
    head.replaceChildren(labRunHead(view));
    consoleBox.replaceChildren(...labConsole(view, countdown, apply));
    planRows.forEach((row, index) => {
      row.className = index === view.state.stepIndex && view.state.status !== "FINISHED" ? "current" : "";
    });
    devices.replaceChildren(labDevices(view, offset));
    live.replaceChildren(...labLive(view, offset));
    tickClock();
  }
  function tickClock() {
    const left = labLeftMillis(view, offset);
    countdown.textContent = left == null ? "" : labClock(left);
  }
  function apply(next) {
    if (!next || generation !== labGeneration) return;
    view = next;
    offset = view.state.serverTimeMillis - Date.now();
    status.textContent = `Обновлено ${new Date().toLocaleTimeString("ru-RU")}, каждые 2 секунды.`;
    render();
  }

  show(el("a", { href: "#/lab" }, "← Все прогоны"), head, consoleBox, plan,
    el("h2", {}, "Телефоны"), devices,
    el("h2", {}, "Вживую"),
    el("p", { class: "muted small" }, "Из журналов, пришедших за последние секунды: кто кого слышит за 10 секунд. " +
      "Телефоны шлют журналы раз в несколько секунд, так что картинка отстаёт."),
    live, status);
  apply(view);

  let busy = false;
  labTimers.push(setInterval(tickClock, 250));
  labTimers.push(setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      apply(await get(labPath(id)));
    } catch (e) {
      if (generation !== labGeneration) return;
      if (e instanceof ApiError && e.status === 401) {
        me = null;
        notice("Сессия закончилась: войдите снова.");
        route();
      } else if (e instanceof ApiError && e.status === 404) {
        stopLab();
        labGone(e);
      } else {
        status.textContent = `Не обновилось: ${e instanceof ApiError ? errorText(e) : e.message}. Попробую снова.`;
      }
    } finally {
      busy = false;
    }
  }, LAB_POLL_MS));
}

/** The run's title, code and QR, and what can be done with it as a whole. */
function labRunHead(view) {
  const r = view.run;
  const id = r.id;
  const finished = r.status === "FINISHED";
  return el("div", {},
    el("div", { class: "row spread" }, el("h1", {}, r.title, " ", labStatusTag(r.status)),
      el("div", { class: "row" },
        r.reportReady ? el("a", { class: "button", href: labHash(id, "report") }, "Отчёт")
          : finished ? el("span", { class: "muted small" }, "Отчёт считается…") : null,
        el("button", { class: "secondary", onclick: () => labDownload(r) }, "Скачать сырые журналы"),
        el("button", { class: "danger", onclick: () => labDelete(r) }, "Удалить"))),
    el("div", { class: "card lab-head" },
      el("div", {},
        el("div", { class: "muted small" }, "Код для телефонов"),
        el("div", { class: "code-big" }, r.code),
        el("p", { class: "small muted" }, "В приложении: диагностика → «Радиолаба» → «Прогон на сервере»: код или QR и метка " +
          "телефона. Войти можно сутки после создания, пока прогон не завершён."),
        el("dl", { class: "grid" },
          field("Сценарий", `${r.scenarioId} v${r.scenarioVersion}`),
          field("Метки", (view.labels ?? []).join(", ")),
          field("Создал", `${r.createdByName || "—"}, ${fmt.time(r.createdAtMillis)}`),
          field("Начат", fmt.time(r.startedAtMillis)),
          field("Завершён", fmt.time(r.finishedAtMillis)),
          field("Журналы", `${labBytes(r.bytes ?? 0)} от ${fmt.plural(r.devices ?? 0, "телефона", "телефонов", "телефонов")}`))),
      qrSvg(r.qr ?? [])));
}

/** «Step N of M», the countdown, what every label does now, and the buttons that move the plan. */
function labConsole(view, countdown, apply) {
  const { run: r, state } = view;
  const steps = view.steps ?? [];
  const step = steps[state.stepIndex];
  const shown = state.status === "CREATED" ? steps[0] : state.status === "FINISHED" ? null : step;
  let headline;
  if (state.status === "CREATED") headline = `Не начат: ${fmt.plural(steps.length, "шаг", "шага", "шагов")}. Телефоны вошли — «Начать».`;
  else if (state.status === "FINISHED") headline = "Прогон завершён.";
  else headline = `Шаг ${state.stepIndex + 1} из ${steps.length}: ${step?.title ?? "?"}`;
  const timing = !step || state.status === "CREATED" || state.status === "FINISHED" ? null
    : step.seconds == null ? el("p", { class: "muted" }, "Этот шаг идёт до кнопки «Дальше».")
      : el("p", {}, state.status === "PAUSED" ? "На паузе, осталось " : "Осталось ", countdown, ` из ${labClock(step.seconds * 1000)}`);

  const control = (title, action, text, danger) => el("button", {
    class: danger ? "danger" : action === "NEXT" ? null : "secondary",
    async onclick() {
      const values = await ask(`${title}: ${r.title}`, { text, confirm: title, danger });
      if (!values) return;
      apply(await run(() => (action
        ? post(labPath(r.id, "advance"), { action, reason: values.reason })
        : post(labPath(r.id, "finish"), { reason: values.reason })), "Готово."));
    },
  }, title);
  const last = state.stepIndex >= steps.length - 1;
  const going = state.status === "RUNNING" || state.status === "PAUSED";
  const buttons = el("div", { class: "row" },
    state.status === "CREATED" ? control("Начать", "NEXT", "Первый шаг начнётся сейчас на всех телефонах.") : null,
    going ? control("Дальше", "NEXT", last ? "Это последний шаг: прогон закончится." : "Следующий шаг начнётся сейчас.") : null,
    going ? control("Повторить", "REPEAT", "Этот шаг начнётся заново с полным временем.") : null,
    state.status === "RUNNING" ? control("Пауза", "PAUSE", "Таймер шага встанет; телефоны остаются в своём шаге.") : null,
    state.status === "PAUSED" ? control("Продолжить", "RESUME", "Таймер шага пойдёт дальше с того места.") : null,
    state.status === "FINISHED" ? null : control("Завершить", null,
      "Прогон закончится, телефоны отправят остаток журналов, сервер посчитает отчёт. Войти в него больше нельзя.", true));

  const hints = shown?.hints ?? {};
  const labels = view.labels ?? [];
  return [
    el("h2", { class: "first" }, headline, state.status === "CREATED" || state.status === "FINISHED" ? null
      : el("span", { class: "mono muted small" }, ` ${step?.id ?? ""}`)),
    timing,
    shown ? el("div", {},
      el("div", { class: "small muted" }, state.status === "CREATED" ? "Первый шаг, что делает каждый:" : "Что делает каждый:"),
      el("table", {}, labels.map((label) => el("tr", {},
        el("td", { class: "mono" }, label), el("td", {}, hints[label] || "—"))))) : null,
    buttons,
    el("p", { class: "small muted" }, `Ревизия ${state.revision}: растёт с каждой кнопкой, телефоны сверяют по ней шаг.`),
  ];
}

function labDevices(view, offset) {
  const list = view.devices ?? [];
  if (!list.length) return el("p", { class: "muted" }, "Ещё никто не вошёл.");
  return el("table", {},
    el("tr", {}, ["Метка", "Телефон", "Сборка", "Вошёл", "Последний кусок", "seq", "Объём", "Событий", "Возможности"]
      .map((t) => el("th", {}, t))),
    list.map((d) => el("tr", {},
      el("td", { class: "mono" }, d.label),
      el("td", {}, d.model ?? "—", el("div", { class: "small muted" }, d.os ?? "")),
      el("td", { class: "mono" }, d.build ?? "—", d.commit ? el("div", { class: "small muted" }, d.commit) : null),
      el("td", {}, fmt.time(d.joinedAtMillis)),
      el("td", {}, labAgo(d.lastChunkAtMillis, offset)),
      el("td", { class: "mono" }, d.lastSeq ?? "—"),
      el("td", {}, labBytes(d.bytes ?? 0)),
      el("td", {}, fmt.number(d.events ?? 0)),
      el("td", { class: "small" }, labCapabilities(d.capabilities)))));
}

/** The live view: every phone's last state, then who hears whom over the last 10 seconds. */
function labLive(view, offset) {
  const live = view.live ?? {};
  const liveDevices = live.devices ?? [];
  const pairs = [...(live.pairs ?? [])];
  // Pairs of the phones in the run that nobody heard at all, so a silent direction shows as one.
  const labels = [...new Set(liveDevices.map((d) => d.label))];
  for (const from of labels) {
    for (const to of labels) {
      if (from !== to && !pairs.some((p) => p.from === from && p.to === to)) {
        pairs.push({ from, to, channel: "—", heardInLast10s: 0 });
      }
    }
  }
  pairs.sort((a, b) => a.from.localeCompare(b.from) || a.to.localeCompare(b.to) || a.channel.localeCompare(b.channel));
  const heardClass = (n) => (n >= 5 ? "heard ok" : n >= 1 ? "heard weak" : "heard none");
  return [
    liveDevices.length ? el("table", {},
      el("tr", {}, ["Метка", "Последнее событие", "Часы", "Приложение", "Bluetooth", "Батарея", "Шаг"].map((t) => el("th", {}, t))),
      liveDevices.map((d) => el("tr", {},
        el("td", { class: "mono" }, d.label),
        el("td", {}, labAgo(d.lastEventAtMillis, offset)),
        el("td", {}, d.clockOffsetMillis == null ? "—" : `${d.clockOffsetMillis > 0 ? "+" : ""}${fmt.number(d.clockOffsetMillis)} мс`),
        el("td", {}, d.appState ?? "—"),
        el("td", {}, d.bluetooth ?? "—"),
        el("td", {}, d.batteryLevel == null ? "—" : `${Math.round(d.batteryLevel * 100)} %`),
        el("td", {}, d.stepIndex == null ? "—" : d.stepIndex < 0 ? "до старта" : d.stepIndex + 1))))
      : el("p", { class: "muted" }, "Журналов ещё не пришло."),
    pairs.length ? el("table", { class: "pairs" },
      el("tr", {}, ["Кого слышно → кто слышит", "Канал", "За 10 с", "RSSI, медиана"].map((t) => el("th", {}, t))),
      pairs.map((p) => el("tr", {},
        el("td", { class: "mono" }, `${p.from} → ${p.to}`),
        el("td", { class: "mono" }, p.channel),
        el("td", { class: heardClass(p.heardInLast10s) }, fmt.number(p.heardInLast10s)),
        el("td", {}, p.medianRssi == null ? "—" : `${p.medianRssi} дБм`)))) : null,
  ];
}

/** Asks for the reason, then downloads the zip of every phone's log (the audit log records it). */
async function labDownload(r) {
  const values = await ask(`Скачать журналы: ${r.title}`, {
    text: "Сырые журналы каждого телефона (JSONL в zip): RSSI, токены радара, события приложения. Без координат. " +
      "Причина попадёт в журнал.",
    confirm: "Скачать",
  });
  if (!values) return;
  const blob = await run(async () => (await send("POST", labPath(r.id, "raw"), { reason: values.reason })).blob(),
    "Журналы скачаны.");
  const url = URL.createObjectURL(blob);
  const link = el("a", { href: url, download: `hovanki-lab-${r.code}.zip` });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

async function labDelete(r) {
  const values = await ask(`Удалить прогон: ${r.title}`, {
    text: "Прогон, его телефоны, журналы и отчёт удалятся насовсем. Идущий прогон телефоны потеряют.",
    confirm: "Удалить", danger: true,
  });
  if (!values) return;
  await run(() => post(labPath(r.id, "delete"), { reason: values.reason }), "Прогон удалён.");
  go("#/lab");
}

// The report

async function labReportView(id) {
  const view = await get(labPath(id)).catch(() => null);
  // A game's report is a FieldReport, which the field tab shows (an old link lands here).
  if (view?.run.kind === "GAME") {
    go(`#/field/${encodeURIComponent(id)}/report`);
    return;
  }
  let report;
  try {
    report = await get(labPath(id, "report"));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      frame("lab");
      show(el("a", { href: labHash(id) }, "← К прогону"), el("div", { class: "card narrow" }, el("h1", {}, "Отчёта нет"),
        el("p", {}, "Сервер считает его, когда прогон завершён; обычно это секунды. Или прогон удалён.")));
    } else {
      run(() => Promise.reject(e)).catch(() => {});
    }
    return;
  }
  frame("lab");
  const empty = (list) => (list?.length ? null : el("p", { class: "muted" }, "Нет данных."));
  const table = (headers, rows) => el("table", {}, el("tr", {}, headers.map((t) => el("th", {}, t))), rows);
  const seconds = (ms) => `${fmt.number(ms / 1000, 1)} с`;
  const percent = (level) => (level == null ? "—" : `${Math.round(level * 100)} %`);
  const sender = (from) => (from.startsWith("?") ? [el("span", { class: "mono" }, from), " ", el("span", { class: "tag mute" }, "неизвестный")]
    : el("span", { class: "mono" }, from));
  const cover = (perSecond) => (perSecond >= 2 ? "cover-ok" : perSecond >= 0.5 ? "cover-weak" : "cover-none");

  const devices = report.devices ?? [];
  const steps = report.steps ?? [];
  const carry = report.carry ?? [];
  const verdictText = { keep: "оставить", drop: "выбросить", too_little_data: "мало данных" };
  const verdictClass = { keep: "cover-ok", drop: "cover-none", too_little_data: "cover-weak" };
  const pct = (value) => (value == null ? "—" : `${fmt.number(value, 1)} %`);
  const db = (value) => (value == null ? "—" : `${fmt.number(value, 1)} дБ`);
  const dbm = (value) => (value == null ? "—" : `${value} дБм`);
  const touchSource = { button: "кнопка", detector: "детектор", both: "детектор и кнопка" };
  const bandErrors = (rows) => empty(rows) ?? table(["Техника", "Секунд", "Неверно", "Ошибка полосы", "Направлений", "Без поправки"],
    rows.map((r) => el("tr", {}, el("td", { class: "mono" }, r.tech), el("td", {}, fmt.number(r.seconds)),
      el("td", {}, fmt.number(r.wrong)), el("td", {}, pct(r.errorPercent)), el("td", {}, fmt.number(r.directions ?? 0)),
      el("td", {}, fmt.number(r.uncalibrated ?? 0)))));
  const witness = report.witness;
  const detector = report.touchDetector;
  show(el("a", { href: labHash(id) }, "← К прогону"),
    el("div", { class: "report" },
      el("h1", {}, `Отчёт: ${view?.run.title ?? report.runId}`),
      el("p", { class: "muted small" }, `Посчитан ${fmt.time(report.computedAtMillis)} из журналов телефонов` +
        `${report.scenarioId ? `, сценарий ${report.scenarioId}` : ""}. Время — по часам сервера. ` +
        "«В секунду»: зелёный — от 2, жёлтый — от 0,5, красный — меньше."),

      el("h2", {}, "Телефоны"),
      empty(devices) ?? table(["Метка", "Телефон", "Сборка", "Схема", "Событий", "Сдвиг часов", "Токен радара"],
        devices.map((d) => {
          const offsets = [...(d.clockOffsetsMillis ?? [])].sort((a, b) => a - b);
          const median = offsets.length ? offsets[Math.floor(offsets.length / 2)] : null;
          return el("tr", {},
            el("td", { class: "mono" }, d.label, d.deviceId ? el("div", { class: "small muted" }, d.deviceId) : null),
            el("td", {}, d.model ?? "—", el("div", { class: "small muted" }, d.os ?? "")),
            el("td", { class: "mono" }, d.build ?? "—", d.commit ? el("div", { class: "small muted" }, d.commit) : null),
            el("td", {}, d.schema ?? "—"),
            el("td", {}, fmt.number(d.events)),
            el("td", {}, median == null ? "—" : `${fmt.number(median)} мс`,
              offsets.length ? el("div", { class: "small muted" },
                `${fmt.plural(offsets.length, "замер", "замера", "замеров")}, ${fmt.number(offsets[0])}…${fmt.number(offsets.at(-1))}`) : null),
            el("td", { class: "mono" }, d.radarToken ?? "—"));
        })),

      el("h2", {}, "Проблемы"),
      report.problems?.length ? el("ul", {}, report.problems.map((p) => el("li", {}, p))) : el("p", { class: "muted" }, "Нет."),

      // Version 2 (docs/radar-run.md step 4): a card per technique against its criterion; older reports have none.
      el("h2", {}, "Техники"),
      el("p", { class: "muted small" }, "Критерий записан до замеров (ADR 0017 §2.3); вердикт — по цифрам этого прогона."),
      empty(report.cards) ?? table(["Техника", "Группа", "Вердикт", "Критерий", "Цифры"],
        report.cards.map((c) => el("tr", {},
          el("td", { class: "mono" }, c.id),
          el("td", { class: "small" }, c.group),
          el("td", { class: verdictClass[c.verdict] ?? null }, verdictText[c.verdict] ?? c.verdict),
          el("td", { class: "small" }, c.criterion),
          el("td", { class: "small" }, c.numbers)))),

      el("h2", {}, "Кто кого слышал, по шагам"),
      empty(steps),
      steps.map((step) => el("div", {},
        el("h3", {}, step.index < 0 ? "До первого шага" : `Шаг ${step.index + 1}: ${step.title}`, " ",
          el("span", { class: "mono muted small" }, step.id), " ",
          el("span", { class: "muted small" }, `${seconds(step.endMillis - step.startMillis)}, с ${fmt.time(step.startMillis)}`)),
        step.directions?.length ? table(
          ["Кого слышно → кто слышит", "Канал", "Приёмов", "В секунду", "RSSI медиана", "p80", "мин…макс", "Дольше всего тишина", "Приложение слушателя"],
          step.directions.map((d) => el("tr", {},
            el("td", {}, sender(d.from), " → ", el("span", { class: "mono" }, d.to)),
            el("td", { class: "mono" }, d.channel),
            el("td", {}, fmt.number(d.readings)),
            el("td", { class: cover(d.perSecond) }, fmt.number(d.perSecond, 2)),
            el("td", {}, `${d.medianRssi} дБм`),
            el("td", {}, d.p80Rssi),
            el("td", {}, `${d.minRssi}…${d.maxRssi}`),
            el("td", {}, seconds(d.longestGapMillis)),
            el("td", { class: "small" }, d.during || "—")))) : el("p", { class: "muted" }, "Никто никого не слышал."))),

      el("h2", {}, "Карман"),
      el("p", { class: "muted small" }, "Секунды: строка — где телефон был на самом деле (по отметкам), столбец — что сказал датчик кармана."),
      empty(carry),
      [...new Set(carry.map((c) => c.label))].map((label) => {
        const rows = carry.filter((c) => c.label === label);
        const truths = [...new Set(rows.map((c) => c.truth))];
        const said = [...new Set(rows.map((c) => c.said))];
        return el("div", {}, el("h3", { class: "mono" }, label),
          table(["Было \\ сказал", ...said], truths.map((truth) => el("tr", {},
            el("td", { class: "mono" }, truth),
            said.map((s) => {
              const cell = rows.find((c) => c.truth === truth && c.said === s);
              return el("td", { class: truth === s ? "cover-ok" : null }, cell ? fmt.number(cell.seconds) : "—");
            })))));
      }),

      el("h2", {}, "Карман: классификаторы против разметки"),
      el("p", { class: "muted small" }, "carry.v1 — нынешний датчик кармана, carry.v2 — кандидат в тени (radio-lab.md §7.3)."),
      empty(report.carryClassifiers) ?? table(["Метка", "Классификатор", "Секунд с разметкой", "Совпало"],
        report.carryClassifiers.map((c) => el("tr", {}, el("td", { class: "mono" }, c.label), el("td", { class: "mono" }, c.tech),
          el("td", {}, fmt.number(c.seconds)), el("td", {}, `${fmt.number(c.agree)} (${pct(c.agreePercent)})`)))),

      el("h2", {}, "Чоканье"),
      el("p", { class: "muted small" }, detector
        ? `Кнопкой отмечено ${fmt.number(detector.buttonTouches)}, детектор нашёл из них ${fmt.number(detector.found)}, ` +
          `ложных ${fmt.number(detector.falseAlarms)}. RSSI a→b — что b слышал от a в момент касания.`
        : "Касаний нет."),
      report.touches?.length ? table(["Пара", "Время", "Источник", "RSSI a→b", "RSSI b→a", "Удары, g", "Разнос, мс"],
        report.touches.map((t) => el("tr", {}, el("td", { class: "mono" }, `${t.a} · ${t.b}`), el("td", {}, fmt.time(t.atMillis)),
          el("td", {}, touchSource[t.source] ?? t.source), el("td", {}, dbm(t.rssiAToB)), el("td", {}, dbm(t.rssiBToA)),
          el("td", {}, t.impactA == null && t.impactB == null ? "—" : `${t.impactA ?? "—"} / ${t.impactB ?? "—"}`),
          el("td", {}, t.skewMillis ?? "—")))) : null,
      report.touchPairs?.length ? table(["Пара", "Касаний", "Поправка a→b", "Поправка b→a", "Разброс трёх", "Дрейф"],
        report.touchPairs.map((p) => el("tr", {}, el("td", { class: "mono" }, `${p.a} · ${p.b}`), el("td", {}, fmt.number(p.touches)),
          el("td", {}, db(p.offsetAToB)), el("td", {}, db(p.offsetBToA)),
          el("td", { class: p.spreadDb != null && p.spreadDb > 6 ? "cover-none" : null }, db(p.spreadDb)), el("td", {}, db(p.driftDb))))) : null,

      el("h2", {}, "Калибровка: ошибка полосы против расстояний шагов"),
      bandErrors(report.calibration),

      el("h2", {}, "Сглаживание: ошибка полосы против расстояний шагов"),
      bandErrors(report.smoothing),

      el("h2", {}, "Без канала"),
      el("p", { class: "muted small" }, "Полоса со всеми каналами против полосы без одного: доля одинаковых секунд и секунды, когда слышал только он."),
      empty(report.without) ?? table(["Канал", "Направлений", "Секунд", "Полоса та же", "Слышал только он, с"],
        report.without.map((w) => el("tr", {}, el("td", { class: "mono" }, w.tech), el("td", {}, fmt.number(w.directions)),
          el("td", {}, fmt.number(w.seconds)), el("td", {}, pct(w.equalPercent)), el("td", {}, fmt.number(w.aloneSeconds ?? 0))))),

      el("h2", {}, "Свидетель (infer.witness)"),
      el("p", { class: witness ? null : "muted" }, witness
        ? `Пар-секунд с выводом: ${fmt.number(witness.inferredSeconds)}; с расстоянием шага ${fmt.number(witness.withTruth)}, ` +
          `из них верно ${fmt.number(witness.right)}.`
        : "Нет данных: меньше трёх телефонов слышали друг друга."),

      el("h2", {}, "Маски (iOS overflow)"),
      empty(report.masks) ?? table(["Метка", "Кадров", "Совпали с пробой", "Расшифрован токен"],
        report.masks.map((m) => el("tr", {}, el("td", { class: "mono" }, m.label), el("td", {}, fmt.number(m.frames)),
          el("td", {}, fmt.number(m.matched)), el("td", {}, fmt.number(m.decoded))))),

      el("h2", {}, "Вибрация"),
      empty(report.haptics) ?? table(["Метка", "Способ", "Сыграно", "Ошибки", "Пропущено", "Движок остановлен"],
        report.haptics.map((h) => el("tr", {}, el("td", { class: "mono" }, h.label), el("td", { class: "mono" }, h.kind),
          el("td", {}, fmt.number(h.played)), el("td", {}, fmt.number(h.errors)), el("td", {}, fmt.number(h.skipped)),
          el("td", {}, fmt.number(h.engineStopped))))),

      el("h2", {}, "Батарея"),
      empty(report.battery) ?? table(["Метка", "В начале", "В конце", "Замеров"],
        report.battery.map((b) => el("tr", {}, el("td", { class: "mono" }, b.label), el("td", {}, percent(b.firstLevel)),
          el("td", {}, percent(b.lastLevel)), el("td", {}, fmt.number(b.samples))))),

      el("h2", {}, "Тики"),
      el("p", { class: "muted small" }, "Тик — раз в секунду, пока приложение живо; пропуск — тишина дольше 2,5 с: приложение спало."),
      empty(report.ticks) ?? table(["Метка", "Тиков", "Пропусков", "Самая долгая тишина"],
        report.ticks.map((t) => el("tr", {}, el("td", { class: "mono" }, t.label), el("td", {}, fmt.number(t.ticks)),
          el("td", { class: t.gaps ? "cover-weak" : null }, fmt.number(t.gaps)), el("td", {}, seconds(t.longestGapMillis)))))));
}

/** Shows [hash]'s page: through hashchange, or right away when it is the current one. */
function go(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

window.addEventListener("hashchange", route);
// run() already showed API errors; the handlers that awaited it just stop.
window.addEventListener("unhandledrejection", (event) => {
  if (event.reason instanceof ApiError) event.preventDefault();
});
route();
