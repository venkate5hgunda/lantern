import { execFile, spawn } from "node:child_process";
import { createServer } from "node:http";
import { createSocket } from "node:dgram";
import { createRequire } from "node:module";
import { parseArgs, promisify } from "node:util";

const require = createRequire(new URL("../web/package.json", import.meta.url));
const { chromium } = require("@playwright/test");
const execute = promisify(execFile);
const { values } = parseArgs({ options: {
  serial: { type: "string" },
  adb: { type: "string", default: "adb" },
  "adb-port": { type: "string", default: "5037" },
  "emulator-udp": { type: "boolean", default: false },
} });
if (!values.serial || !/^[A-Za-z0-9:_.-]{1,100}$/.test(values.serial)) {
  throw new Error("Provide --serial for a dedicated emulator or test phone.");
}
const port = Number(values["adb-port"]);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid ADB port.");
const base = ["-P", String(port), "-s", values.serial];
if (values["emulator-udp"] && !/^emulator-\d+$/.test(values.serial)) {
  throw new Error("--emulator-udp is only for an explicitly selected Android emulator.");
}
const forwards = [];
const app = "dev.lantern.peer";
const offerPath = "files/lantern-interop-offer.json";
const answerPath = "files/lantern-interop-answer.json";
const stopPath = "files/lantern-interop-stop";
let stage = "initializing";
let instrument;
let instrumentResult;
let instrumentOutput = "";
let browser;
let page;
let server;
let started = false;
let nativeFailure = "";
let failureName = "";
let mediaDiagnostics;

async function adb(...args) {
  return execute(values.adb, [...base, ...args], { maxBuffer: 131_072, timeout: 120_000 });
}

async function writeOffer(payload) {
  const child = spawn(values.adb, [...base, "shell", "-T",
    `run-as ${app} sh -c 'cat > ${offerPath}'`], { stdio: ["pipe", "ignore", "pipe"], timeout: 15_000 });
  let failure = "";
  child.stderr.on("data", (chunk) => { failure += chunk; });
  const completion = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.stdin.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolve() : reject(new Error("Private fixture transfer failed")));
  });
  child.stdin.end(JSON.stringify(payload));
  await completion;
  if (failure) throw new Error("Private fixture transfer reported an error.");
}

