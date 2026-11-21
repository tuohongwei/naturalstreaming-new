"use strict";

const byId = (id) => document.getElementById(id);
const state = { assets: [], signedIn: false, preview: null, permissions: new Set(), panel: null, theme: null };
const loginForm = byId("login-form");
const assetPlayer = byId("asset-player");
const feedback = byId("feedback");

function report(message, error = false) {
  feedback.textContent = message;
  feedback.classList.toggle("error", error);
  feedback.hidden = !message;
}

async function request(path, options = {}) {
  const response = await fetch(path, { ...options, credentials: "same-origin" });
  if (!response.ok) {
    const failure = await response.json().catch(() => null);
    const reason = failure?.message ?? failure?.detail ?? failure?.error ?? failure?.code;
    const badPassword = reason === "invalid_credentials" || reason === "invalid_current_password";
    if (response.status === 401 && state.signedIn && !badPassword) setSignedIn(false);
    const error = new Error(typeof reason === "string" ? reason : "请求失败（HTTP " + response.status + "）");
    error.status = response.status;
    throw error;
  }
  return response;
}

async function api(path, options) {
  const response = await request(path, options);
  return response.status === 204 || response.headers.get("Content-Length") === "0"
    ? null
    : response.json();
}

function sendJson(method, data) {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) };
}

function items(payload, key) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.[key])) return payload[key];
  if (Array.isArray(payload?.items)) return payload.items;
  throw new Error("服务返回的列表格式有误");
}

function stopAudio() {
  assetPlayer.pause();
  assetPlayer.removeAttribute("src");
  assetPlayer.hidden = true;
  assetPlayer.load();
}

function setSignedIn(signedIn) {
  state.signedIn = signedIn;
  byId("login-panel").hidden = signedIn;
  byId("workspace").hidden = !signedIn;
  byId("account-menu").hidden = !signedIn;
  if (signedIn) return;
  state.assets = [];
  state.theme = null;
  state.permissions.clear();
  state.panel = null;
  byId("account-menu").open = false;
  byId("account-name").textContent = "";
  byId("edit-dialog").close();
  stopAudio();
  stopPreview();
  for (const id of ["asset-list", "theme-list", "preset-list", "member-list", "account-list", "role-list", "import-detail", "json-content"]) {
    byId(id).replaceChildren();
  }
  byId("import-result").hidden = true;
  byId("json-dialog").close();
  for (const id of ["asset-import", "theme-create", "preset-import"]) byId(id).reset();
}

function makeButton(label, handler, className = "secondary") {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.textContent = label;
  button.addEventListener("click", () => run(button, handler));
  return button;
}

async function run(button, action) {
  button.disabled = true;
  report("");
  try {
    await action();
  } catch (error) {
    report(error.message, true);
  } finally {
    button.disabled = false;
  }
}

function renderRows(id, itemsToRender, cellsFor, columnCount) {
  const body = byId(id);
  body.replaceChildren();
  if (!itemsToRender.length) {
    const cell = body.insertRow().insertCell();
    cell.colSpan = columnCount;
    cell.textContent = "暂无记录";
    return;
  }
  for (const item of itemsToRender) {
    const row = body.insertRow();
    for (const value of cellsFor(item)) {
      const cell = row.insertCell();
      if (value instanceof Node) cell.append(value);
      else cell.textContent = value == null ? "—" : String(value);
    }
  }
}

function actions(...buttons) {
  const container = document.createElement("div");
  container.className = "actions";
  container.append(...buttons);
  return container;
}

function showJson(title, data) {
  byId("json-title").textContent = title;
  byId("json-content").textContent = JSON.stringify(data, null, 2);
  byId("json-dialog").showModal();
}

