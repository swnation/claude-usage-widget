const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, session } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

// ── 상수 ──
const SETTINGS_PATH = path.join(app.getPath('userData'), 'settings.json');
const PRELOAD = path.join(__dirname, 'preload.js');
const USAGE_URL = 'https://claude.ai/settings/usage';
const SCRAPE_TIMEOUT_MS = 30000;

// ── AI 정의 ──
const AI_DEFS = {
  gpt:    { name: 'GPT',        color: '#10a37f', usageUrl: 'https://platform.openai.com/usage', hasBillingApi: true },
  claude: { name: 'Claude',     color: '#c96442', usageUrl: 'https://console.anthropic.com/settings/billing', hasBillingApi: true },
  gemini: { name: 'Gemini',     color: '#4285f4', usageUrl: 'https://aistudio.google.com/apikey', hasBillingApi: false },
  grok:   { name: 'Grok',       color: '#1DA1F2', usageUrl: 'https://console.x.ai/', hasBillingApi: false },
  perp:   { name: 'Perplexity', color: '#20808d', usageUrl: 'https://www.perplexity.ai/settings/api', hasBillingApi: false },
};

// ── 상태 ──
let tray = null;
let mainWin = null;
let widgetWin = null;
let loginWin = null;
let scraperWin = null;
let scrapeTimer = null;
let usageData = null;
let costData = null;
// displayMode: 'CLAUDE_ONLY' | 'API_COST_ONLY' | 'BOTH'
let settings = { refreshInterval: 120, widgetVisible: true, displayMode: 'CLAUDE_ONLY', overlayMode: 'MINIMAL' };

// ────────────────────────────
//  설정 저장/불러오기
// ────────────────────────────
function loadSettings() {
  try {
    if (fs.existsSync(SETTINGS_PATH)) {
      Object.assign(settings, JSON.parse(fs.readFileSync(SETTINGS_PATH, 'utf8')));
    }
  } catch (_) {}
}

function saveSettings() {
  try { fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2)); }
  catch (_) {}
}

// ────────────────────────────
//  트레이 아이콘 생성
// ────────────────────────────
function createTrayIcon(r, g, b) {
  const s = 16;
  const buf = Buffer.alloc(s * s * 4);
  for (let y = 0; y < s; y++) {
    for (let x = 0; x < s; x++) {
      const i = (y * s + x) * 4;
      const dx = x - 7.5, dy = y - 7.5;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist <= 6.5) {
        buf[i] = r; buf[i+1] = g; buf[i+2] = b; buf[i+3] = 255;
      } else if (dist <= 7.5) {
        const a = Math.round(Math.max(0, (7.5 - dist)) * 255);
        buf[i] = r; buf[i+1] = g; buf[i+2] = b; buf[i+3] = a;
      }
    }
  }
  return nativeImage.createFromBitmap(buf, { width: s, height: s });
}

function getStatusIcon(pct) {
  if (pct >= 90) return createTrayIcon(244, 67, 54);   // 빨강
  if (pct >= 70) return createTrayIcon(255, 152, 0);    // 노랑
  return createTrayIcon(76, 175, 80);                    // 초록
}

// ────────────────────────────
//  시스템 트레이
// ────────────────────────────
function createTray() {
  tray = new Tray(createTrayIcon(100, 100, 100));
  updateTrayMenu();
  tray.setToolTip('Claude 사용량 위젯');
  tray.on('click', () => showMainWindow());
}