try {
  stage = "checking dedicated device";
  await adb("shell", "run-as", app, "ls", "files");
  await adb("shell", "run-as", app, "rm", "-f", offerPath, answerPath, stopPath);
  server = createServer((_, response) => response.end(
    "<!doctype html><title>Native camera interop</title><video autoplay muted playsinline></video>",
  ));
  await new Promise((resolve, reject) => {
    server.once("error", reject); server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Local fixture did not bind.");
  // Emulator NAT does not forward mDNS. This is a lab setting, not a viewer configuration.
  browser = await chromium.launch({ args: ["--disable-features=WebRtcHideLocalIpsWithMdns"] });
  page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${address.port}`);
  stage = "creating browser offer";
  const offer = await page.evaluate(async () => {
    const peer = new RTCPeerConnection({ iceServers: [] });
    window.peer = peer;
    peer.ontrack = (event) => {
      const video = document.querySelector("video");
      video.srcObject = new MediaStream([event.track]);
      void video.play();
    };
    peer.addTransceiver("video", { direction: "recvonly" });
    const complete = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("ICE gathering deadline")), 10_000);
      peer.onicegatheringstatechange = () => {
        if (peer.iceGatheringState === "complete") { clearTimeout(timeout); resolve(); }
      };
    });
    await peer.setLocalDescription(await peer.createOffer());
    await complete;
    return peer.localDescription.toJSON();
  });
  if (!offer.sdp || !/a=candidate:[^\r\n]+ \d+\.\d+\.\d+\.\d+ \d+ typ host/.test(offer.sdp)) {
    throw new Error("Emulator interop requires an actual raw IPv4 host candidate.");
  }
  await writeOffer({ offer, audio: false });
  stage = "starting native camera fixture";
  instrument = spawn(values.adb, [...base, "shell", "am", "instrument", "-w",
    "-e", "class", "dev.lantern.peer.BrowserInteropHarness",
    "-e", "lantern.interop", "1",
    `${app}.test/androidx.test.runner.AndroidJUnitRunner`], { stdio: ["ignore", "pipe", "pipe"], timeout: 120_000 });
  instrument.stdout.on("data", (chunk) => { instrumentOutput = (instrumentOutput + chunk).slice(-131_072); });
  instrument.stderr.on("data", (chunk) => { instrumentOutput = (instrumentOutput + chunk).slice(-131_072); });
  instrumentResult = new Promise((resolve, reject) => {
    instrument.once("error", reject);
    instrument.once("exit", (code) => resolve(code));
  });
  started = true;
  stage = "awaiting native answer";
  let response;
  const deadline = Date.now() + 35_000;
  while (Date.now() < deadline) {
    const result = await adb("shell", "-T",
      `run-as ${app} sh -c 'if test -f ${answerPath}; then cat ${answerPath}; else exit 3; fi'`,
    ).catch((error) => {
      if (error.code === 3) return null;
      throw error;
    });
    if (result) { response = JSON.parse(result.stdout); break; }
    if (instrument.exitCode !== null) throw new Error("Native fixture terminated before answering.");
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  if (response?.error || response?.answer?.type !== "answer" || typeof response.answer.sdp !== "string") {
    if (typeof response?.error === "string" && /^[a-z_]{1,80}$/.test(response.error)) nativeFailure = response.error;
    throw new Error("Native fixture did not supply a usable answer.");
  }
  mediaDiagnostics = {
    activeVideo: /^m=video [1-9][0-9]* /m.test(response.answer.sdp),
    answerCandidates: (response.answer.sdp.match(/^a=candidate:/gm) ?? []).length,
    sendsVideo: /^a=send(?:only|recv)$/m.test(response.answer.sdp),
  };
  stage = "decoding native camera";
  if (values["emulator-udp"]) {
    // Real emulator-console NAT mappings, never a deployed relay or fabricated media.
    const lines = response.answer.sdp.split(/\r?\n/);
    for (const line of lines) {
      const candidate = line.match(/^a=candidate:(\S+) (\d+) udp (\d+) 10\.0\.2\.15 (\d+) typ host\b/i);
      if (!candidate) continue;
      const reservation = createSocket("udp4");
      await new Promise((resolve, reject) => {
        reservation.once("error", reject); reservation.bind(0, "127.0.0.1", resolve);
      });
      const hostPort = reservation.address().port;
      await new Promise((resolve) => reservation.close(resolve));
      const mapping = await adb("emu", "redir", "add", `udp:${hostPort}:${candidate[4]}`);
      if (!/\bOK\b/.test(mapping.stdout) || /\bKO\b/.test(mapping.stdout)) {
        throw new Error("Emulator UDP mapping unavailable.");
      }
      forwards.push(hostPort);
      const mapped = `a=candidate:${candidate[1]}nat ${candidate[2]} udp ${candidate[3]} 127.0.0.1 ${hostPort} typ srflx raddr 10.0.2.15 rport ${candidate[4]}`;
      response.answer.sdp = response.answer.sdp.replace(line, `${line}\r\n${mapped}`);
    }
    if (!forwards.length) throw new Error("No emulator Ethernet UDP candidate to map.");
  }
  await page.evaluate((answer) => window.peer.setRemoteDescription(answer), response.answer);
  let frame;
  const decodeDeadline = Date.now() + 45_000;
  while (Date.now() < decodeDeadline) {
    const sample = await page.evaluate(async () => {
      const video = document.querySelector("video");
      const inbound = Array.from((await window.peer.getStats()).values()).filter((row) =>
        row.type === "inbound-rtp" && row.kind === "video");
      return { connected: window.peer.connectionState === "connected",
        width: video.videoWidth, height: video.videoHeight,
        decodedFrames: inbound.reduce((count, row) => count + (row.framesDecoded ?? 0), 0) };
    });
    if (sample.connected && sample.width > 0 && sample.height > 0 && sample.decodedFrames >= 60) {
      const { connected, ...decoded } = sample;
      frame = decoded;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  if (!frame) throw new Error("Native video did not reach 60 decoded frames before the deadline.");
  await adb("shell", "run-as", app, "touch", stopPath);
  const exit = await instrumentResult;
  if (exit !== 0 || !/OK \(1 test\)/.test(instrumentOutput) || /FAILURES|INSTRUMENTATION_FAILED/.test(instrumentOutput)) {
    throw new Error("Native fixture did not finish cleanly.");
  }
  console.log(JSON.stringify({ nativeCameraInterop: "passed", emulatorUdpMapping: values["emulator-udp"],
    browser: browser.version(), ...frame }));
} catch (error) {
  failureName = error.name;
  if (page && !page.isClosed() && stage === "decoding native camera") {
    mediaDiagnostics = { ...mediaDiagnostics, ...await page.evaluate(async () => {
      const stats = Array.from((await window.peer.getStats()).values());
      return {
        connection: window.peer.connectionState, ice: window.peer.iceConnectionState,
        candidates: stats.filter((row) => row.type === "remote-candidate").map((row) => ({
          protocol: row.protocol, type: row.candidateType, loopback: row.address === "127.0.0.1",
        })),
        pairs: stats.filter((row) => row.type === "candidate-pair").map((row) => ({
          state: row.state, nominated: row.nominated, requestsSent: row.requestsSent,
          requestsReceived: row.requestsReceived, responsesReceived: row.responsesReceived,
        })),
        video: stats.filter((row) => row.type === "inbound-rtp" && row.kind === "video").map((row) => ({
          bytesReceived: row.bytesReceived, framesDecoded: row.framesDecoded,
        })),
      };
    }) };
  }
  process.exitCode = 1;
} finally {
  if (started && instrument.exitCode === null) {
    await adb("shell", "run-as", app, "touch", stopPath);
    await instrumentResult;
  }
  if (page && !page.isClosed()) await page.evaluate(() => window.peer?.close());
  await browser?.close();
  for (const hostPort of forwards) {
    const result = await adb("emu", "redir", "del", `udp:${hostPort}`);
    if (!/\bOK\b/.test(result.stdout) || /\bKO\b/.test(result.stdout)) {
      throw new Error("Owned emulator UDP mapping cleanup failed.");
    }
  }
  if (server) await new Promise((resolve) => server.close(resolve));
  await adb("shell", "run-as", app, "rm", "-f", offerPath, answerPath, stopPath);
}
if (failureName) {
  nativeFailure ||= instrumentOutput.match(/Interop harness failed: ([a-z_]+)/)?.[1] ?? "";
  const summary = instrumentOutput.match(/(?:Error in |OK \(|FAILURES|shortMsg=)[^\r\n]*/g)?.join("; ") ?? "no runner summary";
  console.error(`Native camera interoperability failed during ${stage} (${failureName})${nativeFailure ? `: ${nativeFailure}` : ""}. ${summary}`);
  if (mediaDiagnostics) console.error(JSON.stringify(mediaDiagnostics));
  const transport = instrumentOutput.split(/\r?\n/)
    .findLast((line) => line.startsWith("INSTRUMENTATION_STATUS: lantern.transport="));
  if (transport) console.error(transport);
}
