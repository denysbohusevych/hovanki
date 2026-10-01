// The field tests in the admin (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6): admins only. The games
// of the field build that wrote a log (the testers agreed), here: the list of games, a game's live view (every phone's
// last chunk, app state, battery and sync, refreshed every 3 seconds) with the organizer's «Отметка», the report as
// tables, and what can be taken out (report.md, digest.jsonl, raw.zip, each with a reason for the audit log) or
// deleted. Players are P1…Pn in the report; their ids (the raw logs' names) are shown only next to them, here.
//
// Rules of this file, as admin.js's: everything from the server goes in as text (el() makes text nodes, nothing here
// sets HTML), nothing loads from another host, every request goes through admin.js's helpers (the admin header and
// the session cookie). The helpers come in as `ctx`: admin.js and this file would import each other otherwise.

const POLL_MS = 3000;
const STATUS = { CREATED: "ждёт старта", RUNNING: "идёт", PAUSED: "идёт", FINISHED: "закончена" };
const KIND = {
  phase: "фаза", claim: "заявка на поимку", catch: "поимка", reveal: "раскрытие", glow: "свечение", mark: "отметка",
  err: "ошибка", srv: "сервер", dispute: "спор", band: "полоса радара", fixes: "точки GPS", survey: "опрос",
};
const ANOMALY = {
  journal_gap: "дыра в журнале", sync_stall: "зависший sync", gps_jump: "прыжок GPS", refused_near: "отказ вблизи",
  err: "ошибка", srv_5xx: "5xx сервера", srv_slow: "медленный сервер",
};
const SERVER_LABELS = new Set(["server", "staff"]);
// The app's state on an event (LabLog's appState): not on the screen.
const BACKGROUND = new Set(["inactive", "background", "screen_off"]);

