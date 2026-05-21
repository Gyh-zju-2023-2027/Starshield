
const WebSocket = require("ws");

// ── 解析命令行参数 ────────────────────────────────────────────────
function parseArgs() {
  const args = process.argv.slice(2);
  const get = (flag, def) => {
    const i = args.indexOf(flag);
    return i !== -1 && args[i + 1] ? args[i + 1] : def;
  };
  return {
    host:        get("--host",        "ws://localhost:8080"),
    connections: parseInt(get("--connections", "200"), 10),
    slowCount:   parseInt(get("--slow",         "0"),   10),
    rampMs:      parseInt(get("--ramp-ms",      "20"),  10), // 每隔多少 ms 建一个连接
  };
}

const cfg = parseArgs();
const WS_URL = `${cfg.host}/ws/dashboard`;

// ── 统计 ─────────────────────────────────────────────────────────
const stat = {
  attempted:  0,
  connected:  0,
  closed:     0,
  errors:     0,
  messages:   0,
  latencies:  [],   // 从连接建立到首条消息的时延(ms)
};

function percentile(arr, p) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.floor(s.length * p / 100)];
}

// ── 建立单个连接 ─────────────────────────────────────────────────
function createClient(index) {
  const isSlow = index < cfg.slowCount;   // 前 N 个为慢客户端
  const openTime = Date.now();
  let firstMessage = true;

  stat.attempted++;
  const ws = new WebSocket(WS_URL, {
    handshakeTimeout: 5000,
  });

  ws.on("open", () => {
    stat.connected++;
    if (stat.connected % 50 === 0 || stat.connected === cfg.connections) {
      console.log(
        `[连接] 已建立 ${stat.connected}/${cfg.connections}` +
        (isSlow ? "  ← 慢客户端" : "")
      );
    }
  });

  ws.on("message", (data) => {
    stat.messages++;

    // 记录首条消息延迟
    if (firstMessage) {
      firstMessage = false;
      stat.latencies.push(Date.now() - openTime);
    }

    // 慢客户端：人为 sleep 500ms 再处理下一条，模拟前端渲染卡顿
    // 目的：测试后端广播时对慢订阅者的容忍度（是否 OOM / 阻塞）
    if (isSlow) {
      const start = Date.now();
      while (Date.now() - start < 500) { /* busy wait */ }
    }

    if (stat.messages % 500 === 0) {
      console.log(
        `[广播] 累计收到消息 ${stat.messages} 条  ` +
        `首消息延迟 P50=${percentile(stat.latencies, 50)}ms ` +
        `P99=${percentile(stat.latencies, 99)}ms`
      );
    }
  });

  ws.on("error", (err) => {
    stat.errors++;
    // 只打印前 5 个错误，避免刷屏
    if (stat.errors <= 5) {
      console.error(`[错误] #${index}: ${err.message}`);
    }
  });

  ws.on("close", (code, reason) => {
    stat.connected = Math.max(0, stat.connected - 1);
    stat.closed++;
    // 断线重连，模拟前端大屏刷新行为
    setTimeout(() => createClient(index), 3000);
  });

  return ws;
}

// ── 阶梯建连（避免瞬间握手风暴） ───────────────────────────────────
console.log(`\n🚀  开始建立 ${cfg.connections} 个 WebSocket 长连接`);
console.log(`    目标: ${WS_URL}`);
console.log(`    慢客户端: ${cfg.slowCount} 个`);
console.log(`    建连间隔: ${cfg.rampMs} ms\n`);

let current = 0;
const rampTimer = setInterval(() => {
  if (current >= cfg.connections) {
    clearInterval(rampTimer);
    console.log(`\n[就绪] 所有连接已发起，进入监控模式...\n`);
    return;
  }
  createClient(current);
  current++;
}, cfg.rampMs);

// ── 定期打印健康报告 ─────────────────────────────────────────────
setInterval(() => {
  console.log(
    `📊  在线=${stat.connected}  ` +
    `累计消息=${stat.messages}  ` +
    `断开=${stat.closed}  ` +
    `错误=${stat.errors}  ` +
    `首消息延迟 P50=${percentile(stat.latencies, 50)}ms / ` +
    `P99=${percentile(stat.latencies, 99)}ms`
  );
}, 5000);

// ── 优雅退出 ─────────────────────────────────────────────────────
process.on("SIGINT", () => {
  console.log("\n\n──────────── 最终报告 ────────────");
  console.log(`尝试建立连接 : ${stat.attempted}`);
  console.log(`最终在线连接 : ${stat.connected}`);
  console.log(`累计断开次数 : ${stat.closed}`);
  console.log(`连接/消息错误: ${stat.errors}`);
  console.log(`累计收到消息 : ${stat.messages}`);
  console.log(`首消息延迟   : P50=${percentile(stat.latencies, 50)}ms  P99=${percentile(stat.latencies, 99)}ms`);
  console.log("──────────────────────────────────\n");
  process.exit(0);
});