function updateTrayMenu() {
  const pct = usageData?.session?.usedPercent;
  const mode = settings.displayMode || 'CLAUDE_ONLY';
  let statusLabel;

  if (mode === 'CLAUDE_ONLY') {
    const pctText = pct != null ? ` (세션 ${Math.round(pct)}%)` : '';
    statusLabel = `Claude 사용량${pctText}`;
  } else if (mode === 'API_COST_ONLY') {
    const costText = costData ? ` ($${costData.todayTotal.toFixed(4)})` : '';
    statusLabel = `API 요금${costText}`;
  } else {
    const pctText = pct != null ? `세션 ${Math.round(pct)}%` : '';
    const costText = costData ? `$${costData.todayTotal.toFixed(4)}` : '';
    statusLabel = [pctText, costText].filter(Boolean).join(' | ') || 'Claude + API';
  }

  const menu = Menu.buildFromTemplate([
    { label: statusLabel, enabled: false },
    { type: 'separator' },
    { label: '새로고침', click: () => { scrapeNow(); fetchAllCosts(); } },
    { label: settings.widgetVisible ? '위젯 숨기기' : '위젯 표시',
      click: () => toggleWidget() },
    { label: '설정 열기', click: () => showMainWindow() },
    { type: 'separator' },
    { label: '종료', click: () => app.quit() }
  ]);
  tray.setContextMenu(menu);
}

function updateTrayFromUsage() {
  if (!tray) return;
  const mode = settings.displayMode || 'CLAUDE_ONLY';

  if (mode === 'API_COST_ONLY') {
    if (costData) {
      tray.setImage(createTrayIcon(76, 175, 80)); // 초록
      tray.setToolTip(`API 요금 오늘: $${costData.todayTotal.toFixed(4)}`);
    }
  } else if (usageData?.session) {
    const pct = Math.round(usageData.session.usedPercent || 0);
    tray.setImage(getStatusIcon(pct));
    const extra = (mode === 'BOTH' && costData)
      ? ` | 요금: $${costData.todayTotal.toFixed(4)}` : '';
    tray.setToolTip(`Claude 세션 ${pct}% 사용됨${extra}`);
  }
  updateTrayMenu();
}

// ────────────────────────────
//  스크래핑
// ────────────────────────────
const SCRAPE_JS = `
(function() {
  var body = document.body ? document.body.innerText : '';
  var url = window.location.href;
  var sessionIdx = body.indexOf('현재 세션');
  var weeklyIdx = body.indexOf('주간 한도');

  function extract(text) {
    var pct = text.match(/(\\d+)%\\s*사용됨/);
    var reset = text.match(/\\d+시간[\\s\\d]*분?\\s*후\\s*재설정/) ||
                text.match(/.{1,20}에\\s*재설정/);
    return { percent: pct ? parseInt(pct[1]) : -1,
             reset: reset ? reset[0].trim() : '' };
  }

  var session = null, weekly = null;
  if (sessionIdx >= 0) {
    session = extract(body.substring(sessionIdx,
      weeklyIdx >= 0 ? weeklyIdx : body.length));
  }
  if (weeklyIdx >= 0) {
    weekly = extract(body.substring(weeklyIdx));
  }

  var barValues = [];
  document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]').forEach(function(bar) {
    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
  });

  return JSON.stringify({ url: url, session: session, weekly: weekly, barValues: barValues });
})();
`;

function getScraperWindow() {
  if (scraperWin && !scraperWin.isDestroyed()) return scraperWin;
  scraperWin = new BrowserWindow({
    show: false,
    width: 800,
    height: 600,
    webPreferences: { contextIsolation: true, nodeIntegration: false }
  });
  scraperWin.on('closed', () => { scraperWin = null; });
  return scraperWin;
}

let isScraping = false;

