const emoji = document.getElementById('emoji');
const text = document.getElementById('text');

window.api.onUsageUpdate((data) => {
  if (!data || !data.session) return;
  const pct = Math.round(data.session.usedPercent || 0);
  if (pct >= 90) {
    emoji.textContent = '🔴';
  } else if (pct >= 70) {
    emoji.textContent = '🟡';
  } else {
    emoji.textContent = '🟢';
  }
  text.textContent = `세션 ${pct}%`;
});

// 초기 데이터 로딩
(async () => {
  const data = await window.api.getUsage();
  if (data && data.session) {
    const pct = Math.round(data.session.usedPercent || 0);
    emoji.textContent = pct >= 90 ? '🔴' : pct >= 70 ? '🟡' : '🟢';
    text.textContent = `세션 ${pct}%`;
  }
})();
