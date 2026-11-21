"use strict";

const byId = (id) => document.getElementById(id);
const state = { assets: [], signedIn: false, preview: null };
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
    if (response.status === 401 && state.signedIn) setSignedIn(false);
    const reason = failure?.message ?? failure?.detail ?? failure?.error ?? failure?.code;
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
  byId("logout").hidden = !signedIn;
  if (signedIn) return;
  state.assets = [];
  stopAudio();
  stopPreview();
  for (const id of ["asset-list", "theme-list", "preset-list", "import-detail", "json-content"]) {
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
      makeButton("试听预设", () => startPreview(preset)),
      makeButton("导出 JSON", () => download(
        new Blob([JSON.stringify(editable, null, 2) + "\n"], { type: "application/json" }),
        preset.preset_id + "-r" + preset.revision + ".json"
      ))
    ];
    if (!published && (currentRevision == null || preset.revision > currentRevision)) controls.push(makeButton("发布", async () => {
      if (!window.confirm("发布 " + preset.preset_id + " 的版本 " + preset.revision + "？")) return;
      await api(presetPath(preset) + "/publish", { method: "POST" });
      await refreshPresets();
      report("版本已发布");
    }, ""));
    return [
      preset.preset_id,
      [target.scene_id, target.weather_id, target.function_id].filter(Boolean).join(" · "),
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

async function refreshThemes() {
  const themes = items(await api("/admin/themes"), "themes");
  renderRows("theme-list", themes, (theme) => [theme.name, theme.id ?? theme.theme_id ?? theme.scene_id], 2);
}

async function refreshPresets() {
  renderPresets(items(await api("/admin/presets"), "presets"));
}

async function loadWorkspace() {
  await refreshAssets();
  setSignedIn(true);
  await Promise.all([refreshThemes(), refreshPresets()]);
}

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
  let preset;
  try {
    preset = JSON.parse((await body.get("file").text()).replace(/^\uFEFF/, ""));
  } catch (_) {
    throw new Error("预设文件的 JSON 格式有误");
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
