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
  gpt:    { name: 'GPT',        color: '#10a37f' },
  claude: { name: 'Claude',     color: '#c96442' },
  gemini: { name: 'Gemini',     color: '#4285f4' },
  grok:   { name: 'Grok',       color: '#1DA1F2' },
  perp:   { name: 'Perplexity', color: '#20808d' },
};

// ── 상태 ──
let tray = null;
let mainWin = null;
let widgetWin = null;
let loginWin = null;
let scraperWin = null;
let obsScraperWin = null;
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

// ── 오랑붕쌤 비용 스크래핑 ──
const OBS_SCRAPE_JS = `
(function() {
  var kstNow = new Date(Date.now() + 9*3600*1000);
  var today = kstNow.toISOString().slice(0,10);
  var month = today.slice(0,7);
  var result = { today: 0, month: 0, byAI: {} };
  var found = false;
  for (var i = 0; i < localStorage.length; i++) {
    var key = localStorage.key(i);
    if (key.indexOf('om_usage_') !== 0) continue;
    found = true;
    try {
      var data = JSON.parse(localStorage.getItem(key));
      var dates = Object.keys(data);
      for (var d = 0; d < dates.length; d++) {
        var date = dates[d];
        var aiMap = data[date];
        var aiIds = Object.keys(aiMap);
        for (var a = 0; a < aiIds.length; a++) {
          var aiId = aiIds[a];
          var info = aiMap[aiId];
          var cost = info.cost || 0;
          if (!result.byAI[aiId]) result.byAI[aiId] = { today: 0, month: 0 };
          if (date === today) {
            result.today += cost;
            result.byAI[aiId].today += cost;
          }
          if (date.indexOf(month) === 0) {
            result.month += cost;
            result.byAI[aiId].month += cost;
          }
        }
      }
    } catch(e) {}
  }
  result.hasData = found;
  return JSON.stringify(result);
})();
`;

function getObsScraperWindow() {
  if (obsScraperWin && !obsScraperWin.isDestroyed()) return obsScraperWin;
  obsScraperWin = new BrowserWindow({
    show: false,
    width: 800,
    height: 600,
    webPreferences: { contextIsolation: true, nodeIntegration: false }
  });
  obsScraperWin.on('closed', () => { obsScraperWin = null; });
  return obsScraperWin;
}

let isObsScraping = false;

async function scrapeObsCost() {
  if (isObsScraping) return;
  if (settings.displayMode === 'CLAUDE_ONLY') return;
  if (!settings.obsLoggedIn) return;

  isObsScraping = true;
  const timeout = setTimeout(() => { isObsScraping = false; }, SCRAPE_TIMEOUT_MS);

  try {
    const win = getObsScraperWindow();
    await win.loadURL(OBS_URL);
    await new Promise(r => setTimeout(r, 3500));

    const raw = await win.webContents.executeJavaScript(OBS_SCRAPE_JS);
    const json = JSON.parse(raw);

    if (json.hasData) {
      const byAI = [];
      Object.entries(json.byAI || {}).forEach(([aiId, data]) => {
        const def = AI_DEFS[aiId] || { name: aiId, color: '#888' };
        byAI.push({
          aiId,
          name: def.name,
          color: def.color,
          todayCost: data.today || 0,
          monthCost: data.month || 0,
        });
      });
      byAI.sort((a, b) => b.monthCost - a.monthCost);

      costData = {
        todayTotal: json.today || 0,
        monthTotal: json.month || 0,
        byAI,
        lastUpdated: new Date().toISOString(),
      };

      broadcastCost();
      updateTrayFromUsage();
    }
  } catch (e) {
    // 오랑붕쌤 스크래핑 실패 시 조용히 무시
  }

  clearTimeout(timeout);
  isObsScraping = false;
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

  // 데이터 로드 감지: 주기적으로 localStorage 체크
  let checkCount = 0;
  const checkInterval = setInterval(async () => {
    if (!obsLoginWin || obsLoginWin.isDestroyed()) {
      clearInterval(checkInterval);
      return;
    }
    checkCount++;
    if (checkCount > 60) { clearInterval(checkInterval); return; }

    try {
      const hasData = await obsLoginWin.webContents.executeJavaScript(`
        (function() {
          for (var i = 0; i < localStorage.length; i++) {
            if (localStorage.key(i).indexOf('om_usage_') === 0) return true;
          }
          return false;
        })();
      `);
      if (hasData) {
        clearInterval(checkInterval);
        settings.obsLoggedIn = true;
        saveSettings();
        broadcastStatus('오랑붕쌤 연결 완료');
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

async function fetchAnthropicCost(apiKey) {
  return new Promise((resolve) => {
    const options = {
      hostname: 'api.anthropic.com',
      path: '/v1/organizations/cost_report',
      method: 'GET',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      }
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (mainWin && !mainWin.isDestroyed()) {
            mainWin.webContents.send('admin-cost-update', {
              success: res.statusCode === 200,
              data: json,
              statusCode: res.statusCode,
            });
          }
          resolve(json);
        } catch (e) {
          resolve({ error: e.message });
        }
      });
    });

    req.on('error', (e) => {
      if (mainWin && !mainWin.isDestroyed()) {
        mainWin.webContents.send('admin-cost-update', {
          success: false,
          error: e.message,
        });
      }
      resolve({ error: e.message });
    });

    req.setTimeout(10000, () => req.destroy());
    req.end();
  });
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
ipcMain.handle('save-admin-key', (_, key) => {
  settings.adminKey = key;
  saveSettings();
  if (key) fetchAnthropicCost(key);
});
ipcMain.handle('get-admin-key', () => settings.adminKey ? '****' : '');
ipcMain.handle('fetch-admin-cost', () => {
  if (settings.adminKey) fetchAnthropicCost(settings.adminKey);
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
  if (obsScraperWin && !obsScraperWin.isDestroyed()) obsScraperWin.destroy();
});

// 단일 인스턴스 보장
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showMainWindow());
}
