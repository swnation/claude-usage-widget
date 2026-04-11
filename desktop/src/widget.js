const emoji = document.getElementById('emoji');
const text = document.getElementById('text');

let lastUsage = null;
let lastCost = null;
let currentMode = 'CLAUDE_ONLY';

window.api.onUsageUpdate((data) => {
  lastUsage = data;
  updateWidget();
});

window.api.onCostUpdate((data) => {
  lastCost = data;
  updateWidget();
});

function updateWidget() {
  if (currentMode === 'CLAUDE_ONLY') {
    if (!lastUsage || !lastUsage.session) return;
    const pct = Math.round(lastUsage.session.usedPercent || 0);
    emoji.textContent = pct >= 90 ? '🔴' : pct >= 70 ? '🟡' : '🟢';
    text.textContent = `세션 ${pct}%`;
  } else if (currentMode === 'API_COST_ONLY') {
    if (!lastCost) return;
    emoji.textContent = '💰';
    text.textContent = `$${lastCost.todayTotal.toFixed(4)}`;
  } else {
    // BOTH
    const parts = [];
    if (lastUsage && lastUsage.session) {
      const pct = Math.round(lastUsage.session.usedPercent || 0);
      emoji.textContent = pct >= 90 ? '🔴' : pct >= 70 ? '🟡' : '🟢';
      parts.push(`${pct}%`);
    }
    if (lastCost) {
      parts.push(`💰$${lastCost.todayTotal.toFixed(4)}`);
    }
    text.textContent = parts.join(' ') || '로딩...';
  }
}

// 초기 데이터 로딩
(async () => {
  const settings = await window.api.getSettings();
  currentMode = settings.displayMode || 'CLAUDE_ONLY';

  lastUsage = await window.api.getUsage();
  lastCost = await window.api.getCost();
  updateWidget();
})();
