const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

const loginDot = $('#loginDot');
const loginText = $('#loginText');
const loginBtn = $('#loginBtn');
const logoutBtn = $('#logoutBtn');
const usageCards = $('#usageCards');
const costCards = $('#costCards');
const intervalInput = $('#intervalInput');
const statusText = $('#statusText');

let currentMode = 'CLAUDE_ONLY';

// ── AI 정의 ──
const AI_DEFS = {
  gpt:    { name: 'GPT',        color: '#10a37f' },
  claude: { name: 'Claude',     color: '#c96442' },
  gemini: { name: 'Gemini',     color: '#4285f4' },
  grok:   { name: 'Grok',       color: '#1DA1F2' },
  perp:   { name: 'Perplexity', color: '#20808d' },
};

// 초기 로딩
(async () => {
  const settings = await window.api.getSettings();
  intervalInput.value = settings.refreshInterval || 120;
  currentMode = settings.displayMode || 'CLAUDE_ONLY';

  // 모드 라디오 복원
  const radio = $(`input[name="displayMode"][value="${currentMode}"]`);
  if (radio) radio.checked = true;
  updateModeVisibility();

  const usage = await window.api.getUsage();
  if (usage) renderUsage(usage);

  const cost = await window.api.getCost();
  if (cost) renderCost(cost);

  // 오랑붕쌤 상태
  const obsStatus = await window.api.getObsStatus();
  setObsState(obsStatus);

  // Admin 키
  const adminKey = await window.api.getAdminKey();
  if (adminKey) {
    $('#adminKeyInput').value = adminKey;
    window.api.fetchAdminCost();
  }
})();

// 실시간 업데이트 수신
window.api.onUsageUpdate((data) => renderUsage(data));
window.api.onCostUpdate((data) => renderCost(data));
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

  const modeRadio = $('input[name="displayMode"]:checked');
  currentMode = modeRadio ? modeRadio.value : 'CLAUDE_ONLY';

  await window.api.saveSettings({
    refreshInterval: Math.max(30, val),
    displayMode: currentMode,
  });
  updateModeVisibility();
  statusText.textContent = '저장됨';
};

// 모드 변경 시 즉시 반영
$$('input[name="displayMode"]').forEach(radio => {
  radio.addEventListener('change', () => {
    currentMode = radio.value;
    updateModeVisibility();
  });
});

loginBtn.onclick = () => window.api.login();

// 오랑붕쌤 연결
$('#obsBtn').onclick = () => window.api.obsLogin();
window.api.onObsStatus((status) => setObsState(status));

function setObsState(connected) {
  const dot = $('#obsDot');
  const text = $('#obsText');
  dot.className = 'dot ' + (connected ? 'green' : 'gray');
  text.textContent = connected ? '오랑붕쌤: 연결됨' : '오랑붕쌤: 연결 안됨';
  $('#obsBtn').textContent = connected ? '재연결' : '연결';
}

// Admin API 키
$('#adminKeySave').onclick = async () => {
  const key = $('#adminKeyInput').value.trim();
  await window.api.saveAdminKey(key);
  $('#adminCostText').textContent = key ? 'Admin 키 저장됨' : 'Admin 키 삭제됨';
};

window.api.onAdminCostUpdate((result) => {
  if (result.success) {
    const total = result.data?.total_cost;
    if (total != null) {
      $('#adminCostText').textContent = `Anthropic 실제 청구: $${total.toFixed(4)}`;
    } else {
      $('#adminCostText').textContent = 'Anthropic: 응답 수신됨';
    }
  } else {
    $('#adminCostText').textContent = `Admin API 오류: ${result.error || result.statusCode}`;
  }
});
logoutBtn.onclick = async () => {
  await window.api.logout();
  setLoginState(false);
  usageCards.innerHTML = '';
  statusText.textContent = '로그아웃됨';
};

function updateModeVisibility() {
  usageCards.style.display = currentMode !== 'API_COST_ONLY' ? '' : 'none';
  costCards.style.display = currentMode !== 'CLAUDE_ONLY' ? '' : 'none';
}

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

function renderCost(data) {
  if (!data) return;

  $('#costToday').textContent = `$${data.todayTotal.toFixed(4)}`;
  $('#costTodayKrw').textContent = `≈${Math.round(data.todayTotal * 1450).toLocaleString()}원`;
  $('#costMonth').textContent = `$${data.monthTotal.toFixed(4)}`;
  $('#costMonthKrw').textContent = `≈${Math.round(data.monthTotal * 1450).toLocaleString()}원`;

  const byAI = $('#costByAI');
  byAI.innerHTML = '';

  (data.byAI || []).filter(ai => ai.monthCost > 0).forEach(ai => {
    const def = AI_DEFS[ai.aiId] || { name: ai.name, color: '#888' };
    const row = document.createElement('div');
    row.style.cssText = 'display:flex;align-items:center;gap:8px;padding:3px 0;font-size:12px';
    row.innerHTML = `
      <div style="width:8px;height:8px;border-radius:50%;background:${def.color}"></div>
      <span style="flex:1;color:#aaa">${def.name}</span>
      <span style="font-family:monospace">$${ai.monthCost.toFixed(4)}</span>
    `;
    byAI.appendChild(row);
  });
}
