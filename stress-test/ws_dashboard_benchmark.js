const WebSocket = require("ws");

function parseArgs() {
  const args = process.argv.slice(2);
  const get = (flag, def) => {
    const i = args.indexOf(flag);
    return i !== -1 && args[i + 1] ? args[i + 1] : def;
  };
  return {
    host: get("--host", "ws://127.0.0.1:8080"),
    path: get("--path", "/ws/dashboard"),
    connections: parseInt(get("--connections", "1000"), 10),
    rampMs: parseInt(get("--ramp-ms", "2"), 10),
    durationSec: parseInt(get("--duration", "60"), 10),
    handshakeTimeout: parseInt(get("--handshake-timeout", "10000"), 10),
    reportIntervalMs: parseInt(get("--report-interval-ms", "5000"), 10)
  };
}

const cfg = parseArgs();
const wsUrl = `${cfg.host}${cfg.path}`;
const clients = new Set();

const stat = {
  attempted: 0,
  opened: 0,
  online: 0,
  firstMessages: 0,
  closed: 0,
  errors: 0,
  messages: 0,
  openLatencies: [],
  firstFromOpenLatencies: [],
  firstFromCreateLatencies: []
};

let lastReportAt = Date.now();
let lastReportMessages = 0;
let rampDone = false;
let stopTimer = null;

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[index];
}

function formatLatency(values) {
  return `P50=${percentile(values, 50)}ms P95=${percentile(values, 95)}ms P99=${percentile(values, 99)}ms`;
}

function createClient(index) {
  const createAt = Date.now();
  let openAt = 0;
  let receivedFirst = false;

  stat.attempted++;
  const ws = new WebSocket(wsUrl, {
    handshakeTimeout: cfg.handshakeTimeout
  });
  clients.add(ws);

  ws.on("open", () => {
    openAt = Date.now();
    stat.opened++;
    stat.online++;
    stat.openLatencies.push(openAt - createAt);
  });

  ws.on("message", () => {
    stat.messages++;
    if (!receivedFirst) {
      receivedFirst = true;
      stat.firstMessages++;
      const firstAt = Date.now();
      stat.firstFromCreateLatencies.push(firstAt - createAt);
      if (openAt > 0) {
        stat.firstFromOpenLatencies.push(firstAt - openAt);
      }
    }
  });

  ws.on("error", (error) => {
    stat.errors++;
    if (stat.errors <= 10) {
      console.error(`[错误] #${index}: ${error.message}`);
    }
  });

  ws.on("close", () => {
    clients.delete(ws);
    stat.online = Math.max(0, stat.online - 1);
    stat.closed++;
  });
}

function report(label = "状态") {
  const now = Date.now();
  const elapsedSec = Math.max(0.001, (now - lastReportAt) / 1000);
  const deltaMessages = stat.messages - lastReportMessages;
  const msgPerSec = deltaMessages / elapsedSec;
  lastReportAt = now;
  lastReportMessages = stat.messages;

  console.log(
    `[${label}] attempted=${stat.attempted}/${cfg.connections} ` +
    `opened=${stat.opened} online=${stat.online} first=${stat.firstMessages} ` +
    `closed=${stat.closed} errors=${stat.errors} messages=${stat.messages} ` +
    `msg/s=${msgPerSec.toFixed(1)}`
  );
  console.log(`       open latency         ${formatLatency(stat.openLatencies)}`);
  console.log(`       first from open      ${formatLatency(stat.firstFromOpenLatencies)}`);
  console.log(`       first from create    ${formatLatency(stat.firstFromCreateLatencies)}`);
}

function finish() {
  console.log("\n──────────── WebSocket Benchmark 最终报告 ────────────");
  report("最终");
  console.log(`目标: ${wsUrl}`);
  console.log(`连接数: ${cfg.connections}, rampMs=${cfg.rampMs}, duration=${cfg.durationSec}s`);
  console.log("说明: first from open 更接近服务端首包延迟；first from create 包含客户端建连排队/握手时间。");
  console.log("────────────────────────────────────────────────────\n");

  for (const ws of clients) {
    try {
      ws.close();
    } catch (_) {
    }
  }
  setTimeout(() => process.exit(stat.errors > 0 ? 1 : 0), 300);
}

console.log("\n开始 WebSocket 并发基准测试");
console.log(`目标: ${wsUrl}`);
console.log(`连接数: ${cfg.connections}`);
console.log(`建连间隔: ${cfg.rampMs}ms`);
console.log(`稳定观测: ${cfg.durationSec}s\n`);

let current = 0;
const rampTimer = setInterval(() => {
  if (current >= cfg.connections) {
    clearInterval(rampTimer);
    rampDone = true;
    console.log("\n[就绪] 所有连接已发起，进入稳定观测...\n");
    stopTimer = setTimeout(finish, cfg.durationSec * 1000);
    return;
  }
  createClient(current);
  current++;
}, Math.max(0, cfg.rampMs));

const reportTimer = setInterval(() => {
  report(rampDone ? "观测" : "建连");
}, cfg.reportIntervalMs);

process.on("SIGINT", () => {
  clearInterval(rampTimer);
  clearInterval(reportTimer);
  if (stopTimer) clearTimeout(stopTimer);
  finish();
});
