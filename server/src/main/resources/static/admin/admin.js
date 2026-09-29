// Hovanki admin (docs/adr/0008-admin.md): a page without a build step or libraries, over /api/v1/admin.
// Safety rules of this file:
// - everything from the server (report texts, nicknames, reasons) goes into the page as text: el() makes text nodes,
//   nothing here ever sets innerHTML;
// - every request carries the X-Hovanki-Admin header (CSRF) and the session cookie, which scripts can't read.

const API = "/api/v1/admin";
const ROLE = { PLAYER: "игрок", MODERATOR: "модератор", ADMIN: "админ" };
const PHASE = { LOBBY: "лобби", HIDING: "прячутся", SEEKING: "поиск", FINISHED: "закончена" };
const ACTION = {
  LOGIN: "вход", ENROLL_TOTP: "подключил аутентификатор", RESOLVE_REPORT: "разобрал жалобу", BAN: "бан",
  UNBAN: "снял бан", MUTE: "запрет чата", UNMUTE: "снял запрет чата", RENAME: "сменил ник",
  LOGOUT_DEVICES: "выход на всех устройствах", DELETE_ACCOUNT: "удалил аккаунт", SHOW_EMAIL: "показал email",
  FIND_BY_EMAIL: "искал по email", END_GAME: "завершил игру", SET_ROLE: "сменил роль", RESET_TOTP: "сбросил аутентификатор",
  SET_FEATURE: "переключил возможность",
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
  for (const child of children.flat()) {
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

async function api(method, path, body) {
  const response = await fetch(API + path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: { "X-Hovanki-Admin": "1", ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 204) return null;
  const text = await response.text();
  const json = text ? JSON.parse(text) : null;
  if (!response.ok) throw new ApiError(response.status, json);
  return json;
}

const get = (path) => api("GET", path);
const post = (path, body = {}) => api("POST", path, body);

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
  ["staff", "Сотрудники", true], ["audit", "Журнал", true],
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

async function route() {
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
  const [page, id] = location.hash.replace(/^#\/?/, "").split("/");
  try {
    if (page === "users" && id) await userView(decodeURIComponent(id));
    else if (page === "users") await usersView();
    else if (page === "games") await gamesView();
    else if (page === "features") await featuresView();
    else if (page === "stats") await statsView();
    else if (page === "staff" && isAdmin()) await staffView();
    else if (page === "audit" && isAdmin()) await auditView();
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
    el("p", { class: "muted small" }, "Без центра зоны, позиций и чата: только числа."),
    games.length ? el("table", {},
      el("tr", {}, ["Игра", "Фаза", "Хост", "Игроки (гости, ищущие)", "Создана", "В фазе с", "Активность", "Зона", "Карта", "Чат", ""]
        .map((t) => el("th", {}, t))),
      games.map((game) => el("tr", {},
        el("td", { class: "mono" }, game.gameId),
        el("td", {}, PHASE[game.phase]),
        el("td", {}, game.hostName),
        el("td", {}, `${game.players} (${game.guests}, ${game.seekers})`),
        el("td", {}, fmt.ago(game.createdAtMillis)),
        el("td", {}, fmt.ago(game.phaseStartedAtMillis)),
        el("td", {}, fmt.ago(game.lastActivityMillis)),
        el("td", {}, `${Math.round(game.zoneRadiusMeters)} м`),
        el("td", {}, mapSummary(game)),
        el("td", {}, game.chatMessages),
        el("td", {}, isAdmin() && game.phase !== "FINISHED" ? el("button", {
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
      "Всё выключено по умолчанию. Включённое хост может выбрать в настройках новой игры; идущие игры не меняются."),
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
