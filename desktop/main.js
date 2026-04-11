const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, session } = require('electron');
const path = require('path');
const fs = require('fs');

// ── 상수 ──
const SETTINGS_PATH = path.join(app.getPath('userData'), 'settings.json');
const PRELOAD = path.join(__dirname, 'preload.js');
const USAGE_URL = 'https://claude.ai/settings/usage';
const OBS_URL = 'https://swnation.github.io/OrangBoongSSem/';
const SCRAPE_TIMEOUT_MS = 30000;

// ── AI 정의 (오랑붕쌤과 동일) ──
const AI_DEFS = {
  gpt:    { name: 'GPT',        color: '#10a37f', usageUrl: 'https://platform.openai.com/usage' },
  claude: { name: 'Claude',     color: '#c96442', usageUrl: 'https://console.anthropic.com/settings/billing' },
  gemini: { name: 'Gemini',     color: '#4285f4', usageUrl: 'https://aistudio.google.com/apikey' },
  grok:   { name: 'Grok',       color: '#1DA1F2', usageUrl: 'https://console.x.ai/' },
  perp:   { name: 'Perplexity', color: '#20808d', usageUrl: 'https://www.perplexity.ai/settings/api' },
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
let settings = { refreshInterval: 120, widgetVisible: true, displayMode: 'CLAUDE_ONLY' };

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
    { label: '새로고침', click: () => { scrapeNow(); scrapeObsCost(); } },
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
    scrapeObsCost();
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

// ── 오랑붕쌤 Drive API 직접 호출 ──
const OBS_DOMAINS = [
  ['Orangi Migraine', 'orangi_master.json'],
  ['Orangi Mental', 'orangi_mental_master.json'],
  ['Orangi Health', 'orangi_health_master.json'],
  ['Bung Mental', 'bung_mental_master.json'],
  ['Bung Health', 'bung_health_master.json'],
  ['Bungruki Pregnancy', 'bungruki_master.json'],
];

async function driveGet(urlPath, token) {
  return new Promise((resolve, reject) => {
    https.get(urlPath, { headers: { 'Authorization': `Bearer ${token}` } }, (res) => {
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

let isObsFetching = false;

async function scrapeObsCost() {
  if (isObsFetching) return;
  if (settings.displayMode === 'CLAUDE_ONLY') return;
  if (!settings.googleToken) return;

  isObsFetching = true;
  const token = settings.googleToken;

  try {
    const now = new Date(Date.now() + 9*3600*1000);
    const today = now.toISOString().slice(0,10);
    const month = today.slice(0,7);
    let todayTotal = 0, monthTotal = 0;
    const byAIMap = {};
    let anySuccess = false;

    for (const [folderName, masterFile] of OBS_DOMAINS) {
      try {
        // 폴더 찾기
        const q1 = encodeURIComponent(`name='${folderName}' and mimeType='application/vnd.google-apps.folder' and trashed=false`);
        const folders = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${q1}&fields=files(id)`, token);
        if (!folders?.files?.length) continue;
        const folderId = folders.files[0].id;

        // 마스터 파일 찾기
        const q2 = encodeURIComponent(`name='${masterFile}' and '${folderId}' in parents and trashed=false`);
        const files = await driveGet(`https://www.googleapis.com/drive/v3/files?q=${q2}&fields=files(id)`, token);
        if (!files?.files?.length) continue;
        const fileId = files.files[0].id;

        // 파일 읽기
        const master = await driveGet(`https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`, token);
        if (!master?.usage_data) continue;

        anySuccess = true;
        for (const [date, aiMap] of Object.entries(master.usage_data)) {
          for (const [aiId, info] of Object.entries(aiMap)) {
            const cost = info.cost || 0;
            if (!byAIMap[aiId]) byAIMap[aiId] = { today: 0, month: 0 };
            if (date === today) { todayTotal += cost; byAIMap[aiId].today += cost; }
            if (date.startsWith(month)) { monthTotal += cost; byAIMap[aiId].month += cost; }
          }
        }
      } catch (e) {
        if (e.message === 'TOKEN_EXPIRED') {
          settings.googleToken = null;
          settings.obsLoggedIn = false;
          saveSettings();
          broadcastStatus('토큰 만료 — 오랑붕쌤 재연결 필요');
          isObsFetching = false;
          return;
        }
      }
    }

    if (anySuccess) {
      const byAI = Object.entries(byAIMap).map(([aiId, data]) => {
        const def = AI_DEFS[aiId] || { name: aiId, color: '#888' };
        return { aiId, name: def.name, color: def.color, todayCost: data.today, monthCost: data.month };
      }).sort((a, b) => b.monthCost - a.monthCost);

      costData = { todayTotal, monthTotal, byAI, bySys: [{ systemName: '오랑붕쌤', todayCost: todayTotal, monthCost: monthTotal }], lastUpdated: new Date().toISOString() };
      broadcastCost();
      updateTrayFromUsage();
    }
  } catch (_) {}

  isObsFetching = false;
}

function startScrapeTimer() {
  stopScrapeTimer();
  const ms = Math.max(30, settings.refreshInterval || 120) * 1000;
  scrapeTimer = setInterval(() => {
    scrapeNow();
    scrapeObsCost();
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
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.webContents.send('status-update', msg);
  }
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
  const mode = settings.displayMode || 'CLAUDE_ONLY';
  if (mode === 'BOTH') {
    widgetWin.setSize(250, 42);
  } else {
    widgetWin.setSize(170, 42);
  }
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

// ────────────────────────────
//  윈도우: 오랑붕쌤 로그인
// ────────────────────────────
let obsLoginWin = null;

function showObsLoginWindow() {
  if (obsLoginWin && !obsLoginWin.isDestroyed()) {
    obsLoginWin.show();
    obsLoginWin.focus();
    return;
  }
  obsLoginWin = new BrowserWindow({
    width: 900, height: 700,
    webPreferences: { contextIsolation: true, nodeIntegration: false }
  });
  obsLoginWin.setMenuBarVisibility(false);
  obsLoginWin.loadURL(OBS_URL);

  // 토큰 감지: 주기적으로 S.token 체크
  let checkCount = 0;
  const checkInterval = setInterval(async () => {
    if (!obsLoginWin || obsLoginWin.isDestroyed()) {
      clearInterval(checkInterval);
      return;
    }
    checkCount++;
    if (checkCount > 60) { clearInterval(checkInterval); return; }

    try {
      const result = await obsLoginWin.webContents.executeJavaScript(`
        (function() {
          var token = (typeof S !== 'undefined' && S && S.token) ? S.token : null;
          return JSON.stringify({ token: token });
        })();
      `);
      const { token } = JSON.parse(result);
      if (token) {
        clearInterval(checkInterval);
        settings.obsLoggedIn = true;
        settings.googleToken = token;
        saveSettings();
        broadcastStatus('오랑붕쌤 연결 완료 (Drive API)');
        if (mainWin && !mainWin.isDestroyed()) {
          mainWin.webContents.send('obs-status', true);
        }
        obsLoginWin.close();
        scrapeObsCost();
      }
    } catch (_) {}
  }, 2000);

  obsLoginWin.on('closed', () => {
    clearInterval(checkInterval);
    obsLoginWin = null;
  });
}

// ────────────────────────────
//  Anthropic Admin API
// ────────────────────────────
const https = require('https');

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
  const results = { claude: null, gpt: null };

  // Anthropic
  if (settings.adminKey) {
    const res = await adminApiRequest('api.anthropic.com', '/v1/organizations/cost_report', {
      'x-api-key': settings.adminKey,
      'anthropic-version': '2023-06-01',
    });
    if (res.ok && res.data?.total_cost != null) {
      results.claude = { actual: res.data.total_cost };
    } else {
      results.claude = { error: res.error || `HTTP ${res.status}` };
    }
  }

  // OpenAI
  if (settings.openaiKey) {
    const now = new Date();
    const monthStart = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-01`;
    const tomorrow = new Date(now.getTime() + 86400000).toISOString().slice(0,10);
    const res = await adminApiRequest('api.openai.com',
      `/v1/organization/costs?start_date=${monthStart}&end_date=${tomorrow}`, {
      'Authorization': `Bearer ${settings.openaiKey}`,
    });
    if (res.ok) {
      let total = 0;
      (res.data?.data || []).forEach(bucket => {
        (bucket.results || []).forEach(r => { total += r.amount?.value || 0; });
      });
      results.gpt = { actual: total };
    } else {
      results.gpt = { error: res.error || `HTTP ${res.status}` };
    }
  }

  // 추정치와 비교하여 전송
  const estimated = {};
  if (costData?.byAI) {
    costData.byAI.forEach(ai => { estimated[ai.aiId] = ai.monthCost; });
  }

  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.webContents.send('admin-cost-update', { results, estimated });
  }
  return results;
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
});
ipcMain.handle('refresh', () => {
  scrapeNow();
  scrapeObsCost();
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
ipcMain.handle('obs-login', () => showObsLoginWindow());
ipcMain.handle('get-obs-status', () => settings.obsLoggedIn || false);
ipcMain.handle('save-admin-key', (_, type, key) => {
  if (type === 'anthropic') settings.adminKey = key;
  else if (type === 'openai') settings.openaiKey = key;
  saveSettings();
  fetchAllAdminCosts();
});
ipcMain.handle('get-admin-keys', () => ({
  anthropic: settings.adminKey ? '****' : '',
  openai: settings.openaiKey ? '****' : '',
}));
ipcMain.handle('fetch-admin-cost', () => fetchAllAdminCosts());
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
  scrapeObsCost();
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