function download(blob, name) {
  const link = document.createElement("a");
  const url = URL.createObjectURL(blob);
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function metadataOf(asset) {
  return asset.metadata ?? asset.description_json ?? asset.json ?? asset;
}

function hashOf(asset) {
  const metadata = metadataOf(asset);
  return asset.sha256 ?? metadata.sha256 ?? metadata.analysis?.sha256;
}

function audioPath(hash) {
  if (typeof hash !== "string" || !/^[a-f0-9]{64}$/.test(hash)) throw new Error("素材 SHA-256 无效");
  return "/admin/assets/" + hash + "/audio";
}

async function playAsset(asset) {
  stopPreview();
  assetPlayer.src = audioPath(hashOf(asset));
  assetPlayer.hidden = false;
  await assetPlayer.play();
}

function renderAssets() {
  renderRows("asset-list", state.assets, (asset) => {
    const metadata = metadataOf(asset);
    const name = asset.original_filename ?? asset.filename ?? metadata.original_filename ?? metadata.media_ref ?? asset.name;
    const id = document.createElement("code");
    id.textContent = asset.asset_id ?? asset.id ?? metadata.asset_id ?? hashOf(asset) ?? "—";
    const controls = [makeButton("试听音频", () => playAsset(asset))];
    if (metadata !== asset) controls.push(makeButton("查看说明 JSON", () => showJson("素材说明", metadata)));
    return [name, id, actions(...controls)];
  }, 3);
}

function presetOf(row) {
  return row.preset ?? row.rule ?? row.document ?? row.json ?? row;
}

function presetPath(preset) {
  return "/admin/presets/" + encodeURIComponent(preset.preset_id) + "/" + encodeURIComponent(preset.revision);
}

function renderPresets(presets) {
  const currentRevisions = new Map(presets.filter((row) => row.current === true)
    .map((row) => [presetOf(row).preset_id, presetOf(row).revision]));
  renderRows("preset-list", presets, (row) => {
    const preset = presetOf(row);
    const target = preset.target ?? {};
    const status = row.status ?? preset.status;
    const published = row.published === true || status === "published";
    const current = row.current ?? published;
    const currentRevision = currentRevisions.get(preset.preset_id);
    const { published: ignoredPublished, current: ignoredCurrent, ...editable } = preset;
    editable.status = "draft";
    const controls = [
      makeButton("导出 JSON", () => download(
        new Blob([JSON.stringify(editable, null, 2) + "\n"], { type: "application/json" }),
        preset.preset_id + "-r" + preset.revision + ".json"
      ))
    ];
    if (can("assets.read")) controls.unshift(makeButton("试听预设", () => startPreview(preset)));
    if (can("presets.publish") && !published && (currentRevision == null || preset.revision > currentRevision)) controls.push(makeButton("发布", async () => {
      if (!window.confirm("发布 " + preset.preset_id + " 的版本 " + preset.revision + "？")) return;
      await api(presetPath(preset) + "/publish", { method: "POST" });
      await refreshPresets();
      report("版本已发布");
    }, ""));
    return [
      preset.preset_id,
      [target.weather_id, target.function_id].filter(Boolean).join(" · "),
      preset.revision,
      current ? "当前发布" : published ? "历史发布" : "草稿",
      actions(...controls)
    ];
  }, 5);
}

async function refreshAssets() {
  state.assets = items(await api("/admin/assets"), "assets");
  renderAssets();
}

function showThemeList() {
  state.theme = null;
  stopPreview();
  byId("theme-overview").hidden = false;
  byId("presets-panel").hidden = true;
  byId("preset-import").reset();
}

async function refreshThemes() {
  showThemeList();
  const themes = can("themes.read")
    ? items(await api("/admin/themes"), "themes")
    : Array.from(new Set(items(await api("/admin/presets"), "presets")
        .map((preset) => presetOf(preset).target.scene_id))).map((id) => ({ id, name: id }));
  renderRows("theme-list", themes, (theme) => [
    theme.name, theme.id, can("presets.read")
      ? makeButton("管理预设", async () => {
          state.theme = theme;
          byId("theme-detail-name").textContent = theme.name;
          byId("theme-overview").hidden = true;
          byId("presets-panel").hidden = false;
          byId("preset-list").replaceChildren();
          await refreshPresets();
        })
      : "—"
  ], 3);
}

async function refreshPresets() {
  const theme = state.theme;
  if (!theme) return;
  const presets = items(await api("/admin/presets"), "presets");
  if (state.theme === theme) {
    renderPresets(presets.filter((preset) => presetOf(preset).target.scene_id === theme.id));
  }
}

byId("return-to-themes").addEventListener("click", () => {
  showThemeList();
  report("");
});

const permissionLabels = {
  "assets.read": "查看、试听和导出素材",
  "assets.write": "导入素材",
  "themes.read": "查看主题",
  "themes.write": "创建主题",
  "presets.read": "查看和导出预设",
  "presets.write": "导入预设",
  "presets.publish": "发布预设",
  "members.manage": "管理 App 会员",
  "accounts.manage": "管理后台账号",
  "roles.manage": "管理角色"
};

const navigation = [
  ["assets-panel", refreshAssets],
  ["themes-panel", refreshThemes],
  ["members-panel", refreshMembers],
  ["accounts-panel", refreshAccounts],
  ["roles-panel", refreshRoles]
];
const menuLinks = Array.from(document.querySelectorAll(".sidebar nav a"));
const can = (permission) => state.permissions.has(permission);

async function showPanel() {
  if (!state.signedIn) return;
  const available = menuLinks.filter((link) => !link.hidden);
  const selected = available.find((link) => link.hash === location.hash) ?? available[0];
  const panel = selected?.hash.slice(1) ?? "empty-panel";
  if (state.panel === panel) return;
  state.panel = panel;
  stopAudio();
  stopPreview();
  for (const [id] of navigation) byId(id).hidden = id !== panel;
  byId("empty-panel").hidden = panel !== "empty-panel";
  for (const link of menuLinks) {
    if (link === selected) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  report("");
  const entry = navigation.find(([id]) => id === panel);
  if (entry) await entry[1]();
}

async function loadWorkspace() {
  const identity = await api("/admin/me");
  state.permissions = new Set(identity.permissions);
  byId("account-name").textContent = identity.username;
  for (const element of document.querySelectorAll("[data-permission]")) {
    element.hidden = !can(element.dataset.permission);
  }
  byId("themes-link").hidden = !can("themes.read") && !can("presets.read");
  byId("content-nav-heading").hidden = !["assets.read", "themes.read", "presets.read"].some(can);
  byId("members-nav-heading").hidden = !can("members.manage");
  byId("system-nav-heading").hidden = !can("accounts.manage") && !can("roles.manage");
  setSignedIn(true);
  await showPanel();
}

window.addEventListener("hashchange", () => showPanel().catch((error) => report(error.message, true)));
function onSubmit(form, action) {
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const body = new FormData(form);
    run(form.querySelector("button[type=submit]"), () => action(body));
  });
}