/** @param ctx the helpers of admin.js: el, get, post, del, send, run, ask, fmt, frame, show, go, notice, ApiError, errorText, sessionLost */
export function createFieldTab(ctx) {
  const { el, get, post, del, send, run, ask, fmt, frame, show, go, notice, ApiError, errorText, sessionLost } = ctx;

  let timers = [];
  // Grows with every stop(): an answer that arrives for an older page is dropped.
  let generation = 0;

  function stop() {
    for (const timer of timers) clearInterval(timer);
    timers = [];
    generation++;
  }

  const path = (id, sub) => `/field/games/${encodeURIComponent(id)}${sub ? `/${sub}` : ""}`;
  const hash = (id, sub) => `#/field/${encodeURIComponent(id)}${sub ? `/${sub}` : ""}`;

  // Small things

  const table = (headers, rows) => el("table", {}, el("tr", {}, headers.map((t) => el("th", {}, t))), rows);
  const wide = (node) => el("div", { class: "scroll" }, node);
  const empty = (list) => (list?.length ? null : el("p", { class: "muted" }, "Нет данных."));
  const bytes = (n) => {
    if (n == null) return "—";
    if (n < 1024) return `${fmt.number(n)} Б`;
    if (n < 1024 * 1024) return `${fmt.number(n / 1024, 1)} КБ`;
    return `${fmt.number(n / 1024 / 1024, 1)} МБ`;
  };
  const num = (value, digits = 0, unit = "") => (value == null ? "—" : `${fmt.number(value, digits)}${unit}`);
  const pct = (value, digits = 0) => (value == null ? "—" : `${fmt.number(value, digits)} %`);
  const level = (value) => (value == null ? "—" : `${Math.round(value * 100)} %`);
  const clock = (ms) => (ms == null ? "—" : new Date(ms).toLocaleTimeString("ru-RU"));
  const span = (seconds) => {
    if (seconds == null) return "—";
    const s = Math.round(seconds);
    return s >= 3600 ? `${Math.floor(s / 3600)} ч ${Math.floor((s % 3600) / 60)} мин` : s >= 60 ? `${Math.floor(s / 60)} мин ${s % 60} с` : `${s} с`;
  };
  /** How long ago [ms] of the server's clock was; [offset] = the server's clock − this computer's. */
  const ago = (ms, offset) => {
    if (ms == null) return "—";
    const seconds = Math.max(0, Math.round((Date.now() + offset - ms) / 1000));
    return seconds < 120 ? `${seconds} с назад` : fmt.ago(ms - offset);
  };
  const statusTag = (status) => el("span", { class: `tag ${status === "FINISHED" ? "" : "ok"}` }, STATUS[status] ?? status);
  const counts = (list) => (list?.length ? list.map((c) => `${c.name} × ${c.count}`).join(", ") : "—");
  const tile = (value, title) => el("div", { class: "tile" }, el("b", {}, value), el("span", { class: "muted small" }, title));
  const dl = (...pairs) => el("dl", { class: "grid" }, pairs.flatMap(([title, value]) => [el("dt", {}, title), el("dd", {}, value)]));

  /** The game is gone (deleted) or the answer failed: a card instead of the page. */
  function gone(e, what = "Игры") {
    const back = el("a", { href: "#/field" }, "← Все игры");
    if (e instanceof ApiError && e.status === 404) {
      frame("field");
      show(back, el("div", { class: "card narrow" }, el("h1", {}, `${what} нет`), el("p", {}, "Её удалили, или ссылка неверна.")));
    } else {
      run(() => Promise.reject(e)).catch(() => {});
    }
  }

  // The list

  async function listView() {
    const page = await run(() => get("/field/games"));
    frame("field");
    const runs = page.runs ?? [];
    show(el("h1", {}, "Полевые тесты"),
      el("p", { class: "muted small" },
        "Игры, в которых телефоны полевой сборки (preview) писали журнал: тестеры согласились на экране согласия. Журнал, " +
        "включая координаты, виден только админам и только по причине в журнале аудита; отчёт и digest — без координат и " +
        "без ников: игроки там P1…Pn. Журналы хранятся 90 дней после игры."),
      runs.length ? table(["Игра", "Статус", "Телефоны", "Журналы", "Начата", "Закончена", "Отчёт"],
        runs.map((r) => el("tr", {},
          el("td", {}, el("a", { href: hash(r.id) }, el("span", { class: "mono" }, r.gameId ?? r.id))),
          el("td", {}, statusTag(r.status)),
          el("td", {}, fmt.number(r.devices ?? 0)),
          el("td", {}, bytes(r.bytes ?? 0)),
          el("td", {}, fmt.time(r.createdAtMillis)),
          el("td", {}, fmt.time(r.finishedAtMillis)),
          el("td", {}, r.reportReady ? el("a", { href: hash(r.id, "report") }, "✓ открыть") : "—")))) :
        el("p", { class: "muted" }, "Игр с полевым журналом ещё не было. Они появятся на сервере с включённой возможностью " +
          "«Полевой журнал», когда тестер полевой сборки начнёт раунд."));
  }

  // A game

  async function gameView(id) {
    const mine = generation;
    let view;
    try {
      view = await get(path(id));
    } catch (e) {
      if (mine === generation) gone(e);
      return;
    }
    if (mine !== generation) return;
    frame("field");
    let offset = view.serverTimeMillis - Date.now();
    const head = el("div");
    const phones = el("div");
    const status = el("p", { class: "small muted" });
    const markText = el("input", { name: "mark", maxLength: 120, placeholder: "Что случилось, например: прячущийся зашёл в магазин" });

    function render() {
      const r = view.run;
      head.replaceChildren(
        el("div", { class: "row spread" },
          el("h1", {}, "Игра ", el("span", { class: "mono" }, r.gameId ?? r.id), " ", statusTag(r.status)),
          el("div", { class: "row" },
            r.reportReady ? el("a", { class: "button", href: hash(r.id, "report") }, "Отчёт") : el("span", { class: "muted small" }, "Отчёт ещё не посчитан"),
            el("button", { class: "secondary", onclick: () => download(r, "report.md", "text/markdown") }, "report.md"),
            el("button", { class: "secondary", onclick: () => download(r, "digest.jsonl", "application/x-ndjson") }, "digest.jsonl"),
            el("button", { class: "secondary", onclick: () => rawDownload(r) }, "raw.zip"),
            el("button", { class: "danger", onclick: () => remove(r) }, "Удалить"))),
        dl(["Создан", fmt.time(r.createdAtMillis)],
          ["Закончен", r.finishedAtMillis ? fmt.time(r.finishedAtMillis) : "идёт"],
          ["Телефоны", fmt.number(r.devices ?? 0)],
          ["Журналы", bytes(r.bytes ?? 0)]),
        el("p", { class: "small muted" }, r.status === "FINISHED"
          ? "Игры больше нет на сервере; ещё полчаса телефоны досылают журналы, потом отчёт считается заново целиком."
          : "Игра идёт: журнал пишется. Живой отчёт пересчитывается раз в пару минут по мере прихода журналов."));
      phones.replaceChildren(devices(view, offset));
    }
    function apply(next) {
      if (!next || mine !== generation) return;
      view = next;
      offset = view.serverTimeMillis - Date.now();
      status.textContent = `Обновлено ${new Date().toLocaleTimeString("ru-RU")}, каждые 3 секунды.`;
      render();
    }

    const mark = el("form", {
      class: "card",
      async onsubmit(event) {
        event.preventDefault();
        const text = markText.value.trim();
        if (!text) return;
        const values = await ask("Отметка организатора", {
          text: `«${text}» попадёт в журнал игры на текущее время сервера и в отчёт, с минутой вокруг. Ваше имя — только в журнале аудита.`,
          confirm: "Отметить",
        });
        if (!values) return;
        await run(() => post(path(id, "marks"), { text, reason: values.reason }), "Отметка записана.");
        markText.value = "";
        apply(await get(path(id)).catch(() => null));
      },
    },
    el("h2", { class: "first" }, "Отметка"),
    el("p", { class: "muted small" }, "Заметка организатора в журнал игры: что вы видите на площадке. По ней в отчёте видно, что происходило вокруг."),
    el("div", { class: "row" }, markText, el("button", { type: "submit" }, "Отметить")));

    show(el("a", { href: "#/field" }, "← Все игры"), head, el("h2", {}, "Телефоны"),
      el("p", { class: "muted small" },
        "Из последних пришедших кусков журнала. Телефоны шлют журнал раз в 10 секунд; «Приложение» — состояние на последнем событии. " +
        "Метка телефона — id игрока в игре; в отчёте это P1, P2…"),
      phones, status, mark);
    apply(view);

    let busy = false;
    timers.push(setInterval(async () => {
      if (busy) return;
      busy = true;
      try {
        apply(await get(path(id)));
      } catch (e) {
        if (mine !== generation) return;
        if (e instanceof ApiError && e.status === 404) {
          stop();
          gone(e);
        } else if (e instanceof ApiError && e.status === 401) {
          notice("Сессия закончилась: войдите снова.");
          sessionLost();
        } else {
          status.textContent = `Не обновилось: ${e instanceof ApiError ? errorText(e) : e.message}. Попробую снова.`;
        }
      } finally {
        busy = false;
      }
    }, POLL_MS));
  }

  /** Every phone of the game: when it last wrote, what the app and the battery were, how its last sync went. */
  function devices(view, offset) {
    const live = new Map((view.live?.devices ?? []).map((d) => [d.deviceId, d]));
    const all = view.devices ?? [];
    const phones = all.filter((d) => !SERVER_LABELS.has(d.label));
    const server = all.find((d) => d.label === "server");
    const marks = all.find((d) => d.label === "staff");
    if (!phones.length) return el("p", { class: "muted" }, "Ещё ни один телефон не вошёл: журнал начинается со старта раунда.");
    return el("div", {},
      wide(table(["Игрок", "Телефон", "Сборка", "Последний кусок", "Последнее событие", "Приложение", "Батарея", "Sync", "События", "Объём"],
        phones.map((d) => {
          const l = live.get(d.id) ?? {};
          const background = BACKGROUND.has(l.appState);
          const sync = l.syncOk == null ? "—" : `${l.syncOk ? "ok" : "ошибка"}, ${num(l.syncMillis, 0, " мс")}${l.syncTransport ? `, ${l.syncTransport}` : ""}`;
          const silent = d.lastChunkAtMillis != null && Date.now() + offset - d.lastChunkAtMillis > 60000;
          return el("tr", {},
            el("td", { class: "mono" }, d.label),
            el("td", {}, d.model ?? "—", el("div", { class: "small muted" }, d.os ?? "")),
            el("td", { class: "mono" }, d.build ?? "—"),
            el("td", { class: silent ? "cover-weak" : null }, ago(d.lastChunkAtMillis, offset)),
            el("td", {}, ago(l.lastEventAtMillis, offset)),
            el("td", { class: background ? "cover-weak" : null }, l.appState ?? "—"),
            el("td", {}, level(l.batteryLevel)),
            el("td", { class: l.syncOk === false ? "cover-none" : null }, sync),
            el("td", {}, fmt.number(d.events ?? 0)),
            el("td", {}, bytes(d.bytes ?? 0)));
        }))),
      el("p", { class: "small muted" },
        `Журнал сервера: ${server ? `последний кусок ${ago(server.lastChunkAtMillis, offset)}, ${fmt.number(server.events ?? 0)} событий` : "ещё не писался"}. ` +
        `Отметки организаторов: ${fmt.number(marks?.events ?? 0)}.`));
  }

  // Taking out and deleting

  async function save(name, blob) {
    const url = URL.createObjectURL(blob);
    const link = el("a", { href: url, download: name });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  const fileName = (r, file) => `hovanki-field-${r.gameId ?? r.id}-${file}`;

  /** report.md and digest.jsonl: a reason first, then the file (the audit log records it). */
  async function download(r, file, type) {
    const values = await ask(`Скачать ${file}`, {
      text: "Отчёт и digest без координат и без ников: игроки P1…Pn. Предназначены для разбора, в том числе ИИ. Причина попадёт в журнал.",
      confirm: "Скачать",
    });
    if (!values) return;
    const blob = await run(async () => (await send("POST", path(r.id, file), { reason: values.reason })).blob(), `${file} скачан.`);
    await save(fileName(r, file), new Blob([blob], { type }));
  }

  /** raw.zip: the logs as they were uploaded, with coordinates; all or the chosen players and time. */
  async function rawDownload(r) {
    const values = await ask("Скачать сырые журналы", {
      text: "Журналы телефонов и сервера как пришли, с координатами (только событие gps). Это самое чувствительное, что есть в админке: " +
        "причина попадёт в журнал. Можно взять часть: игроков (id через запятую, «server» — журнал сервера) и время.",
      confirm: "Скачать",
      danger: true,
      fields: [
        { name: "devices", label: "Игроки (пусто — все)" },
        { name: "from", label: "С (пусто — с начала)", type: "datetime-local" },
        { name: "to", label: "До (пусто — до конца)", type: "datetime-local" },
      ],
    });
    if (!values) return;
    const time = (text) => (text ? new Date(text).getTime() : null);
    const body = {
      reason: values.reason,
      devices: (values.devices ?? "").split(",").map((d) => d.trim()).filter(Boolean),
      fromMillis: time(values.from),
      toMillis: time(values.to),
    };
    const blob = await run(async () => (await send("POST", path(r.id, "raw.zip"), body)).blob(), "Журналы скачаны.");
    await save(`${fileName(r, "raw")}.zip`, new Blob([blob], { type: "application/zip" }));
  }

  async function remove(r) {
    const values = await ask(`Удалить игру ${r.gameId ?? r.id}`, {
      text: "Журналы всех телефонов, журнал сервера, отметки и отчёт удалятся насовсем.",
      confirm: "Удалить", danger: true,
    });
    if (!values) return;
    await run(() => del(path(r.id), { reason: values.reason }), "Журнал игры удалён.");
    go("#/field");
  }

  // The report

  async function reportView(id) {
    const mine = generation;
    let report;
    try {
      report = await get(path(id, "report"));
    } catch (e) {
      if (mine !== generation) return;
      if (e instanceof ApiError && e.status === 404) {
        frame("field");
        show(el("a", { href: hash(id) }, "← К игре"), el("div", { class: "card narrow" }, el("h1", {}, "Отчёта нет"),
          el("p", {}, "Сервер считает его, пока идёт игра, раз в пару минут по мере прихода журналов. Или игры нет.")));
      } else {
        run(() => Promise.reject(e)).catch(() => {});
      }
      return;
    }
    if (mine !== generation) return;
    frame("field");
    show(el("a", { href: hash(id) }, "← К игре"),
      el("div", { class: "report" },
        el("h1", {}, "Отчёт игры ", el("span", { class: "mono" }, report.gameId ?? report.runId), " ",
          el("span", { class: `tag ${report.final ? "" : "ok"}` }, report.final ? "итоговый" : "живой")),
        el("p", { class: "muted small" },
          `Посчитан ${fmt.time(report.computedAtMillis)} по ${fmt.plural(report.windows ?? 0, "окну", "окнам", "окнам")} журналов. ` +
          "Время — по часам сервера. Игроки — P1…Pn в порядке входа телефонов; координат здесь нет, расстояния — в метрах."),
        summarySection(report),
        problemsSection(report),
        anomaliesSection(report),
        timelineSection(report),
        marksSection(report),
        playersSection(report),
        serverSection(report),
        radarSection(report)));
  }

  function summarySection(report) {
    const s = report.summary ?? {};
    return [
      el("h2", {}, "Сводка"),
      el("div", { class: "tiles" },
        tile(num(s.players), "игроков"), tile(num(s.devices), "телефонов (с перезапусками)"),
        tile(span(s.roundSeconds), "раунд"), tile(num(s.restarts), "перезапусков"), tile(num(s.errors), "ошибок"),
        tile(num(s.marks), "отметок"), tile(s.ratingAverage == null ? "—" : fmt.number(s.ratingAverage, 1), `оценка из 5, ответов ${num(s.surveys)}`)),
      dl(["С", fmt.time(s.firstMillis)], ["По", fmt.time(s.lastMillis)],
        ["Раунд", s.roundStartMillis ? `${fmt.time(s.roundStartMillis)} — ${s.roundEndMillis ? fmt.time(s.roundEndMillis) : "идёт"}` : "не начинался"],
        ["Телефоны", counts(s.models)], ["Системы", counts(s.os)], ["Сборки", counts(s.builds)],
        ["Где носили", counts(s.carry)], ["Что сломалось (опрос)", counts(s.broken)],
        ["Ушли до конца раунда", s.dropouts?.length ? s.dropouts.map((d) => `${d.player} в ${clock(d.atMillis)}`).join(", ") : "никто"],
        ["События Sentry", num(s.sentryEvents)]),
    ];
  }

  function problemsSection(report) {
    if (!report.problems?.length) return null;
    return [el("h2", {}, "Проблемы отчёта"), el("ul", {}, report.problems.map((p) => el("li", {}, p)))];
  }

  function anomaliesSection(report) {
    const list = report.anomalies ?? [];
    const countsMap = Object.entries(report.anomalyCounts ?? {});
    return [
      el("h2", {}, "Аномалии"),
      el("p", { class: "muted small" }, "Находят детекторы по журналам: дыра больше минуты, sync дольше 20 с, прыжок GPS быстрее 12 м/с, " +
        "отказ в поимке вблизи, ошибки приложения и сервера."),
      countsMap.length ? el("p", {}, countsMap.map(([kind, n]) => el("span", { class: "tag mute chip" }, `${ANOMALY[kind] ?? kind}: ${n}`))) : null,
      empty(list) ?? wide(table(["Время", "Тип", "Игрок", "Подробности"], list.map((a) => el("tr", {},
        el("td", {}, clock(a.atMillis), a.untilMillis ? ` — ${clock(a.untilMillis)}` : ""),
        el("td", {}, ANOMALY[a.kind] ?? a.kind),
        el("td", { class: "mono" }, a.player ?? "—"),
        el("td", { class: "small" }, a.detail))))),
    ];
  }

  function timelineSection(report) {
    const list = report.timeline ?? [];
    const kinds = [...new Set(list.map((e) => e.kind))].sort();
    const body = el("tbody");
    const filter = el("select", { name: "kind" }, el("option", { value: "" }, "всё"),
      kinds.map((k) => el("option", { value: k }, KIND[k] ?? k)));
    const fill = () => {
      const rows = list.filter((e) => !filter.value || e.kind === filter.value);
      body.replaceChildren(...rows.map((e) => el("tr", {},
        el("td", {}, clock(e.atMillis)),
        el("td", {}, KIND[e.kind] ?? e.kind),
        el("td", { class: "mono" }, e.player ?? "—"),
        el("td", {}, e.text))));
    };
    filter.addEventListener("change", fill);
    fill();
    return [
      el("h2", {}, "Таймлайн"),
      el("p", { class: "muted small" }, "Фазы игры, заявки на поимку, поимки, раскрытия, свечение, отметки и плохие минуты сервера, по порядку." +
        (report.timelineDropped ? ` Ещё ${fmt.number(report.timelineDropped)} записей не поместилось.` : "")),
      list.length ? [el("label", {}, "Показать ", filter),
        wide(el("table", {}, el("thead", {}, el("tr", {}, ["Время", "Что", "Игрок", "Текст"].map((t) => el("th", {}, t)))), body))] :
        el("p", { class: "muted" }, "Пока ничего."),
    ];
  }

  function marksSection(report) {
    const list = report.marks ?? [];
    if (!list.length) return [el("h2", {}, "Отметки"), el("p", { class: "muted" }, "Отметок не было.")];
    return [
      el("h2", {}, "Отметки"),
      el("p", { class: "muted small" }, "«Что-то не так» от игроков и заметки организаторов, и что происходило за 30 секунд вокруг."),
      list.map((m) => el("div", { class: "card" },
        el("b", {}, `${clock(m.atMillis)} · ${m.by === "staff" ? "организатор" : `игрок ${m.player ?? "?"}`}`),
        m.text ? el("p", {}, `«${m.text}»`) : el("p", { class: "muted" }, "без текста"),
        m.around?.length ? el("ul", { class: "small" }, m.around.map((line) => el("li", {}, line))) : null)),
    ];
  }

  function playersSection(report) {
    const list = report.players ?? [];
    if (!list.length) return [el("h2", {}, "По игрокам"), el("p", { class: "muted" }, "Нет данных.")];
    const refused = (gps) => Object.entries(gps.serverRefused ?? {}).map(([why, n]) => `${why} ${n}`).join(", ");
    return [
      el("h2", {}, "По игрокам"),
      el("h3", {}, "Телефон и связь"),
      wide(table(["Игрок", "Телефон", "Платформа", "События", "Sync: всего / ошибок", "Sync p50 / p95", "Сокет", "Зависаний", "Ответ p50 / p95", "Ошибок", "Отметок"],
        list.map((p) => el("tr", {},
          el("td", { class: "mono" }, p.alias, el("div", { class: "small muted" }, p.label)),
          el("td", {}, p.model ?? "—", el("div", { class: "small muted" }, `${p.os ?? ""} ${p.build ?? ""}`.trim())),
          el("td", {}, p.platform ?? "—", p.devices > 1 ? el("div", { class: "small muted" }, `телефонов: ${p.devices}`) : null),
          el("td", {}, fmt.number(p.events)),
          el("td", {}, `${num(p.sync?.count)} / ${num(p.sync?.errors)}`),
          el("td", {}, `${num(p.sync?.p50)} / ${num(p.sync?.p95)} мс`),
          el("td", {}, pct(p.sync?.socketPercent)),
          el("td", { class: p.sync?.stalls ? "cover-weak" : null }, num(p.sync?.stalls)),
          el("td", {}, `${bytes(p.sync?.bytesP50)} / ${bytes(p.sync?.bytesP95)}`),
          el("td", { class: p.errors ? "cover-weak" : null }, num(p.errors)),
          el("td", {}, num(p.marks)))))),
      el("h3", {}, "GPS"),
      wide(table(["Игрок", "Точек", "Точность p50 / p95, м", "Дыр > 30 с", "Самая долгая", "Прыжков", "Сервер принял", "Сервер отбросил"],
        list.map((p) => el("tr", {},
          el("td", { class: "mono" }, p.alias),
          el("td", {}, num(p.gps?.fixes)),
          el("td", {}, `${num(p.gps?.accP50, 1)} / ${num(p.gps?.accP95, 1)}`),
          el("td", { class: p.gps?.gaps ? "cover-weak" : null }, num(p.gps?.gaps)),
          el("td", {}, span(p.gps?.longestGapSeconds)),
          el("td", { class: p.gps?.jumps ? "cover-weak" : null }, num(p.gps?.jumps)),
          el("td", {}, num(p.gps?.serverAccepted)),
          el("td", { class: "small" }, refused(p.gps ?? {}) || "—"))))),
      el("h3", {}, "Фон, батарея, разрешения"),
      wide(table(["Игрок", "В фоне", "Батарея", "Расход, %/ч", "Нагрев", "Разрешения", "Опрос"],
        list.map((p) => el("tr", {},
          el("td", { class: "mono" }, p.alias),
          el("td", {}, span(p.backgroundSeconds)),
          el("td", {}, `${level(p.battery?.firstLevel)} → ${level(p.battery?.lastLevel)}`, p.battery?.lowPower ? " (экономия)" : ""),
          el("td", {}, num(p.battery?.percentPerHour, 1)),
          el("td", { class: "small" }, p.thermal?.length ? p.thermal.join(", ") : "—"),
          el("td", { class: "small" }, Object.entries(p.permissions ?? {}).map(([k, v]) => `${k}: ${v}`).join(", ") || "—"),
          el("td", { class: "small" }, p.survey ? [`оценка ${p.survey.rating ?? "—"}`, p.survey.broken?.length ? `сломалось: ${p.survey.broken.join(", ")}` : null,
            p.survey.carry ? `носил: ${p.survey.carry}` : null, p.survey.text ? `«${p.survey.text}»` : null].filter(Boolean).join("; ") : "—"))))),
      el("h3", {}, "Кого слышал радар"),
      el("p", { class: "muted small" }, "Секунды, в которые телефон слышал чужой, и каналы."),
      wide(table(["Слушатель", "Слышал", "Секунд", "Приёмов", "Каналы"],
        list.flatMap((p) => (p.heard ?? []).map((h) => el("tr", {},
          el("td", { class: "mono" }, p.alias), el("td", { class: "mono" }, h.peer),
          el("td", {}, fmt.number(h.seconds)), el("td", {}, fmt.number(h.readings)),
          el("td", { class: "small mono" }, (h.channels ?? []).join(", "))))))),
    ];
  }

  function serverSection(report) {
    const s = report.server ?? {};
    return [
      el("h2", {}, "Сервер"),
      s.samples ? dl(["Замеров", fmt.number(s.samples)], ["Sync за игру", fmt.number(s.syncs)],
        ["Sync p50 / p95 (худшее окно)", `${num(s.syncP50Max)} / ${num(s.syncP95Max)} мс`],
        ["5xx / 429", `${num(s.errors5xx)} / ${num(s.errors429)}`],
        ["Куча, макс.", `${num(s.heapMaxMb)} из ${num(s.heapLimitMb)} МБ`], ["Процессор, макс.", pct(s.cpuMax == null ? null : s.cpuMax * 100)],
        ["Потеряно событий журнала", num(s.dropped)], ["Игроков, макс.", num(s.playersMax)], ["Сокетов, макс.", num(s.socketsMax)]) :
        el("p", { class: "muted" }, "Сервер не писал свои числа в этот журнал."),
    ];
  }

  function radarSection(report) {
    const r = report.radar ?? {};
    const rules = r.shadowRules ?? {};
    const t = r.techniques;
    return [
      el("h2", {}, "Радар"),
      el("p", { class: "muted small" }, "Расстояния — по GPS обоих телефонов в ту же секунду (между точками интерполяция); RSSI — медианы секунд."),
      el("h3", {}, "Секунды с RSSI и расстоянием, по платформам"),
      el("p", {}, counts(r.pairSeconds)),
      el("h3", {}, "RSSI против расстояния"),
      empty(r.rssi) ?? wide(table(["Модели (кто → кто слушает)", "Где телефоны", "Расстояние, м", "Секунд", "RSSI медиана", "p10", "p90"],
        r.rssi.map((x) => el("tr", {}, el("td", { class: "small" }, x.models), el("td", { class: "mono" }, x.carry), el("td", {}, x.bucket),
          el("td", {}, fmt.number(x.seconds)), el("td", {}, `${x.median} дБм`), el("td", {}, x.p10), el("td", {}, x.p90))))),
      el("h3", {}, "Нулевые точки: поимки и касания"),
      el("p", { class: "muted small" }, "Подтверждённая поимка или касание — это «0 м». Рядом: что телефоны слышали друг от друга и что сказал GPS."),
      empty(r.zeroPoints) ?? wide(table(["Время", "Что", "Пара", "RSSI a→b", "RSSI b→a", "GPS, м"],
        r.zeroPoints.map((z) => el("tr", {}, el("td", {}, clock(z.atMillis)), el("td", {}, z.kind === "catch" ? "поимка" : "касание"),
          el("td", { class: "mono" }, `${z.a} · ${z.b}`), el("td", {}, z.rssiAToB == null ? "—" : `${z.rssiAToB} дБм`),
          el("td", {}, z.rssiBToA == null ? "—" : `${z.rssiBToA} дБм`), el("td", {}, num(z.gpsMeters, 1)))))),
      el("h3", {}, "Покрытие: слышали ли друг друга в пределах 20 м"),
      empty(r.coverage) ?? table(["Передатчик", "Слушатель", "Секунд рядом", "Слышал", "Доля"],
        r.coverage.map((c) => el("tr", {}, el("td", { class: "mono" }, c.sender), el("td", { class: "mono" }, c.listener),
          el("td", {}, fmt.number(c.nearSeconds)), el("td", {}, fmt.number(c.heardSeconds)),
          el("td", { class: c.percent == null ? null : c.percent >= 80 ? "cover-ok" : c.percent >= 40 ? "cover-weak" : "cover-none" }, pct(c.percent))))),
      el("h3", {}, "Маски в тени"),
      empty(r.masks) ?? table(["Канал", "Слушатель", "Передатчик", "Кадров", "Расшифровано до игрока"],
        r.masks.map((m) => el("tr", {}, el("td", { class: "mono" }, m.tech), el("td", {}, m.listener), el("td", {}, m.sender ?? "—"),
          el("td", {}, fmt.number(m.frames)), el("td", {}, fmt.number(m.resolved))))),
      el("h3", {}, "Правила в тени"),
      el("p", { class: "muted small" }, "Правила, которые только пишутся в журнал и не применяются (ADR 0018 §3.3)."),
      dl(["Заявок на поимку", `${num(rules.claims)}, с ответом правила близости: ${num(rules.claimsWithShadow)}, приняло бы: ${num(rules.shadowAccepted)}`],
        ["Подтверждённых поимок", `${num(rules.catches)}, правило близости отказало бы: ${num(rules.catchesShadowWouldRefuse)}`],
        ["Смен полосы радара", `${num(rules.bandChanges)}, в тени полоса другая: ${num(rules.bandShifted)}`],
        ["Сдвиги полосы", counts(rules.shifts)]),
      t ? techniques(t) : null,
    ];
  }

  function techniques(t) {
    const dbm = (rssi, key) => (rssi?.[key] == null ? "—" : `${rssi[key]} дБм`);
    const touches = t.touches ?? [];
    const pressed = touches.filter((x) => x.markAtMillis != null).length;
    return [
      el("h3", {}, "Техники радиолабы"),
      t.computed === false ? el("p", { class: "muted" }, t.note ?? "Событий слишком много, техники не считались.") : null,
      el("p", { class: "muted small" }, "Детектор касаний и «без канала» радиолабы (ADR 0017 §3, §7) на журналах игры. " +
        "Карточек и ошибок полос нет: в игре нет расстояний по шагам."),
      el("p", {}, `Касаний нашёл детектор: ${fmt.number(touches.length)}, из них отмечено кнопкой: ${fmt.number(pressed)}; ` +
        `нажатий без касания в журнале: ${fmt.number(t.missedTouches ?? 0)}.`),
      touches.length ? wide(table(["Пара", "Время", "RSSI по направлениям", "Удары, g", "Кнопка"], touches.map((x) => {
        const [a, b] = x.pair.split("|");
        return el("tr", {},
          el("td", { class: "mono" }, `${a} · ${b}`), el("td", {}, clock(x.atMillis)),
          el("td", {}, `${a}→${b}: ${dbm(x.rssi, `${a}|${b}`)}, ${b}→${a}: ${dbm(x.rssi, `${b}|${a}`)}`),
          el("td", {}, Object.entries(x.peaksG ?? {}).map(([d, g]) => `${d} ${g}`).join(", ") || "—"),
          el("td", {}, x.markAtMillis == null ? "—" : clock(x.markAtMillis)));
      }))) : null,
      t.touchSpreads?.length ? table(["Пара", "Направление", "Касаний", "Разброс, дБ", "Дрейф, дБ"], t.touchSpreads.map((p) => el("tr", {},
        el("td", { class: "mono" }, p.pair), el("td", { class: "mono" }, p.direction), el("td", {}, fmt.number(p.touches)),
        el("td", { class: p.spreadDb > 6 ? "cover-none" : null }, fmt.number(p.spreadDb)), el("td", {}, fmt.number(p.driftDb))))) : null,
      t.without?.length ? [el("h3", {}, "Без канала"),
        el("p", { class: "muted small" }, "Пар-секунд, где у пары была полоса со всеми каналами или без этого; в скольких полоса та же; " +
          "в скольких слышал только этот канал."),
        table(["Канал", "Пар-секунд", "Полоса та же", "Слышал только он"], t.without.map((w) => el("tr", {},
          el("td", { class: "mono" }, w.tech), el("td", {}, fmt.number(w.seconds)),
          el("td", {}, `${fmt.number(w.same)} (${pct(w.seconds ? (100 * w.same) / w.seconds : null, 1)})`),
          el("td", {}, fmt.number(w.onlyChannel)))))] : null,
    ];
  }

  return { stop, listView, gameView, reportView };
}
