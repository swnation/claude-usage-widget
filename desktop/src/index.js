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
  gpt:    { name: 'GPT',        color: '#10a37f', url: 'https://platform.openai.com/usage' },
  claude: { name: 'Claude',     color: '#c96442', url: 'https://console.anthropic.com/settings/billing' },
  gemini: { name: 'Gemini',     color: '#4285f4', url: 'https://aistudio.google.com/apikey' },
  grok:   { name: 'Grok',       color: '#1DA1F2', url: 'https://console.x.ai/' },
  perp:   { name: 'Perplexity', color: '#20808d', url: 'https://www.perplexity.ai/settings/api' },
};

// ══════════════════════════════
//  스킨 시스템
// ══════════════════════════════
const SKINS = [
  { id: 'default',       label: '기본',     emoji: '🌙', preview: 'linear-gradient(135deg, #1a1a2e, #16213e)' },
  { id: 'spring',        label: '봄',       emoji: '🌸', preview: 'linear-gradient(135deg, #ffe4eb, #fff5f5)' },
  { id: 'summer',        label: '여름',     emoji: '🌊', preview: 'linear-gradient(135deg, #c8ebff, #e8f4f8)' },
  { id: 'autumn',        label: '가을',     emoji: '🍂', preview: 'linear-gradient(135deg, #fae6c8, #faf5ef)' },
  { id: 'winter',        label: '겨울',     emoji: '❄️', preview: 'linear-gradient(135deg, #dcebff, #eef2f7)' },
  { id: 'fluffy-pink',   label: '몽글핑크', emoji: '🩷', preview: 'linear-gradient(135deg, #ffdcf0, #fff0f6)' },
  { id: 'fluffy-purple', label: '몽글퍼플', emoji: '💜', preview: 'linear-gradient(135deg, #e6d7ff, #f5f0ff)' },
  { id: 'fluffy-mint',   label: '몽글민트', emoji: '💚', preview: 'linear-gradient(135deg, #c8f5e6, #f0faf7)' },
  { id: 'fluffy-yellow', label: '몽글옐로', emoji: '💛', preview: 'linear-gradient(135deg, #fff5b4, #fffde8)' },
  { id: 'fluffy-sky',    label: '몽글스카이', emoji: '🩵', preview: 'linear-gradient(135deg, #d2e6ff, #f0f6ff)' },
];

let currentSkin = 'default';
let customSkinImage = null; // Base64 data URL

function applySkin(skinId) {
  currentSkin = skinId;
  document.body.setAttribute('data-skin', skinId);

  // 커스텀 스킨이면 배경 이미지 적용
  if (skinId === 'custom' && customSkinImage) {
    document.body.style.backgroundImage = `url(${customSkinImage})`;
    document.body.style.backgroundSize = 'cover';
    document.body.style.backgroundPosition = 'center';
  } else {
    document.body.style.backgroundImage = '';
  }

  // 스킨 그리드 active 표시
  $$('.skin-item').forEach(el => {
    el.classList.toggle('active', el.dataset.skin === skinId);
  });

  // 설정 저장
  window.api.saveSettings({ skin: skinId, customSkinImage: skinId === 'custom' ? customSkinImage : undefined });
}

function renderSkinGrid() {
  const grid = $('#skinGrid');
  grid.innerHTML = '';

  SKINS.forEach(skin => {
    const item = document.createElement('div');
    item.className = 'skin-item' + (currentSkin === skin.id ? ' active' : '');
    item.dataset.skin = skin.id;
    item.innerHTML = `
      <div class="skin-preview" style="background:${skin.preview}">${skin.emoji}</div>
      <div class="skin-label">${skin.label}</div>
    `;
    item.onclick = () => applySkin(skin.id);
    grid.appendChild(item);
  });

  // 커스텀 (사진 업로드)
  const customItem = document.createElement('div');
  customItem.className = 'skin-item' + (currentSkin === 'custom' ? ' active' : '');
  customItem.dataset.skin = 'custom';
  if (customSkinImage) {
    customItem.innerHTML = `
      <div class="skin-preview" style="background:url(${customSkinImage}) center/cover">📷</div>
      <div class="skin-label">내 사진</div>
    `;
  } else {
    customItem.innerHTML = `
      <div class="skin-upload-btn" style="width:100%;height:100%;aspect-ratio:auto">+</div>
      <div class="skin-label">사진</div>
    `;
  }
  customItem.onclick = () => {
    $('#skinFileInput').click();
  };
  grid.appendChild(customItem);
}