async function scrapeNow() {
  if (isScraping) return;
  if (settings.displayMode === 'API_COST_ONLY') {
    // API_COST_ONLY 모드에서는 Claude 스크래핑 생략
    fetchAllCosts();
    return;
  }
  isScraping = true;
  broadcastStatus('새로고침 중...');

  const timeout = setTimeout(() => { isScraping = false; }, SCRAPE_TIMEOUT_MS);

  try {
    const win = getScraperWindow();
    await win.loadURL(USAGE_URL);
    // SPA 렌더링 대기
    await new Promise(r => setTimeout(r, 3500));

    const raw = await win.webContents.executeJavaScript(SCRAPE_JS);
    const json = JSON.parse(raw);

    // 로그인 페이지 리다이렉트 감지
    if (json.url && json.url.includes('/login')) {
      broadcastStatus('세션 만료. 다시 로그인하세요.');
      clearTimeout(timeout);
      isScraping = false;
      return;
    }

    let sessionPct = json.session?.percent ?? -1;
    let weeklyPct = json.weekly?.percent ?? -1;
    const sessionReset = json.session?.reset || '';
    const weeklyReset = json.weekly?.reset || '';
    const barValues = json.barValues || [];

    // fallback: progressbar
    if (sessionPct < 0 && barValues.length > 0) {
      const v = parseInt(barValues[0]);
      if (!isNaN(v) && v >= 0 && v <= 100) sessionPct = v;
    }
    if (weeklyPct < 0 && barValues.length > 1) {
      const v = parseInt(barValues[1]);
      if (!isNaN(v) && v >= 0 && v <= 100) weeklyPct = v;
    }

    if (sessionPct >= 0) {
      usageData = {
        planName: 'Max',
        session: { label: '현재 세션', usedPercent: sessionPct, resetTime: sessionReset },
        weekly: weeklyPct >= 0
          ? { label: '주간 한도', usedPercent: weeklyPct, resetTime: weeklyReset }
          : null,
        lastUpdated: new Date().toISOString()
      };
      broadcastUsage();
      updateTrayFromUsage();
      const hh = String(new Date().getHours()).padStart(2, '0');
      const mm = String(new Date().getMinutes()).padStart(2, '0');
      broadcastStatus(`마지막 업데이트: ${hh}:${mm}`);
    } else {
      broadcastStatus('사용량을 찾을 수 없음. 다시 시도하세요.');
    }
  } catch (e) {
    broadcastStatus(`오류: ${e.message}`);
  }

  clearTimeout(timeout);
  isScraping = false;
}