onSubmit(loginForm, async (data) => {
  try {
    await api("/admin/session", sendJson("POST", Object.fromEntries(data)));
    await loadWorkspace();
  } finally {
    loginForm.elements.password.value = "";
  }
});

byId("logout").addEventListener("click", () => run(byId("logout"), async () => {
  await api("/admin/session", { method: "DELETE" });
  setSignedIn(false);
  loginForm.elements.username.focus();
}));

onSubmit(byId("asset-import"), async (body) => {
  const result = await api("/admin/assets/import", { method: "POST", body });
  byId("import-detail").textContent = JSON.stringify(result, null, 2);
  byId("import-result").hidden = false;
  await refreshAssets();
  byId("asset-import").reset();
  report("素材导入完成，可展开查看每项结果");
});

byId("export-assets").addEventListener("click", () => run(byId("export-assets"), async () => {
  const response = await request("/admin/assets/export");
  download(await response.blob(), "naturalstreaming-asset-catalog.zip");
}));

onSubmit(byId("theme-create"), async (body) => {
  await api("/admin/themes", sendJson("POST", Object.fromEntries(body)));
  await refreshThemes();
  byId("theme-create").reset();
  report("主题已创建");
});

onSubmit(byId("preset-import"), async (body) => {
  const theme = state.theme;
  let preset;
  try {
    preset = JSON.parse((await body.get("file").text()).replace(/^\uFEFF/, ""));
  } catch (_) {
    throw new Error("预设文件的 JSON 格式有误");
  }
  if (!theme || preset?.target?.scene_id !== theme.id) {
    throw new Error("预设场景 ID 必须与当前主题一致：" + (theme?.id ?? ""));
  }
  await api("/admin/presets", sendJson("POST", preset));
  await refreshPresets();
  byId("preset-import").reset();
  report("预设草稿已保存");
});

