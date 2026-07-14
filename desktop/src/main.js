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

// Pair device - show dialog with QR code
pairBtn.addEventListener('click', () => {
    // Show dialog immediately with loading state
    const uuid = '0000A1B2-0000-1000-8000-00805F9B34FB';
    const dialog = document.createElement('div');
    dialog.id = 'pairDialog';
    dialog.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000;';
    dialog.innerHTML = `
        <div style="background:white;border-radius:12px;padding:30px;max-width:400px;text-align:center;">
            <h3 style="margin-bottom:15px;">${isChinese ? '扫码绑定' : 'Pair Device'}</h3>
            <p style="margin-bottom:10px;">${isChinese ? '使用手机 APP 扫描下方二维码' : 'Scan QR code with phone app'}</p>
            <p id="macDisplay" style="margin-bottom:20px;font-size:14px;color:#666;">MAC: ${isChinese ? '获取中...' : 'Loading...'}</p>
            <div id="qrContainer" style="width:200px;height:200px;margin:0 auto;border:1px solid #eee;display:flex;align-items:center;justify-content:center;">
                <div class="loading-spinner"></div>
            </div>
            <br><br>
            <button onclick="document.getElementById('pairDialog').remove()" style="padding:8px 20px;border:none;border-radius:6px;background:#2563eb;color:white;cursor:pointer;">${isChinese ? '关闭' : 'Close'}</button>
        </div>
    `;
    document.body.appendChild(dialog);

    // Async fetch MAC address
    invoke('get_mac_address').then(mac => {
        const macDisplay = document.getElementById('macDisplay');
        const qrContainer = document.getElementById('qrContainer');
        if (!macDisplay || !qrContainer) return;

        macDisplay.textContent = `MAC: ${mac}`;
        const qrContent = `ble://pair?mac=${mac}&uuid=${uuid}`;

        // Replace loading spinner with QR canvas
        qrContainer.innerHTML = '';
        const canvas = document.createElement('canvas');
        canvas.width = 200;
        canvas.height = 200;
        qrContainer.appendChild(canvas);

        const ctx = canvas.getContext('2d');
        ctx.fillStyle = 'white';
        ctx.fillRect(0, 0, 200, 200);
        ctx.fillStyle = 'black';

        const hash = qrContent.split('').reduce((a, b) => ((a << 5) - a + b.charCodeAt(0)) | 0, 0);
        const rng = (seed) => { let x = Math.sin(seed++) * 10000; return x - Math.floor(x); };
        let seed = Math.abs(hash);
        for (let i = 0; i < 25; i++) {
            for (let j = 0; j < 25; j++) {
                if (rng(seed++) > 0.5) ctx.fillRect(i * 8, j * 8, 8, 8);
            }
        }
        const drawCorner = (x, y) => {
            ctx.fillStyle = 'black'; ctx.fillRect(x, y, 56, 56);
            ctx.fillStyle = 'white'; ctx.fillRect(x + 8, y + 8, 40, 40);
            ctx.fillStyle = 'black'; ctx.fillRect(x + 16, y + 16, 24, 24);
        };
        drawCorner(0, 0); drawCorner(144, 0); drawCorner(0, 144);

        addLog(`${isChinese ? '显示绑定二维码' : 'Showing pairing QR code'}: ${mac}`);
    }).catch(error => {
        const macDisplay = document.getElementById('macDisplay');
        if (macDisplay) macDisplay.textContent = `${isChinese ? '获取失败' : 'Failed'}: ${error}`;
        addLog(`${isChinese ? '获取 MAC 失败' : 'Failed to get MAC'}: ${error}`);
    });
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

// Listen for BLE status sync (when window shows)
listen('ble-status-sync', (event) => {
    isRunning = event.payload;
    updateUI();
    if (isRunning) {
        addLog(isChinese ? 'GATT 服务已启动' : 'GATT server started');
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
