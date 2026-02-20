#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CONFIG = {
  chunkMs: 260,
  preRollMs: 640,
  minSpeechMs: 260,
  silenceMs: 760,
  maxTurnMs: 14000,
  loopTimeoutMs: 18000,
  vadRms: 120.0,
  postTurnGapMs: 140,
  refFrameMs: 20,
  refVadRms: 55.0,
  refSilenceMs: 620,
  refMinSpeechMs: 220,
  startClipWarnMs: 180,
  endClipWarnMs: 260,
};

function parseArgs(argv) {
  const args = {
    inputs: [],
    outdir: null,
    emitTurnWavs: true,
    maxTurnsPerFile: 200,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === "--input") {
      args.inputs.push(argv[++i]);
    } else if (token === "--outdir") {
      args.outdir = argv[++i];
    } else if (token === "--no-emit-turn-wavs") {
      args.emitTurnWavs = false;
    } else if (token === "--max-turns") {
      args.maxTurnsPerFile = Number(argv[++i]);
    } else if (token === "--max-turn-ms") {
      CONFIG.maxTurnMs = Number(argv[++i]);
    } else if (token === "--loop-timeout-ms") {
      CONFIG.loopTimeoutMs = Number(argv[++i]);
    } else if (token === "--chunk-ms") {
      CONFIG.chunkMs = Number(argv[++i]);
    } else if (token === "--pre-roll-ms") {
      CONFIG.preRollMs = Number(argv[++i]);
    } else if (token === "--min-speech-ms") {
      CONFIG.minSpeechMs = Number(argv[++i]);
    } else if (token === "--silence-ms") {
      CONFIG.silenceMs = Number(argv[++i]);
    } else if (token === "--post-turn-gap-ms") {
      CONFIG.postTurnGapMs = Number(argv[++i]);
    } else if (token === "--help" || token === "-h") {
      printHelp();
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${token}`);
    }
  }
  if (args.inputs.length === 0) {
    throw new Error("Missing --input (repeat per file or pass a directory)");
  }
  if (!args.outdir) {
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    args.outdir = path.resolve("debug-wavs", `stream-sim-${stamp}`);
  } else {
    args.outdir = path.resolve(args.outdir);
  }
  return args;
}

function printHelp() {
  console.log(
    [
      "simulate-stream-capture.mjs",
      "",
      "Usage:",
      "  node scripts/simulate-stream-capture.mjs --input <wav-or-dir> [--input <wav-or-dir> ...] [--outdir <dir>] [--no-emit-turn-wavs]",
      "  Optional overrides: --max-turn-ms --loop-timeout-ms --chunk-ms --pre-roll-ms --min-speech-ms --silence-ms --post-turn-gap-ms",
      "",
      "Behavior:",
      "  - Simulates SparkCallAssistantService captureUtteranceStateMachine() over PCM stream.",
      "  - Computes turn boundaries, clipping estimates, and per-file summary metrics.",
      "  - Emits summary JSON + markdown report.",
    ].join("\n"),
  );
}

function resolveInputs(entries) {
  const files = [];
  for (const entry of entries) {
    const full = path.resolve(entry);
    if (!fs.existsSync(full)) {
      throw new Error(`Input not found: ${full}`);
    }
    const stat = fs.statSync(full);
    if (stat.isDirectory()) {
      const listing = fs.readdirSync(full, { withFileTypes: true });
      for (const dirent of listing) {
        if (!dirent.isFile()) continue;
        if (!dirent.name.toLowerCase().endsWith(".wav")) continue;
        files.push(path.join(full, dirent.name));
      }
    } else if (stat.isFile()) {
      files.push(full);
    }
  }
  const deduped = [...new Set(files)].sort();
  if (deduped.length === 0) {
    throw new Error("No WAV files found from inputs");
  }
  return deduped;
}

function readWavPcm16Mono(wavPath) {
  const bytes = fs.readFileSync(wavPath);
  if (bytes.length < 44) {
    throw new Error(`Invalid WAV (too small): ${wavPath}`);
  }
  const riff = bytes.toString("ascii", 0, 4);
  const wave = bytes.toString("ascii", 8, 12);
  if (riff !== "RIFF" || wave !== "WAVE") {
    throw new Error(`Invalid WAV header: ${wavPath}`);
  }
  let offset = 12;
  let channels = 0;
  let sampleRate = 0;
  let bitsPerSample = 0;
  let audioFormat = 0;
  let dataOffset = -1;
  let dataSize = 0;

  while (offset + 8 <= bytes.length) {
    const chunkId = bytes.toString("ascii", offset, offset + 4);
    const chunkSize = bytes.readUInt32LE(offset + 4);
    const chunkData = offset + 8;
    if (chunkId === "fmt ") {
      audioFormat = bytes.readUInt16LE(chunkData);
      channels = bytes.readUInt16LE(chunkData + 2);
      sampleRate = bytes.readUInt32LE(chunkData + 4);
      bitsPerSample = bytes.readUInt16LE(chunkData + 14);
    } else if (chunkId === "data") {
      dataOffset = chunkData;
      dataSize = chunkSize;
      break;
    }
    offset = chunkData + chunkSize + (chunkSize % 2);
  }

  if (audioFormat !== 1) {
    throw new Error(`Unsupported WAV format in ${wavPath}; expected PCM16`);
  }
  if (channels !== 1) {
    throw new Error(`Unsupported channels in ${wavPath}; expected mono`);
  }
  if (bitsPerSample !== 16) {
    throw new Error(`Unsupported bit depth in ${wavPath}; expected 16-bit`);
  }
  if (dataOffset < 0 || dataOffset + dataSize > bytes.length) {
    throw new Error(`Invalid data chunk in ${wavPath}`);
  }
  const sampleCount = Math.floor(dataSize / 2);
  const samples = new Int16Array(sampleCount);
  for (let i = 0; i < sampleCount; i += 1) {
    samples[i] = bytes.readInt16LE(dataOffset + i * 2);
  }
  return { sampleRate, samples };
}

function writeWavPcm16Mono(outPath, sampleRate, samples) {
  const dataSize = samples.length * 2;
  const wav = Buffer.alloc(44 + dataSize);
  wav.write("RIFF", 0, "ascii");
  wav.writeUInt32LE(36 + dataSize, 4);
  wav.write("WAVE", 8, "ascii");
  wav.write("fmt ", 12, "ascii");
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36, "ascii");
  wav.writeUInt32LE(dataSize, 40);
  for (let i = 0; i < samples.length; i += 1) {
    wav.writeInt16LE(samples[i], 44 + i * 2);
  }
  fs.writeFileSync(outPath, wav);
}

function rmsPcm16(samples, start, end) {
  const count = end - start;
  if (count <= 0) return 0;
  let sum = 0;
  for (let i = start; i < end; i += 1) {
    const value = samples[i];
    sum += value * value;
  }
  return Math.sqrt(sum / count);
}

function appendAndTrim(existing, incoming, maxLen) {
  const merged = new Int16Array(existing.length + incoming.length);
  merged.set(existing, 0);
  merged.set(incoming, existing.length);
  if (maxLen > 0 && merged.length > maxLen) {
    return merged.slice(merged.length - maxLen);
  }
  return merged;
}

function detectReferenceUtterances(samples, sampleRate) {
  const frameSamples = Math.max(1, Math.floor((sampleRate * CONFIG.refFrameMs) / 1000));
  const silenceSamples = Math.floor((sampleRate * CONFIG.refSilenceMs) / 1000);
  const minSpeechSamples = Math.floor((sampleRate * CONFIG.refMinSpeechMs) / 1000);
  let speaking = false;
  let start = 0;
  let speech = 0;
  let silence = 0;
  const utterances = [];

  for (let offset = 0; offset < samples.length; offset += frameSamples) {
    const end = Math.min(samples.length, offset + frameSamples);
    const rms = rmsPcm16(samples, offset, end);
    const voiced = rms >= CONFIG.refVadRms;
    if (!speaking) {
      if (!voiced) continue;
      speaking = true;
      start = offset;
      speech = end - offset;
      silence = 0;
      continue;
    }
    if (voiced) {
      speech += end - offset;
      silence = 0;
    } else {
      silence += end - offset;
      if (silence >= silenceSamples) {
        const rawEnd = end - silence;
        if (speech >= minSpeechSamples && rawEnd > start) {
          utterances.push({ start, end: rawEnd, duration: rawEnd - start });
        }
        speaking = false;
        speech = 0;
        silence = 0;
      }
    }
  }
  if (speaking && speech >= minSpeechSamples) {
    utterances.push({ start, end: samples.length, duration: samples.length - start });
  }
  return utterances;
}

function captureTurn(samples, sampleRate, startIndex) {
  const preRollMax = Math.floor((sampleRate * CONFIG.preRollMs) / 1000);
  const minSpeechSamples = Math.floor((sampleRate * CONFIG.minSpeechMs) / 1000);
  const silenceLimit = Math.floor((sampleRate * CONFIG.silenceMs) / 1000);
  const maxTurnSamples = Math.floor((sampleRate * CONFIG.maxTurnMs) / 1000);
  const loopMaxSamples = Math.floor((sampleRate * CONFIG.loopTimeoutMs) / 1000);
  const chunkSamples = Math.max(1, Math.floor((sampleRate * CONFIG.chunkMs) / 1000));

  let pointer = startIndex;
  let loopSamples = 0;
  let speaking = false;
  let preRoll = new Int16Array(0);
  let current = new Int16Array(0);
  let speechSamples = 0;
  let silenceSamples = 0;
  let chunkCount = 0;
  let sourceStart = -1;
  let sourceEnd = -1;
  let endReason = "loop_timeout";

  while (pointer < samples.length && loopSamples < loopMaxSamples) {
    const chunkStart = pointer;
    const chunkEnd = Math.min(samples.length, pointer + chunkSamples);
    if (chunkEnd <= chunkStart) break;
    const chunk = samples.slice(chunkStart, chunkEnd);
    pointer = chunkEnd;
    loopSamples += chunk.length;
    const rms = rmsPcm16(samples, chunkStart, chunkEnd);
    const voiced = rms >= CONFIG.vadRms;
    let appendChunk = true;

    if (!speaking) {
      preRoll = appendAndTrim(preRoll, chunk, preRollMax);
      if (!voiced) continue;
      speaking = true;
      current = new Int16Array(0);
      if (preRoll.length > 0) {
        current = preRoll;
        appendChunk = false;
        sourceStart = Math.max(0, chunkEnd - preRoll.length);
        sourceEnd = chunkEnd;
      }
      speechSamples = chunk.length;
      silenceSamples = 0;
      if (sourceStart < 0) sourceStart = chunkStart;
      if (sourceEnd < 0) sourceEnd = chunkEnd;
    } else if (voiced) {
      speechSamples += chunk.length;
      silenceSamples = 0;
    } else {
      silenceSamples += chunk.length;
    }

    if (appendChunk) {
      const merged = new Int16Array(current.length + chunk.length);
      merged.set(current, 0);
      merged.set(chunk, current.length);
      current = merged;
      sourceEnd = chunkEnd;
    } else {
      sourceEnd = Math.max(sourceEnd, chunkEnd);
    }
    chunkCount += 1;

    const shouldFlushByMax = current.length >= maxTurnSamples;
    const shouldFlushBySilence = silenceSamples >= silenceLimit && speechSamples >= minSpeechSamples;
    if (shouldFlushByMax) {
      endReason = "max_turn";
      break;
    }
    if (shouldFlushBySilence) {
      endReason = "silence";
      break;
    }
  }

  if (!speaking || speechSamples < minSpeechSamples || current.length < 2) {
    return {
      captured: false,
      nextIndex: pointer,
      reason: "no_utterance",
    };
  }

  let out = current;
  if (out.length > maxTurnSamples) {
    out = out.slice(0, maxTurnSamples);
    sourceEnd = sourceStart + out.length;
    endReason = "max_turn";
  }

  if (pointer >= samples.length && endReason === "loop_timeout") {
    endReason = "eof";
  }

  return {
    captured: true,
    nextIndex: pointer,
    start: sourceStart,
    end: sourceEnd,
    endReason,
    speechSamples,
    chunkCount,
    samples: out,
  };
}

function overlap(aStart, aEnd, bStart, bEnd) {
  const lo = Math.max(aStart, bStart);
  const hi = Math.min(aEnd, bEnd);
  return Math.max(0, hi - lo);
}

function evaluateTurnAgainstReference(turn, references, sampleRate) {
  let best = null;
  let bestIndex = -1;
  let bestOverlap = 0;
  for (let index = 0; index < references.length; index += 1) {
    const ref = references[index];
    const ov = overlap(turn.start, turn.end, ref.start, ref.end);
    if (ov > bestOverlap) {
      best = ref;
      bestIndex = index;
      bestOverlap = ov;
    }
  }
  if (!best || bestOverlap === 0) {
    return {
      matched: false,
      referenceIndex: -1,
      overlapRatio: 0,
      startClipMs: 0,
      endClipMs: 0,
      warning: "no_reference_overlap",
    };
  }

  const overlapRatio = bestOverlap / Math.max(1, best.duration);
  const startClipMs = Math.max(0, ((turn.start - best.start) / sampleRate) * 1000);
  const endClipMs = turn.endReason === "max_turn"
    ? 0
    : Math.max(0, ((best.end - turn.end) / sampleRate) * 1000);

  const continuationWindowMs = 260;
  const priorTurnContinuation = turn.previousTurn &&
    turn.previousTurn.referenceIndex === bestIndex &&
    turn.previousTurn.endReason === "max_turn" &&
    Math.abs(turn.start - turn.previousTurn.end) <= Math.floor((sampleRate * continuationWindowMs) / 1000);

  const warnings = [];
  if (!priorTurnContinuation && startClipMs > CONFIG.startClipWarnMs) warnings.push("start_clip");
  if (endClipMs > CONFIG.endClipWarnMs) warnings.push("end_clip");
  if (!priorTurnContinuation && overlapRatio < 0.75 && turn.endReason !== "max_turn") warnings.push("low_overlap");

  return {
    matched: true,
    referenceIndex: bestIndex,
    overlapRatio,
    startClipMs: priorTurnContinuation ? 0 : startClipMs,
    endClipMs,
    warning: warnings.join(","),
  };
}

function simulateFile(wavPath, outDir, emitTurnWavs, maxTurnsPerFile) {
  const { sampleRate, samples } = readWavPcm16Mono(wavPath);
  const refs = detectReferenceUtterances(samples, sampleRate);
  const turns = [];
  let index = 0;
  const gapSamples = Math.floor((sampleRate * CONFIG.postTurnGapMs) / 1000);
  let previousTurnContext = null;

  for (let i = 0; i < maxTurnsPerFile && index < samples.length; i += 1) {
    const turn = captureTurn(samples, sampleRate, index);
    if (!turn.captured) {
      if (turn.nextIndex <= index) {
        index += Math.floor((sampleRate * CONFIG.chunkMs) / 1000);
      } else {
        index = turn.nextIndex;
      }
      continue;
    }
    const evalResult = evaluateTurnAgainstReference(
      { ...turn, previousTurn: previousTurnContext },
      refs,
      sampleRate,
    );
    const ms = (value) => Number(((value * 1000) / sampleRate).toFixed(1));
    const turnRecord = {
      index: turns.length,
      startMs: ms(turn.start),
      endMs: ms(turn.end),
      durationMs: ms(turn.end - turn.start),
      endReason: turn.endReason,
      chunkCount: turn.chunkCount,
      speechMs: ms(turn.speechSamples),
      overlapRatio: Number(evalResult.overlapRatio.toFixed(3)),
      startClipMs: Number(evalResult.startClipMs.toFixed(1)),
      endClipMs: Number(evalResult.endClipMs.toFixed(1)),
      warning: evalResult.warning || "",
      referenceIndex: evalResult.referenceIndex,
    };
    turns.push(turnRecord);
    previousTurnContext = turnRecord;

    if (emitTurnWavs) {
      const base = path.basename(wavPath, ".wav");
      const turnName = `${base}-turn-${String(turnRecord.index).padStart(3, "0")}-${turn.endReason}.wav`;
      writeWavPcm16Mono(path.join(outDir, turnName), sampleRate, turn.samples);
    }
    index = turn.nextIndex + gapSamples;
  }

  const clippedStart = turns.filter((t) => t.startClipMs > CONFIG.startClipWarnMs).length;
  const clippedEnd = turns.filter((t) => t.endClipMs > CONFIG.endClipWarnMs).length;
  const lowOverlap = turns.filter((t) => t.warning.includes("low_overlap")).length;
  const byReason = turns.reduce((acc, turn) => {
    acc[turn.endReason] = (acc[turn.endReason] || 0) + 1;
    return acc;
  }, {});

  return {
    file: wavPath,
    sampleRate,
    sourceDurationSec: Number((samples.length / sampleRate).toFixed(3)),
    referenceUtterances: refs.length,
    capturedTurns: turns.length,
    clippedStart,
    clippedEnd,
    lowOverlap,
    endReasons: byReason,
    turns,
  };
}

function writeReports(outDir, results) {
  const summary = {
    generatedAt: new Date().toISOString(),
    config: CONFIG,
    files: results,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));

  const lines = [];
  lines.push("# Stream Capture Simulation Report");
  lines.push("");
  lines.push("## Config");
  lines.push("");
  lines.push("```json");
  lines.push(JSON.stringify(CONFIG, null, 2));
  lines.push("```");
  lines.push("");

  for (const file of results) {
    lines.push(`## ${path.basename(file.file)}`);
    lines.push("");
    lines.push(`- sourceDurationSec: ${file.sourceDurationSec}`);
    lines.push(`- referenceUtterances: ${file.referenceUtterances}`);
    lines.push(`- capturedTurns: ${file.capturedTurns}`);
    lines.push(`- clippedStart: ${file.clippedStart}`);
    lines.push(`- clippedEnd: ${file.clippedEnd}`);
    lines.push(`- lowOverlap: ${file.lowOverlap}`);
    lines.push(`- endReasons: ${JSON.stringify(file.endReasons)}`);
    lines.push("");
    lines.push("| turn | startMs | endMs | durMs | reason | overlap | startClip | endClip | warning |");
    lines.push("|---:|---:|---:|---:|---|---:|---:|---:|---|");
    file.turns.slice(0, 40).forEach((turn) => {
      lines.push(
        `| ${turn.index} | ${turn.startMs} | ${turn.endMs} | ${turn.durationMs} | ${turn.endReason} | ${turn.overlapRatio} | ${turn.startClipMs} | ${turn.endClipMs} | ${turn.warning || ""} |`,
      );
    });
    if (file.turns.length > 40) {
      lines.push(`| ... | ... | ... | ... | ... | ... | ... | ... | ${file.turns.length - 40} more turns |`);
    }
    lines.push("");
  }

  fs.writeFileSync(path.join(outDir, "report.md"), lines.join("\n"));
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const inputs = resolveInputs(args.inputs);
  fs.mkdirSync(args.outdir, { recursive: true });
  const turnsDir = path.join(args.outdir, "turns");
  fs.mkdirSync(turnsDir, { recursive: true });

  const results = [];
  for (const wavPath of inputs) {
    const result = simulateFile(wavPath, turnsDir, args.emitTurnWavs, args.maxTurnsPerFile);
    results.push(result);
  }
  writeReports(args.outdir, results);

  console.log(`Simulation complete: ${args.outdir}`);
  for (const file of results) {
    console.log(
      `- ${path.basename(file.file)} turns=${file.capturedTurns} refs=${file.referenceUtterances} startClip=${file.clippedStart} endClip=${file.clippedEnd} lowOverlap=${file.lowOverlap}`,
    );
  }
}

main();