function stopPreview() {
  const preview = state.preview;
  if (preview) {
    state.preview = null;
    preview.abort.abort();
    clearInterval(preview.timer);
    preview.context.close().catch(() => {});
  }
  byId("preview-controls").hidden = true;
}

function originalSampleRate(asset, response) {
  const metadata = metadataOf(asset ?? {});
  return Number(response.headers.get("X-Audio-Sample-Rate")
    ?? asset?.sample_rate ?? asset?.sampleRate
    ?? metadata.sample_rate ?? metadata.sampleRate ?? metadata.analysis?.sampleRate);
}

async function loadPreviewClip(hash, preview) {
  const response = await request(audioPath(hash), { signal: preview.abort.signal });
  const asset = state.assets.find((item) => hashOf(item) === hash);
  const sampleRate = originalSampleRate(asset, response);
  if (!Number.isFinite(sampleRate) || sampleRate <= 0) throw new Error("素材缺少原始采样率，无法按帧试听");
  const buffer = await preview.context.decodeAudioData(await response.arrayBuffer());
  return { buffer, sampleRate, aac: /\.m4a$/i.test(asset?.filename ?? "") };
}

function prepareTrack(track, audio, context) {
  const clips = track.clips.map((clip) => {
    const { buffer, sampleRate, aac } = audio.get(clip.sha256);
    const start = clip.start_frame / sampleRate;
    const duration = (clip.end_frame_exclusive - clip.start_frame) / sampleRate;
    const tolerance = (aac ? 1024 / sampleRate : 0) + 1 / buffer.sampleRate;
    if (!Number.isFinite(start) || !Number.isFinite(duration) || start < 0 || duration <= 0 ||
        start >= buffer.duration || start + duration > buffer.duration + tolerance) {
      throw new Error("试听片段帧范围无效");
    }
    // Some AAC decoders remove the last packet's padding. Keep the configured timeline.
    return { buffer, start, duration, audibleDuration: Math.min(duration, buffer.duration - start) };
  });
  const overlap = Number(track.overlap_seconds);
  const gain = Number(track.gain);
  if (clips.length !== (track.loop_mode === "aa" ? 1 : 2) ||
      !["aa", "ab"].includes(track.loop_mode) || !Number.isFinite(gain) ||
      !Number.isFinite(overlap) || overlap < 0 || clips.some((clip) => clip.duration <= overlap)) {
    throw new Error("试听循环规则无效");
  }
  const volume = context.createGain();
  volume.gain.value = gain;
  volume.connect(context.destination);
  return { clips, overlap, volume, index: 0, next: 0 };
}

function scheduleTracks(tracks, context) {
  for (const track of tracks) {
    if (track.next < context.currentTime) track.next = context.currentTime;
    while (track.next < context.currentTime + .2) {
      const clip = track.clips[track.index];
      const source = context.createBufferSource();
      source.buffer = clip.buffer;
      source.connect(track.volume);
      source.addEventListener("ended", () => source.disconnect(), { once: true });
      source.start(track.next, clip.start, clip.audibleDuration);
      track.next += clip.duration - track.overlap;
      track.index = (track.index + 1) % track.clips.length;
    }
  }
}

