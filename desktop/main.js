const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, session } = require('electron');
const path = require('path');
const fs = require('fs');

// ── 상수 ──
const SETTINGS_PATH = path.join(app.getPath('userData'), 'settings.json');
const PRELOAD = path.join(__dirname, 'preload.js');
const USAGE_URL = 'https://claude.ai/settings/usage';
const SCRAPE_TIMEOUT_MS = 30000;

// ── 상태 ──
let tray = null;
let mainWin = null;
let widgetWin = null;
let loginWin = null;
let scraperWin = null;
let scrapeTimer = null;
let usageData = null;
let settings = { refreshInterval: 120, widgetVisible: true };

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
  const pctText = pct != null ? ` (세션 ${Math.round(pct)}%)` : '';
  const menu = Menu.buildFromTemplate([
    { label: `Claude 사용량${pctText}`, enabled: false },
    { type: 'separator' },
    { label: '새로고침', click: () => scrapeNow() },
    { label: settings.widgetVisible ? '위젯 숨기기' : '위젯 표시',
      click: () => toggleWidget() },
    { label: '설정 열기', click: () => showMainWindow() },
    { type: 'separator' },
    { label: '종료', click: () => app.quit() }
  ]);
  tray.setContextMenu(menu);
}

function updateTrayFromUsage() {
  if (!tray || !usageData?.session) return;
  const pct = Math.round(usageData.session.usedPercent || 0);
  tray.setImage(getStatusIcon(pct));
  tray.setToolTip(`Claude 세션 ${pct}% 사용됨`);
  updateTrayMenu();
}

// ────────────────────────────
//  스크래핑
// ────────────────────────────
const SCRAPE_JS = `
(function() {
  var body = document.body ? document.body.innerText : '';
  var percentMatches = body.match(/(\\d+)%\\s*사용됨/g) || [];
  var allResets = [];
  var r1 = body.match(/\\d+시간[\\s\\d]*분?\\s*후\\s*재설정/g);
  if (r1) r1.forEach(function(m) { allResets.push(m); });
  var r2 = body.match(/.{1,20}에\\s*재설정/g);
  if (r2) r2.forEach(function(m) {
    var t = m.trim();
    if (t.length > 3 && allResets.indexOf(t) === -1 && !t.match(/^\\d+시간/)) {
      allResets.push(t);
    }
  });
  var barValues = [];
  document.querySelectorAll('[role="progressbar"], progress, [aria-valuenow]').forEach(function(bar) {
    barValues.push(bar.getAttribute('aria-valuenow') || bar.value || '');
  });
  return JSON.stringify({
    url: window.location.href,
    percentMatches: percentMatches,
    resetMatches: allResets,
    barValues: barValues
  });
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

    const percents = [];
    (json.percentMatches || []).forEach(m => {
      const match = m.match(/(\d+)%/);
      if (match) percents.push(parseInt(match[1]));
    });
    const resets = json.resetMatches || [];

    if (percents.length === 0 && json.barValues) {
      json.barValues.forEach(v => {
        const n = parseInt(v);
        if (!isNaN(n) && n >= 0 && n <= 100) percents.push(n);
      });
    }

    if (percents.length > 0) {
      usageData = {
        planName: 'Max',
        session: { label: '현재 세션', usedPercent: percents[0], resetTime: resets[0] || '' },
        weekly: percents.length > 1
          ? { label: '주간 한도', usedPercent: percents[1], resetTime: resets[1] || '' }
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

function startScrapeTimer() {
  stopScrapeTimer();
  const ms = Math.max(30, settings.refreshInterval || 120) * 1000;
  scrapeTimer = setInterval(() => scrapeNow(), ms);
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
ipcMain.handle('get-settings', () => settings);
ipcMain.handle('save-settings', (_, s) => {
  Object.assign(settings, s);
  saveSettings();
  startScrapeTimer(); // 주기 변경 반영
});
ipcMain.handle('refresh', () => scrapeNow());
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
ipcMain.handle('quit', () => app.quit());

// ────────────────────────────
//  앱 시작
// ────────────────────────────
app.whenReady().then(() => {
  loadSettings();
  createTray();
  if (settings.widgetVisible) createWidgetWindow();

  // 초기 스크래핑 (로그인 상태 확인)
  scrapeNow();
  startScrapeTimer();
});

app.on('window-all-closed', (e) => {
  // 트레이 상주: 모든 윈도우 닫아도 앱 종료 안 함
  e.preventDefault?.();
});

app.on('before-quit', () => {
  stopScrapeTimer();
});

// 단일 인스턴스 보장
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showMainWindow());
}
