const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { getCurrentWindow } = window.__TAURI__.window;

// Language detection - follow system, fallback to English
const isChinese = navigator.language.startsWith('zh');

// DOM elements
const statusEl = document.getElementById('status');
const connectionsEl = document.getElementById('connections');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const pairBtn = document.getElementById('pairBtn');
const logBox = document.getElementById('logBox');

let isRunning = false;

// Add log entry
function addLog(message) {
    const timestamp = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `<span class="timestamp">[${timestamp}]</span> ${message}`;
    logBox.appendChild(entry);
    logBox.scrollTop = logBox.scrollHeight;
}

// Update UI state
function updateUI() {
    statusEl.textContent = isRunning 
        ? (isChinese ? '运行中' : 'Running') 
        : (isChinese ? '未启动' : 'Stopped');
    startBtn.disabled = isRunning;
    stopBtn.disabled = !isRunning;
}

// Start server
async function startServer() {
    try {
        addLog(isChinese ? '正在启动服务...' : 'Starting server...');
        await invoke('start_gatt_server');
        isRunning = true;
        updateUI();
        addLog(isChinese ? 'GATT 服务已启动' : 'GATT server started');
    } catch (error) {
        addLog(`${isChinese ? '启动失败' : 'Start failed'}: ${error}`);
    }
}

// Stop server
async function stopServer() {
    try {
        addLog(isChinese ? '正在停止服务...' : 'Stopping server...');
        await invoke('stop_gatt_server');
        isRunning = false;
        updateUI();
        addLog(isChinese ? 'GATT 服务已停止' : 'GATT server stopped');
    } catch (error) {
        addLog(`${isChinese ? '停止失败' : 'Stop failed'}: ${error}`);
    }
}

// Button click handlers
startBtn.addEventListener('click', startServer);
stopBtn.addEventListener('click', stopServer);

// Pair device
pairBtn.addEventListener('click', async () => {
    addLog(isChinese ? '扫码绑定功能开发中...' : 'Pair feature coming soon...');
});

// Listen for tray menu events
listen('tray-action', async (event) => {
    const action = event.payload;
    if (action === 'start') {
        await startServer();
    } else if (action === 'stop') {
        await stopServer();
    }
});

// Listen for show window event from tray
listen('tray-show-window', async () => {
    const window = getCurrentWindow();
    await window.show();
    await window.unminimize();
    await window.setFocus();
});

// Initialize
addLog(isChinese ? '应用已启动' : 'Application started');
updateUI();