// ── Google Drive API 헬퍼 ──
async function driveGet(urlPath, token) {
  return new Promise((resolve, reject) => {
    https.get(urlPath, { headers: { 'Authorization': \`Bearer \${token}\` } }, (res) => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        if (res.statusCode === 401) return reject(new Error('TOKEN_EXPIRED'));
        if (res.statusCode !== 200) return resolve(null);
        try { resolve(JSON.parse(data)); } catch(e) { resolve(null); }
      });
    }).on('error', reject);
  });
}

// ── Billing API 호출 ──
async function fetchBillingApi(aiId, key) {
  const today = new Date().toLocaleDateString('sv'); // YYYY-MM-DD (로컬 시간대)

  if (aiId === 'claude') {
    const now = new Date();
    const startingAt = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-01T00:00:00Z`;
    const endingAt = now.toISOString();
    const res = await adminApiRequest('api.anthropic.com',
      `/v1/organizations/cost_report?starting_at=${startingAt}&ending_at=${endingAt}&bucket_width=1d`, {
      'x-api-key': key, 'anthropic-version': '2023-06-01',
    });
    if (!res.ok) return { error: res.error || `HTTP ${res.status}` };
    let monthCents = 0, todayCents = 0;
    (res.data?.data || []).forEach(bucket => {
      let bucketTotal = 0;
      (bucket.results || []).forEach(r => { bucketTotal += parseFloat(r.amount || '0'); });
      monthCents += bucketTotal;
      if ((bucket.started_at || '').startsWith(today)) todayCents += bucketTotal;
    });
    return { todayCost: todayCents / 100, monthCost: monthCents / 100 };
  }

  if (aiId === 'gpt') {
    const now = new Date();
    const monthStartEpoch = Math.floor(new Date(now.getFullYear(), now.getMonth(), 1).getTime() / 1000);
    const nowEpoch = Math.floor(now.getTime() / 1000);
    const res = await adminApiRequest('api.openai.com',
      `/v1/organization/costs?start_time=${monthStartEpoch}&end_time=${nowEpoch}&bucket_width=1d`, {
      'Authorization': `Bearer ${key}`,
    });
    if (!res.ok) return { error: res.error || `HTTP ${res.status}` };
    let monthTotal = 0, todayTotal = 0;
    (res.data?.data || []).forEach(bucket => {
      let bucketTotal = 0;
      (bucket.results || []).forEach(r => { bucketTotal += r.amount?.value || 0; });
      monthTotal += bucketTotal;
      if (bucket.start_time) {
        const bucketDate = new Date(bucket.start_time * 1000).toLocaleDateString('sv');
        if (bucketDate === today) todayTotal += bucketTotal;
      }
    });
    return { todayCost: todayTotal, monthCost: monthTotal };
  }

  return { error: 'Unsupported AI' };
}

// ── 통합 비용 fetch (Billing 우선 + Drive 보조) ──
let isCostFetching = false;

async function fetchAllCosts() {
  if (isCostFetching) return;
  isCostFetching = true;

  try {
    const billingResults = {};
    if (settings.adminKey) {
      billingResults.claude = await fetchBillingApi('claude', settings.adminKey);
    }
    if (settings.openaiKey) {
      billingResults.gpt = await fetchBillingApi('gpt', settings.openaiKey);
    }

    const hasBilling = Object.values(billingResults).some(r => !r.error);
    if (!hasBilling) { isCostFetching = false; return; }

    let todayTotal = 0, monthTotal = 0;
    const byAI = [];
    for (const [aiId, billing] of Object.entries(billingResults)) {
      if (billing.error) continue;
      const def = AI_DEFS[aiId] || { name: aiId, color: '#888' };
      todayTotal += billing.todayCost;
      monthTotal += billing.monthCost;
      byAI.push({
        aiId, name: def.name, color: def.color,
        todayCost: billing.todayCost, monthCost: billing.monthCost,
        source: 'billing',
      });
    }
    byAI.sort((a, b) => b.monthCost - a.monthCost);

    const subscriptions = settings.subscriptions || [];

    costData = {
      todayTotal, monthTotal, source: 'billing',
      byAI, subscriptions,
      lastUpdated: new Date().toISOString(),
    };

    broadcastCost();
    updateTrayFromUsage();

    if (mainWin && !mainWin.isDestroyed()) {
      mainWin.webContents.send('admin-cost-update', { costData });
    }
  } catch (_) {}

  isCostFetching = false;
}

function startScrapeTimer() {
  stopScrapeTimer();
  const ms = Math.max(30, settings.refreshInterval || 120) * 1000;
  scrapeTimer = setInterval(() => {
    scrapeNow();
    fetchAllCosts();
  }, ms);
}

function stopScrapeTimer() {
  if (scrapeTimer) { clearInterval(scrapeTimer); scrapeTimer = null; }
}

// ────────────────────────────
//  브로드캐스트
// ────────────────────────────
function broadcastUsage() {
  const wins = [mainWin, widgetWin].filter(w => w && !w.isDestroyed());
  wins.forEach(w => w.webContents.send('usage-update', usageData));
}

function broadcastCost() {
  const wins = [mainWin, widgetWin].filter(w => w && !w.isDestroyed());
  wins.forEach(w => w.webContents.send('cost-update', costData));
}

function broadcastStatus(msg) {
  [mainWin, widgetWin].forEach(w => {
    if (w && !w.isDestroyed()) w.webContents.send('status-update', msg);
  });
}

// ────────────────────────────
//  윈도우: 메인 (설정/상태)
// ────────────────────────────
function showMainWindow() {
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.show();
    mainWin.focus();
    return;
  }
  mainWin = new BrowserWindow({
    width: 440, height: 520,
    resizable: false,
    icon: getStatusIcon(usageData?.session?.usedPercent ?? 0),
    webPreferences: { preload: PRELOAD, contextIsolation: true, nodeIntegration: false }
  });
  mainWin.setMenuBarVisibility(false);
  mainWin.loadFile(path.join(__dirname, 'src', 'index.html'));
  mainWin.on('closed', () => { mainWin = null; });
}

// ────────────────────────────
//  윈도우: 플로팅 위젯
// ────────────────────────────
function createWidgetWindow() {
  if (widgetWin && !widgetWin.isDestroyed()) return;
  widgetWin = new BrowserWindow({
    width: 170, height: 42,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    skipTaskbar: true,
    webPreferences: { preload: PRELOAD, contextIsolation: true, nodeIntegration: false }
  });
  widgetWin.setMenuBarVisibility(false);
  widgetWin.loadFile(path.join(__dirname, 'src', 'widget.html'));
  widgetWin.on('closed', () => { widgetWin = null; });
}

function updateWidgetSize() {
  if (!widgetWin || widgetWin.isDestroyed()) return;
  const om = settings.overlayMode || 'MINIMAL';
  const w = om === 'FULL' ? 320 : (om === 'BASIC' || om === 'COST') ? 250 : 170;
  widgetWin.setSize(w, 42);
}

function toggleWidget() {
  if (widgetWin && !widgetWin.isDestroyed()) {
    widgetWin.destroy();
    widgetWin = null;
    settings.widgetVisible = false;
  } else {
    createWidgetWindow();
    settings.widgetVisible = true;
  }
  saveSettings();
  updateTrayMenu();
}

// (오랑붕쌤 로그인 제거됨 — Drive 백업은 Android에서만 지원)

// ────────────────────────────
//  Anthropic Admin API
// ────────────────────────────

// ── Admin 키 암호화 (AES-256-GCM, PBKDF2) ──
const KEY_SALT = 'claude-widget-keys-v1';
const KEY_ITERATIONS = 100000;

function deriveKeyFromPin(pin) {
  return new Promise((resolve, reject) => {
    crypto.pbkdf2(pin, KEY_SALT, KEY_ITERATIONS, 32, 'sha256', (err, key) => {
      if (err) reject(err); else resolve(key);
    });
  });
}

async function encryptKeys(data, pin) {
  const key = await deriveKeyFromPin(pin);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(data, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, tag]).toString('base64');
}

async function decryptKeys(encrypted, pin) {
  try {
    const buf = Buffer.from(encrypted, 'base64');
    if (buf.length < 12 + 16) return null; // IV + 최소 GCM 태그
    const key = await deriveKeyFromPin(pin);
    const iv = buf.subarray(0, 12);
    const tag = buf.subarray(buf.length - 16);
    const cipherText = buf.subarray(12, buf.length - 16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(cipherText), decipher.final()]).toString('utf8');
  } catch (_) { return null; }
}

async function saveKeysToDriveDesktop(token, encrypted) {
  try {
    // 폴더 찾기/생성
    const folders = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent("name='Claude Usage Widget' and mimeType='application/vnd.google-apps.folder' and trashed=false")}&fields=files(id)`, token);
    let folderId;
    if (folders?.files?.length) {
      folderId = folders.files[0].id;
    } else {
      const createRes = await drivePost(token, '/drive/v3/files?fields=id',
        JSON.stringify({ name: 'Claude Usage Widget', mimeType: 'application/vnd.google-apps.folder' }));
      folderId = createRes.id;
    }

    const files = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(`name='admin_keys_backup.json' and '${folderId}' in parents and trashed=false`)}&fields=files(id)`, token);
    const content = JSON.stringify({ encrypted, updatedAt: new Date().toISOString() });

    if (files?.files?.length) {
      await driveWrite(token, 'PATCH', `/upload/drive/v3/files/${files.files[0].id}?uploadType=media`, content);
    } else {
      const boundary = 'widget_bound_x7';
      const meta = JSON.stringify({ name: 'admin_keys_backup.json', parents: [folderId], mimeType: 'application/json' });
      const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${content}\r\n--${boundary}--`;
      await driveWrite(token, 'POST', '/upload/drive/v3/files?uploadType=multipart&fields=id', body, `multipart/related; boundary=${boundary}`);
    }
    return true;
  } catch (_) { return false; }
}

// Drive 쓰기 유틸리티
function drivePost(token, apiPath, body) {
  return new Promise((resolve, reject) => {
    const req = https.request({ hostname: 'www.googleapis.com', path: apiPath, method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' }
    }, (res) => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => { try { resolve(JSON.parse(d)); } catch(e) { reject(e); } });
    });
    req.on('error', reject);
    req.write(body); req.end();
  });
}

function driveWrite(token, method, apiPath, body, contentType) {
  return new Promise((resolve, reject) => {
    const req = https.request({ hostname: 'www.googleapis.com', path: apiPath, method,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': contentType || 'application/json' }
    }, (res) => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        if (res.statusCode >= 400) return reject(new Error(`HTTP ${res.statusCode}: ${d}`));
        resolve(d);
      });
    });
    req.on('error', reject);
    req.write(body); req.end();
  });
}

async function loadKeysFromDriveDesktop(token) {
  try {
    const folders = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent("name='Claude Usage Widget' and mimeType='application/vnd.google-apps.folder' and trashed=false")}&fields=files(id)`, token);
    if (!folders?.files?.length) return null;
    const files = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(`name='admin_keys_backup.json' and '${folders.files[0].id}' in parents and trashed=false`)}&fields=files(id)`, token);
    if (!files?.files?.length) return null;
    const data = await driveGet(`https://www.googleapis.com/drive/v3/files/${files.files[0].id}?alt=media`, token);
    return data?.encrypted || null;
  } catch (_) { return null; }
}

