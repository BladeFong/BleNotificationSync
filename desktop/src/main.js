const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { getCurrentWindow } = window.__TAURI__.window;

// Language detection - follow system, fallback to English
const isChinese = navigator.language.startsWith('zh');

// DOM elements
const statusEl = document.getElementById('status');
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
        await invoke('start_gatt_server');
    } catch (error) {
        addLog(`${isChinese ? '启动失败' : 'Start failed'}: ${error}`);
    }
}

// Stop server
async function stopServer() {
    try {
        await invoke('stop_gatt_server');
    } catch (error) {
        addLog(`${isChinese ? '停止失败' : 'Stop failed'}: ${error}`);
    }
}

// Button click handlers
startBtn.addEventListener('click', startServer);
stopBtn.addEventListener('click', stopServer);

// Pair device - show dialog with QR code
pairBtn.addEventListener('click', () => {
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

        // Replace loading spinner with real QR code
        qrContainer.innerHTML = '';
        const qr = qrcode(0, 'M'); // type 0 (auto), error correction M
        qr.addData(qrContent);
        qr.make();
        qrContainer.innerHTML = qr.createSvgTag({ scalable: true });
        qrContainer.querySelector('svg').style.width = '200px';
        qrContainer.querySelector('svg').style.height = '200px';

        addLog(`${isChinese ? '显示绑定二维码' : 'Showing pairing QR code'}: ${mac}`);
    }).catch(error => {
        const macDisplay = document.getElementById('macDisplay');
        if (macDisplay) macDisplay.textContent = `${isChinese ? '获取失败' : 'Failed'}: ${error}`;
        addLog(`${isChinese ? '获取 MAC 失败' : 'Failed to get MAC'}: ${error}`);
    });
});

// ── 事件监听 ──

// BLE 状态同步（Rust 后端统一推送，前端仅被动更新 UI，不做日志）
listen('ble-status-sync', (event) => {
    isRunning = event.payload;
    updateUI();
});

// 托盘日志消息
listen('log-message', (event) => {
    addLog(event.payload);
});

// 托盘显示窗口事件
listen('show-window', () => {
    const window = getCurrentWindow();
    window.show();
    window.unminimize();
    window.setFocus();
});

// ── 设备列表 ──

async function refreshDeviceList() {
    try {
        const devices = await invoke('get_paired_devices');
        const deviceList = document.getElementById('deviceList');
        const deviceCount = document.getElementById('deviceCount');
        if (!deviceList || !deviceCount) return;
        deviceCount.textContent = devices.length;
        if (devices.length === 0) {
            deviceList.innerHTML = `<span class="device-empty">${isChinese ? '暂无绑定设备' : 'No paired devices'}</span>`;
            return;
        }
        deviceList.innerHTML = devices.map(d =>
            `<div class="device-item">
                <span class="device-name">${d.app_name}</span>
                <span class="device-pkg">${d.package_name}</span>
                <span class="device-mac">${d.mac}</span>
            </div>`
        ).join('');
    } catch (e) {
        // ignore
    }
}

// 设备注册事件
listen('device-registered', () => {
    refreshDeviceList();
});

// Initialize
addLog(isChinese ? '应用已启动' : 'Application started');
updateUI();
refreshDeviceList();
