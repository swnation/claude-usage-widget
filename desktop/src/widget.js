const emoji = document.getElementById('emoji');
const text = document.getElementById('text');
const widget = document.getElementById('widget');

let lastUsage = null;
let lastCost = null;
let overlayMode = 'MINIMAL'; // MINIMAL, BASIC, COST, FULL

// 클릭 → 메인 윈도우 열기 (드래그와 구분)
let isDragging = false;
widget.addEventListener('mousedown', () => { isDragging = false; });
widget.addEventListener('mousemove', () => { isDragging = true; });
widget.addEventListener('mouseup', () => {
  if (!isDragging) window.api.showMain();
});

window.api.onUsageUpdate((data) => {
  lastUsage = data;
  updateWidget();
});

window.api.onCostUpdate((data) => {
  lastCost = data;
  updateWidget();
});

function updateWidget() {
  let sessionPct = null;
  let weeklyPct = null;
  let emojiStr = '⚪';

  if (lastUsage && lastUsage.session) {
    sessionPct = Math.round(lastUsage.session.usedPercent || 0);
    emojiStr = sessionPct >= 90 ? '🔴' : sessionPct >= 70 ? '🟡' : '🟢';
  }
  if (lastUsage && lastUsage.weekly) {
    weeklyPct = Math.round(lastUsage.weekly.usedPercent || 0);
  }

  let todayCost = null;
  if (lastCost) {
    todayCost = `$${lastCost.todayTotal.toFixed(4)}`;
  }

  emoji.textContent = emojiStr;
  const pctStr = sessionPct != null ? `${sessionPct}%` : '--%';
  const parts = [pctStr];

  if (overlayMode === 'BASIC' || overlayMode === 'FULL') {
    if (weeklyPct != null) parts.push(`주간 ${weeklyPct}%`);
  }
  if (overlayMode === 'COST' || overlayMode === 'FULL') {
    if (todayCost) parts.push(`💰${todayCost}`);
  }

  text.textContent = parts.join(' │ ');
}

// 스킨 적용
function applySkin(skinId, customSkinImage) {
  document.body.setAttribute('data-skin', skinId || 'default');
  if (skinId === 'custom' && customSkinImage) {
    widget.style.backgroundImage = `url(${customSkinImage})`;
    widget.style.backgroundSize = 'cover';
    widget.style.backgroundPosition = 'center';
  } else {
    widget.style.backgroundImage = '';
  }
}

// 초기 데이터 로딩
(async () => {
  const settings = await window.api.getSettings();
  overlayMode = settings.overlayMode || 'MINIMAL';

  // 스킨 적용
  applySkin(settings.skin, settings.customSkinImage);

  lastUsage = await window.api.getUsage();
  lastCost = await window.api.getCost();
  updateWidget();
})();

// 설정 변경 시 스킨 실시간 반영
window.api.onStatusUpdate(async () => {
  const settings = await window.api.getSettings();
  overlayMode = settings.overlayMode || 'MINIMAL';
  applySkin(settings.skin, settings.customSkinImage);
  updateWidget();
});
