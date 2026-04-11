const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getUsage: () => ipcRenderer.invoke('get-usage'),
  getCost: () => ipcRenderer.invoke('get-cost'),
  getSettings: () => ipcRenderer.invoke('get-settings'),
  saveSettings: (s) => ipcRenderer.invoke('save-settings', s),
  refresh: () => ipcRenderer.invoke('refresh'),
  login: () => ipcRenderer.invoke('login'),
  logout: () => ipcRenderer.invoke('logout'),
  toggleWidget: () => ipcRenderer.invoke('toggle-widget'),
  obsLogin: () => ipcRenderer.invoke('obs-login'),
  getObsStatus: () => ipcRenderer.invoke('get-obs-status'),
  saveAdminKeyEncrypted: (type, key, pin) => ipcRenderer.invoke('save-admin-key-encrypted', type, key, pin),
  restoreAdminKeys: (pin) => ipcRenderer.invoke('restore-admin-keys', pin),
  getAdminKeys: () => ipcRenderer.invoke('get-admin-keys'),
  fetchAdminCost: () => ipcRenderer.invoke('fetch-admin-cost'),
  onObsStatus: (cb) => { ipcRenderer.on('obs-status', (_, s) => cb(s)); },
  onAdminCostUpdate: (cb) => { ipcRenderer.on('admin-cost-update', (_, d) => cb(d)); },
  quit: () => ipcRenderer.invoke('quit'),
  onUsageUpdate: (cb) => {
    ipcRenderer.on('usage-update', (_, data) => cb(data));
  },
  onCostUpdate: (cb) => {
    ipcRenderer.on('cost-update', (_, data) => cb(data));
  },
  onStatusUpdate: (cb) => {
    ipcRenderer.on('status-update', (_, msg) => cb(msg));
  },
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
});
