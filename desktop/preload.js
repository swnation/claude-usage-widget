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
  quit: () => ipcRenderer.invoke('quit'),
  onUsageUpdate: (cb) => {
    ipcRenderer.on('usage-update', (_, data) => cb(data));
  },
  onCostUpdate: (cb) => {
    ipcRenderer.on('cost-update', (_, data) => cb(data));
  },
  onStatusUpdate: (cb) => {
    ipcRenderer.on('status-update', (_, msg) => cb(msg));
  }
});