function adminApiRequest(hostname, path, headers) {
  return new Promise((resolve) => {
    const req = https.request({ hostname, path, method: 'GET', headers }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try { resolve({ ok: res.statusCode === 200, status: res.statusCode, data: JSON.parse(data) }); }
        catch (e) { resolve({ ok: false, error: e.message }); }
      });
    });
    req.on('error', (e) => resolve({ ok: false, error: e.message }));
    req.setTimeout(10000, () => req.destroy());
    req.end();
  });
}

async function fetchAllAdminCosts() {
  // 이제 fetchAllCosts()가 Billing + Drive를 통합 처리하므로
  // 이 함수는 즉시 통합 fetch를 트리거한다
  await fetchAllCosts();
  return costData;
}

// ────────────────────────────
//  윈도우: 로그인
// ────────────────────────────
function showLoginWindow() {
  if (loginWin && !loginWin.isDestroyed()) {
    loginWin.show();
    loginWin.focus();
    return;
  }
  loginWin = new BrowserWindow({
    width: 900, height: 700,
    webPreferences: { contextIsolation: true, nodeIntegration: false }
  });
  loginWin.setMenuBarVisibility(false);
  loginWin.loadURL('https://claude.ai/login');

  loginWin.webContents.on('did-navigate', (_, url) => {
    // 로그인 성공 → claude.ai 메인 페이지로 이동
    if (url.includes('claude.ai') && !url.includes('/login') && !url.includes('/oauth')) {
      loginWin.close();
      scrapeNow();
      startScrapeTimer();
    }
  });

  loginWin.on('closed', () => { loginWin = null; });
}