$('#skinFileInput').onchange = (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = (ev) => {
    customSkinImage = ev.target.result;
    applySkin('custom');
    renderSkinGrid();
  };
  reader.readAsDataURL(file);
  e.target.value = '';
};

// ── 초기 로딩 ──
(async () => {
  const settings = await window.api.getSettings();
  intervalInput.value = settings.refreshInterval || 120;
  currentMode = settings.displayMode || 'CLAUDE_ONLY';

  // 모드 라디오 복원
  const radio = $(`input[name="displayMode"][value="${currentMode}"]`);
  if (radio) radio.checked = true;
  updateModeVisibility();

  // 플로팅 모드 복원
  if (settings.overlayMode) $('#overlayModeSelect').value = settings.overlayMode;

  // 스킨 복원
  currentSkin = settings.skin || 'default';
  customSkinImage = settings.customSkinImage || null;
  renderSkinGrid();
  applySkin(currentSkin);

  const usage = await window.api.getUsage();
  if (usage) renderUsage(usage);

  const cost = await window.api.getCost();
  if (cost) renderCost(cost);

  // 오랑붕쌤 상태
  const obsStatus = await window.api.getObsStatus();
  setObsState(obsStatus);

  // Admin 키
  const adminKeys = await window.api.getAdminKeys();
  if (adminKeys.anthropic) $('#anthropicKeyInput').value = adminKeys.anthropic;
  if (adminKeys.openai) $('#openaiKeyInput').value = adminKeys.openai;
  if (adminKeys.anthropic || adminKeys.openai) window.api.fetchAdminCost();
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

// 플로팅 정보량 모드
$('#overlayModeSelect').onchange = async () => {
  const mode = $('#overlayModeSelect').value;
  await window.api.saveSettings({ overlayMode: mode });
};

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

// Admin API 키 (암호화 저장)
$('#anthropicKeySave').onclick = () => promptPinAndSave('anthropic');
$('#openaiKeySave').onclick = () => promptPinAndSave('openai');
$('#adminKeyRestore').onclick = () => promptPinAndRestore();

function promptPinAndSave(type) {
  const input = type === 'anthropic' ? '#anthropicKeyInput' : '#openaiKeyInput';
  const key = $(input).value.trim();
  if (key === '****' || !key) return;
  const pin = prompt('🔐 키 암호화 PIN (4자리 이상):');
  if (!pin || pin.length < 4) { alert('PIN은 4자리 이상이어야 합니다'); return; }
  window.api.saveAdminKeyEncrypted(type, key, pin).then(r => {
    $('#adminCostText').textContent = r.driveBackup
      ? '🔐 암호화 저장 + ☁️ Drive 백업 완료' : '🔐 암호화 저장됨 (Drive 미연결)';
  });
}

function promptPinAndRestore() {
  const pin = prompt('🔓 백업 복원 PIN:');
  if (!pin) return;
  window.api.restoreAdminKeys(pin).then(r => {
    if (r.error) { $('#adminCostText').textContent = `❌ ${r.error}`; return; }
    if (r.anthropic) $('#anthropicKeyInput').value = '****';
    if (r.openai) $('#openaiKeyInput').value = '****';
    $('#adminCostText').textContent = '🔓 키 복원 완료';
  });
}

// Admin 비용 업데이트 (통합 Billing 시스템)
window.api.onAdminCostUpdate(({ costData }) => {
  if (!costData) return;
  const lines = [];
  lines.push(`소스: ${sourceLabel(costData.source)}`);
  (costData.byAI || []).filter(ai => ai.monthCost > 0).forEach(ai => {
    const tag = ai.source === 'billing' || ai.source === 'hybrid' ? '✓실제' : '~추정';
    lines.push(`${ai.name}: $${ai.monthCost.toFixed(4)} ${tag}`);
    if (ai.monthDiff != null) {
      const sign = ai.monthDiff >= 0 ? '+' : '';
      lines.push(`  추정대비 ${sign}$${ai.monthDiff.toFixed(4)}`);
    }
  });
  $('#adminCostText').textContent = lines.join('\n') || 'Billing API 키를 저장하세요';
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

function sourceLabel(src) {
  if (src === 'billing') return '실제 청구';
  if (src === 'hybrid') return '실제+추정';
  return '추정';
}

function sourceBadgeClass(src) {
  if (src === 'billing') return 'billing';
  if (src === 'hybrid') return 'hybrid';
  return 'estimated';
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

  // 소스 뱃지
  const badge = $('#costSourceBadge');
  const src = data.source || 'estimated';
  badge.textContent = sourceLabel(src);
  badge.className = 'source-badge ' + sourceBadgeClass(src);

  $('#costToday').textContent = `$${data.todayTotal.toFixed(4)}`;
  $('#costTodayKrw').textContent = `≈${Math.round(data.todayTotal * 1450).toLocaleString()}원`;
  $('#costMonth').textContent = `$${data.monthTotal.toFixed(4)}`;
  $('#costMonthKrw').textContent = `≈${Math.round(data.monthTotal * 1450).toLocaleString()}원`;

  const byAI = $('#costByAI');
  byAI.innerHTML = '';

  (data.byAI || []).filter(ai => ai.monthCost > 0).forEach(ai => {
    const def = AI_DEFS[ai.aiId] || { name: ai.name, color: '#888', url: null };
    const row = document.createElement('div');
    row.style.cssText = 'display:flex;align-items:center;gap:8px;padding:4px 0;font-size:12px';

    // 소스 태그
    const srcTag = ai.source === 'billing' || ai.source === 'hybrid'
      ? '<span style="font-size:9px;color:#4caf50;margin-left:2px">✓</span>'
      : '<span style="font-size:9px;color:#ff9800;margin-left:2px">~</span>';

    const nameEl = def.url
      ? `<a href="#" class="ai-link" data-url="${def.url}" style="flex:1;color:${def.color};text-decoration:underline;cursor:pointer">${def.name}${srcTag}</a>`
      : `<span style="flex:1;color:var(--text-sub)">${def.name}${srcTag}</span>`;
    row.innerHTML = `
      <div style="width:8px;height:8px;border-radius:50%;background:${def.color}"></div>
      ${nameEl}
      <span style="font-family:monospace">$${ai.monthCost.toFixed(4)}</span>
    `;
    byAI.appendChild(row);
  });

  // AI 링크 클릭 핸들러
  byAI.querySelectorAll('.ai-link').forEach(el => {
    el.onclick = (e) => {
      e.preventDefault();
      window.api.openExternal(el.dataset.url);
    };
  });

  // 구독 표시
  const subsEl = $('#costSubs');
  const subs = data.subscriptions || [];
  if (subs.length > 0) {
    subsEl.style.display = '';
    subsEl.innerHTML = '<div style="font-size:10px;color:var(--text-sub);margin-top:8px;margin-bottom:4px">구독</div>';
    subs.filter(s => s.isActive !== false).forEach(sub => {
      const def = AI_DEFS[sub.aiId] || { name: sub.aiId, color: '#888' };
      const row = document.createElement('div');
      row.className = 'sub-row';
      row.innerHTML = `
        <span class="sub-name" style="color:${def.color}">${def.name} ${sub.planName}</span>
        <span class="sub-cost">$${sub.monthlyFee}/mo</span>
      `;
      subsEl.appendChild(row);
    });
    // 총합
    const monthWithSubs = data.monthTotal + subs.filter(s => s.isActive !== false).reduce((s, sub) => s + (sub.monthlyFee || 0), 0);
    const totalDiv = document.createElement('div');
    totalDiv.className = 'sub-total';
    totalDiv.textContent = `총 (API+구독): $${monthWithSubs.toFixed(2)} (≈${Math.round(monthWithSubs * 1450).toLocaleString()}원)`;
    subsEl.appendChild(totalDiv);
  } else {
    subsEl.style.display = 'none';
  }
}
