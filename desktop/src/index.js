const $ = (sel) => document.querySelector(sel);

const loginDot = $('#loginDot');
const loginText = $('#loginText');
const loginBtn = $('#loginBtn');
const logoutBtn = $('#logoutBtn');
const usageCards = $('#usageCards');
const intervalInput = $('#intervalInput');
const statusText = $('#statusText');

// 초기 로딩
(async () => {
  const settings = await window.api.getSettings();
  intervalInput.value = settings.refreshInterval || 120;

  const usage = await window.api.getUsage();
  if (usage) renderUsage(usage);
})();

// 실시간 업데이트 수신
window.api.onUsageUpdate((data) => renderUsage(data));
window.api.onStatusUpdate((msg) => { statusText.textContent = msg; });

// 버튼 이벤트
$('#refreshBtn').onclick = async () => {
  statusText.textContent = '새로고침 중...';
  await window.api.refresh();
};

$('#widgetBtn').onclick = () => window.api.toggleWidget();

$('#saveBtn').onclick = async () => {
  const val = parseInt(intervalInput.value) || 120;
  intervalInput.value = Math.max(30, val);
  await window.api.saveSettings({ refreshInterval: Math.max(30, val) });
  statusText.textContent = '저장됨';
};

loginBtn.onclick = () => window.api.login();
logoutBtn.onclick = async () => {
  await window.api.logout();
  setLoginState(false);
  usageCards.innerHTML = '';
  statusText.textContent = '로그아웃됨';
};

function setLoginState(loggedIn) {
  loginDot.className = 'dot ' + (loggedIn ? 'green' : 'red');
  loginText.textContent = loggedIn ? '로그인됨' : '로그인 필요';
  loginBtn.style.display = loggedIn ? 'none' : '';
  logoutBtn.style.display = loggedIn ? '' : 'none';
}

function colorClass(pct) {
  if (pct >= 90) return 'red';
  if (pct >= 70) return 'yellow';
  return 'green';
}

function renderUsage(data) {
  if (!data) return;
  setLoginState(true);

  usageCards.innerHTML = '';

  if (data.session) addCard(data.session);
  if (data.weekly) addCard(data.weekly);

  const now = new Date();
  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');
  statusText.textContent = `마지막 업데이트: ${hh}:${mm}`;
}

function addCard(item) {
  const pct = Math.round(item.usedPercent || 0);
  const cls = colorClass(pct);
  const card = document.createElement('div');
  card.className = 'usage-card';
  card.innerHTML = `
    <div class="label">${item.label}</div>
    <div class="percent" style="color:var(--c)">${pct}% 사용됨</div>
    <div class="progress-bar"><div class="fill ${cls}" style="width:${pct}%"></div></div>
    <div class="reset">${item.resetTime || ''}</div>
  `;
  const c = cls === 'green' ? '#4caf50' : cls === 'yellow' ? '#ff9800' : '#f44336';
  card.style.setProperty('--c', c);
  usageCards.appendChild(card);
}