// ────────────────────────────
//  IPC 핸들러
// ────────────────────────────
ipcMain.handle('get-usage', () => usageData);
ipcMain.handle('get-cost', () => costData);
ipcMain.handle('get-settings', () => settings);
ipcMain.handle('save-settings', (_, s) => {
  Object.assign(settings, s);
  saveSettings();
  startScrapeTimer();
  // 모드 변경 시 위젯 크기 조정
  updateWidgetSize();
  // 위젯에 설정 변경 알림 (스킨 반영 등)
  broadcastStatus('');
});
ipcMain.handle('refresh', () => {
  scrapeNow();
  fetchAllCosts();
});
ipcMain.handle('login', () => showLoginWindow());
ipcMain.handle('logout', async () => {
  await session.defaultSession.clearStorageData();
  usageData = null;
  broadcastUsage();
  updateTrayMenu();
  tray.setImage(createTrayIcon(100, 100, 100));
  tray.setToolTip('Claude 사용량 위젯');
  stopScrapeTimer();
});
ipcMain.handle('toggle-widget', () => toggleWidget());
ipcMain.handle('show-main', () => showMainWindow());
// (오랑붕쌤 IPC 제거됨)
ipcMain.handle('save-admin-key-encrypted', async (_, type, key, pin) => {
  if (!['anthropic', 'openai'].includes(type)) return { error: 'Invalid type' };
  if (!pin || pin.length < 4) return { error: 'PIN은 4자리 이상' };
  const keys = {};
  if (settings.adminKey) keys.anthropic = settings.adminKey;
  if (settings.openaiKey) keys.openai = settings.openaiKey;
  keys[type] = key;

  // 암호화
  const encrypted = await encryptKeys(JSON.stringify(keys), pin);
  settings.adminKeysEncrypted = encrypted;
  if (type === 'anthropic') settings.adminKey = key;
  else settings.openaiKey = key;
  saveSettings();

  // Drive 백업
  if (settings.googleToken) {
    await saveKeysToDriveDesktop(settings.googleToken, encrypted);
    return { saved: true, driveBackup: true };
  }
  return { saved: true, driveBackup: false };
});
ipcMain.handle('restore-admin-keys', async (_, pin) => {
  if (!pin || typeof pin !== 'string') return { error: 'PIN 필요' };
  if (!settings.googleToken) return { error: 'Google Drive 미연결 (Android에서 백업/복원 사용)' };
  const encrypted = await loadKeysFromDriveDesktop(settings.googleToken);
  if (!encrypted) return { error: 'Drive에 백업 없음' };
  const decrypted = await decryptKeys(encrypted, pin);
  if (!decrypted) return { error: 'PIN 틀림' };
  try {
    const keys = JSON.parse(decrypted);
    settings.adminKey = keys.anthropic || '';
    settings.openaiKey = keys.openai || '';
    settings.adminKeysEncrypted = encrypted;
    saveSettings();
    if (settings.adminKey || settings.openaiKey) fetchAllAdminCosts();
    return { success: true, anthropic: !!settings.adminKey, openai: !!settings.openaiKey };
  } catch (_) { return { error: '데이터 파싱 실패' }; }
});
ipcMain.handle('get-admin-keys', () => ({
  anthropic: settings.adminKey ? '****' : '',
  openai: settings.openaiKey ? '****' : '',
}));
ipcMain.handle('fetch-admin-cost', () => fetchAllAdminCosts());
ipcMain.handle('get-subscriptions', () => settings.subscriptions || []);
ipcMain.handle('save-subscriptions', (_, subs) => {
  settings.subscriptions = subs;
  saveSettings();
  fetchAllCosts(); // 구독 변경 시 비용 재계산
});
ipcMain.handle('open-external', (_, url) => {
  const { shell } = require('electron');
  shell.openExternal(url);
});
ipcMain.handle('quit', () => app.quit());

// ────────────────────────────
//  앱 시작
// ────────────────────────────
app.whenReady().then(() => {
  loadSettings();
  createTray();
  if (settings.widgetVisible) createWidgetWindow();

  // 초기 스크래핑
  scrapeNow();
  fetchAllCosts();
  startScrapeTimer();
});

app.on('window-all-closed', (e) => {
  // 트레이 상주: 모든 윈도우 닫아도 앱 종료 안 함
  e.preventDefault?.();
});

app.on('before-quit', () => {
  stopScrapeTimer();
  // Drive API 방식이라 별도 정리 불필요
});

// 단일 인스턴스 보장
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showMainWindow());
}