async function startPreview(preset) {
  stopAudio();
  stopPreview();
  const Context = window.AudioContext ?? window.webkitAudioContext;
  if (!Context) throw new Error("此浏览器不支持预设试听");
  if (!Array.isArray(preset.tracks) || !preset.tracks.length) throw new Error("预设缺少可试听音轨");
  const preview = { context: new Context(), abort: new AbortController(), timer: null };
  state.preview = preview;
  const label = byId("preview-label");
  label.textContent = "正在准备 " + preset.preset_id + " · r" + preset.revision;
  byId("preview-controls").hidden = false;
  try {
    await preview.context.resume();
    if (!state.assets.length) await refreshAssets();
    const hashes = [...new Set(preset.tracks.flatMap((track) => track.clips.map((clip) => clip.sha256)))];
    const audio = new Map(await Promise.all(hashes.map(async (hash) => [hash, await loadPreviewClip(hash, preview)])));
    if (state.preview !== preview) return;
    const tracks = preset.tracks.map((track) => prepareTrack(track, audio, preview.context));
    const start = preview.context.currentTime + .06;
    for (const track of tracks) track.next = start;
    scheduleTracks(tracks, preview.context);
    preview.timer = setInterval(() => scheduleTracks(tracks, preview.context), 50);
    label.textContent = "正在试听 " + preset.preset_id + " · r" + preset.revision;
  } catch (error) {
    if (state.preview !== preview) return;
    stopPreview();
    throw error;
  }
}

byId("stop-preview").addEventListener("click", stopPreview);
loadWorkspace().catch((error) => {
  if (error.status !== 401) report(error.message, true);
});

const editDialog = byId("edit-dialog");
const editForm = byId("edit-form");
let submitEdit;

function openEditor(title, fields, action) {
  byId("edit-title").textContent = title;
  byId("edit-fields").replaceChildren(...fields);
  byId("edit-error").textContent = "";
  byId("edit-error").hidden = true;
  submitEdit = action;
  editDialog.showModal();
}

editDialog.addEventListener("close", () => {
  byId("edit-fields").replaceChildren();
  submitEdit = undefined;
});

editForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!submitEdit) return;
  const button = editForm.querySelector("button[type=submit]");
  const errorNode = byId("edit-error");
  button.disabled = true;
  errorNode.hidden = true;
  try {
    await submitEdit(new FormData(editForm));
    if (editDialog.open) editDialog.close();
  } catch (error) {
    const invalidCurrent = error.message === "invalid_credentials" || error.message === "invalid_current_password";
    errorNode.textContent = invalidCurrent ? "当前密码不正确" : error.message;
    errorNode.hidden = false;
  } finally {
    for (const field of editForm.querySelectorAll('input[type="password"]')) field.value = "";
    button.disabled = false;
  }
});

function field(caption, name, {value = "", type = "text", autocomplete, maximum, minimum} = {}) {
  const label = document.createElement("label");
  label.textContent = caption;
  const input = document.createElement("input");
  input.name = name;
  input.type = type;
  input.value = value;
  input.required = true;
  if (autocomplete) input.autocomplete = autocomplete;
  if (maximum) input.maxLength = maximum;
  if (minimum) input.minLength = minimum;
  label.append(input);
  return label;
}

function newPassword(caption, name = "password") {
  return field(caption, name, {type: "password", autocomplete: "new-password", maximum: 72});
}

function roleField(roles, selected) {
  const label = document.createElement("label");
  label.textContent = "角色";
  const select = document.createElement("select");
  select.name = "role_id";
  select.required = true;
  for (const role of roles) {
    const option = new Option(role.name, role.id);
    option.selected = String(selected) === String(role.id);
    select.add(option);
  }
  label.append(select);
  return label;
}

function permissionsField(values) {
  const fieldset = document.createElement("fieldset");
  fieldset.className = "permissions";
  const legend = document.createElement("legend");
  legend.textContent = "权限";
  fieldset.append(legend);
  for (const [permission, caption] of Object.entries(permissionLabels)) {
    const label = document.createElement("label");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.name = "permissions";
    checkbox.value = permission;
    checkbox.checked = values.includes(permission);
    label.append(checkbox, caption);
    fieldset.append(label);
  }
  return fieldset;
}

function verifyPassword(data, name) {
  if (data.get(name) !== data.get("confirm_password")) throw new Error("两次输入的新密码不一致");
}

byId("open-password").addEventListener("click", () => {
  byId("account-menu").open = false;
  openEditor("修改密码", [
    field("当前密码", "current_password", {type: "password", autocomplete: "current-password"}),
    newPassword("新密码", "new_password"),
    newPassword("确认新密码", "confirm_password")
  ], async (data) => {
    verifyPassword(data, "new_password");
    await api("/admin/password", sendJson("POST", {
      current_password: data.get("current_password"), new_password: data.get("new_password")
    }));
    setSignedIn(false);
    loginForm.elements.username.focus();
    report("密码已修改，请用新密码重新登录");
  });
});

async function listRoles() {
  return items(await api("/admin/roles"), "roles");
}

function openRole(role = null) {
  openEditor(role ? "编辑角色" : "新建角色", [
    field("角色名称", "name", {value: role?.name, maximum: 96}),
    permissionsField(role?.permissions ?? [])
  ], async (data) => {
    const route = role ? "/admin/roles/" + encodeURIComponent(role.id) : "/admin/roles";
    await api(route, sendJson(role ? "PUT" : "POST", {
      name: data.get("name").trim(), permissions: data.getAll("permissions")
    }));
    await refreshRoles();
    report("角色已保存");
  });
}

async function refreshRoles() {
  renderRows("role-list", await listRoles(), (role) => [
    role.name,
    role.permissions.map((name) => permissionLabels[name] ?? name).join("、") || "无",
    role.builtin ? "内置角色" : makeButton("编辑", () => openRole(role))
  ], 3);
}

byId("create-role").addEventListener("click", () => openRole());

async function openAccount(account = null) {
  const roles = await listRoles();
  const fields = account ? [] : [
    field("用户名", "username", {autocomplete: "off", maximum: 96}),
    newPassword("初始密码")
  ];
  fields.push(roleField(roles, account?.role_id));
  openEditor(account ? "分配角色 · " + account.username : "新增后台账号", fields, async (data) => {
    const role_id = Number(data.get("role_id"));
    if (account) {
      await api("/admin/accounts/" + encodeURIComponent(account.id), sendJson("PUT", {
        enabled: account.enabled, role_id
      }));
    } else {
      await api("/admin/accounts", sendJson("POST", {
        username: data.get("username").trim(), password: data.get("password"), role_id
      }));
    }
    await refreshAccounts();
    report("后台账号已保存");
  });
}

function toggleStatus(path, account, extra, refresh) {
  const label = account.enabled ? "停用" : "启用";
  return makeButton(label, async () => {
    if (!window.confirm(label + " " + account.username + "？")) return;
    await api(path + "/" + encodeURIComponent(account.id), sendJson("PUT", {
      ...extra, enabled: !account.enabled
    }));
    await refresh();
    report(account.username + " 已" + label);
  });
}

async function refreshAccounts() {
  const [accounts, roles] = await Promise.all([api("/admin/accounts"), listRoles()]);
  const names = new Map(roles.map((role) => [String(role.id), role.name]));
  renderRows("account-list", items(accounts, "accounts"), (account) => [
    account.username,
    names.get(String(account.role_id)) ?? "—",
    account.enabled ? "启用" : "停用",
    actions(
      makeButton("分配角色", () => openAccount(account)),
      toggleStatus("/admin/accounts", account, {role_id: account.role_id}, refreshAccounts)
    )
  ], 4);
}

byId("create-account").addEventListener("click", () => run(byId("create-account"), () => openAccount()));

async function refreshMembers() {
  renderRows("member-list", items(await api("/admin/members"), "members"), (member) => [
    member.username,
    member.enabled ? "启用" : "停用",
    actions(
      makeButton("重置密码", () => openEditor("重置密码 · " + member.username, [
        newPassword("新密码"), newPassword("确认新密码", "confirm_password")
      ], async (data) => {
        verifyPassword(data, "password");
        await api("/admin/members/" + encodeURIComponent(member.id) + "/password",
          sendJson("POST", {password: data.get("password")}));
        report("会员密码已重置");
      })),
      toggleStatus("/admin/members", member, {}, refreshMembers)
    )
  ], 3);
}

byId("create-member").addEventListener("click", () => openEditor("新增会员", [
  field("App 用户名", "username", {autocomplete: "off", maximum: 96}),
  newPassword("初始密码")
], async (data) => {
  await api("/admin/members", sendJson("POST", {
    username: data.get("username").trim(), password: data.get("password")
  }));
  await refreshMembers();
  report("会员已创建");
}));
