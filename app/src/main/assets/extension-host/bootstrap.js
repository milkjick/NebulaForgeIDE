/*
 * NebulaForge 扩展宿主（Node 侧引导程序）
 * ---------------------------------------------------------------------------
 * 「让 VSIX 里的 JS 逻辑真正跑起来」的运行时：VS Code 的扩展是 Node 程序，
 * 宿主必须提供 `require('vscode')` 模块 + 一条回宿主的 RPC 通道。本文件就是这两件事。
 *
 * 协议：Content-Length 分帧 JSON-RPC（与内核 LspClient 同一套，已在真机验证）。
 *   宿主 → 本进程：initialize / activate / executeCommand / provideCompletionItems（请求）
 *                 documentOpened / documentChanged / documentSaved / documentClosed（通知）
 *   本进程 → 宿主：window.show* / commands.executeCommand / workspace.* / editor.*（请求）
 *                 registerCommand / registerCompletionProvider / log / output / statusBar（通知）
 *
 * 为什么必须接管 console.*：stdout 是协议通道，扩展里一句 console.log 就会冲烂帧，
 * 表现为「插件一激活宿主就再也收不到回应」。真实 VS Code 用独立 IPC，我们转发成日志通知。
 *
 * 用法：node bootstrap.js <扩展目录>
 */

'use strict';

const fs = require('fs');
const path = require('path');

const extensionDir = process.argv[2];
if (!extensionDir) {
  process.stderr.write('bootstrap.js: 缺少扩展目录参数\n');
  process.exit(2);
}

// ==== 协议层 =================================================================

let nextId = 1;
const pendingOutgoing = new Map();   // 我方发起的请求：id -> {resolve, reject}
const incomingHandlers = new Map();  // 宿主发起的请求：method -> handler
let stdoutBroken = false;

function writeMessage(msg) {
  if (stdoutBroken) return;
  const body = Buffer.from(JSON.stringify(msg), 'utf8');
  const head = Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii');
  try {
    process.stdout.write(head);
    process.stdout.write(body);
  } catch (err) {
    stdoutBroken = true;
  }
}

function requestToHost(method, params) {
  const id = nextId++;
  traceApi('request', method);
  return new Promise((resolve, reject) => {
    pendingOutgoing.set(id, { resolve, reject });
    writeMessage({ jsonrpc: '2.0', id, method, params });
  });
}

function notifyHost(method, params) {
  traceApi('notify', method);
  writeMessage({ jsonrpc: '2.0', method, params });
}

// ==== 诊断：最近一次「扩展 → 宿主」的调用 =====================================
// 扩展抛出的**不一定**是 Error（vscode-go 在缺工具链时抛的就是 undefined），
// 只打印 err 会得到一句没用的 "undefined"。留一条最近的 API 轨迹，
// 激活失败时才能指出「死在哪个 API 上」。
const apiTrace = [];

function traceApi(dir, method) {
  apiTrace.push(dir + ':' + method);
  if (apiTrace.length > 32) apiTrace.shift();
}

function apiTraceText(count) {
  return apiTrace.slice(-(count || 10)).join(' <- ') || '(无)';
}

/**
 * 把任意抛出值描述成可读文本。
 *
 * 为什么不能只用 `String(err)`：`String(undefined)` 就是 `undefined`，
 * 而真实扩展（尤其是语言客户端）会用 `Promise.reject()` / `throw undefined`
 * 表达「静默失败」。宿主侧必须还原出「抛了非 Error 值」这一事实，
 * 否则用户和日志都只看到一个毫无信息量的 `undefined`。
 */
function errText(err) {
  if (err === undefined) {
    // 最常见的成因：扩展用无参的 `Promise.reject()` 表示「静默失败」，
    // 例如 vscode-go 的 `updateGoVarsFromConfig()` 在找不到 `go` 可执行文件时
    // 直接 `return Promise.reject()`，await 之后就变成裸抛 undefined。
    return 'undefined（扩展抛出了非 Error 值：多为扩展内部无参 Promise.reject()/throw，'
      + '常见于找不到外部工具链，如 go / gopls / rust-analyzer）';
  }
  if (err === null) return '（扩展抛出了 null）';
  if (err instanceof Error) return err.stack || (err.name + ': ' + err.message);
  if (typeof err === 'string') return err;
  if (typeof err === 'number' || typeof err === 'boolean') return String(err) + '（非 Error 值 ' + typeof err + '）';
  if (typeof err === 'object') {
    if (typeof err.stack === 'string' && err.stack) return err.stack;
    const ctor = (err.constructor && err.constructor.name) || 'Object';
    return '[' + ctor + '] ' + safeStringify(err);
  }
  return String(err) + '（非 Error 值 ' + typeof err + '）';
}

function onRequest(method, handler) {
  incomingHandlers.set(method, handler);
}

function safeStringify(value) {
  try { return JSON.stringify(value); } catch (err) { return String(value); }
}

let rxBuffer = Buffer.alloc(0);

function pumpIncoming(chunk) {
  rxBuffer = Buffer.concat([rxBuffer, chunk]);
  for (;;) {
    const sep = rxBuffer.indexOf('\r\n\r\n');
    if (sep < 0) {
      // 头部异常膨胀 = 流已错位：截断重来，别把内存吃光。
      if (rxBuffer.length > (1 << 20)) rxBuffer = Buffer.alloc(0);
      return;
    }
    const header = rxBuffer.slice(0, sep).toString('ascii');
    const match = /content-length:\s*(\d+)/i.exec(header);
    if (!match) { rxBuffer = rxBuffer.slice(sep + 4); continue; }
    const len = parseInt(match[1], 10);
    const bodyStart = sep + 4;
    if (rxBuffer.length < bodyStart + len) return;
    const body = rxBuffer.slice(bodyStart, bodyStart + len).toString('utf8');
    rxBuffer = rxBuffer.slice(bodyStart + len);
    let msg;
    try {
      msg = JSON.parse(body);
    } catch (err) {
      notifyHost('log', { level: 'error', text: '协议帧不是合法 JSON: ' + err.message });
      continue;
    }
    dispatch(msg);
  }
}

/**
 * 把宿主返回的 JSON-RPC 错误还原成 JS Error，并**保留 code**。
 *
 * 为什么必须保留：扩展普遍靠 `err.code === 'FileNotFound'` / `err instanceof FileSystemError`
 * 做分支（rust-analyzer 找语言服务器、Git 扩展判断仓库是否存在都这样）。
 * 早先一律 `new Error(message)`，扩展就会走进「文件存在」的错分支，
 * 表现为后续行为莫名其妙（或者干脆卡住），而不是一个清晰的错误。
 */
function makeTransportError(errorPayload) {
  const data = errorPayload && errorPayload.data;
  const code = (data && (data.code || data.errorCode)) || errorPayload.code;
  const message = (errorPayload && errorPayload.message) || JSON.stringify(errorPayload);
  if (typeof code === 'string' && /^(FileNotFound|FileExists|NoPermissions|FileIsADirectory|FileNotADirectory|Unavailable)$/.test(code)) {
    const fsErr = new FileSystemError(message);
    fsErr.code = code;
    return fsErr;
  }
  const err = new Error(message);
  if (code !== undefined && code !== null) err.code = code;
  return err;
}

function dispatch(msg) {
  // 响应：有 id 无 method
  if (msg.id !== undefined && msg.method === undefined) {
    const slot = pendingOutgoing.get(msg.id);
    if (!slot) return;
    pendingOutgoing.delete(msg.id);
    if (msg.error) slot.reject(makeTransportError(msg.error));
    else slot.resolve(msg.result);
    return;
  }
  const handler = incomingHandlers.get(msg.method);
  if (!handler) {
    if (msg.id !== undefined) {
      writeMessage({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: '未实现的方法: ' + msg.method } });
    }
    return;
  }
  Promise.resolve()
    .then(() => handler(msg.params || {}))
    .then((result) => {
      if (msg.id !== undefined) writeMessage({ jsonrpc: '2.0', id: msg.id, result: result === undefined ? null : result });
    })
    .catch((err) => {
      notifyHost('log', { level: 'error', text: String((err && err.stack) || err) });
      if (msg.id !== undefined) {
        writeMessage({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: String((err && err.message) || err) } });
      }
    });
}

process.stdin.on('data', pumpIncoming);
process.stdin.resume();
// 宿主关掉管道（IDE 退出 / 重启宿主）即视作没人要这个宿主了：立刻退出。
// 真机教训：不做这件事会在设备上留下孤儿 node 进程（上次装机残留了 6 个），
// 它们持有旧的 pty/管道，只等重启才能清掉。
function exitWhenOrphaned() {
  setTimeout(() => process.exit(0), 20);
}
process.stdin.on('end', exitWhenOrphaned);
process.stdin.on('close', exitWhenOrphaned);

// 协议通道专属：console.* 一律转日志通知。
const consoleSink = { log: 'info', info: 'info', warn: 'warn', error: 'error', debug: 'debug', trace: 'debug' };
for (const name of Object.keys(consoleSink)) {
  console[name] = (...args) => {
    const text = args.map((a) => (typeof a === 'string' ? a : safeStringify(a))).join(' ');
    notifyHost('log', { level: consoleSink[name], text });
  };
}

// ==== 文档缓存（宿主推来的真实编辑状态）======================================

const documents = new Map(); // uri -> { text, languageId, version }

function uriToPath(uri) {
  if (typeof uri !== 'string') return String(uri);
  if (uri.startsWith('file://')) {
    try { return decodeURIComponent(new URL(uri).pathname); } catch (err) { return uri.slice(7); }
  }
  return uri;
}

// ==== vscode API 子集 ========================================================

class Position {
  constructor(line, character) { this.line = line; this.character = character; }
  isBefore(other) { return this.line < other.line || (this.line === other.line && this.character < other.character); }
  isEqual(other) { return this.line === other.line && this.character === other.character; }
  translate(lineDelta = 0, charDelta = 0) { return new Position(this.line + lineDelta, this.character + charDelta); }
  with(line = this.line, character = this.character) { return new Position(line, character); }
  compareTo(other) {
    if (this.line !== other.line) return this.line - other.line;
    return this.character - other.character;
  }
}

class Range {
  constructor(startOrStartLine, startCharOrEnd, endLine, endChar) {
    if (typeof startOrStartLine === 'number') {
      this.start = new Position(startOrStartLine, startCharOrEnd);
      this.end = new Position(endLine, endChar);
    } else {
      this.start = startOrStartLine;
      this.end = startCharOrEnd;
    }
  }
  get isEmpty() { return this.start.isEqual(this.end); }
  get isSingleLine() { return this.start.line === this.end.line; }
  contains(position) { return !position.isBefore(this.start) && !this.end.isBefore(position); }
  isEqual(other) { return this.start.isEqual(other.start) && this.end.isEqual(other.end); }
}

class Selection extends Range {
  constructor(anchor, active) { super(anchor, active); this.anchor = anchor; this.active = active; }
  get isReversed() { return this.anchor.isBefore(this.active); }
}

class Uri {
  constructor(scheme, authority, docPath, query, fragment) {
    this.scheme = scheme;
    this.authority = authority;
    this.path = docPath;
    this.query = query || '';
    this.fragment = fragment || '';
  }
  static file(p) { return new Uri('file', '', p, '', ''); }
  // 官方签名：{scheme, authority?, path?, query?, fragment?} + strict?。
  static from(components, strict) {
    const spec = components || {};
    if (strict && !spec.scheme) {
      throw new Error('Uri.from: strict 模式下缺少 scheme');
    }
    return new Uri(
      spec.scheme === undefined ? 'file' : spec.scheme,
      spec.authority === undefined ? '' : spec.authority,
      spec.path === undefined ? '' : spec.path,
      spec.query === undefined ? '' : spec.query,
      spec.fragment === undefined ? '' : spec.fragment
    );
  }
  static parse(value) {
    if (typeof value !== 'string') return new Uri('file', '', String(value), '', '');
    if (value.startsWith('file://')) {
      const u = new URL(value);
      return new Uri('file', u.host, decodeURIComponent(u.pathname), u.search.slice(1), u.hash.slice(1));
    }
    const idx = value.indexOf(':');
    return new Uri(idx > 0 ? value.slice(0, idx) : 'file', '', idx > 0 ? value.slice(idx + 1) : value, '', '');
  }
  static joinPath(base, ...parts) {
    let joined = base.path;
    for (const part of parts) joined = joined.replace(/\/$/, '') + '/' + String(part).replace(/^\//, '');
    return new Uri(base.scheme, base.authority, joined, '', '');
  }
  get fsPath() { return uriToPath(this.toString()); }
  with(change) {
    return new Uri(
      change.scheme === undefined ? this.scheme : change.scheme,
      change.authority === undefined ? this.authority : change.authority,
      change.path === undefined ? this.path : change.path,
      change.query === undefined ? this.query : change.query,
      change.fragment === undefined ? this.fragment : change.fragment
    );
  }
  toString() {
    if (this.scheme === 'file') return 'file://' + encodeURI(this.path).replace(/#/g, '%23');
    return this.scheme + '://' + this.authority + this.path;
  }
  toJSON() { return { scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment }; }
}

class EventEmitter {
  constructor() { this.listeners = []; }
  get event() {
    return (listener, thisArg) => {
      const entry = { listener, thisArg };
      this.listeners.push(entry);
      return { dispose: () => { const i = this.listeners.indexOf(entry); if (i >= 0) this.listeners.splice(i, 1); } };
    };
  }
  fire(value) {
    for (const entry of this.listeners.slice()) {
      try { entry.listener.call(entry.thisArg, value); }
      catch (err) { notifyHost('log', { level: 'error', text: '事件监听器抛错: ' + String((err && err.stack) || err) }); }
    }
  }
  dispose() { this.listeners.length = 0; }
}

class Disposable {
  constructor(fn) { this.fn = fn; }
  dispose() { if (this.fn) { try { this.fn(); } catch (err) { /* 忽略 */ } this.fn = null; } }
  static from(...items) { return new Disposable(() => items.forEach((i) => i && i.dispose && i.dispose())); }
}

class CancellationTokenSource {
  constructor() { this.token = { isCancellationRequested: false, onCancellationRequested: () => ({ dispose() { } }) }; }
  cancel() { this.token.isCancellationRequested = true; }
  dispose() { }
}

class RelativePattern {
  constructor(base, pattern) { this.base = base; this.pattern = typeof pattern === 'string' ? pattern : pattern.pattern; }
}

class CompletionItem {
  constructor(label, kind) {
    this.label = label;
    this.kind = kind === undefined ? CompletionItemKind.Text : kind;
    this.detail = undefined;
    this.documentation = undefined;
    this.insertText = undefined;
    this.filterText = undefined;
    this.sortText = undefined;
    this.range = undefined;
    this.commitCharacters = undefined;
    this.preselect = undefined;
    this.additionalTextEdits = undefined;
  }
}

const CompletionItemKind = {
  Text: 0, Method: 1, Function: 2, Constructor: 3, Field: 4, Variable: 5, Class: 6,
  Interface: 7, Module: 8, Property: 9, Unit: 10, Value: 11, Enum: 12, Keyword: 13,
  Snippet: 14, Color: 15, File: 16, Reference: 17, Folder: 18, EnumMember: 19,
  Constant: 20, Struct: 21, Event: 22, Operator: 23, TypeParameter: 24, User: 25, Issue: 26,
};

const CompletionTriggerKind = { Invoke: 0, TriggerCharacter: 1, TriggerForIncompleteCompletions: 2 };

const DiagnosticSeverity = { Error: 0, Warning: 1, Information: 2, Hint: 3 };

/**
 * 命名空间里「扩展会 new 出来」的类。
 *
 * 为什么必须补全这一批：官方 API 里这些类型是**构造函数**，扩展代码里到处都是
 * `new vscode.Diagnostic(range, msg, vscode.DiagnosticSeverity.Warning)` 这类写法；
 * 缺一个就直接抛 `TypeError: vscode.X is not a constructor`，整个命令/激活流程崩在运行时，
 * 而宿主侧只看到「命令执行失败」，排查成本极高。
 * 语义要求：凡是宿主真的会解析的字段（Diagnostic.range → 行列、Location.uri 等）必须真实结构化，
 * 其余纯数据类保持「构造即赋值」，不做假行为。
 */
class Diagnostic {
  constructor(range, message, severity = DiagnosticSeverity.Error) {
    this.range = range;
    this.message = String(message);
    this.severity = severity;
    this.source = undefined;
    this.code = undefined;
    this.relatedInformation = undefined;
    this.tags = undefined;
  }
}

class DiagnosticRelatedInformation {
  constructor(location, message) { this.location = location; this.message = String(message); }
}

class Location {
  constructor(uri, rangeOrPosition) {
    this.uri = uri;
    this.range = rangeOrPosition instanceof Range
      ? rangeOrPosition
      : new Range(rangeOrPosition, rangeOrPosition);
  }
}

class TextEdit {
  constructor(range, newText) {
    this.range = range;
    this.newText = String(newText);
    // 官方把 newEol 暴露为可选实例属性，有些扩展会读回来做 diff。
    this.newEol = undefined;
  }
  static replace(range, newText) { return new TextEdit(range, newText); }
  static insert(position, newText) { return new TextEdit(new Range(position, position), newText); }
  static delete(range) { return new TextEdit(range, ''); }
  static setEndOfLine(endOfLine) {
    const edit = new TextEdit(new Range(0, 0, 0, 0), '');
    edit.newEol = endOfLine;
    return edit;
  }
}

class WorkspaceEdit {
  constructor() { this._edits = new Map(); this._fileOps = []; }
  get size() { return Array.from(this._edits.values()).reduce((n, list) => n + list.length, 0); }
  get edits() {
    return Array.from(this._edits.entries()).map(([uri, edits]) => ({ uri: Uri.parse(uri), edits }));
  }
  _push(uri, edit) {
    const key = uri.toString();
    this._edits.set(key, (this._edits.get(key) || []).concat([edit]));
  }
  replace(uri, range, newText) { this._push(uri, new TextEdit(range, newText)); }
  insert(uri, position, newText) { this._push(uri, TextEdit.insert(position, newText)); }
  delete(uri, range) { this._push(uri, TextEdit.delete(range)); }
  // 文件级操作（createFile/deleteFile/renameFile）以前是空操作，
  // 导致靠 WorkspaceEdit 创建/删除/重命名文件的插件（e.g. 模板生成、重构）静默失败。
  // 现在记录下来，由 applyEdit 一并交给宿主执行。
  createFile(uri, options) { this._fileOps.push({ type: 'create', uri: uri.toString(), options: options || {} }); }
  deleteFile(uri, options) { this._fileOps.push({ type: 'delete', uri: uri.toString(), options: options || {} }); }
  renameFile(oldUri, newUri, options) { this._fileOps.push({ type: 'rename', oldUri: oldUri.toString(), newUri: newUri.toString(), options: options || {} }); }
  get fileOperations() { return this._fileOps.slice(); }
  set(uri, edits) { this._edits.set(uri.toString(), (edits || []).slice()); }
  get(uri) { return this._edits.get(uri.toString()); }
  has(uri) { return this._edits.has(uri.toString()); }
  entries() { return this.edits.map((e) => [e.uri, e.edits]); }
}

class CompletionList {
  constructor(items, isIncomplete) { this.items = items || []; this.isIncomplete = !!isIncomplete; }
}

class Hover {
  constructor(contents, range) { this.contents = contents; this.range = range; }
}

class DocumentLink {
  constructor(range, target) { this.range = range; this.target = target; this.tooltip = undefined; }
}

class CodeLens {
  constructor(range, command) { this.range = range; this.command = command; }
}

class CodeActionKind {
  constructor(value) { this.value = value; }
  static Empty = new CodeActionKind('');
  static QuickFix = new CodeActionKind('quickfix');
  static Refactor = new CodeActionKind('refactor');
  static RefactorExtract = new CodeActionKind('refactor.extract');
  static RefactorInline = new CodeActionKind('refactor.inline');
  static RefactorMove = new CodeActionKind('refactor.move');
  static RefactorRewrite = new CodeActionKind('refactor.rewrite');
  static Source = new CodeActionKind('source');
  static SourceOrganizeImports = new CodeActionKind('source.organizeImports');
  static SourceFixAll = new CodeActionKind('source.fixAll');
  static Notebook = new CodeActionKind('notebook');
  append(part) { return new CodeActionKind(this.value ? this.value + '.' + part : part); }
  // 位置很关键：Prettier 12 在**模块顶层**执行 `CodeActionKind.SourceFixAll.append('prettier')`，
  // 缺任何一个静态成员都会让整个扩展在加载阶段抛错（不是运行时降级）。
  intersects(other) { return this.contains(other) || other.contains(this); }
  contains(other) {
    if (!other || typeof other.value !== 'string') return false;
    return this.value === other.value || other.value.startsWith(this.value + '.');
  }
}

class CodeAction {
  constructor(title, kind) { this.title = String(title); this.kind = kind; this.edit = undefined; }
  static create(title, kind) { return new CodeAction(title, kind); }
}

const SymbolKind = {
  File: 0, Module: 1, Namespace: 2, Package: 3, Class: 4, Method: 5, Property: 6, Field: 7,
  Constructor: 8, Enum: 9, Interface: 10, Function: 11, Variable: 12, Constant: 13, String: 14,
  Number: 15, Boolean: 16, Array: 17, Object: 18, Key: 19, Null: 20, EnumMember: 21, Struct: 22,
  Event: 23, Operator: 24, TypeParameter: 25,
};

class SymbolInformation {
  constructor(name, kind, rangeOrContainer, uri) {
    this.name = String(name);
    this.kind = kind;
    this.containerName = typeof rangeOrContainer === 'string' ? rangeOrContainer : '';
    if (uri !== undefined) { this.location = new Location(uri, rangeOrContainer); }
    else { this.location = undefined; }
  }
}

class DocumentSymbol {
  constructor(name, detail, kind, range, selectionRange) {
    this.name = String(name);
    this.detail = detail;
    this.kind = kind;
    this.range = range;
    this.selectionRange = selectionRange;
    this.children = [];
  }
}

class TreeItem {
  constructor(label, collapsibleState) {
    this.label = label;
    this.collapsibleState = collapsibleState;
    this.contextValue = undefined;
    this.iconPath = undefined;
    this.command = undefined;
    this.description = undefined;
    this.tooltip = undefined;
  }
}

class ThemeIcon {
  constructor(id, color) { this.id = id; this.color = color; }
  static File = new ThemeIcon('file');
  static Folder = new ThemeIcon('folder');
}

const TreeItemCollapsibleState = { None: 0, Collapsed: 1, Expanded: 2 };

/**
 * 文件系统错误：扩展常靠 `err instanceof FileSystemError` / `FileSystemError.FileNotFound()` 分支处理。
 *
 * 必须补齐官方 **全部六个静态工厂**（FileNotFound / FileExists / FileNotADirectory /
 * FileIsADirectory / NoPermissions / Unavailable）。实测 ms-python 会直接调用
 * `FileSystemError.FileIsADirectory()`：缺一个就抛
 * `TypeError: FileSystemError.FileIsADirectory is not a function`，
 * 表现为整个扩展激活失败——这正是「插件激活失败」的真实根因之一。
 */
class FileSystemError extends Error {
  constructor(messageOrUri) {
    const text = (messageOrUri && typeof messageOrUri === 'object' && typeof messageOrUri.path === 'string')
      ? messageOrUri.path
      : String(messageOrUri);
    super(text);
    this.name = 'FileSystemError';
    this.code = 'Unknown';
  }
  static _make(code, fallback, messageOrUri) {
    const e = new FileSystemError(messageOrUri === undefined || messageOrUri === null ? fallback : messageOrUri);
    e.code = code;
    return e;
  }
  static FileNotFound(messageOrUri) { return FileSystemError._make('FileNotFound', 'File not found', messageOrUri); }
  static FileExists(messageOrUri) { return FileSystemError._make('FileExists', 'File exists', messageOrUri); }
  static FileNotADirectory(messageOrUri) { return FileSystemError._make('FileNotADirectory', 'File is not a directory', messageOrUri); }
  static FileIsADirectory(messageOrUri) { return FileSystemError._make('FileIsADirectory', 'File is a directory', messageOrUri); }
  static NoPermissions(messageOrUri) { return FileSystemError._make('NoPermissions', 'No permissions', messageOrUri); }
  static Unavailable(messageOrUri) { return FileSystemError._make('Unavailable', 'Unavailable', messageOrUri); }
}
const ProgressLocation = { SourceControl: 1, Window: 10, Notification: 15 };

class SnippetString {
  constructor(value) { this.value = value || ''; }
  appendText(text) { this.value += text; return this; }
  appendPlaceholder(value, number) { this.value += '${' + (number || 1) + ':' + value + '}'; return this; }
  appendTabstop(number = 0) { this.value += '$' + number; return this; }
  appendVariable(name, defaultValue) {
    this.value += '${' + name + (defaultValue === undefined ? '' : ':' + defaultValue) + '}';
    return this;
  }
}

class ThemeColor { constructor(id) { this.id = id; } }

class MarkdownString {
  constructor(value) { this.value = value || ''; this.isTrusted = false; this.supportThemeIcons = false; }
  appendText(v) { this.value += v; return this; }
  appendMarkdown(v) { this.value += v; return this; }
  appendCodeblock(v, lang) { this.value += '\n```' + (lang || '') + '\n' + v + '\n```\n'; return this; }
}

/** 输出通道：真实 VS Code 里是底部面板，这里转发到宿主的日志面板。 */
const LogLevel = { Off: 0, Trace: 1, Debug: 2, Info: 3, Warning: 4, Error: 5 };

/**
 * 输出通道。VS Code 的 `window.createOutputChannel(name, { log: true })` 返回的是
 * LogOutputChannel，扩展会直接调用 `.info()/.debug()/.warn()/.error()/.trace()`；
 * 缺这些方法会让「只想写一行日志」的扩展在激活期直接抛 TypeError 而整体失败。
 */
class OutputChannel {
  constructor(name, options) {
    this.name = name;
    this.isLogChannel = !!(options && options.log);
    this.logLevel = LogLevel.Info;
    this.onDidChangeLogLevel = new EventEmitter().event;
  }
  append(value) { notifyHost('output', { channel: this.name, text: String(value), line: false }); }
  appendLine(value) { notifyHost('output', { channel: this.name, text: String(value), line: true }); }
  replace(value) { notifyHost('output', { channel: this.name, text: String(value), line: false, clear: true }); }
  clear() { notifyHost('output', { channel: this.name, text: '', line: false, clear: true }); }
  show() { notifyHost('output', { channel: this.name, text: '', line: false, focus: true }); }
  hide() { }
  dispose() { }
  #emit(level, value) {
    notifyHost('output', { channel: this.name, text: String(value), line: true, level });
  }
  trace(value) { this.#emit('trace', value); }
  debug(value) { this.#emit('debug', value); }
  info(value) { this.#emit('info', value); }
  warn(value) { this.#emit('warn', value); }
  error(value) { this.#emit('error', value); }
}

class StatusBarItem {
  constructor(alignment, priority) {
    this.alignment = alignment;
    this.priority = priority;
    this.text = '';
    this.tooltip = undefined;
    this.command = undefined;
    this.color = undefined;
    this.visible = false;
  }
  show() { this.visible = true; this.push(); }
  hide() { this.visible = false; this.push(); }
  push() { notifyHost('statusBar', { text: this.visible ? this.text : '', tooltip: this.tooltip == null ? null : String(this.tooltip) }); }
  dispose() { this.visible = false; this.push(); }
}

const StatusBarAlignment = { Left: 1, Right: 2 };
const ViewColumn = {
  Active: -1, Beside: -2, One: 1, Two: 2, Three: 3, Four: 4,
  Five: 5, Six: 6, Seven: 7, Eight: 8, Nine: 9,
};
const ConfigurationTarget = { Global: 1, Workspace: 2, WorkspaceFolder: 3 };
const TextEditorRevealType = { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 };
const EndOfLine = { LF: 1, CRLF: 2 };
const FileType = { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 };
const ExtensionMode = { Production: 1, Development: 2, Test: 3 };
const ExtensionKind = { UI: 1, Workspace: 2 };
const QuickPickItemKind = { Separator: -1, Default: 0 };

// 文本行对象：扩展常读 lineAt / getText，基于宿主推来的文本构造。
class TextLine {
  constructor(lineNumber, text) {
    this.lineNumber = lineNumber;
    this.text = text;
    this.range = new Range(new Position(lineNumber, 0), new Position(lineNumber, text.length));
    this.rangeIncludingLineBreak = new Range(new Position(lineNumber, 0), new Position(lineNumber + 1, 0));
    this.firstNonWhitespaceCharacterIndex = text.length - text.replace(/^\s+/, '').length;
    this.isEmptyOrWhitespace = text.trim().length === 0;
  }
}

class TextDocument {
  constructor(uriString, languageId, text, version) {
    this.uri = Uri.parse(uriString);
    this.languageId = languageId;
    this.version = version;
    this.fileName = uriToPath(uriString);
    this.isUntitled = false;
    this.isDirty = false;
    this.isClosed = false;
    this.eol = EndOfLine.LF;
    this._text = text;
    this.lineCount = text.split('\n').length;
    this._lines = null;
  }
  _split() {
    if (this._lines === null) this._lines = this._text.split('\n');
    return this._lines;
  }
  getText(range) {
    if (!range) return this._text;
    const lines = this._split();
    if (range.start.line === range.end.line) {
      return (lines[range.start.line] || '').slice(range.start.character, range.end.character);
    }
    const out = [(lines[range.start.line] || '').slice(range.start.character)];
    for (let i = range.start.line + 1; i < range.end.line; i++) out.push(lines[i] || '');
    out.push((lines[range.end.line] || '').slice(0, range.end.character));
    return out.join('\n');
  }
  lineAt(lineOrPosition) {
    const lineNumber = typeof lineOrPosition === 'number' ? lineOrPosition : lineOrPosition.line;
    const lines = this._split();
    return new TextLine(lineNumber, lines[lineNumber] === undefined ? '' : lines[lineNumber]);
  }
  offsetAt(position) {
    const lines = this._split();
    let offset = 0;
    for (let i = 0; i < position.line && i < lines.length; i++) offset += lines[i].length + 1;
    return offset + position.character;
  }
  positionAt(offset) {
    const lines = this._text.slice(0, offset).split('\n');
    return new Position(lines.length - 1, lines[lines.length - 1].length);
  }
  getWordRangeAtPosition(position) {
    const line = this.lineAt(position.line).text;
    const isWord = (c) => /[A-Za-z0-9_$]/.test(c);
    let start = position.character;
    let end = position.character;
    while (start > 0 && isWord(line[start - 1])) start--;
    while (end < line.length && isWord(line[end])) end++;
    if (start === end) return undefined;
    return new Range(new Position(position.line, start), new Position(position.line, end));
  }
  save() { return requestToHost('workspace.saveDocument', { uri: this.uri.toString() }).then(() => true); }
  validateRange(range) { return range; }
  validatePosition(position) { return position; }
}

function serializeRange(range) {
  return {
    start: { line: range.start.line, character: range.start.character },
    end: { line: range.end.line, character: range.end.character },
  };
}

class TextEditor {
  constructor(document, selection) {
    this.document = document;
    this.selection = selection || new Selection(new Position(0, 0), new Position(0, 0));
    this.selections = [this.selection];
    this.visibleRanges = [new Range(new Position(0, 0), new Position(document.lineCount, 0))];
    this.options = { tabSize: 4, insertSpaces: true };
    this.viewColumn = ViewColumn.One;
  }
  edit(callback) {
    // 编辑落到宿主的真实编辑器缓冲：builder 收集编辑，再一次性发回宿主应用。
    const edits = [];
    const builder = {
      replace: (range, text) => { edits.push({ range: serializeRange(range), text: text === undefined ? '' : String(text) }); },
      insert: (position, text) => { edits.push({ range: serializeRange(new Range(position, position)), text: String(text) }); },
      delete: (range) => { edits.push({ range: serializeRange(range), text: '' }); },
      setEndOfLine: () => { },
    };
    callback(builder);
    if (edits.length === 0) return Promise.resolve(true);
    return requestToHost('editor.applyEdits', { uri: this.document.uri.toString(), edits })
      .then(() => true)
      .catch((err) => {
        notifyHost('log', { level: 'error', text: '应用编辑失败: ' + String((err && err.message) || err) });
        return false;
      });
  }
  revealRange() { }
  setDecorations() { }
  insertSnippet() { return Promise.resolve(true); }
}

function documentFor(uriString) {
  const entry = documents.get(uriString);
  return entry ? new TextDocument(uriString, entry.languageId, entry.text, entry.version) : undefined;
}

// ==== 事件总线（宿主推通知 → 触发扩展注册的监听器）==========================

const events = {
  didOpenTextDocument: new EventEmitter(),
  didChangeTextDocument: new EventEmitter(),
  didCloseTextDocument: new EventEmitter(),
  didSaveTextDocument: new EventEmitter(),
  willSaveTextDocument: new EventEmitter(),
  didChangeConfiguration: new EventEmitter(),
  didChangeActiveTextEditor: new EventEmitter(),
  didChangeVisibleTextEditors: new EventEmitter(),
  didChangeTextEditorSelection: new EventEmitter(),
  didChangeTextEditorVisibleRanges: new EventEmitter(),
  didChangeWindowState: new EventEmitter(),
  didChangeActiveColorTheme: new EventEmitter(),
  didChangeDiagnostics: new EventEmitter(),
  didChangeWorkspaceFolders: new EventEmitter(),
  didCreateFiles: new EventEmitter(),
  didDeleteFiles: new EventEmitter(),
  didRenameFiles: new EventEmitter(),
  didChangeShell: new EventEmitter(),
  activeEditorChanged: new EventEmitter(),
  // 以下事件宿主目前不会主动触发，但必须**存在**：扩展在 activate 里普遍
  // `context.subscriptions.push(window.onDidOpenTerminal(...))`，缺了这个属性
  // 就是 `Cannot read properties of undefined`，整插件激活失败。
  didOpenTerminal: new EventEmitter(),
  didCloseTerminal: new EventEmitter(),
  didChangeActiveTerminal: new EventEmitter(),
  didChangeTerminalState: new EventEmitter(),
  didChangeActiveNotebookEditor: new EventEmitter(),
  didChangeVisibleNotebookEditors: new EventEmitter(),
  didChangeNotebookEditorSelection: new EventEmitter(),
  didChangeNotebookEditorVisibleRanges: new EventEmitter(),
  didChangeTextEditorOptions: new EventEmitter(),
  didChangeTextEditorViewColumn: new EventEmitter(),
  willCreateFiles: new EventEmitter(),
  willDeleteFiles: new EventEmitter(),
  willRenameFiles: new EventEmitter(),
  didGrantWorkspaceTrust: new EventEmitter(),
};

let activeEditorRef = null;

const configurationCache = new Map(); // "section|key" -> value（宿主侧用户设置）
const configUser = new Map(); // 点分全键 -> 宿主侧用户设置值（预载）
let configPreloaded = false;

function getConfigValue(section, key) {
  const cacheKey = (section || '') + '|' + (key || '');
  if (configurationCache.has(cacheKey)) return Promise.resolve(configurationCache.get(cacheKey));
  return requestToHost('workspace.getConfiguration', { section: section || null, key: key || null })
    .then((payload) => {
      const value = unwrapConfigPayload(payload);
      configurationCache.set(cacheKey, value);
      return value;
    })
    .catch(() => null);
}

/** 宿主的 `workspace.getConfiguration` 返回 `{value, hasUserValue, section, key}` 包装；
 *  不解包的话扩展拿到的是包装对象（例如把 `mappings` 当成 `{value:null,...}`），必然报错。 */
function unwrapConfigPayload(payload) {
  if (payload && typeof payload === 'object' && !Array.isArray(payload) && 'value' in payload) {
    return payload.value;
  }
  return payload;
}

/**
 * 声明了 `type` 但没写 `default` 的配置项，按类型合成零值。
 *
 * 为什么必须合成（真机实测结论，不是猜的）：
 *  - Vue Volar 3.3.11 在**模块顶层**就访问 `config.server.path`；
 *  - `vue.server.path` 在它自己的 package.json 里只有 `"type": "string"`，**没有 default**；
 *  - Volar 的 defineConfig 代理一拿到 `undefined` 就
 *    `throw Error('Configuration key "vue.server.path" is not defined.')`，
 *    于是整个扩展连 require 都过不去（表现为「装了但永远不激活」）。
 *  - 同一处的 `config.server` 又必须返回**子树对象**（见 configEntries/subtreeOf）。
 *    两条事实合起来只有一种解释：VS Code 为已注册属性建出配置树，叶子按 type 补默认值，
 *    中间节点成为对象。因此这里按 type 合成零值。
 * 范围被严格限制在**本扩展自己声明过的键**上，绝不给未声明的键凭空造值。
 */
function syntheticDefault(schema) {
  if (!schema || typeof schema !== 'object') return undefined;
  let type = schema.type;
  // 联合类型（常见 ["string",""]）：剔除空项后只剩一种就按它取值，否则不猜。
  if (Array.isArray(type)) {
    const real = type.filter((t) => t && t !== '' && t !== '');
    if (real.length !== 1) return undefined;
    type = real[0];
  }
  switch (type) {
    case 'string': return '';
    case 'boolean': return false;
    case 'number':
    case 'integer': return 0;
    case 'array': return [];
    default: return undefined;
  }
}

/**
 * 扩展自己声明的配置默认值（`contributes.configuration` 的 properties.*.default）。
 *
 * 为什么由 guest 读而不是问宿主：VS Code 里 `getConfiguration('x').get('k')` 返回的就是
 * package.json 里声明的 default —— 这份数据只存在于扩展包里，宿主侧根本没有。
 * 没有 default 的项按 type 合成（见 syntheticDefault），否则会被严格的配置代理判成「未定义」。
 */
let declaredDefaultsCache = null;

function declaredConfigDefaults() {
  if (declaredDefaultsCache) return declaredDefaultsCache;
  const out = {};
  let contributes = {};
  try { contributes = packageJson().contributes || {}; } catch (err) { return out; }
  const decl = contributes.configuration;
  const merge = (cfg) => {
    const props = cfg && cfg.properties;
    if (!props || typeof props !== 'object') return;
    for (const key of Object.keys(props)) {
      const schema = props[key];
      if (!schema || typeof schema !== 'object') { out[key] = undefined; continue; }
      out[key] = Object.prototype.hasOwnProperty.call(schema, 'default')
        ? schema.default
        : syntheticDefault(schema);
    }
  };
  if (Array.isArray(decl)) decl.forEach(merge); else merge(decl);
  declaredDefaultsCache = out;   // 只算一次：rust-analyzer 有约 1900 项声明，重复算就是 O(n²) 的根源
  return out;
}

function declaredConfigSections() {
  const sections = new Set();
  for (const key of Object.keys(declaredConfigDefaults())) {
    const parts = key.split('.');
    for (let i = 1; i < parts.length; i++) sections.add(parts.slice(0, i).join('.'));
  }
  return Array.from(sections);
}

/**
 * 扩展激活前把宿主侧用户设置一次性拉进内存。
 *
 * 必须预载的原因：`Configuration.get()` 在 VS Code 里是**同步** API，扩展会直接写
 * `cfg.get('mappings')` / `cfg['autoSlashAfterDirectory']`，不可能每次都等一次 RPC。
 */
function preloadConfiguration() {
  const sections = declaredConfigSections();
  if (sections.length === 0) { configPreloaded = true; return Promise.resolve(); }
  return Promise.all(sections.map((section) =>
    requestToHost('workspace.getConfiguration', { section, key: null })
      .then((payload) => {
        const value = unwrapConfigPayload(payload);
        if (value && typeof value === 'object' && !Array.isArray(value)) {
          for (const key of Object.keys(value)) configUser.set(section + '.' + key, value[key]);
          invalidateConfigIndex();   // 用户配置变了，缓存索引立即失效
        }
      })
      .catch(() => { /* 宿主不可用：退回声明默认值，不阻断激活 */ })
  )).then(() => { configPreloaded = true; });
}

/** 组装某个 section 的「有效配置」：声明默认值打底，宿主用户设置覆盖。
 *
 * 返回的是 **section 相对的点分条目**（例如 section='vue' → 'server.path'），
 * 既能拼扁平的 bag，也能按前缀还原成嵌套对象 —— 后者是 VS Code 的真实语义：
 * `getConfiguration('vue').get('server')` 必须返回 `{path: ..., includeLanguages: ...}`
 * 这个**子树对象**，而不是 undefined。
 * 真机教训：Vue Volar 的 defineConfig 代理在 `get('server') === undefined` 时直接
 * `throw new Error('Configuration key "vue.server" is not defined.')`，
 * 于是整个扩展在 require 阶段就崩了（表现为「插件装了但永远不激活」）。
 */
/**
 * 配置索引：把「扁平的点分配置」一次性整理成 扁平表 + 嵌套树，并按 section 缓存。
 *
 * 为什么必须缓存（真机实测的严重缺陷）：rust-analyzer 在 package.json 里声明了约 1900 项配置，
 * 而老实现每次读配置都要遍历全部键，**并且在循环体里反复调用 declaredConfigDefaults()**
 * （每调用一次就重建一个 1900 项的默认值对象），再加上 Proxy 的 getOwnPropertyDescriptor
 * 每次属性访问都重跑一遍 —— 复杂度从 O(n) 变成 O(n²)（甚至看着像死循环）。
 * 现象就是：activate 里一读配置就吃满一个 CPU 核、长时间不返回，不报错也不结束，
 * 看起来像「插件卡死」，实际是宿主侧平方级开销。实测断在
 * merge -> declaredConfigDefaults -> configEntries -> configBag -> getOwnPropertyDescriptor。
 *
 * 现在：按 section 缓存（带 generation，用户配置一变就整体失效），
 * 取属性 O(1)、取子树 O(键深)，读配置不再是瓶颈。
 */
let configGeneration = 0;
const configIndexCache = new Map(); // sectionKey -> { generation, flat: Map, tree, bag }

function invalidateConfigIndex() {
  configGeneration++;
  configIndexCache.clear();
}

function declaredFlatKeyFor(section, name) {
  return section ? section + '.' + name : name;
}

function configIndex(section) {
  const key = section === undefined || section === null ? '' : String(section);
  const cached = configIndexCache.get(key);
  if (cached && cached.generation === configGeneration) return cached;
  const defaults = declaredConfigDefaults();
  const prefix = key ? key + '.' : '';
  const flat = new Map();
  for (const full of Object.keys(defaults)) {
    if (prefix && !full.startsWith(prefix)) continue;
    flat.set(prefix ? full.slice(prefix.length) : full, defaults[full]);
  }
  for (const [full, value] of configUser.entries()) {
    if (prefix && !full.startsWith(prefix)) continue;
    flat.set(prefix ? full.slice(prefix.length) : full, value);
  }
  // 嵌套树：中间节点是对象、叶子是值 —— 与 VS Code 的 section 语义一致
  //（Volar 会 `config.server.path` 逐级下钻，get('server') 必须给出对象而不是 undefined）。
  const tree = {};
  for (const [rel, value] of flat) {
    const parts = rel.split('.');
    let node = tree;
    for (let i = 0; i < parts.length - 1; i++) {
      const seg = parts[i];
      if (typeof node[seg] !== 'object' || node[seg] === null) node[seg] = {};
      node = node[seg];
    }
    const leaf = parts[parts.length - 1];
    if (!Object.prototype.hasOwnProperty.call(node, leaf)) node[leaf] = value;
  }
  const entry = { generation: configGeneration, flat, tree, bag: null };
  configIndexCache.set(key, entry);
  return entry;
}

/** 某个 section 的分值条目（section 相对的点分键），保留给需要遍历的场景。 */
function configEntries(section) {
  return configIndex(section).flat;
}

/** 由点分条目还原某个前缀的嵌套子树；无子键时返回 undefined。 */
function subtreeOf(entries, prefix) {
  const prefixDot = prefix + '.';
  const out = {};
  let found = false;
  for (const [key, value] of entries.entries()) {
    if (!key.startsWith(prefixDot)) continue;
    found = true;
    const rest = key.slice(prefixDot.length).split('.');
    let node = out;
    for (let i = 0; i < rest.length - 1; i++) {
      const seg = rest[i];
      if (typeof node[seg] !== 'object' || node[seg] === null) node[seg] = {};
      node = node[seg];
    }
    const leaf = rest[rest.length - 1];
    if (!(leaf in node)) node[leaf] = value;
  }
  return found ? out : undefined;
}

/**
 * 深拷贝子树/整包，避免把缓存对象直接交给扩展。
 * VS Code 每次 get 都返回新对象；若交出缓存本体，扩展一旦就地改配置对象，
 * 后续所有读取都会拿到被污染的值（而且是极难排查的串味）。
 */
function cloneConfigValue(value) {
  if (Array.isArray(value)) return value.map((item) => cloneConfigValue(item));
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const key of Object.keys(value)) out[key] = cloneConfigValue(value[key]);
    return out;
  }
  return value;
}

/** section 相对名 -> 叶子值（O(1)）。 */
function configLeafLookup(entry, name) {
  return entry.flat.has(name) ? { found: true, value: entry.flat.get(name) } : { found: false, value: undefined };
}

/** section 相对名 -> 子树对象（O(1)，取缓存树上的节点后拷贝）。 */
function configSubtreeLookup(entry, name) {
  const node = entry.tree[name];
  if (node === undefined || node === null || typeof node !== 'object') return undefined;
  return cloneConfigValue(node);
}

function configBag(section) {
  const entry = configIndex(section);
  if (!entry.bag) {
    const bag = {};
    for (const [key, value] of entry.flat) bag[key] = value;
    entry.bag = bag;
  }
  return entry.bag;
}

function configurationFor(section) {
  // 关键语义：`get('server')` 这类「非叶子键」要返回**子树对象**（VS Code 行为）。
  // 不做这一步，像 Volar 这种按 `config.server.path` 逐级下钻的扩展会直接抛错。
  const api = {
    // 同步返回：与 VS Code 一致（异步在这里是致命的，扩展会直接把 Promise 当配置对象用）。
    get: (key, defaultValue) => {
      const entry = configIndex(section);
      if (key === undefined || key === null) return Object.assign({}, configBag(section));
      const name = String(key);
      const leaf = configLeafLookup(entry, name);
      if (leaf.found) { traceConfig('get section=' + section + ' key=' + name + ' -> ' + typeof leaf.value); return cloneConfigValue(leaf.value); }
      const sub = configSubtreeLookup(entry, name);
      if (sub !== undefined) { traceConfig('get section=' + section + ' key=' + name + ' -> subtree'); return sub; }
      // 回退：也接受点分路径（getConfiguration('editor').get('tabSize') 等价 'editor.tabSize'）。
      const globalEntry = configIndex(null);
      const full = declaredFlatKeyFor(section, name);
      const globalLeaf = configLeafLookup(globalEntry, full);
      if (globalLeaf.found) return cloneConfigValue(globalLeaf.value);
      const globalSub = configSubtreeLookup(globalEntry, full);
      if (globalSub !== undefined) { traceConfig('get section=' + section + ' key=' + name + ' -> global subtree'); return globalSub; }
      // VS Code 的 language-override section（如 `[xml]`）即使没有扩展声明，
      // 也返回可读取的配置对象；语言客户端常直接访问它的子键。
      if (typeof name === 'string' && /^\[[^\]]+\]$/.test(name)) {
        traceConfig('get section=' + section + ' key=' + name + ' -> empty override object');
        return {};
      }
      traceConfig('get section=' + section + ' key=' + name + ' -> UNDEFINED (fallback=' + typeof defaultValue + ')');
      return defaultValue;
    },
    has: (key) => {
      const entry = configIndex(section);
      const name = String(key);
      if (configLeafLookup(entry, name).found) return true;
      if (configSubtreeLookup(entry, name) !== undefined) return true;
      const globalEntry = configIndex(null);
      const full = declaredFlatKeyFor(section, name);
      return configLeafLookup(globalEntry, full).found || configSubtreeLookup(globalEntry, full) !== undefined;
    },
    inspect: (key) => {
      const name = String(key);
      const defaults = declaredConfigDefaults();
      const full = declaredFlatKeyFor(section, name);
      return {
        key: name,
        defaultValue: defaults[full],
        globalValue: configUser.has(full) ? configUser.get(full) : undefined,
        workspaceValue: undefined,
        workspaceFolderValue: undefined,
      };
    },
    update: (key, value) => requestToHost('workspace.updateConfiguration', { section: section || null, key, value })
      .then(() => {
        const name = declaredFlatKeyFor(section, String(key));
        if (value === undefined) configUser.delete(name); else configUser.set(name, value);
        configurationCache.clear();
        invalidateConfigIndex();
      }),
  };
  // VS Code 的 Configuration 允许**直接取属性**（`cfg['autoSlashAfterDirectory']`），
  // 老扩展大量这么写；不用 Proxy 的话拿到的是 undefined，扩展会静默退化成「没有配置」。
  // 注意：这里的每个 trap 都必须是 O(1) —— 它们会被扩展高频调用（含 Object.keys/spread）。
  return new Proxy(api, {
    get(target, prop) {
      if (prop in target) return target[prop];
      if (typeof prop === 'string') {
        const entry = configIndex(section);
        const leaf = configLeafLookup(entry, prop);
        if (leaf.found) return cloneConfigValue(leaf.value);
        const sub = configSubtreeLookup(entry, prop);
        if (sub !== undefined) return sub;
      }
      return undefined;
    },
    has(target, prop) {
      if (prop in target) return true;
      const entry = configIndex(section);
      const name = String(prop);
      return configLeafLookup(entry, name).found || configSubtreeLookup(entry, name) !== undefined;
    },
    ownKeys(target) {
      return Array.from(new Set([...Reflect.ownKeys(target), ...Object.keys(configBag(section))]));
    },
    getOwnPropertyDescriptor(target, prop) {
      if (prop in target) return Reflect.getOwnPropertyDescriptor(target, prop);
      const entry = configIndex(section);
      const name = String(prop);
      const leaf = configLeafLookup(entry, name);
      if (leaf.found) return { value: cloneConfigValue(leaf.value), writable: false, enumerable: true, configurable: true };
      const sub = configSubtreeLookup(entry, name);
      if (sub !== undefined) return { value: sub, writable: false, enumerable: true, configurable: true };
      return undefined;
    },
  });
}

// ==== window ================================================================

function normalizeMessageItems(rest) {
  const flat = [];
  const push = (value) => {
    if (value === null || value === undefined) return;
    if (typeof value === 'string') { flat.push({ label: value, payload: value }); return; }
    if (typeof value === 'object' && value.title) { flat.push({ label: String(value.title), payload: value }); return; }
    flat.push({ label: String(value), payload: value });
  };
  for (const entry of rest) {
    if (Array.isArray(entry)) entry.forEach(push);
    else if (entry && typeof entry === 'object' && !entry.title && (entry.items || entry.modal || entry.detail)) {
      // 选项对象（{modal:true}/{items:[...]}）不当作按钮
      if (Array.isArray(entry.items)) entry.items.forEach(push);
    } else push(entry);
  }
  return flat;
}

function messageRequest(kind, message, rest) {
  const items = normalizeMessageItems(rest);
  return requestToHost('window.showMessage', { kind, message: String(message), items: items.map((i) => i.label) })
    .then((selected) => {
      if (selected === null || selected === undefined) return undefined;
      const hit = items.find((i) => i.label === selected);
      return hit ? hit.payload : selected;
    })
    .catch(() => undefined);
}

function showQuickPickImpl(items, options) {
  const opts = options || {};
  return Promise.resolve(items).then((resolved) => {
    const list = Array.isArray(resolved) ? resolved : [];
    const serialized = list.map((it) => {
      if (typeof it === 'string') return { label: it };
      return {
        label: String(it.label === undefined ? '' : it.label),
        description: it.description === undefined ? null : String(it.description),
        detail: it.detail === undefined ? null : String(it.detail),
      };
    });
    const canPickMany = opts.canPickMany === true;
    return requestToHost('window.showQuickPick', {
      items: serialized,
      placeholder: opts.placeholder ? String(opts.placeholder) : (opts.title ? String(opts.title) : null),
      canPickMany,
    }).then((selected) => {
      if (selected === null || selected === undefined) return undefined;
      if (canPickMany) {
        const labels = Array.isArray(selected) ? selected : [selected];
        return labels.map((label) => list.find((it) => (typeof it === 'string' ? it : it.label) === label)).filter(Boolean);
      }
      const label = typeof selected === 'string' ? selected : (selected && selected.label);
      return list.find((it) => (typeof it === 'string' ? it : it.label) === label);
    });
  });
}

// 终端登记表：window.terminals / window.activeTerminal 需要有真实来源，
// createTerminal 时登记，dispose 时摘掉。
const terminalRegistry = [];
let activeTerminalRef = undefined;

const webviewViewProviders = new Map();
const webviewPanelSerializers = new Map();

// ==== WebView 面板宿主支持 ===================================================
// 扩展的界面（VS Code webview API）画在 WebView 里。宿主必须真的渲染它，
// 否则扩展「命令执行成功但没有任何界面」——用户只能看到一行日志，等于打不开插件。
const webviewPanels = new Map();          // id -> { panel, receive }
let webviewPanelSeq = 0;

/** 插件本地资源在 WebView 里的虚拟源。宿主侧拦截该源并直接从插件目录读盘。 */
function webviewResourceOrigin() { return 'https://nebulaforge.local'; }

/**
 * `webview.asWebviewUri(uri)`：把扩展自己的文件映射成 WebView 可加载的 URL。
 * 扩展几乎都会用它引用打包出来的 webview.js / index.css，
 * 原样返回 Uri 对象会让界面白屏（资源 404），所以这里必须真的映射。
 */
function webviewUriFor(uri) {
  try {
    if (!uri) return '';
    const p = typeof uri === 'string' ? uri : (uri.fsPath || uri.path || String(uri));
    if (!p) return '';
    const rel = path.relative(extensionDir, p).split(path.sep).join('/');
    if (!rel || rel.startsWith('..')) return String(p);
    return webviewResourceOrigin() + '/ext/' + rel.split('/').map(encodeURIComponent).join('/');
  } catch (err) {
    return String(uri);
  }
}

/** WebView 门面：扩展侧（面板 / 侧边视图）共用同一份实现。 */
function createWebviewFacade(id, options) {
  const receive = new EventEmitter();
  const facade = {
    options: options || {},
    _html: '',
    onDidReceiveMessage: receive.event,
    postMessage: (message) => {
      // 扩展 → WebView：交给宿主投递（宿主用 evaluateJavascript 派发 message 事件）。
      notifyHost('webview.postMessage', { id, message: message === undefined ? null : message });
      return Promise.resolve(true);
    },
    asWebviewUri: (uri) => webviewUriFor(uri),
    cspSource: webviewResourceOrigin(),
  };
  Object.defineProperty(facade, 'html', {
    get() { return facade._html; },
    set(value) {
      facade._html = value == null ? '' : String(value);
      notifyHost('webview.setHtml', { id, html: facade._html });
    },
  });
  return { facade, receive };
}
const uriHandlers = new Set();
const fileDecorationProviders = new Set();
const treeViews = new Map();
const taskProviders = new Map();
// 注意：不要在这里引用 ColorThemeKind —— 它声明在本文件更靠后的位置，
// 模块求值时处于 TDZ，直接读会 `ReferenceError: Cannot access before initialization`
// 让整个扩展宿主启动即崩。这里保持惰性求值，首次访问时再构造。
// 配置读取追踪（NEBULA_DEBUG_CONFIG=1 时打开）：排查「扩展读配置拿到 undefined 就抛错」类问题时，
// 这是唯一能看到扩展真实请求的 section/key 的手段，平时零开销。
const DEBUG_CONFIG = !!(typeof process !== 'undefined' && process.env && process.env.NEBULA_DEBUG_CONFIG);
function traceConfig(msg) {
  // 必须直接写 fd 2：本文件顶部把 console.* 接管成了宿主日志通知（保护 stdout 协议通道），
  // 用 console.error 追踪会跑到 IPC 里，调试时反而看不见。
  if (DEBUG_CONFIG) { try { process.stderr.write('[cfg] ' + msg + '\n'); } catch (err) { } }
}

let activeColorTheme = undefined;
function currentColorTheme() {
  if (!activeColorTheme) activeColorTheme = { kind: ColorThemeKind.Dark };
  return activeColorTheme;
}

const windowApi = {
  get activeTextEditor() { return activeEditorRef; },
  get visibleTextEditors() { return activeEditorRef ? [activeEditorRef] : []; },
  get state() { return { focused: true }; },
  showInformationMessage: (message, ...rest) => messageRequest('info', message, rest),
  showWarningMessage: (message, ...rest) => messageRequest('warn', message, rest),
  showErrorMessage: (message, ...rest) => messageRequest('error', message, rest),
  showQuickPick: (items, options) => showQuickPickImpl(items, options),
  showInputBox: (options) => requestToHost('window.showInputBox', {
    prompt: options && options.prompt ? String(options.prompt) : null,
    value: options && options.value ? String(options.value) : null,
    placeHolder: options && options.placeHolder ? String(options.placeHolder) : null,
    password: !!(options && options.password),
  }).then((v) => (v === null || v === undefined ? undefined : String(v))).catch(() => undefined),
  showOpenDialog: () => requestToHost('window.showOpenDialog', {}).then(() => undefined).catch(() => undefined),
  showSaveDialog: () => requestToHost('window.showSaveDialog', {}).then(() => undefined).catch(() => undefined),
  setStatusBarMessage: (text, timeoutMs) => {
    notifyHost('statusBar', { text: String(text), tooltip: null });
    if (typeof timeoutMs === 'number' && timeoutMs > 0) setTimeout(() => notifyHost('statusBar', { text: '', tooltip: null }), timeoutMs);
    return new Disposable(() => notifyHost('statusBar', { text: '', tooltip: null }));
  },
  createStatusBarItem: (alignment, priority) => new StatusBarItem(alignment, priority),
  createLanguageStatusItem: (id, selector) => new LanguageStatusItem(id, selector),
  createOutputChannel: (name, options) => new OutputChannel(String(name), options),
  createTextEditorDecorationType: (options) => new TextEditorDecorationType(options || {}),
  createQuickPick: () => new QuickPick(),
  createInputBox: () => new InputBox(),
  createTreeView: (viewId, options) => {
    const key = String(viewId);
    const view = new TreeView(key, options || {}, (options && options.treeDataProvider) || null);
    notifyHost('registerTreeDataProvider', { viewId: key, options: {} });
    return view;
  },
  registerTreeDataProvider: (viewId, provider) => {
    const key = String(viewId);
    const view = new TreeView(key, {}, provider);
    notifyHost('registerTreeDataProvider', { viewId: key, options: {} });
    return new Disposable(() => view.dispose());
  },
  registerFileDecorationProvider: (provider) => {
    const entry = { provider };
    fileDecorationProviders.add(entry);
    notifyHost('registerFileDecorationProvider', {});
    return new Disposable(() => fileDecorationProviders.delete(entry));
  },
  createCommentController: (id, label) => new CommentController(id, label),
  registerTerminalLinkProvider: (provider) => new Disposable(() => { }),
  registerTerminalProfileProvider: (id, provider) => new Disposable(() => { }),
  get activeColorTheme() { return currentColorTheme(); },
  // ---- 终端 ----
  get terminals() { return terminalRegistry.slice(); },
  get activeTerminal() { return activeTerminalRef; },
  onDidOpenTerminal: events.didOpenTerminal.event,
  onDidCloseTerminal: events.didCloseTerminal.event,
  onDidChangeActiveTerminal: events.didChangeActiveTerminal.event,
  onDidChangeTerminalState: events.didChangeTerminalState.event,
  // ---- Notebook ----
  // 宿主不提供 notebook 编辑能力，按官方语义给「空值」而不是 undefined 属性；
  // 扩展拿到的 [] / undefined 都是合法状态，不会崩。
  get activeNotebookEditor() { return undefined; },
  get visibleNotebookEditors() { return []; },
  onDidChangeActiveNotebookEditor: events.didChangeActiveNotebookEditor.event,
  onDidChangeVisibleNotebookEditors: events.didChangeVisibleNotebookEditors.event,
  onDidChangeNotebookEditorSelection: events.didChangeNotebookEditorSelection.event,
  onDidChangeNotebookEditorVisibleRanges: events.didChangeNotebookEditorVisibleRanges.event,
  // ---- 编辑器选项 / 视图列 ----
  onDidChangeTextEditorOptions: events.didChangeTextEditorOptions.event,
  onDidChangeTextEditorViewColumn: events.didChangeTextEditorViewColumn.event,
  // VS Code 已废弃但仍被旧扩展调用的 SCM 进度包装：直接跑任务。
  withScmProgress: (task) => Promise.resolve().then(() => task({
    report: () => { },
  })),
  onDidChangeActiveColorTheme: events.didChangeActiveColorTheme.event,
  onDidChangeTextEditorSelection: events.didChangeTextEditorSelection.event,
  onDidChangeTextEditorVisibleRanges: events.didChangeTextEditorVisibleRanges.event,
  onDidChangeWindowState: events.didChangeWindowState.event,
  showWorkspaceFolderPick: (options) => {
    const folders = workspaceFolders.slice();
    if (folders.length === 0) return Promise.resolve(undefined);
    return Promise.resolve(folders[0]);
  },
  showNotebookDocument: () => Promise.reject(FileSystemError.FileNotFound('宿主当前不支持打开 notebook 文档')),
  createTerminal: (options) => {
    const opts = typeof options === 'string' ? { name: options } : (options || {});
    const name = opts.name ? String(opts.name) : '扩展终端';
    const terminal = {
      name,
      processId: Promise.resolve(undefined),
      creationOptions: opts,
      exitStatus: undefined,
      sendText: (text) => notifyHost('terminal.sendText', { name, text: String(text) }),
      show: () => {
        if (activeTerminalRef !== terminal) {
          activeTerminalRef = terminal;
          events.didChangeActiveTerminal.fire(terminal);
        }
        events.didChangeTerminalState.fire(terminal);
        notifyHost('terminal.show', { name });
      },
      hide: () => { },
      dispose: () => {
        const at = terminalRegistry.indexOf(terminal);
        if (at >= 0) terminalRegistry.splice(at, 1);
        if (activeTerminalRef === terminal) {
          activeTerminalRef = terminalRegistry[0];
          events.didChangeActiveTerminal.fire(activeTerminalRef);
        }
        events.didCloseTerminal.fire(terminal);
      },
    };
    terminalRegistry.push(terminal);
    activeTerminalRef = terminal;
    events.didOpenTerminal.fire(terminal);
    events.didChangeActiveTerminal.fire(terminal);
    notifyHost('terminal.create', { name, cwd: opts.cwd == null ? null : String(opts.cwd) });
    if (opts.message) notifyHost('log', { level: 'info', text: String(opts.message) });
    return terminal;
  },
  createWebviewPanel: (viewType, title, showOptions, options) => {
    const id = 'panel-' + (++webviewPanelSeq) + '-' + Date.now();
    const { facade: webview, receive } = createWebviewFacade(id, options);
    const disposeEmitter = new EventEmitter();
    const viewStateEmitter = new EventEmitter();
    const panel = {
      viewType: String(viewType),
      visible: true,
      active: true,
      viewColumn: (showOptions && showOptions.viewColumn) || ViewColumn.One,
      iconPath: undefined,
      onDidDispose: disposeEmitter.event,
      onDidChangeViewState: viewStateEmitter.event,
      webview,
      reveal: () => {
        panel.visible = true;
        panel.active = true;
        notifyHost('webview.reveal', { id });
        viewStateEmitter.fire({ webviewPanel: panel });
      },
      dispose: () => {
        if (panel._disposed) return;
        panel._disposed = true;
        webviewPanels.delete(id);
        notifyHost('webview.dispose', { id });
        disposeEmitter.fire();
      },
    };
    Object.defineProperty(panel, 'title', {
      get() { return panel._title; },
      set(value) {
        panel._title = value == null ? '' : String(value);
        notifyHost('webview.title', { id, title: panel._title });
      },
    });
    panel._title = title == null ? String(viewType) : String(title);
    webviewPanels.set(id, { panel, receive });
    notifyHost('webview.create', {
      id,
      viewType: panel.viewType,
      title: panel._title,
      rootDir: extensionDir,
      enableScripts: !(options && options.enableScripts === false),
    });
    return panel;
  },
  registerWebviewViewProvider: (viewId, provider, options) => {
    const key = String(viewId);
    webviewViewProviders.set(key, provider);
    notifyHost('registerWebviewViewProvider', { viewId: key, options: options || {} });
    return new Disposable(() => {
      if (webviewViewProviders.get(key) === provider) webviewViewProviders.delete(key);
      notifyHost('unregisterWebviewViewProvider', { viewId: key });
    });
  },
  registerWebviewPanelSerializer: (viewType, serializer) => {
    const key = String(viewType);
    webviewPanelSerializers.set(key, serializer);
    notifyHost('registerWebviewPanelSerializer', { viewType: key });
    return new Disposable(() => {
      if (webviewPanelSerializers.get(key) === serializer) webviewPanelSerializers.delete(key);
      notifyHost('unregisterWebviewPanelSerializer', { viewType: key });
    });
  },
  registerCustomEditorProvider: (viewType, provider, options) => {
    const key = String(viewType);
    notifyHost('registerCustomEditorProvider', { viewType: key, options: options || {} });
    return new Disposable(() => notifyHost('unregisterCustomEditorProvider', { viewType: key }));
  },
  registerUriHandler: (handler) => {
    uriHandlers.add(handler);
    return new Disposable(() => uriHandlers.delete(handler));
  },
  get tabGroups() {
    return {
      all: [],
      activeTabGroup: { viewColumn: ViewColumn.One, isActive: true, tabs: [], viewColumnValue: ViewColumn.One },
      onDidChangeTabs: new EventEmitter().event,
      onDidChangeTabGroups: new EventEmitter().event,
      close: () => Promise.resolve(true),
    };
  },
  onDidChangeActiveTextEditor: events.didChangeActiveTextEditor.event,
  onDidChangeVisibleTextEditors: events.didChangeVisibleTextEditors.event,
  showTextDocument: (documentOrUri, options) => {
    const uri = typeof documentOrUri === 'string'
      ? documentOrUri
      : (documentOrUri && documentOrUri.uri ? documentOrUri.uri.toString() : String(documentOrUri));
    return requestToHost('editor.openDocument', { uri, options: options || null })
      .then(() => {
        const entry = documents.get(uri);
        const doc = entry ? new TextDocument(uri, entry.languageId, entry.text, entry.version) : documentFor(uri);
        if (!doc) return undefined;
        const editor = new TextEditor(doc);
        activeEditorRef = editor;
        events.didChangeActiveTextEditor.fire(editor);
        return editor;
      })
      .catch(() => undefined);
  },
  withProgress: (options, task) => {
    const title = options && (options.title || options.message) ? String(options.title || options.message) : '进行中';
    notifyHost('progress.begin', { title, cancellable: !!(options && options.cancellable) });
    const reporter = {
      report: (value) => {
        if (!value) return;
        notifyHost('progress.report', {
          message: value.message === undefined ? null : String(value.message),
          increment: typeof value.increment === 'number' ? value.increment : null,
        });
      },
    };
    const source = new CancellationTokenSource();
    return Promise.resolve()
      .then(() => task(reporter, source.token))
      .finally(() => notifyHost('progress.end', { title }));
  },
};

// ==== workspace =============================================================

let workspaceFolders = [];
const stateStore = { global: {}, workspace: {} };
const fileSystemProviders = new Map();
const textDocumentContentProviders = new Map();

function uriSchemeOf(uri) {
  if (uri === null || uri === undefined) return '';
  if (typeof uri === 'string') return (uri.split('://')[0] || '').toLowerCase();
  const scheme = uri.scheme !== undefined ? uri.scheme : String(uri).split('://')[0];
  return String(scheme).toLowerCase();
}

function fileSystemProviderFor(uri) {
  return fileSystemProviders.get(uriSchemeOf(uri));
}

function contentProviderFor(uri) {
  return textDocumentContentProviders.get(uriSchemeOf(uri));
}

function providerCall(provider, method, args) {
  if (!provider || typeof provider[method] !== 'function') {
    return Promise.reject(FileSystemError.FileNotFound('文件系统 provider 未实现: ' + method));
  }
  try {
    return Promise.resolve(provider[method](...args));
  } catch (err) {
    return Promise.reject(err);
  }
}

function stateAccessor(isGlobal) {
  const bucket = isGlobal ? stateStore.global : stateStore.workspace;
  return {
    get: (key, defaultValue) => (Object.prototype.hasOwnProperty.call(bucket, key) ? bucket[key] : defaultValue),
    update: (key, value) => {
      bucket[key] = value;
      requestToHost('state.set', { key, value, global: isGlobal }).catch(() => { });
      return Promise.resolve();
    },
    keys: () => Object.keys(bucket),
    setKeysForSync: () => { },
  };
}

function workspaceFolderFor(uriString) {
  const target = uriToPath(uriString);
  for (const folder of workspaceFolders) {
    const root = uriToPath(folder.uri.toString());
    if (target === root || target.startsWith(root.replace(/\/$/, '') + '/')) return folder;
  }
  return undefined;
}

const workspaceApi = {
  // 文件「将要」变更事件：真实宿主在写盘前触发，我们只提供事件面。
  onWillCreateFiles: events.willCreateFiles.event,
  onWillDeleteFiles: events.willDeleteFiles.event,
  onWillRenameFiles: events.willRenameFiles.event,
  onWillSaveNotebookDocument: new EventEmitter().event,
  onDidGrantWorkspaceTrust: events.didGrantWorkspaceTrust.event,
  get name() { return workspaceFolders.length ? workspaceFolders[0].name : undefined; },
  get rootPath() { return workspaceFolders.length ? workspaceFolders[0].uri.fsPath : undefined; },
  get workspaceFolders() { return workspaceFolders.length ? workspaceFolders.slice() : undefined; },
  get workspaceFile() { return undefined; },
  get textDocuments() { return Array.from(documents.keys()).map(documentFor).filter(Boolean); },
  get isTrusted() { return true; },
  onDidOpenTextDocument: events.didOpenTextDocument.event,
  onDidChangeTextDocument: events.didChangeTextDocument.event,
  onDidCloseTextDocument: events.didCloseTextDocument.event,
  onDidSaveTextDocument: events.didSaveTextDocument.event,
  onDidChangeConfiguration: events.didChangeConfiguration.event,
  onDidChangeWorkspaceFolders: events.didChangeWorkspaceFolders.event,
  onDidCreateFiles: events.didCreateFiles.event,
  onDidDeleteFiles: events.didDeleteFiles.event,
  onDidRenameFiles: events.didRenameFiles.event,
  onWillSaveTextDocument: events.willSaveTextDocument.event,
  getConfiguration: (section, scope) => {
    traceConfig('getConfiguration(section=' + JSON.stringify(section === undefined ? null : section) + ')');
    return configurationFor(section === undefined ? null : section);
  },
  getWorkspaceFolder: (uri) => workspaceFolderFor(typeof uri === 'string' ? uri : (uri && uri.toString ? uri.toString() : String(uri))),
  asRelativePath: (pathOrUri) => {
    const target = typeof pathOrUri === 'string' ? pathOrUri : uriToPath(pathOrUri.toString());
    const folder = workspaceFolderFor(target.startsWith('file://') ? target : 'file://' + target);
    if (!folder) return target;
    const root = uriToPath(folder.uri.toString()).replace(/\/$/, '');
    return target.startsWith(root + '/') ? target.slice(root.length + 1) : target;
  },
  openTextDocument: (arg, languageId) => {
    const uri = typeof arg === 'string'
      ? (arg.includes('://') ? arg : Uri.file(arg).toString())
      : (arg && arg.uri ? arg.uri.toString() : String(arg));
    const options = typeof arg === 'object' && arg && !arg.uri ? arg : null;
    if (documents.has(uri)) return Promise.resolve(documentFor(uri));
    const contentProvider = contentProviderFor(uri);
    if (contentProvider && typeof contentProvider.provideTextDocumentContent === 'function') {
      // 自定义 scheme（如 claude:、git:）由扩展自带的 content provider 生成内容，
      // 不能走宿主的磁盘读取，否则会落到不存在的文件而打开失败。
      const uriObj = Uri.parse(uri);
      return Promise.resolve()
        .then(() => contentProvider.provideTextDocumentContent(uriObj, new CancellationTokenSource().token))
        .then((text) => {
          if (text === null || text === undefined) throw FileSystemError.FileNotFound('content provider 未返回内容: ' + uri);
          const lang = languageId || (options && options.language) || 'plaintext';
          documents.set(uri, { text: String(text), languageId: lang, version: 1 });
          const doc = documentFor(uri);
          events.didOpenTextDocument.fire(doc);
          return doc;
        })
        .catch((err) => {
          notifyHost('log', { level: 'error', text: 'content provider 打开失败 ' + uri + ': ' + String((err && err.message) || err) });
          return undefined;
        });
    }
    return requestToHost('workspace.readFile', { uri })
      .then((payload) => {
        const text = payload && payload.text !== undefined ? payload.text : '';
        const lang = languageId || (options && options.language) || (payload && payload.languageId) || 'plaintext';
        documents.set(uri, { text, languageId: lang, version: 1 });
        const doc = documentFor(uri);
        events.didOpenTextDocument.fire(doc);
        return doc;
      })
      .catch(() => undefined);
  },
  registerTextDocumentContentProvider: (scheme, provider) => {    const key = String(scheme).toLowerCase();
    textDocumentContentProviders.set(key, provider);
    notifyHost('registerTextDocumentContentProvider', { scheme: key });
    return new Disposable(() => {
      if (textDocumentContentProviders.get(key) === provider) textDocumentContentProviders.delete(key);
      notifyHost('unregisterTextDocumentContentProvider', { scheme: key });
    });
  },
  saveAll: () => Promise.resolve(true),
  applyEdit: (edit) => requestToHost('workspace.applyEdit', {
    fileOperations: (edit && edit.fileOperations ? edit.fileOperations : []),
    edits: (edit && edit.edits ? edit.edits : []).map((e) => ({
      uri: e.uri ? e.uri.toString() : null,
      edits: (e.edits || (e.range ? [{ range: e.range, newText: e.newText }] : [])).map((inner) => ({
        range: inner.range ? serializeRange(inner.range) : null,
        newText: inner.newText === undefined ? (inner.text === undefined ? '' : String(inner.text)) : String(inner.newText),
      })),
    })),
  }).then(() => true).catch(() => false),
  findFiles: (include, exclude) => requestToHost('workspace.findFiles', {
    include: include === undefined ? null : String(include),
    exclude: exclude === undefined ? null : String(exclude),
  }).then((list) => (Array.isArray(list) ? list.map((p) => Uri.file(p)) : [])).catch(() => []),
  createFileSystemWatcher: (globPattern, ignoreCreateEvents, ignoreChangeEvents, ignoreDeleteEvents) => {
    // 宿主当前不会主动推送文件系统事件；但必须返回**可用对象**：扩展会立刻在它上面
    // 注册 onDidCreate/onDidChange/onDidDelete，返回 `{}` 会让注册调用直接抛 TypeError，
    // 进而让整个激活失败（用户表现为「插件装了但没反应」）。
    const watcher = {
      ignoreCreateEvents: !!ignoreCreateEvents,
      ignoreChangeEvents: !!ignoreChangeEvents,
      ignoreDeleteEvents: !!ignoreDeleteEvents,
      onDidCreate: () => new Disposable(() => { }),
      onDidChange: () => new Disposable(() => { }),
      onDidDelete: () => new Disposable(() => { }),
      dispose() { },
    };
    notifyHost('createFileSystemWatcher', { pattern: String(globPattern) });
    return watcher;
  },
  updateWorkspaceFolders: () => false,
  registerTaskProvider: (type, provider) => {
    const key = String(type);
    taskProviders.set(key, provider);
    notifyHost('registerTaskProvider', { type: key });
    return new Disposable(() => {
      if (taskProviders.get(key) === provider) taskProviders.delete(key);
    });
  },
  get onDidChangeNotebookDocumentSerializers() { return new EventEmitter().event; },
  registerNotebookSerializer: (notebookType, serializer, options) => {
    const key = String(notebookType);
    notifyHost('registerNotebookSerializer', { notebookType: key, options: options || {} });
    return new Disposable(() => notifyHost('unregisterNotebookSerializer', { notebookType: key }));
  },
  get notebookDocuments() { return []; },
  openNotebookDocument: () => Promise.reject(FileSystemError.FileNotFound('宿主当前不支持打开 notebook 文档')),
  onDidOpenNotebookDocument: () => ({ dispose() { } }),
  onDidChangeNotebookDocument: () => ({ dispose() { } }),
  onDidCloseNotebookDocument: () => ({ dispose() { } }),
  onDidSaveNotebookDocument: () => ({ dispose() { } }),
  registerNotebookCellStatusBarItemProvider: (notebookType, provider) => {
    notifyHost('registerNotebookCellStatusBarItemProvider', { notebookType: String(notebookType) });
    return new Disposable(() => notifyHost('unregisterNotebookCellStatusBarItemProvider', { notebookType: String(notebookType) }));
  },
  registerFileSystemProvider: (scheme, provider, options) => {
    const key = String(scheme).toLowerCase();
    if (!key) throw new Error('文件系统 scheme 不能为空');
    fileSystemProviders.set(key, provider);
    notifyHost('registerFileSystemProvider', { scheme: key, options: options || {} });
    return new Disposable(() => {
      if (fileSystemProviders.get(key) === provider) fileSystemProviders.delete(key);
      notifyHost('unregisterFileSystemProvider', { scheme: key });
    });
  },
  get fs() {
    // 所有落回宿主的 fs 调用都经这里：把失败统一成 FileSystemError（带 code），
    // 与 VS Code 的行为一致；否则扩展的 `.then(ok, notFound => ...)` 分支永远走不到。
    const fsHostRequest = (method, params, uriText) => requestToHost(method, params).catch((err) => {
      if (err instanceof FileSystemError) throw err;
      const wrapped = new FileSystemError((err && err.message) || String(err));
      wrapped.code = (err && typeof err.code === 'string') ? err.code : 'FileNotFound';
      wrapped.fsPath = uriText;
      throw wrapped;
    });
    const hostReadFile = (uri) => fsHostRequest('workspace.readFile', { uri: uri.toString(), base64: true }, uri.toString())
      .then((payload) => {
        if (!payload || payload.base64 === undefined) throw FileSystemError.FileNotFound('读取失败: ' + uri.toString());
        return Buffer.from(payload.base64, 'base64');
      });
    return {
      readFile: (uri) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'readFile', [uri])
        : hostReadFile(uri),
      writeFile: (uri, content) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'writeFile', [uri, content]).then(() => undefined)
        : fsHostRequest('workspace.writeFile', { uri: uri.toString(), base64: Buffer.from(content).toString('base64') }, uri.toString()).then(() => undefined),
      stat: (uri) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'stat', [uri])
        : fsHostRequest('workspace.stat', { uri: uri.toString() }, uri.toString()).then((info) => ({
          type: info && info.directory ? FileType.Directory : FileType.File,
          ctime: 0, mtime: info && info.mtime ? info.mtime : 0,
          size: info && info.size ? info.size : 0,
        })),
      readDirectory: (uri) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'readDirectory', [uri])
        : fsHostRequest('workspace.readDirectory', { uri: uri.toString() }, uri.toString())
          .then((entries) => (Array.isArray(entries) ? entries.map((e) => [e.name, e.directory ? FileType.Directory : FileType.File]) : [])),
      createDirectory: (uri) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'createDirectory', [uri]).then(() => undefined)
        : fsHostRequest('workspace.createDirectory', { uri: uri.toString() }, uri.toString()).then(() => undefined),
      delete: (uri, options) => fileSystemProviderFor(uri)
        ? providerCall(fileSystemProviderFor(uri), 'delete', [uri, options]).then(() => undefined)
        : fsHostRequest('workspace.delete', { uri: uri.toString() }, uri.toString()).then(() => undefined),
      rename: (from, to, options) => fileSystemProviderFor(from)
        ? providerCall(fileSystemProviderFor(from), 'rename', [from, to, options]).then(() => undefined)
        : fsHostRequest('workspace.rename', { from: from.toString(), to: to.toString() }, from.toString()).then(() => undefined),
      copy: (from, to, options) => fileSystemProviderFor(from)
        ? providerCall(fileSystemProviderFor(from), 'copy', [from, to, options]).then(() => undefined)
        : fsHostRequest('workspace.copy', { from: from.toString(), to: to.toString() }, from.toString()).then(() => undefined),
      isWritableFileSystem: (scheme) => scheme === undefined || !!fileSystemProviders.get(String(scheme).toLowerCase()),
    };
  },
  get workspaceState() { return stateAccessor(false); },
  get globalState() { return stateAccessor(true); },
};

// ==== languages =============================================================

function selectorToLanguages(selector) {
  const out = [];
  const visit = (value) => {
    if (value === null || value === undefined) return;
    if (typeof value === 'string') { out.push(value); return; }
    if (Array.isArray(value)) { value.forEach(visit); return; }
    if (typeof value === 'object' && value.language) { out.push(String(value.language)); }
  };
  visit(selector);
  return out.filter((v, i) => out.indexOf(v) === i);
}

let providerSeq = 1;
const completionProviders = new Map(); // id -> {languages, provider}

function registerProvider(kind, selector, provider, triggerCharacters) {
  const id = kind + '-' + (providerSeq++);
  const languages = selectorToLanguages(selector);
  if (kind === 'completion') completionProviders.set(id, { languages, provider });
  // 宿主目前只驱动 completion；其余能力如实上报，宿主会汇总成「尚未支持」清单，
  // 而不是让扩展静默失效、用户以为插件没装。
  notifyHost('registerProvider', {
    kind,
    id,
    languages,
    triggerCharacters: triggerCharacters || [],
  });
  return new Disposable(() => {
    completionProviders.delete(id);
    notifyHost('unregisterProvider', { kind, id });
  });
}

const diagnosticsCollections = new Map(); // name -> Map(uri -> items)

function serializeDiagnostics(list) {
  return (Array.isArray(list) ? list : []).map((d) => ({
    message: String(d.message === undefined ? '' : d.message),
    severity: typeof d.severity === 'number' ? d.severity : 0,
    line: d.range && d.range.start ? d.range.start.line : 0,
    character: d.range && d.range.start ? d.range.start.character : 0,
    source: d.source === undefined ? null : String(d.source),
    code: d.code === undefined ? null : String(d.code),
  }));
}

function diagnosticCollection(name) {
  const store = new Map();
  diagnosticsCollections.set(name, store);
  const push = (uri) => {
    notifyHost('diagnostics.update', {
      collection: name,
      uri: uri.toString(),
      items: store.get(uri.toString()) || [],
    });
    // 扩展（如 ESLint/诊断转发类）会监听 onDidChangeDiagnostics 做后续处理，必须真触发。
    events.didChangeDiagnostics.fire({ uris: [uri] });
  };
  return {
    name,
    set: (uriOrEntries, diagnostics) => {
      if (Array.isArray(uriOrEntries)) {
        for (const entry of uriOrEntries) {
          store.set(entry[0].toString(), serializeDiagnostics(entry[1]));
          push(entry[0]);
        }
        return;
      }
      store.set(uriOrEntries.toString(), serializeDiagnostics(diagnostics));
      push(uriOrEntries);
    },
    delete: (uri) => {
      store.delete(uri.toString());
      notifyHost('diagnostics.update', { collection: name, uri: uri.toString(), items: [] });
    },
    clear: () => {
      for (const uri of Array.from(store.keys())) {
        notifyHost('diagnostics.update', { collection: name, uri, items: [] });
      }
      store.clear();
    },
    forEach: (callback) => {
      for (const [uri, items] of store.entries()) callback(Uri.parse(uri), items.slice());
    },
    get: (uri) => store.get(uri.toString()),
    dispose: () => { store.clear(); diagnosticsCollections.delete(name); },
  };
}

const languagesApi = {
  createDiagnosticCollection: (name) => diagnosticCollection(name === undefined ? 'extension' : String(name)),
  // LanguageStatusItem 在官方属于 languages 命名空间（window 下那个是同名便利入口）。
  createLanguageStatusItem: (id, selector) => new LanguageStatusItem(id, selector),
  // 内联补全：宿主不驱动它，但注册必须成功返回 Disposable。
  registerInlineCompletionItemProvider: (selector, provider, ...triggerCharacters) =>
    registerProvider('inlineCompletion', selector, provider, triggerCharacters),
  // 切换文档语言：宿主没有语言服务，改掉实例上的语言标识即可（扩展通常只关心回读）。
  setTextDocumentLanguage: (document, languageId) => {
    const target = languageId === undefined ? '' : String(languageId);
    try { document.languageId = target; } catch (err) { /* 只读文档：忽略 */ }
    return Promise.resolve(document);
  },
  registerCompletionItemProvider: (selector, provider, ...triggers) => registerProvider('completion', selector, provider, triggers),
  registerHoverProvider: (selector, provider) => registerProvider('hover', selector, provider, []),
  registerDefinitionProvider: (selector, provider) => registerProvider('definition', selector, provider, []),
  registerDocumentSymbolProvider: (selector, provider) => registerProvider('documentSymbol', selector, provider, []),
  registerWorkspaceSymbolProvider: (provider) => registerProvider('workspaceSymbol', '*', provider, []),
  registerCodeLensProvider: (selector, provider) => registerProvider('codeLens', selector, provider, []),
  registerCodeActionsProvider: (selector, provider) => registerProvider('codeAction', selector, provider, []),
  registerDocumentFormattingEditProvider: (selector, provider) => registerProvider('documentFormat', selector, provider, []),
  registerDocumentRangeFormattingEditProvider: (selector, provider) => registerProvider('rangeFormat', selector, provider, []),
  registerRenameProvider: (selector, provider) => registerProvider('rename', selector, provider, []),
  registerSignatureHelpProvider: (selector, provider, ...triggers) => registerProvider('signatureHelp', selector, provider, triggers),
  registerReferenceProvider: (selector, provider) => registerProvider('reference', selector, provider, []),
  registerDocumentHighlightProvider: (selector, provider) => registerProvider('documentHighlight', selector, provider, []),
  registerFoldingRangeProvider: (selector, provider) => registerProvider('foldingRange', selector, provider, []),
  registerSelectionRangeProvider: (selector, provider) => registerProvider('selectionRange', selector, provider, []),
  registerColorProvider: (selector, provider) => registerProvider('color', selector, provider, []),
  registerCallHierarchyProvider: (selector, provider) => registerProvider('callHierarchy', selector, provider, []),
  registerTypeHierarchyProvider: (selector, provider) => registerProvider('typeHierarchy', selector, provider, []),
  registerImplementationProvider: (selector, provider) => registerProvider('implementation', selector, provider, []),
  registerTypeDefinitionProvider: (selector, provider) => registerProvider('typeDefinition', selector, provider, []),
  registerDeclarationProvider: (selector, provider) => registerProvider('declaration', selector, provider, []),
  registerDocumentLinkProvider: (selector, provider) => registerProvider('documentLink', selector, provider, []),
  registerOnTypeFormattingEditProvider: (selector, provider) => registerProvider('onTypeFormat', selector, provider, []),
  registerEvaluatableExpressionProvider: (selector, provider) => registerProvider('evaluatableExpression', selector, provider, []),
  registerInlineValuesProvider: (selector, provider) => registerProvider('inlineValues', selector, provider, []),
  registerInlayHintsProvider: (selector, provider) => registerProvider('inlayHints', selector, provider, []),
  registerLinkedEditingRangeProvider: (selector, provider) => registerProvider('linkedEditingRange', selector, provider, []),
  getLanguages: () => Promise.resolve(Array.from(new Set(Array.from(documents.values()).map((d) => d.languageId)))),
  setLanguageConfiguration: () => new Disposable(() => { }),
  match: () => 0,
  registerDocumentSemanticTokensProvider: (selector, provider, legend) => {
    const id = 'semanticTokens-' + (providerSeq++);
    notifyHost('registerProvider', { kind: 'semanticTokens', id, languages: selectorToLanguages(selector), triggerCharacters: [] });
    return new Disposable(() => notifyHost('unregisterProvider', { kind: 'semanticTokens', id }));
  },
  registerDocumentRangeSemanticTokensProvider: (selector, provider, legend) => {
    const id = 'rangeSemanticTokens-' + (providerSeq++);
    notifyHost('registerProvider', { kind: 'rangeSemanticTokens', id, languages: selectorToLanguages(selector), triggerCharacters: [] });
    return new Disposable(() => notifyHost('unregisterProvider', { kind: 'rangeSemanticTokens', id }));
  },
  registerDocumentDropEditProvider: (selector, provider) => registerProvider('documentDropEdit', selector, provider, []),
  registerDocumentPasteEditProvider: (selector, provider) => registerProvider('documentPasteEdit', selector, provider, []),
  // 扩展常靠 getDiagnostics 判断「自己刚发的诊断有没有被宿主接收」，返回真实快照而不是空数组。
  getDiagnostics: (resource) => {
    if (resource === undefined) {
      const out = [];
      for (const store of diagnosticsCollections.values()) {
        for (const [uri, items] of store.entries()) out.push([Uri.parse(uri), items.slice()]);
      }
      return out;
    }
    const out = [];
    for (const store of diagnosticsCollections.values()) {
      const items = store.get(resource.toString());
      if (items) out.push(...items);
    }
    return out;
  },
  onDidChangeDiagnostics: events.didChangeDiagnostics.event,
};

// ==== commands ==============================================================

const commandHandlers = new Map(); // id -> {callback, thisArg}

/** package.json `contributes.commands` 声明的命令 id 集合（用于区分「声明了但没注册」与「压根没声明」）。 */
function declaredCommandIds() {
  const out = new Set();
  const pkg = packageJson();
  const list = (pkg.contributes && pkg.contributes.commands) || [];
  for (const c of list) if (c && c.command) out.add(String(c.command));
  return out;
}

const commandsApi = {
  registerCommand: (id, callback, thisArg) => {
    const key = String(id);
    if (commandHandlers.has(key)) {
      notifyHost('log', { level: 'warn', text: '命令重复注册: ' + key });
    }
    commandHandlers.set(key, { callback, thisArg });
    notifyHost('registerCommand', { id: key });
    return new Disposable(() => {
      commandHandlers.delete(key);
      notifyHost('unregisterCommand', { id: key });
    });
  },
  registerTextEditorCommand: (id, callback, thisArg) => commandsApi.registerCommand(id, (...args) => {
    const editor = activeEditorRef;
    if (!editor) return undefined;
    return callback.call(thisArg, editor, editor.edit ? {
      insertSnippet: () => Promise.resolve(true),
      replace: (range, text) => editor.edit((b) => b.replace(range, text)),
    } : null, ...args);
  }, thisArg),
  executeCommand: (id, ...args) => {
    const key = String(id);
    const handler = commandHandlers.get(key);
    if (handler) {
      return Promise.resolve()
        .then(() => handler.callback.apply(handler.thisArg, args))
        .then((result) => serializeResult(result))
        .catch((err) => {
          notifyHost('log', { level: 'error', text: '命令 ' + key + ' 执行失败: ' + String((err && err.stack) || err) });
          return undefined;
        });
    }
    return requestToHost('commands.executeCommand', { command: key, args: serializeArgs(args) })
      .then((result) => result === null ? undefined : result)
      .catch((err) => {
        notifyHost('log', { level: 'error', text: '命令 ' + key + ' 未找到或执行失败: ' + String((err && err.message) || err) });
        return undefined;
      });
  },
  getCommands: () => Promise.resolve(Array.from(commandHandlers.keys())),
};

function serializeArgs(args) {
  return args.map((a) => {
    if (a === null || a === undefined) return null;
    if (typeof a === 'string' || typeof a === 'number' || typeof a === 'boolean') return a;
    if (a instanceof Uri) return a.toString();
    return safeStringify(a);
  });
}

function serializeResult(value) {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;
  if (value instanceof Uri) return value.toString();
  return safeStringify(value);
}

// ==== extensions ============================================================

let extensionIdentity = { id: 'unknown', displayName: 'extension', version: '0.0.0' };
let packageJsonCache = null;

function packageJson() {
  if (packageJsonCache === null) {
    try {
      packageJsonCache = JSON.parse(fs.readFileSync(path.join(extensionDir, 'package.json'), 'utf8'));
    } catch (err) {
      packageJsonCache = {};
    }
  }
  return packageJsonCache;
}

function selfExtension() {
  return {
    id: extensionIdentity.id,
    extensionPath: extensionDir,
    extensionUri: Uri.file(extensionDir),
    isActive: activated,
    packageJSON: packageJson(),
    exports: extensionExports,
    extensionKind: ExtensionKind.Workspace,
    activate: () => activateExtension('api'),
  };
}

/** 两个扩展 id 是否指同一个扩展（宽松匹配，用于单扩展宿主）。 */
function extensionIdMatchesId(requested, actual) {
  const want = String(requested || '').toLowerCase();
  const have = String(actual || '').toLowerCase();
  if (!want || !have) return false;
  if (want === have) return true;
  // 宿主内部 id 带 `vscode.` 前缀（vscode.golang.go），扩展自报的 id 常是 `golang.go`；
  // 也有反向（扩展问 ms-python.python，宿主装成 vscode.ms-python.python）。两种都放行。
  const stripPrefix = (v) => v.replace(/^(vscode|ms-vscode|ms-python)\./, '');
  return have.endsWith('.' + want) || want.endsWith('.' + have) || stripPrefix(want) === stripPrefix(have);
}

function extensionIdMatches(requested) {
  return extensionIdMatchesId(requested, extensionIdentity.id);
}

const extensionsApi = {
  getExtension: (id) => (extensionIdMatches(id) ? selfExtension() : builtinExtension(id)),
  get all() { return [selfExtension(), ...builtinExtensionList()]; },
  onDidChange: new EventEmitter().event,
};

// ==== 内置扩展（built-in）====================================================
//
// 真实 VS Code 自带一批内置扩展，插件常把它们当作「平台能力」直接用，而不是可选项：
//  - Vue Volar：`getExtension('vscode.typescript-language-features').isActive`，
//    拿到 undefined 就 `TypeError: Cannot read properties of undefined (reading 'isActive')`，
//    并且这行在**模块顶层**，于是整个插件连 require 都过不去（表现为「装了但永远不激活」）。
//  - 还有一批插件用 `getExtension('vscode.typescript-language-features')` 存在与否
//    来决定要不要自己接管 TS 支持。
// 因此 guest 侧提供一份**真实存在于磁盘上**的最小内置扩展集合：
// 有 package.json、有可解析的 main 文件，isActive 恒为 false（与全新宿主一致）。
// 目录由宿主在落盘 bootstrap.js 时一并铺好（hostDir/builtin/<name>）。
const BUILTIN_ROOT = typeof __dirname === 'string' ? path.join(__dirname, 'builtin') : null;
let builtinCache = null;

function loadBuiltins() {
  if (builtinCache) return builtinCache;
  const byId = new Map();
  if (BUILTIN_ROOT) {
    let names = [];
    try { names = fs.readdirSync(BUILTIN_ROOT); } catch (err) { names = []; }
    for (const name of names) {
      const dir = path.join(BUILTIN_ROOT, name);
      let pkg = null;
      try { pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')); } catch (err) { continue; }
      if (!pkg || !pkg.name) continue;
      const id = (pkg.publisher ? pkg.publisher + '.' : '') + pkg.name;
      byId.set(id.toLowerCase(), { id, dir, pkg });
    }
  }
  builtinCache = byId;
  return byId;
}

function builtinDescriptor(hit) {
  return {
    id: hit.id,
    extensionPath: hit.dir,
    extensionUri: Uri.file(hit.dir),
    // 内置扩展在全新宿主里尚未激活；如实上报 false，扩展据此走「稍后加载」分支。
    isActive: false,
    isBuiltin: true,
    packageJSON: hit.pkg,
    exports: undefined,
    extensionKind: ExtensionKind.Workspace,
    activate: () => Promise.resolve(undefined),
  };
}

function builtinExtension(id) {
  const wanted = String(id || '').toLowerCase();
  if (!wanted) return undefined;
  const all = loadBuiltins();
  if (all.has(wanted)) return builtinDescriptor(all.get(wanted));
  // 内置扩展 id 一律带 `vscode.` 前缀，插件偶尔省略或写成 `ms-vscode.`。
  for (const [key, hit] of all.entries()) {
    if (extensionIdMatchesId(wanted, key)) return builtinDescriptor(hit);
  }
  return undefined;
}

function builtinExtensionList() {
  return Array.from(loadBuiltins().values()).map(builtinDescriptor);
}

// ==== ExtensionContext ======================================================

let extensionContextRef = null;
const secretsStore = new Map();

function extensionContext() {
  const ensureDir = (p) => { try { fs.mkdirSync(p, { recursive: true }); } catch (err) { /* 已存在 */ } return Uri.file(p); };
  const storageRoot = path.join(extensionDir, '.nebulaforge');
  return {
    subscriptions: [],
    extensionPath: extensionDir,
    extensionUri: Uri.file(extensionDir),
    extension: selfExtension(),
    environmentVariableCollection: {
      persistent: false, replace() { }, append() { }, prepend() { }, get() { return undefined; },
      forEach() { }, delete() { }, clear() { },
    },
    globalState: stateAccessor(true),
    workspaceState: stateAccessor(false),
    secrets: {
      get: (key) => Promise.resolve(secretsStore.get(String(key))),
      store: (key, value) => { secretsStore.set(String(key), value); return Promise.resolve(); },
      delete: (key) => { secretsStore.delete(String(key)); return Promise.resolve(); },
      onDidChange: new EventEmitter().event,
    },
    extensionMode: ExtensionMode.Production,
    storageUri: ensureDir(path.join(storageRoot, 'storage')),
    globalStorageUri: ensureDir(path.join(storageRoot, 'global')),
    logUri: ensureDir(path.join(storageRoot, 'log')),
    asAbsolutePath: (rel) => path.join(extensionDir, String(rel)),
  };
}

// ==== vscode 模块 ===========================================================

// Claude Code 在加载 notebook 适配器时会读取 NotebookCellOutputItem.error(...).mime。
// 保持 VS Code 的最小数据形态即可：插件只需要 MIME 类型和二进制/文本内容，真正的
// notebook 渲染不在当前宿主职责内。
class NotebookCellOutputItem {
  constructor(data, mime) {
    this.data = data instanceof Uint8Array ? data : Buffer.from(String(data), 'utf8');
    this.mime = String(mime || 'text/plain');
  }
  static text(value, mime) { return new NotebookCellOutputItem(value, mime || 'text/plain'); }
  static error(error) {
    const value = error && error.stack ? error.stack : String(error);
    return new NotebookCellOutputItem(value, 'application/vnd.code.notebook.error');
  }
  static stdout(value, mime) { return new NotebookCellOutputItem(value, mime || 'application/vnd.code.notebook.stdout'); }
  static stderr(value, mime) { return new NotebookCellOutputItem(value, mime || 'application/vnd.code.notebook.stderr'); }
  static json(value, mime) {
    return new NotebookCellOutputItem(JSON.stringify(value), mime || 'application/json');
  }
  static markdown(value) { return new NotebookCellOutputItem(value, 'text/markdown'); }
}

// ==== notebook 支持 =========================================================
// Claude Code 会注册 notebook 适配器（serializer / 单元格数据类型），若这些构造器
// 缺失，加载适配器时就会抛 TypeError 直接导致整个扩展激活失败。这里给出与 VS Code
// 同形的最小实现：数据能构造、serializer 能被注册，真正的 notebook 渲染不在宿主职责内。
const NotebookCellKind = { Markup: 1, Code: 2 };

class NotebookRange {
  constructor(start, end) { this.start = start; this.end = end; }
  get isEmpty() { return this.start === this.end; }
  with(change) {
    return new NotebookRange(
      change.start === undefined ? this.start : change.start,
      change.end === undefined ? this.end : change.end
    );
  }
  static get empty() { return new NotebookRange(0, 0); }
}

class NotebookCellOutput {
  constructor(items, metadata) {
    this.items = (Array.isArray(items) ? items : [items]).map((item) =>
      item instanceof NotebookCellOutputItem ? item : new NotebookCellOutputItem(item && item.data, item && item.mime));
    this.metadata = metadata;
  }
}

class NotebookCellData {
  constructor(kind, value, languageId) {
    this.kind = kind;
    this.value = value;
    this.languageId = languageId;
    this.outputs = [];
    this.metadata = {};
    this.executionSummary = undefined;
  }
}

class NotebookData {
  constructor(cells) { this.cells = cells || []; this.metadata = {}; }
}

class NotebookEdit {
  constructor(range, newCells) { this.range = range; this.newCells = newCells; }
  static replaceCells(range, newCells) { return new NotebookEdit(range, newCells); }
  static insertCells(index, newCells) { return new NotebookEdit(new NotebookRange(index, index), newCells); }
  static deleteCells(range) { return new NotebookEdit(range, []); }
  static updateCellMetadata(index, newCellMetadata) { const e = new NotebookEdit(new NotebookRange(index, index + 1)); e.newCellMetadata = newCellMetadata; return e; }
  static updateNotebookMetadata(newNotebookMetadata) { const e = new NotebookEdit(NotebookRange.empty); e.newNotebookMetadata = newNotebookMetadata; return e; }
}

class NotebookCellStatusBarItem {
  constructor(text, alignment) {
    this.text = String(text);
    this.alignment = alignment;
    this.command = undefined;
    this.tooltip = undefined;
    this.priority = undefined;
    this.accessibilityInformation = undefined;
  }
  show() { }
  hide() { }
  dispose() { }
}

class NotebookCellStatusBarItemProviderRegistry { constructor() { this.items = new Map(); } }

// ==== vscode-languageclient 所需的数据类 ====================================
// vscode-languageclient 的协议转换器大量使用 `class ProtocolX extends code.X`。
// 只要对应基类在 vscode 命名空间里是 undefined，模块加载阶段就会抛
// “Class extends value undefined is not a constructor or null”，整个扩展（Go、
// Python、各类 LSP 扩展）都装不起来。这里补齐这些数据类与枚举。

class Command {
  constructor(title, command, ...args) {
    this.title = String(title);
    this.command = String(command);
    this.arguments = args.length ? args : undefined;
    this.tooltip = undefined;
  }
}

class Color {
  constructor(red, green, blue, alpha) { this.red = red; this.green = green; this.blue = blue; this.alpha = alpha; }
}

class ColorInformation {
  constructor(range, color) { this.range = range; this.color = color; }
}

class ColorPresentation {
  constructor(label) { this.label = String(label); this.textEdit = undefined; this.additionalTextEdits = undefined; }
}

const DocumentHighlightKind = { Text: 0, Read: 1, Write: 2 };

class DocumentHighlight {
  constructor(range, kind) { this.range = range; this.kind = kind; }
}

const FoldingRangeKind = { Comment: new class { constructor() { this.value = 'comment'; } }(), Imports: new class { constructor() { this.value = 'imports'; } }(), Region: new class { constructor() { this.value = 'region'; } }() };

class FoldingRange {
  constructor(start, end, kind) { this.start = start; this.end = end; this.kind = kind; }
}

class ParameterInformation {
  constructor(label, documentation) { this.label = label; this.documentation = documentation; }
}

class SignatureInformation {
  constructor(label, documentation) {
    this.label = String(label);
    this.documentation = documentation;
    this.parameters = [];
    this.activeParameter = undefined;
  }
}

class SignatureHelp {
  constructor() { this.signatures = []; this.activeSignature = 0; this.activeParameter = 0; }
}

class SelectionRange {
  constructor(range, parent) { this.range = range; this.parent = parent; }
}

class LocationLink {
  constructor(targetUri, targetRange, targetSelectionRange, originSelectionRange) {
    this.targetUri = targetUri;
    this.targetRange = targetRange;
    this.targetSelectionRange = targetSelectionRange;
    this.originSelectionRange = originSelectionRange;
  }
}

class CallHierarchyItem {
  constructor(kind, name, detail, uri, range, selectionRange) {
    this.kind = kind;
    this.name = String(name);
    this.detail = detail;
    this.uri = uri;
    this.range = range;
    this.selectionRange = selectionRange;
    this.tags = undefined;
  }
}

class CallHierarchyIncomingCall {
  constructor(item, fromRanges) { this.from = item; this.fromRanges = fromRanges; }
}

class CallHierarchyOutgoingCall {
  constructor(item, fromRanges) { this.to = item; this.fromRanges = fromRanges; }
}

class TypeHierarchyItem {
  constructor(kind, name, detail, uri, range, selectionRange) {
    this.kind = kind;
    this.name = String(name);
    this.detail = detail;
    this.uri = uri;
    this.range = range;
    this.selectionRange = selectionRange;
    this.tags = undefined;
  }
}

class SemanticTokens {
  constructor(data, resultId) {
    this.data = data instanceof Uint32Array || Array.isArray(data) ? data : new Uint32Array(0);
    this.resultId = resultId;
  }
}

class SemanticTokensEdit {
  constructor(start, deleteCount, data) { this.start = start; this.deleteCount = deleteCount; this.data = data; }
}

class SemanticTokensLegend {
  constructor(tokenTypes, tokenModifiers) { this.tokenTypes = tokenTypes || []; this.tokenModifiers = tokenModifiers || []; }
}

class SemanticTokensBuilder {
  constructor(legend) {
    this.legend = legend;
    this._data = [];
    this._prevLine = 0;
    this._prevChar = 0;
  }
  push(line, char, length, tokenType, tokenModifiers) {
    let deltaLine = line - this._prevLine;
    let deltaChar = deltaLine === 0 ? char - this._prevChar : char;
    this._data.push(deltaLine, deltaChar, length, tokenType, tokenModifiers || 0);
    this._prevLine = line; this._prevChar = char;
  }
  build() { return new SemanticTokens(new Uint32Array(this._data), undefined); }
  get isDirty() { return false; }
}

const InlayHintKind = { Type: 1, Parameter: 2 };

class InlayHintLabelPart {
  constructor(value) {
    this.value = String(value);
    this.tooltip = undefined;
    this.location = undefined;
    this.command = undefined;
  }
}

class InlayHint {
  constructor(position, label, kind) {
    this.position = position;
    this.label = label;
    this.kind = kind;
    this.tooltip = undefined;
    this.textEdits = undefined;
    this.paddingLeft = undefined;
    this.paddingRight = undefined;
  }
}

class WorkspaceSymbol {
  constructor(name, kind, containerNameOrUri, range) {
    this.name = String(name);
    this.kind = kind;
    if (containerNameOrUri instanceof Uri) {
      this.location = { uri: containerNameOrUri, range };
      this.containerName = undefined;
    } else {
      this.location = undefined;
      this.containerName = containerNameOrUri;
    }
  }
}

const CompletionItemTag = { Deprecated: 1 };
const DiagnosticTag = { Unnecessary: 1, Deprecated: 2 };
const SymbolTag = { Deprecated: 1 };

class DataTransferItem {
  constructor(value) { this.value = value; this._mime = undefined; }
  get asString() { return Promise.resolve(typeof this.value === 'string' ? this.value : JSON.stringify(this.value)); }
  get asFile() { return Promise.resolve(undefined); }
}
class DataTransfer {
  constructor() { this._items = new Map(); }
  get(mime) { const item = this._items.get(mime); return item ? item.value : undefined; }
  set(mime, value) { this._items.set(mime, new DataTransferItem(value)); }
  forEach(cb) { for (const [mime, item] of this._items) cb(item, mime); }
  get size() { return this._items.size; }
  [Symbol.iterator]() { return this._items.entries(); }
}

class LinkedEditingRanges {
  constructor(ranges, wordPattern) { this.ranges = ranges; this.wordPattern = wordPattern; }
}

/** 取消错误：LSP 扩展会 `class LSPCancellationError extends vscode.CancellationError`。 */
class CancellationError extends Error {
  constructor(message) {
    super(message);
    this.name = 'Canceled';
  }
}

const CancellationToken = {
  None: {
    isCancellationRequested: false,
    onCancellationRequested: () => new Disposable(() => { }),
  },
  Cancelled: {
    isCancellationRequested: true,
    onCancellationRequested: () => new Disposable(() => { }),
  },
};

const LanguageStatusSeverity = { Information: 0, Warning: 1, Error: 2 };

class LanguageStatusItem {
  constructor(id, selector) {
    this.id = String(id);
    this.selector = selector;
    this.name = undefined;
    this.text = '';
    this.detail = undefined;
    this.severity = LanguageStatusSeverity.Information;
    this.command = undefined;
    this.accessibilityInformation = undefined;
    this.busy = false;
    this.dispose = () => notifyHost('languageStatus.dispose', { id: this.id });
  }
  update() { notifyHost('languageStatus.update', { id: this.id, text: String(this.text), severity: this.severity }); }
}

// ==== 补充枚举 / 类（VS Code 1.84 常用面）====================================
// 说明：这里补齐的不是「完整 VS Code」，而是**扩展在激活路径上会直接构造/读取的类型**。
// 缺任何一个都会让扩展在 activate() 里抛 TypeError 并整体失败，用户看到的是「插件装了但没反应」。

const UIKind = { Desktop: 1, Web: 2 };
const ColorThemeKind = { Light: 1, Dark: 2, HighContrast: 3, HighContrastLight: 4 };
const FileChangeType = { Changed: 1, Created: 2, Deleted: 3 };
const FilePermission = { Readonly: 1 };
const CommentMode = { Editing: 0, Preview: 1 };
const CommentThreadCollapsibleState = { Collapsed: 0, Expanded: 1 };
const TextEditorSelectionChangeKind = { Keyboard: 1, Mouse: 2, Command: 3 };
const TextEditorLineNumbersStyle = { Off: 0, On: 1, Relative: 2 };
const TextDocumentSaveReason = { Manual: 1, AfterDelay: 2, FocusOut: 3 };
const EnvironmentVariableMutatorType = { Replace: 1, Append: 2, Prepend: 3 };
const NotebookCellStatusBarAlignment = { Left: 1, Right: 2 };
const NotebookCellExecutionState = { Idle: 1, Pending: 2, Executing: 3 };
const TaskRevealKind = { Always: 1, Silent: 2, Never: 3 };
const TaskPanelKind = { Shared: 1, Dedicated: 2, New: 3 };

class ColorTheme { constructor(kind) { this.kind = kind === undefined ? ColorThemeKind.Dark : kind; } }

/** 文本编辑器装饰类型：扩展只在 dispose / setDecorations 上用它，返回稳定对象即可。 */
class TextEditorDecorationType {
  constructor(options) {
    this.options = options || {};
    this.key = 'decoration-' + (nextId++);
  }
  dispose() { }
}

/** 结构化通知（window.showXxxMessage 的 {modal, detail, items} 形态）。 */
class MessageOptions {
  constructor(options) { Object.assign(this, options || {}); }
}

class QuickPick {
  constructor() {
    this.title = undefined;
    this.placeholder = undefined;
    this.value = '';
    this.items = [];
    this.activeItems = [];
    this.selectedItems = [];
    this.canSelectMany = false;
    this.busy = false;
    this.enabled = true;
    this.ignoreFocusOut = false;
    this.matchOnDescription = false;
    this.matchOnDetail = false;
    this.keepScrollPosition = false;
    this.buttons = [];
    this._onDidChangeValue = new EventEmitter();
    this._onDidChangeSelection = new EventEmitter();
    this._onDidAccept = new EventEmitter();
    this._onDidHide = new EventEmitter();
    this._onDidTriggerButton = new EventEmitter();
    this.onDidChangeValue = this._onDidChangeValue.event;
    this.onDidChangeSelection = this._onDidChangeSelection.event;
    this.onDidAccept = this._onDidAccept.event;
    this.onDidHide = this._onDidHide.event;
    this.onDidTriggerButton = this._onDidTriggerButton.event;
  }
  show() {
    return Promise.resolve().then(() => {
      const items = Array.isArray(this.items) ? this.items : [];
      const serialized = items.map((it) => (typeof it === 'string' ? { label: it } : {
        label: String(it.label === undefined ? '' : it.label),
        description: it.description === undefined ? null : String(it.description),
        detail: it.detail === undefined ? null : String(it.detail),
      }));
      return requestToHost('window.showQuickPick', {
        items: serialized,
        placeholder: this.placeholder ? String(this.placeholder) : (this.title ? String(this.title) : null),
        canPickMany: !!this.canSelectMany,
      }).then((selected) => {
        if (selected === null || selected === undefined) {
          this.hide();
          return;
        }
        const labels = Array.isArray(selected) ? selected : [selected];
        const picked = labels
          .map((label) => items.find((it) => (typeof it === 'string' ? it : it.label) === (typeof label === 'string' ? label : label && label.label)))
          .filter(Boolean);
        this.value = picked.length ? String(picked[0].label === undefined ? picked[0] : picked[0].label) : String(labels[0]);
        this._onDidChangeValue.fire(this.value);
        if (picked.length) {
          this.selectedItems = picked;
          this._onDidChangeSelection.fire(picked);
        }
        this._onDidAccept.fire();
      });
    });
  }
  hide() { this._onDidHide.fire(); }
  dispose() { this._onDidHide.fire(); }
}

class InputBox {
  constructor() {
    this.title = undefined;
    this.placeholder = undefined;
    this.value = '';
    this.password = false;
    this.prompt = undefined;
    this.ignoreFocusOut = false;
    this.enabled = true;
    this.busy = false;
    this.validationMessage = undefined;
    this.buttons = [];
    this._onDidChangeValue = new EventEmitter();
    this._onDidAccept = new EventEmitter();
    this._onDidHide = new EventEmitter();
    this._onDidTriggerButton = new EventEmitter();
    this.onDidChangeValue = this._onDidChangeValue.event;
    this.onDidAccept = this._onDidAccept.event;
    this.onDidHide = this._onDidHide.event;
    this.onDidTriggerButton = this._onDidTriggerButton.event;
  }
  show() {
    return requestToHost('window.showInputBox', {
      prompt: this.prompt ? String(this.prompt) : (this.title ? String(this.title) : null),
      value: this.value === undefined || this.value === null ? null : String(this.value),
      placeHolder: this.placeholder || null,
      password: !!this.password,
    }).then((v) => {
      if (v === null || v === undefined) { this.hide(); return; }
      this.value = String(v);
      this._onDidChangeValue.fire(this.value);
      this._onDidAccept.fire();
    });
  }
  hide() { this._onDidHide.fire(); }
  dispose() { this._onDidHide.fire(); }
}

/** TreeView：扩展会读 title/message/badge/selection 并注册事件。 */
class TreeView {
  constructor(viewId, options, provider) {
    this.viewId = String(viewId);
    this.options = options || {};
    this.provider = provider || null;
    this.visible = true;
    this.selection = [];
    this.title = undefined;
    this.description = undefined;
    this.badge = undefined;
    this.message = undefined;
    this._onDidChangeSelection = new EventEmitter();
    this._onDidChangeVisibility = new EventEmitter();
    this._onDidChangeCheckboxState = new EventEmitter();
    this._onDidExpandElement = new EventEmitter();
    this._onDidCollapseElement = new EventEmitter();
    this.onDidChangeSelection = this._onDidChangeSelection.event;
    this.onDidChangeVisibility = this._onDidChangeVisibility.event;
    this.onDidChangeCheckboxState = this._onDidChangeCheckboxState.event;
    this.onDidExpandElement = this._onDidExpandElement.event;
    this.onDidCollapseElement = this._onDidCollapseElement.event;
    treeViews.set(this.viewId, this);
  }
  reveal() { return Promise.resolve(); }
  dispose() { treeViews.delete(this.viewId); }
}

class FileDecoration {
  constructor(badge, tooltip, color) {
    this.badge = badge;
    this.tooltip = tooltip;
    this.color = color;
    this.propagate = false;
  }
}

class TaskGroup {
  constructor(id, label) { this.id = id; this.label = label; }
  static from(value, label) { return value instanceof TaskGroup ? value : new TaskGroup(value, label); }
}
// 官方静态成员必须齐全：很多扩展在模块顶层或 build 任务里直接 `TaskGroup.Build`。
TaskGroup.Clean = new TaskGroup('clean', 'Clean');
TaskGroup.Build = new TaskGroup('build', 'Build');
TaskGroup.Rebuild = new TaskGroup('rebuild', 'Rebuild');
TaskGroup.Test = new TaskGroup('test', 'Test');

const TaskScope = { Global: 1, Workspace: 2 };

class ShellExecution {
  constructor(commandLineOrCommand, argsOrOptions, options) {
    if (Array.isArray(argsOrOptions)) {
      this.commandLine = undefined;
      this.command = String(commandLineOrCommand);
      this.args = argsOrOptions;
      this.options = options || {};
    } else {
      this.commandLine = String(commandLineOrCommand);
      this.command = undefined;
      this.args = undefined;
      this.options = argsOrOptions || {};
    }
  }
}

class ProcessExecution {
  constructor(process, argsOrOptions, options) {
    this.process = String(process);
    this.args = Array.isArray(argsOrOptions) ? argsOrOptions : [];
    this.options = options || (Array.isArray(argsOrOptions) ? {} : (argsOrOptions || {}));
  }
}

class CustomExecution {
  constructor(callback) { this.callback = callback; }
}

class Task {
  constructor(taskDefinition, scope, name, source, execution, problemMatchers) {
    this.definition = taskDefinition || {};
    this.scope = scope === undefined ? 0 : scope;
    this.name = String(name === undefined ? '' : name);
    this.source = source === undefined ? '' : String(source);
    this.execution = execution;
    this.problemMatchers = problemMatchers === undefined ? [] : (Array.isArray(problemMatchers) ? problemMatchers : [problemMatchers]);
    this.detail = undefined;
    this.isBackground = false;
    this.group = undefined;
    this.presentationOptions = {};
    this.runOptions = {};
  }
}

class Comment {
  constructor(body, author, mode) {
    this.body = typeof body === 'string' ? new MarkdownString(body) : body;
    this.author = author || { name: 'unknown' };
    this.mode = mode === undefined ? CommentMode.Preview : mode;
    this.contextValue = undefined;
    this.label = undefined;
    this.reactions = [];
    this.timestamp = undefined;
  }
}

class CommentReply {
  constructor(uri, range, body) {
    this.uri = uri;
    this.range = range;
    this.body = typeof body === 'string' ? new MarkdownString(body) : body;
    this.author = { name: 'unknown' };
    this.contextValue = undefined;
    this.label = undefined;
    this.reactions = [];
    this.timestamp = undefined;
  }
}

class CommentThread {
  constructor(uri, range, comments) {
    this.uri = uri;
    this.range = range;
    this.comments = comments || [];
    this.collapsibleState = CommentThreadCollapsibleState.Collapsed;
    this.canReply = false;
    this.contextValue = undefined;
    this.label = undefined;
    this.state = undefined;
    this.threadId = 'thread-' + (nextId++);
    this._threads = [];
    this._onDidChangeThread = new EventEmitter();
    this.onDidChangeThread = this._onDidChangeThread.event;
  }
  dispose() { }
}

class CommentController {
  constructor(id, label) {
    this.id = String(id);
    this.label = label === undefined ? undefined : String(label);
    this.options = undefined;
    this.commentingRangeProvider = undefined;
    this.reactions = [];
    this._threads = new Set();
    this._onDidChangeCommentThread = new EventEmitter();
    this.onDidChangeCommentThread = this._onDidChangeCommentThread.event;
  }
  createCommentThread(uri, range, comments) {
    const thread = new CommentThread(uri, range, comments);
    this._threads.add(thread);
    return thread;
  }
  dispose() { this._threads.clear(); }
}

class DocumentDropEdit {
  constructor(insertText, title) {
    this.insertText = insertText;
    this.title = title;
    this.additionalEdit = undefined;
  }
}

class DocumentPasteEdit {
  constructor(insertText, title, kind) {
    this.insertText = insertText;
    this.title = title;
    this.kind = kind;
    this.additionalEdit = undefined;
  }
}

class SemanticTokensEdits {
  constructor(edits, resultId) { this.edits = edits || []; this.resultId = resultId; }
}

class TabInputText { constructor(uri) { this.uri = uri; } }
class TabInputTextDiff { constructor(original, modified) { this.original = original; this.modified = modified; } }

// ==== 未知 API 安全网 ========================================================
// 为什么需要：VS Code API 面非常大，任何**漏实现**的成员都会让扩展在 activate() 里直接
// 抛 `TypeError: xxx is not a function`，进而整插件失效。这里的兜底策略是：
//   on*/register*/create*/show* 类成员缺失时返回**良性桩**（可 dispose、可注册回调），
//   其它未知成员返回 undefined（保持 `typeof x === 'undefined'` 的能力探测语义）。
// 同时把「被兜底的 API 名」上报宿主，形成一份真实的缺口清单，而不是静默劣化。
const guardedApiNames = new Set();

/**
 * 通用兼容桩：为「宿主没实现」的 API 提供可用替身，而不是抛出 TypeError。
 *
 * 为什么必须这样：`class X extends vscode.DebugAdapterDescriptorFactory {}` 这类写法一旦拿到
 * `undefined`，JS 会抛 `TypeError: Class extends value undefined is not a constructor or null`，
 * 扩展在 activate() 阶段就整插件失败（实测 vscode.golang.go 正是这个错）。
 * 桩是**可构造、可调用、可链式取属性**的，因此 extends / new / 调用 / 静态枚举访问都不会崩。
 * 同一路径只告警一次，并上报宿主，形成可见的 API 缺口清单（不做静默劣化）。
 */
const compatStubs = new Map();

function makeCompatStub(apiPath) {
  const cached = compatStubs.get(apiPath);
  if (cached) return cached;
  const leaf = String(apiPath).split('.').pop() || 'CompatStub';
  const base = function () { return makeCompatStub(apiPath + '()'); };
  try { Object.defineProperty(base, 'name', { value: leaf }); } catch (err) { /* 忽略只读 name */ }
  const stub = new Proxy(base, {
    get(t, prop, receiver) {
      if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined; // 别变成 thenable，否则 await 永挂
      if (prop === Symbol.toPrimitive) return () => 0;
      if (prop === 'toString') return () => '[NebulaCompatStub ' + apiPath + ']';
      if (prop === 'valueOf' || prop === Symbol.toStringTag) return () => 0;
      if (typeof prop === 'symbol') return Reflect.get(t, prop, receiver);
      if (prop in t) return Reflect.get(t, prop, receiver);
      return makeCompatStub(apiPath + '.' + String(prop));
    },
    apply() { return makeCompatStub(apiPath + '()'); },
    construct() {
      // 供 `extends` 得到的实例用：带 dispose/事件，符合 VS Code 大部分返回对象的最小契约。
      return { dispose() { }, onDidChange() { return new Disposable(() => { }); }, onDidDispose: new EventEmitter().event };
    },
  });
  compatStubs.set(apiPath, stub);
  return stub;
}

function warnUnsupported(apiPath) {
  if (guardedApiNames.has(apiPath)) return;
  guardedApiNames.add(apiPath);
  notifyHost('log', { level: 'warn', text: '扩展宿主未实现 API，已用兼容桩兜底: ' + apiPath });
  notifyHost('unsupportedApi', { api: apiPath });
}

function guardNamespace(target, label) {
  return new Proxy(target, {
    get(obj, prop, receiver) {
      if (typeof prop === 'symbol') return Reflect.get(obj, prop, receiver);
      if (prop in obj) return Reflect.get(obj, prop, receiver);
      const name = String(prop);
      if (/^(on[A-Z]|register[A-Z]|create[A-Z]|show[A-Z]|provide)/.test(name)) {
        const key = label + '.' + name;
        if (!guardedApiNames.has(key)) {
          guardedApiNames.add(key);
          notifyHost('log', { level: 'warn', text: '扩展宿主未实现 API，已用兼容桩兜底: ' + key });
          notifyHost('unsupportedApi', { api: key });
        }
        return (...args) => {
          if (/^on[A-Z]/.test(name)) {
            // 事件注册：返回可 dispose 的订阅，并附带 .event 兼容写法。
            const sub = new Disposable(() => { });
            return Object.assign(() => new Disposable(() => { }), { event: () => sub, dispose: () => sub.dispose() });
          }
          if (/^register[A-Z]/.test(name)) return new Disposable(() => { });
          if (/^(create|show|provide)/.test(name)) {
            // create* 可能被当构造器返回值使用；返回「有 dispose/事件」的通用对象最不容易崩。
            return new (class CompatStub {
              constructor() { this.dispose = () => { }; this.onDidDispose = new EventEmitter().event; this.onDidChange = new EventEmitter().event; }
            })();
          }
          return undefined;
        };
      }
      // 未命中的未知成员：仅对「大写开头」（按 TS 定义基本都是 class/枚举/命名空间）降级为桩，
      // 以便 `extends` / 静态成员访问不崩；小写成员仍返回 undefined，保留 `if (api.x)` 的能力探测语义。
      if (/^[A-Z]/.test(name)) {
        warnUnsupported(label + '.' + name);
        return makeCompatStub(label + '.' + name);
      }
      return undefined;
    },
  });
}

// ==== 补充命名空间（激活路径上高频使用）=====================================
class TelemetryLogger {
  constructor(sender, options) { this.sender = sender; this.options = options || {}; this.onDidChangeEnableStates = new EventEmitter().event; }
  logUsage(eventName, data) { this._send('usage', eventName, data); }
  logError(eventNameOrError, data) {
    const eventName = typeof eventNameOrError === 'string' ? eventNameOrError : 'error';
    this._send('error', eventName, data === undefined ? { message: String(eventNameOrError && eventNameOrError.message) } : data);
  }
  _send(kind, eventName, data) {
    try {
      if (this.sender) (kind === 'error' ? this.sender.sendErrorData : this.sender.sendEventData).call(this.sender, eventName, data);
    } catch (err) { /* 遥测失败绝不能影响扩展 */ }
  }
  dispose() { }
}

class NotebookController {
  constructor(id, notebookType, label) {
    this.id = String(id);
    this.notebookType = String(notebookType);
    this.label = String(label);
    this.supportedLanguages = undefined;
    this.supportsExecutionOrder = false;
    this.description = undefined;
    this.detail = undefined;
    this.executeHandler = undefined;
    this.interruptHandler = undefined;
    this.rendererScripts = [];
    this._onDidChangeSelectedNotebooks = new EventEmitter();
    this.onDidChangeSelectedNotebooks = this._onDidChangeSelectedNotebooks.event;
    notifyHost('registerNotebookController', { id: this.id, notebookType: this.notebookType, label: this.label });
  }
  createNotebookCellExecution() {
    return {
      token: new CancellationTokenSource().token,
      executionOrder: undefined,
      start: () => Promise.resolve(),
      end: () => Promise.resolve(),
      clearOutput: () => Promise.resolve(),
      replaceOutput: () => Promise.resolve(),
      appendOutput: () => Promise.resolve(),
      replaceOutputItems: () => Promise.resolve(),
      appendOutputItems: () => Promise.resolve(),
    };
  }
  updateNotebookAffinity() { }
  dispose() { notifyHost('unregisterNotebookController', { id: this.id }); }
}

class SourceControl {
  constructor(id, label, rootUri) {
    this.id = String(id);
    this.label = label;
    this.rootUri = rootUri;
    this.inputBox = { value: '', placeholder: undefined, enabled: true, visible: true, onDidChange: new EventEmitter().event, onDidChangeValue: new EventEmitter().event, dispose() { } };
    this.count = undefined;
    this.quickDiffProvider = undefined;
    this.commitTemplate = undefined;
    this.acceptInputCommand = undefined;
    this.statusBarCommands = [];
    this._resources = [];
    this.onDidChangeInputBox = new EventEmitter().event;
  }
  createResourceGroup(id, label) { return { id, label, hideWhenEmpty: undefined, resources: [], dispose() { } }; }
  dispose() { }
}

const l10nApi = {
  // 新版本扩展会在**模块顶层**就调用 l10n.t（require 阶段），缺它会导致加载即抛错。
  t: (message, ...args) => {
    const template = typeof message === 'string' ? message : String(message && message.message);
    if (args.length === 0) return template;
    let index = 0;
    return template.replace(/\{(\d+)\}/g, (match, num) => {
      const i = Number(num);
      const value = args[i];
      if (value === undefined) return match;
      if (typeof value === 'object' && value !== null && 'value' in value) return String(value.value);
      if (typeof value === 'object' && value !== null && value.toString) return value.toString();
      return String(value);
    });
  },
  bundle: undefined,
  uri: undefined,
};

const tasksApi = {
  registerTaskProvider: (type, provider) => workspaceApi.registerTaskProvider(type, provider),
  fetchTasks: (filter) => Promise.resolve([]),
  executeTask: () => Promise.resolve({ definition: {}, name: 'task', source: 'nebula', execution: undefined, isBackground: false, problemMatchers: [], presentationOptions: {}, runOptions: {} }),
  get taskExecutions() { return []; },
  onDidStartTask: new EventEmitter().event,
  onDidEndTask: new EventEmitter().event,
  onDidStartTaskProcess: new EventEmitter().event,
  onDidEndTaskProcess: new EventEmitter().event,
  onDidWriteTerminalData: new EventEmitter().event,
};

const notebooksApi = {
  createNotebookController: (id, notebookType, label) => new NotebookController(id, notebookType, label),
  registerNotebookCellStatusBarItemProvider: (notebookType, provider) => {
    notifyHost('registerNotebookCellStatusBarItemProvider', { notebookType: String(notebookType) });
    return new Disposable(() => { });
  },
  createRendererMessaging: () => ({ onDidReceiveMessage: new EventEmitter().event, postMessage: () => Promise.resolve(true), dispose() { } }),
  onDidChangeNotebookDocument: new EventEmitter().event,
  onDidChangeNotebookEditorSelection: new EventEmitter().event,
};

const authenticationApi = {
  getSession: () => Promise.resolve(undefined),
  getAccounts: () => Promise.resolve([]),
  onDidChangeSessions: new EventEmitter().event,
  registerAuthenticationProvider: (id, label, provider, options) => {
    notifyHost('registerAuthenticationProvider', { id: String(id), label: String(label) });
    return new Disposable(() => { });
  },
};

const debugApi = {
  get activeDebugSession() { return undefined; },
  get activeDebugConsole() { return { append: () => { }, appendLine: () => { } }; },
  get breakpoints() { return debugBreakpoints.slice(); },
  startDebugging: () => Promise.resolve(true),
  stopDebugging: () => Promise.resolve(),
  registerDebugAdapterDescriptorFactory: (debugType, factory) => new Disposable(() => { }),
  registerDebugConfigurationProvider: (debugType, provider) => new Disposable(() => { }),
  registerDebugAdapterTrackerFactory: (debugType, factory) => new Disposable(() => { }),
  onDidStartDebugSession: new EventEmitter().event,
  onDidTerminateDebugSession: new EventEmitter().event,
  onDidReceiveDebugSessionCustomEvent: new EventEmitter().event,
  onDidChangeActiveDebugSession: new EventEmitter().event,
  onDidChangeBreakpoints: new EventEmitter().event,
  // 断点管理：宿主没有调试引擎，但这三个是 DebugConfigurationProvider 的常见调用点，
  // 返回 undefined 会让扩展在 activate 里直接抛错，所以留真实的登记行为。
  addBreakpoints: (breakpoints) => {
    for (const bp of breakpoints || []) debugBreakpoints.push(bp);
    return Promise.resolve();
  },
  removeBreakpoints: (breakpoints) => {
    for (const bp of breakpoints || []) {
      const at = debugBreakpoints.indexOf(bp);
      if (at >= 0) debugBreakpoints.splice(at, 1);
    }
    return Promise.resolve();
  },
  asDebugSourceUri: (source, session) => {
    const p = source && source.path ? String(source.path) : (source && source.sourceReference !== undefined ? 'debug-source-' + source.sourceReference : 'debug-source');
    const base = Uri.file(p);
    const query = source && source.sourceReference !== undefined ? '?ref=' + source.sourceReference : '';
    return query ? Uri.parse(base.toString() + query) : base;
  },
};

const debugBreakpoints = [];

const scmApi = {
  createSourceControl: (id, label, rootUri) => {
    notifyHost('scm.createSourceControl', { id: String(id), label });
    return new SourceControl(id, label, rootUri);
  },
  get inputBox() { return undefined; },
};

const testsApi = {
  createTestController: (id, label) => ({
    id: String(id), label: String(label), items: new Map(), resolveHandler: undefined, refreshHandler: undefined,
    createTestItem: () => ({ id: '', label: '', uri: undefined, children: new Map(), parent: undefined, error: undefined, tags: [], canResolveChildren: false, busy: false, range: undefined, dispose() { } }),
    createRunProfile: () => ({ dispose() { } }),
    createTestRun: () => ({ enqueued: () => { }, started: () => { }, passed: () => { }, failed: () => { }, errored: () => { }, skipped: () => { }, appendOutput: () => { }, end: () => { }, dispose() { } }),
    dispose() { },
  }),
  onDidChangeTestResults: new EventEmitter().event,
};

let clipboardLastWrite = '';

const envApi = {
  appName: 'NebulaForge IDE',  appHost: 'desktop',
  appRoot: extensionDir,
  language: 'zh-cn',
  machineId: 'nebulaforge',
  sessionId: String(process.pid),
  uiKind: UIKind.Desktop,
  uriScheme: 'nebulaforge',
  shell: '/bin/bash',
  remoteName: undefined,
  logLevel: LogLevel.Info,
  isTelemetryEnabled: false,
  isNewAppInstall: false,
  onDidChangeTelemetryEnabled: new EventEmitter().event,
  onDidChangeLogLevel: new EventEmitter().event,
  onDidChangeShell: events.didChangeShell.event,
  clipboard: {
    readText: () => Promise.resolve(''),
    writeText: (value) => { clipboardLastWrite = String(value); return Promise.resolve(); },
  },
  createTelemetryLogger: (sender, options) => new TelemetryLogger(sender, options),
  // 真机可用：交给宿主进程用 Intent 打开系统浏览器。
  // 说明：app 从 Android 12 起不能再借 `/system/bin/am` 启动 Activity（UID 校验会拒绝），
  // 所以这里必须走 RPC 让宿主去做。只放行 http(s)。
  openExternal: (uri) => {
    const target = typeof uri === 'string'
      ? uri
      : (uri && typeof uri.toString === 'function' ? uri.toString() : '');
    if (!/^https?:\/\//i.test(target)) {
      notifyHost('log', { level: 'warn', text: 'openExternal 已拒绝非 http(s) 目标：' + target });
      return Promise.resolve(false);
    }
    return requestToHost('window.openExternal', { url: target })
      .then((res) => {
        const opened = !(res && typeof res === 'object' && res.opened === false);
        notifyHost('log', {
          level: opened ? 'info' : 'warn',
          text: opened ? ('已交给系统浏览器打开：' + target) : ('宿主未能打开外部链接：' + target),
        });
        return opened;
      })
      .catch((err) => {
        notifyHost('log', { level: 'warn', text: 'openExternal 调用宿主失败：' + String((err && err.message) || err) });
        return false;
      });
  },
  asExternalUri: (uri) => Promise.resolve(uri),
};

// ==== 按官方 vscode.d.ts 补齐的枚举 ==========================================
// 这些值必须**一个不缺**。扩展常在模块顶层或构造期就读它们
// （Prettier 12 顶层执行 `CodeActionKind.SourceFixAll.append('prettier')`、
//  rust-analyzer 构造期读某些枚举成员），缺一个就是 TypeError，
// 结果不是「功能降级」而是**整个插件加载失败**。
const CodeActionTriggerKind = { Invoke: 1, Automatic: 2 };
const CommentThreadState = { Unresolved: 0, Resolved: 1 };
const DebugConfigurationProviderTriggerKind = { Initial: 1, Dynamic: 2 };
const DebugConsoleMode = { Separate: 0, MergeWithParent: 1 };
const DecorationRangeBehavior = { OpenOpen: 0, ClosedClosed: 1, OpenClosed: 2, ClosedOpen: 3 };
const IndentAction = { None: 0, Indent: 1, IndentOutdent: 2, Outdent: 3 };
const InlineCompletionTriggerKind = { Invoke: 0, Automatic: 1 };
const InputBoxValidationSeverity = { Info: 1, Warning: 2, Error: 3 };
const QuickInputButtonLocation = { Inline: 1, Input: 2 };
const NotebookControllerAffinity = { Default: 1, Preferred: 2 };
const NotebookEditorRevealType = { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 };
const OverviewRulerLane = { Left: 1, Center: 2, Right: 4, Full: 7 };
const ShellQuoting = { Escape: 1, Strong: 2, Weak: 3 };
const SignatureHelpTriggerKind = { Invoke: 1, TriggerCharacter: 2, ContentChange: 3 };
const SyntaxTokenType = { Other: 0, Comment: 1, String: 2, RegEx: 3 };
const TerminalExitReason = { Unknown: 0, Shutdown: 1, Process: 2, User: 3, Extension: 4 };
const TerminalLocation = { Panel: 1, Editor: 2 };
const TestRunProfileKind = { Run: 1, Debug: 2, Coverage: 3 };
const TextDocumentChangeReason = { Undo: 1, Redo: 2 };
const TextEditorCursorStyle = { Line: 1, Block: 2, Underline: 3, LineThin: 4, BlockOutline: 5, UnderlineThin: 6 };
const TreeItemCheckboxState = { Unchecked: 0, Checked: 1 };

// ==== 按官方 vscode.d.ts 补齐的数据类 ========================================
// 这些类宿主本身不执行它们的行为，但扩展会 `new` 它们并交给宿主/语言服务器。
// 缺失时抛的是 `vscode.Xxx is not a constructor`，同样是整插件失败。
// 计数器必须声明在使用它的类**之前**：否则构造期读它就是 TDZ ReferenceError
// （和之前 ColorThemeKind 那个坑是同一类问题）。
let breakpointSeq = 0;

class Breakpoint {
  constructor(location, enabled, condition, hitCondition, logMessage) {
    this.id = 'breakpoint-' + (++breakpointSeq);
    this.location = location;
    this.enabled = enabled === undefined ? true : !!enabled;
    this.condition = condition;
    this.hitCondition = hitCondition;
    this.logMessage = logMessage;
  }
}

class DebugAdapterExecutable {
  constructor(command, args, options) {
    this.command = command;
    this.args = args || [];
    this.options = options;
  }
}

class DebugAdapterServer {
  constructor(port, host) { this.port = port; this.host = host; }
}

class DebugAdapterNamedPipeServer {
  constructor(path) { this.path = path; }
}

class DebugAdapterInlineImplementation {
  constructor(implementation) { this.implementation = implementation; }
}

class EvaluatableExpression {
  constructor(range, expression) { this.range = range; this.expression = expression; }
}

class InlineCompletionItem {
  constructor(insertText, range, command) {
    this.insertText = insertText;
    this.range = range;
    this.command = command;
  }
}

class InlineCompletionList {
  constructor(items) { this.items = items || []; }
}

class InlineValueText {
  constructor(range, text) { this.range = range; this.text = text; }
}

class InlineValueVariableLookup {
  constructor(range, variableName, caseSensitiveLookup) {
    this.range = range;
    this.variableName = variableName;
    this.caseSensitiveLookup = caseSensitiveLookup === undefined ? true : !!caseSensitiveLookup;
  }
}

class InlineValueEvaluatableExpression {
  constructor(range, expression) { this.range = range; this.expression = expression; }
}

class QuickInputButtons {
  constructor() {}
}
QuickInputButtons.Back = { iconPath: undefined, tooltip: 'Back' };

class SnippetTextEdit {
  constructor(range, snippet) { this.range = range; this.snippet = snippet; }
}
SnippetTextEdit.replace = function (range, snippet) { return new SnippetTextEdit(range, snippet); };
SnippetTextEdit.insert = function (position, snippet) {
  return new SnippetTextEdit(new Range(position, position), snippet);
};

class TabInputCustom {
  constructor(uri, viewType) { this.uri = uri; this.viewType = viewType; }
}

class TabInputNotebook {
  constructor(uri, notebookType) { this.uri = uri; this.notebookType = notebookType; }
}

class TabInputNotebookDiff {
  constructor(original, modified, notebookType) {
    this.original = original; this.modified = modified; this.notebookType = notebookType;
  }
}

class TabInputTerminal {
  constructor() {}
}

class TabInputWebview {
  constructor(viewType) { this.viewType = viewType; }
}

class TelemetryTrustedValue {
  constructor(value) { this.value = value; }
}

class TerminalLink {
  constructor(startIndex, length, tooltip) {
    this.startIndex = startIndex; this.length = length; this.tooltip = tooltip;
  }
}

class TerminalProfile {
  constructor(options) { this.options = options; }
}

class TestMessage {
  constructor(message) { this.message = message; }
}
TestMessage.diff = function (message, expected, actual) {
  const m = new TestMessage(message);
  m.expected = expected;
  m.actual = actual;
  return m;
};

class TestTag {
  constructor(id) { this.id = id; }
}
TestTag.create = function (id) { return new TestTag(id); };

class TestRunRequest {
  constructor(include, exclude, profile, continuous, preserveFocus) {
    this.include = include;
    this.exclude = exclude;
    this.profile = profile;
    this.continuous = continuous === undefined ? false : !!continuous;
    this.preserveFocus = preserveFocus;
  }
}

// ==== lm（语言模型）/ chat（会话参与者）========================================
//
// 这两块以前在宿主里**完全不存在**：扩展写 `vscode.lm.registerTool(...)` 直接
// TypeError: Cannot read properties of undefined —— ms-python 就是这么在 activate 里崩掉的。
//
// 这里给出**真实**而不是空壳的实现：
//  - registerTool 落到本地注册表，宿主可以反向调用（invokeLmTool），IDE 内的 AI Agent 因此
//    能复用插件工具，而不是"插件声明了工具但没人能调"；
//  - selectChatModels / sendRequest 走宿主的能力网关：**没有授权就没有模型**（返回空列表，
//    这是 VS Code 的合法语义），因此插件会退化成"提示用户配置"，而不是拿到假数据继续跑。

const lmToolRegistry = new Map();

function lmTokenOrDefault(token) {
  return token || { isCancellationRequested: false, onCancellationRequested: () => new Disposable(() => { }) };
}

function lmSerializeToolResult(result) {
  if (result === undefined || result === null) return '';
  if (typeof result === 'string') return result;
  const content = result.content;
  if (Array.isArray(content)) {
    return content.map((part) => {
      if (part === null || part === undefined) return '';
      if (typeof part === 'string') return part;
      if (typeof part.value === 'string') return part.value;
      try { return JSON.stringify(part); } catch { return String(part); }
    }).join('\n');
  }
  try { return JSON.stringify(result); } catch { return String(result); }
}

const lmApi = {
  get tools() {
    return Array.from(lmToolRegistry.values()).map((t) => ({
      name: t.name, description: t.description, inputSchema: t.inputSchema, tags: t.tags || [],
    }));
  },
  get languageModels() { return []; },
  onDidChangeChatModels: () => new Disposable(() => { }),
  selectChatModels: (selector) =>
    requestToHost('lm.selectChatModels', { selector: selector || {} })
      .then((r) => (r && Array.isArray(r.models) ? r.models : []))
      .catch(() => []),
  registerTool: (tool) => {
    const name = tool && tool.name !== undefined ? String(tool.name) : '';
    if (!name) throw new Error('lm.registerTool：缺少 name');
    lmToolRegistry.set(name, {
      name,
      description: tool.description === undefined ? '' : String(tool.description),
      inputSchema: tool.inputSchema,
      tags: tool.tags,
      invoke: typeof tool.invoke === 'function' ? tool.invoke.bind(tool) : null,
    });
    notifyHost('lm.registerTool', {
      name,
      description: tool.description === undefined ? '' : String(tool.description),
    });
    return new Disposable(() => { lmToolRegistry.delete(name); notifyHost('lm.unregisterTool', { name }); });
  },
  registerTools: (tools) => {
    const disposables = (Array.isArray(tools) ? tools : []).map((t) => lmApi.registerTool(t));
    return new Disposable(() => disposables.forEach((d) => { try { d.dispose(); } catch { } }));
  },
  invokeTool: (name, options, token) =>
    requestToHost('lm.invokeTool', { name: String(name), input: (options && options.input) || {} })
      .then((r) => {
        const text = r && r.text !== undefined ? String(r.text) : '';
        return { content: [{ value: text }], toString: () => text };
      })
      .catch((e) => {
        throw e instanceof Error ? e : new Error('lm.invokeTool 失败：' + String(e));
      }),
  sendRequest: (messages, options, token) => {
    const list = Array.isArray(messages) ? messages : [messages];
    const flat = list.map((m) => {
      if (m === null || m === undefined) return '';
      if (typeof m === 'string') return m;
      const content = m.content;
      if (typeof content === 'string') return content;
      if (Array.isArray(content)) {
        return content.map((part) => (part && typeof part.value === 'string' ? part.value : '')).join('');
      }
      return '';
    }).filter((t) => t !== '');
    return requestToHost('lm.sendRequest', { messages: flat.map((c) => ({ content: c })) })
      .then((r) => {
        const text = r && r.text !== undefined ? String(r.text) : '';
        const response = {
          text,
          stream: (async function* () { yield text; })(),
        };
        response[Symbol.asyncIterator] = response.stream;
        return response;
      })
      .catch((e) => { throw e instanceof Error ? e : new Error('模型调用失败：' + String(e)); });
  },
  fileIsIgnored: () => Promise.resolve(false),
};

const chatParticipantRegistry = new Map();

const chatApi = {
  createChatParticipant: (id, handler) => {
    const sid = String(id);
    chatParticipantRegistry.set(sid, handler);
    notifyHost('chat.registerParticipant', { id: sid });
    return {
      iconPath: undefined,
      dispose: () => { chatParticipantRegistry.delete(sid); },
      onDidReceiveFeedback: () => new Disposable(() => { }),
      requestHandler: handler,
    };
  },
  registerChatParticipant: (participant) => {
    const sid = participant && participant.id !== undefined ? String(participant.id) : 'participant';
    chatParticipantRegistry.set(sid, participant);
    notifyHost('chat.registerParticipant', { id: sid });
    return new Disposable(() => { chatParticipantRegistry.delete(sid); });
  },
  registerChatRequestHandler: () => new Disposable(() => { }),
  registerChatSessionItemProvider: () => new Disposable(() => { }),
  onDidReceiveFeedback: () => new Disposable(() => { }),
  onDidChangeChatModels: () => new Disposable(() => { }),
};

// 宿主 → 扩展：调用扩展注册的模型工具。
onRequest('invokeLmTool', async (params) => {
  const name = params && params.name !== undefined ? String(params.name) : '';
  const entry = lmToolRegistry.get(name);
  if (!entry) return { ok: false, text: '扩展没有注册模型工具 ' + name };
  if (!entry.invoke) return { ok: false, text: '模型工具 ' + name + ' 没有 invoke 实现' };
  try {
    const result = await entry.invoke({ input: (params && params.input) || {} }, lmTokenOrDefault());
    return { ok: true, text: lmSerializeToolResult(result) };
  } catch (e) {
    return { ok: false, text: '模型工具 ' + name + ' 调用失败：' + (e && e.message ? e.message : String(e)) };
  }
});

const vscodeApiBase = {
  version: '1.84.0',
  Position, Range, Selection, Uri, Disposable, EventEmitter, CancellationTokenSource,
  RelativePattern, CompletionItem, CompletionItemKind, CompletionTriggerKind, SnippetString,
  ThemeColor, MarkdownString, DiagnosticSeverity, ProgressLocation, StatusBarAlignment,
  Diagnostic, DiagnosticRelatedInformation, Location, TextEdit, WorkspaceEdit, CompletionList,
  Hover, DocumentLink, CodeLens, CodeAction, CodeActionKind, SymbolKind, SymbolInformation,
  DocumentSymbol, TreeItem, TreeItemCollapsibleState, ThemeIcon, FileSystemError,
  ViewColumn, ConfigurationTarget, TextEditorRevealType, EndOfLine, FileType, ExtensionMode,
  ExtensionKind, QuickPickItemKind, NotebookCellOutputItem,
  LogLevel, Command, Color, ColorInformation, ColorPresentation, DocumentHighlight,
  DocumentHighlightKind, FoldingRange, FoldingRangeKind, ParameterInformation,
  SignatureInformation, SignatureHelp, SelectionRange, LocationLink, CallHierarchyItem,
  CallHierarchyIncomingCall, CallHierarchyOutgoingCall, TypeHierarchyItem, SemanticTokens,
  SemanticTokensEdit, SemanticTokensLegend, SemanticTokensBuilder, InlayHint, InlayHintKind,
  InlayHintLabelPart, WorkspaceSymbol, CompletionItemTag, DiagnosticTag, SymbolTag,
  DataTransfer, DataTransferItem, LinkedEditingRanges,
  CancellationError, CancellationToken, LanguageStatusSeverity, LanguageStatusItem,
  NotebookCellKind, NotebookRange, NotebookCellData, NotebookData, NotebookCellOutput,
  NotebookEdit, NotebookCellStatusBarItem,
  // 数据类 / 枚举补齐（对照官方 vscode.d.ts 的差异清单）
  Breakpoint, DebugAdapterExecutable, DebugAdapterServer, DebugAdapterNamedPipeServer,
  DebugAdapterInlineImplementation, EvaluatableExpression, InlineCompletionItem,
  InlineCompletionList, InlineValueText, InlineValueVariableLookup,
  InlineValueEvaluatableExpression, QuickInputButtons, SnippetTextEdit, TabInputCustom,
  TabInputNotebook, TabInputNotebookDiff, TabInputTerminal, TabInputWebview,
  TelemetryTrustedValue, TerminalLink, TerminalProfile, TestMessage, TestTag, TestRunRequest,
  CodeActionTriggerKind, CommentThreadState, DebugConfigurationProviderTriggerKind,
  DebugConsoleMode, DecorationRangeBehavior, IndentAction, InlineCompletionTriggerKind,
  InputBoxValidationSeverity, QuickInputButtonLocation, NotebookControllerAffinity, NotebookEditorRevealType,
  OverviewRulerLane, ShellQuoting, SignatureHelpTriggerKind, SyntaxTokenType,
  TerminalExitReason, TerminalLocation, TestRunProfileKind, TextDocumentChangeReason,
  TextEditorCursorStyle, TreeItemCheckboxState,
  // 本次补齐的类型
  UIKind, ColorTheme, ColorThemeKind, FileChangeType, FilePermission, CommentMode,
  CommentThreadCollapsibleState, Comment, CommentReply, CommentThread, CommentController,
  TextEditorSelectionChangeKind, TextEditorLineNumbersStyle, TextDocumentSaveReason,
  EnvironmentVariableMutatorType, NotebookCellStatusBarAlignment, NotebookCellExecutionState,
  TaskRevealKind, TaskPanelKind, Task, TaskGroup, TaskScope, ShellExecution, ProcessExecution,
  CustomExecution, QuickPick, InputBox, TreeView, FileDecoration, TextEditorDecorationType,
  MessageOptions, DocumentDropEdit, DocumentPasteEdit, SemanticTokensEdits,
  TabInputText, TabInputTextDiff, TelemetryLogger, NotebookController, SourceControl,
  window: guardNamespace(windowApi, 'window'),
  workspace: guardNamespace(workspaceApi, 'workspace'),
  languages: guardNamespace(languagesApi, 'languages'),
  commands: guardNamespace(commandsApi, 'commands'),
  extensions: guardNamespace(extensionsApi, 'extensions'),
  env: guardNamespace(envApi, 'env'),
  l10n: l10nApi,
  tasks: tasksApi,
  notebooks: notebooksApi,
  authentication: authenticationApi,
  debug: debugApi,
  scm: scmApi,
  tests: testsApi,
  lm: lmApi,
  chat: chatApi,
};

/**
 * 顶层 `vscode` 命名空间的兜底代理。
 *
 * 与 guardNamespace 同理，但这里拦的是**顶层标识符**：`vscode.DebugAdapterDescriptorFactory`、
 * `vscode.ChatRequestTurn` 之类。缺失时返回可构造的兼容桩，避免 `extends undefined` 把
 * 整个 activate() 拖死；同时上报缺口清单。
 */
const vscodeApi = new Proxy(vscodeApiBase, {
  get(obj, prop, receiver) {
    if (typeof prop === 'symbol') return Reflect.get(obj, prop, receiver);
    if (prop in obj) return Reflect.get(obj, prop, receiver);
    const name = String(prop);
    if (/^[A-Z]/.test(name)) {
      warnUnsupported('vscode.' + name);
      return makeCompatStub('vscode.' + name);
    }
    return undefined;
  },
});

const Module = require('module');
const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'vscode') return vscodeApi;
  return originalLoad.apply(this, arguments);
};

// ESM 扩展不走 Module._load（那是 CJS 的钩子），所以必须另给一条路：
// 把 API 挂到 globalThis，再由磁盘上的 node_modules/vscode/index.mjs 具名转出。
globalThis.__NEBULA_VSCODE_API__ = vscodeApi;

/**
 * 给 `import ... from 'vscode'` 铺一个真实的 node_modules 垫片。
 *
 * 背景：浏览器/现代构建产物的扩展越来越多地声明 `"type": "module"`（例如 Prettier 12），
 * 它们的 `import * as vscode from 'vscode'` 由 **Node 的 ESM 解析器**处理，
 * 先前只拦截 CJS 的 `Module._load` 对它完全无效，表现为
 * `ERR_REQUIRE_ESM`（对 CJS 加载）或 `ERR_MODULE_NOT_FOUND`（对 ESM 导入）。
 * 这里的垫片直接写进扩展目录，保证解析链一定命中：
 *   <扩展目录>/node_modules/vscode/{package.json,index.mjs,index.cjs}
 * index.mjs 从 globalThis 逐个具名转出，避免 Node 对 CJS 的具名导出探测不到
 * （那样 `import { window } from 'vscode'` 会拿到 undefined）。
 */
function ensureVscodeModuleShim() {
  const shimDir = path.join(extensionDir, 'node_modules', 'vscode');
  const files = {
    'package.json': JSON.stringify({
      name: 'vscode',
      version: '1.0.0',
      description: 'NebulaForge extension host API shim',
      type: 'module',
      main: './index.mjs',
      exports: { '.': { import: './index.mjs', require: './index.cjs', default: './index.mjs' } },
    }, null, 2),
    'index.cjs': 'module.exports = globalThis.__NEBULA_VSCODE_API__;\n',
  };
  const names = Object.keys(vscodeApi).filter((key) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key))
    .filter((key) => key !== 'default');
  const lines = [
    '// 由扩展宿主自动生成，请勿手改。',
    "const api = globalThis.__NEBULA_VSCODE_API__;",
    "if (!api) { throw new Error('NebulaForge vscode API 未初始化'); }",
    'export default api;',
  ];
  for (const name of names) lines.push('export const ' + name + ' = api.' + name + ';');
  files['index.mjs'] = lines.join('\n') + '\n';

  for (const [name, content] of Object.entries(files)) {
    const target = path.join(shimDir, name);
    const current = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
    if (current === content) continue;
    try {
      fs.mkdirSync(shimDir, { recursive: true });
      fs.writeFileSync(target, content);
    } catch (err) {
      notifyHost('log', { level: 'warn', text: '写入 vscode ESM 垫片失败: ' + String(err && err.message || err) });
      return;
    }
  }
}

/** 按 package.json 的模块类型加载扩展入口：ESM 用动态 import，CJS 用 require。 */
function loadExtensionEntry(mainPath) {
  let isEsm = /\.mjs$/i.test(mainPath);
  try {
    const declaredType = packageJson().type;
    if (declaredType === 'module') isEsm = true;
  } catch (err) { /* package.json 读不到：按后缀判断 */ }
  if (!isEsm) return Promise.resolve(require(mainPath));
  ensureVscodeModuleShim();
  const url = require('url').pathToFileURL(mainPath).href;
  return import(url).then((mod) => {
    // ESM 的命名空间对象直接拿来用：activate/deactivate 作为具名导出存在。
    if (mod && typeof mod.activate === 'function') return mod;
    return mod && mod.default ? mod.default : mod;
  });
}

// ==== 扩展加载与激活 =========================================================

let activated = false;
let activating = null;
let extensionExports = undefined;
let activationError = null;
let activationEvent = null;
// 激活尝试次数与失败时的 API 轨迹：允许有限重试（瞬时失败不该永久锁死命令），
// 同时把「死在哪个 API」记录下来，供命令执行失败时给出可定位的提示。
let activationAttempts = 0;
let activationFailedStage = null;

function resolveMainPath() {
  const declaredMain = typeof packageJson().main === 'string' ? packageJson().main.trim() : '';
  const declared = declaredMain.length > 0;
  // 只有声明了 main 才按声明找；否则退到常见入口名（很多扩展省略 main 但带 extension.js）。
  const candidates = declared
    ? [declaredMain]
    : ['./extension.js', './out/extension.js', './src/extension.js', './index.js'];
  for (const candidate of candidates) {
    const abs = path.resolve(extensionDir, candidate);
    for (const probe of [abs, abs + '.js', path.join(abs, 'index.js')]) {
      try {
        if (fs.statSync(probe).isFile()) return { path: probe, declared };
      } catch (err) { /* 继续探测下一个 */ }
    }
  }
  return { path: null, declared };
}

function activateExtension(reason) {
  if (activated) return Promise.resolve({ ok: true, alreadyActive: true });
  if (activating) return activating;
  activationAttempts += 1;
  activating = Promise.resolve().then(() => {
    const resolved = resolveMainPath();
    if (resolved.path === null) {
      if (!resolved.declared) {
        // 未声明 main 的扩展是合法的「纯声明式扩展」（只有 snippets/languages 贡献点）。
        activated = true;
        activationEvent = reason;
        return { ok: true, hasActivate: false, declarativeOnly: true };
      }
      throw new Error('package.json 声明的 main 入口不存在: ' + String(packageJson().main));
    }
    const mainPath = resolved.path;
    notifyHost('log', { level: 'info', text: '加载扩展入口: ' + path.relative(extensionDir, mainPath) });
    return loadExtensionEntry(mainPath).then((loaded) => {
      const activateFn = typeof loaded === 'function' ? loaded : (loaded && loaded.activate);
      extensionExports = loaded;
      if (typeof activateFn !== 'function') {
        // 只有声明式贡献点的扩展（纯 snippets / 语言）没有 activate，这是合法的。
        activated = true;
        activationEvent = reason;
        return { ok: true, hasActivate: false };
      }
      const context = extensionContextRef || extensionContext();
      return Promise.resolve(activateFn.call(loaded, context)).then(() => {
        activated = true;
        activationEvent = reason;
        return { ok: true, hasActivate: true };
      });
    });
  }).catch((err) => {
    activationError = errText(err);
    activationFailedStage = apiTraceText(12);
    notifyHost('log', {
      level: 'error',
      text: '扩展激活失败: ' + activationError
        + '｜已注册命令 ' + commandHandlers.size + ' 个'
        + '｜最近的宿主 API 调用: ' + activationFailedStage,
    });
    return { ok: false, error: activationError, apiTrace: apiTrace.slice(-12) };
  }).then((result) => { activating = null; return result; });
  return activating;
}

function deactivateExtension() {
  const context = extensionContextRef;
  if (context) {
    for (const sub of context.subscriptions.slice()) {
      try { if (sub && sub.dispose) sub.dispose(); } catch (err) { /* 忽略 */ }
    }
  }
  if (!activated) return Promise.resolve();
  const deactivateFn = extensionExports && typeof extensionExports.deactivate === 'function' ? extensionExports.deactivate : null;
  if (!deactivateFn) return Promise.resolve();
  return Promise.resolve().then(() => deactivateFn()).catch((err) => {
    notifyHost('log', { level: 'warn', text: 'deactivate 抛错: ' + String(err) });
  });
}

// ==== 宿主 → 扩展 ===========================================================

onRequest('initialize', (params) => {
  extensionIdentity = {
    id: params.extensionId || 'unknown',
    displayName: params.displayName || 'extension',
    version: params.version || '0.0.0',
  };
  if (Array.isArray(params.workspaceFolders)) {
    workspaceFolders = params.workspaceFolders.map((f) => ({
      uri: Uri.parse(f.uri),
      name: f.name || uriToPath(f.uri).replace(/\/$/, '').split('/').pop(),
    }));
  }
  if (params.state) {
    stateStore.global = params.state.global || {};
    stateStore.workspace = params.state.workspace || {};
  }
  extensionContextRef = extensionContext();
  const pkg = packageJson();
  // 配置必须在 activate 之前落定：扩展一进入激活就会同步读配置（getConfiguration 是同步 API）。
  return preloadConfiguration().then(() => ({
    ok: true,
    extensionId: extensionIdentity.id,
    main: pkg.main || null,
    mainResolved: resolveMainPath().path !== null,
    declaredCommands: ((pkg.contributes && pkg.contributes.commands) || []).map((c) => c.command),
    activationEvents: pkg.activationEvents || [],
    declaredConfiguration: Object.keys(declaredConfigDefaults()),
    nodeVersion: process.version,
  }));
});

onRequest('activate', (params) => activateExtension((params && params.event) || 'unknown'));

/** 命令调用后的返回体（宿主据此判断成功/失败，并展示原因）。 */
function invokeCommand(id, args) {
  const handler = commandHandlers.get(id);
  if (!handler) return Promise.resolve({ ok: false, error: '未注册' });
  return Promise.resolve()
    .then(() => handler.callback.apply(handler.thisArg, args || []))
    .then((result) => ({ ok: true, result: serializeResult(result) }))
    .catch((err) => ({ ok: false, error: errText(err) }));
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 执行命令前先「确保可执行」。
 *
 * 真实扩展有两类行为会让「命令已声明但点不动」：
 *  1. 命令在 `activate()` 的**后段**才注册（vscode-go 的调试类命令注册在
 *     `await maybeInstallImportantTools()` / 拉起语言服务器之后）；
 *  2. `activate()` 中途抛错（常常抛的是 `undefined` 这种非 Error 值），
 *     于是「前半段命令可用、后半段命令永远缺失」。
 *
 * 等待策略刻意分档，而不是一律等满：
 *  - 本次调用**刚刚触发过激活** → 注册很可能就在路上，给足预算；
 *  - 扩展**早已激活**却仍缺该命令 → 只给一个短宽限窗口，用来吃掉
 *    「activate() 已 resolve、注册在紧随其后的微任务/定时器里落地」这种竞态。
 *    更长的等待没有意义：命令若真在很久之后才注册（例如只在该功能被使用时），
 *    等待只会让用户对着转圈发呆 —— 此时如实报出原因更有用。
 */
async function ensureCommandReady(id, budgetMs) {
  if (commandHandlers.has(id)) return { ready: true };
  const alreadyActive = activated;
  // 允许重试：瞬时失败（工具链未就绪、宿主刚重启）不该把命令永久锁死。
  if (!activated && !activating && (!activationError || activationAttempts < 3)) {
    await activateExtension('onCommand:' + id);
  }
  const justActivated = !alreadyActive && activated;
  const wait = activationError ? 200 : (justActivated ? budgetMs : Math.min(budgetMs, 3000));
  const deadline = Date.now() + wait;
  while (!commandHandlers.has(id) && Date.now() < deadline) await sleep(50);
  if (commandHandlers.has(id)) return { ready: true };
  return {
    ready: false,
    activationError,
    activationStage: activationFailedStage,
    registered: commandHandlers.size,
    activating: !!activating,
    waitedMs: wait,
  };
}

onRequest('executeCommand', async (params) => {
  const id = String(params.command);
  const args = params.args || [];
  if (commandHandlers.has(id)) return invokeCommand(id, args);

  const budget = typeof params.waitMs === 'number' ? params.waitMs : 20_000;
  const state = await ensureCommandReady(id, budget);
  if (state.ready) return invokeCommand(id, args);

  // 仍未注册：按可诊断的几种情形给出原因（UI 直接展示给用户）
  const declared = declaredCommandIds().has(id);
  const parts = [];
  if (state.activating) parts.push('扩展仍在激活中，命令尚未注册');
  if (state.activationError) parts.push('扩展激活失败：' + state.activationError);
  if (!declared) parts.push('该扩展的 package.json 未声明此命令');
  else if (!state.activationError && !state.activating) {
    parts.push('命令已声明但运行时未注册（该扩展只在特定功能被触发时才注册它，例如调试相关命令）');
  }
  parts.push('已注册命令 ' + (state.registered || 0) + ' 个');
  if (state.activationStage) parts.push('最近的宿主 API 调用: ' + state.activationStage);
  return { ok: false, error: parts.join('；') };
});

function documentationText(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'object') return value.value === undefined ? null : String(value.value);
  return String(value);
}

onRequest('provideCompletionItems', (params) => {
  const entry = completionProviders.get(String(params.providerId));
  if (!entry) return { items: [] };
  const uri = String(params.uri);
  const text = typeof params.text === 'string' ? params.text : ((documents.get(uri) || { text: '' }).text);
  const doc = new TextDocument(uri, params.languageId || 'plaintext', text, params.version || 1);
  const position = new Position(params.line || 0, params.character || 0);
  const token = new CancellationTokenSource().token;
  const context = {
    triggerKind: params.triggerKind === undefined ? CompletionTriggerKind.Invoke : params.triggerKind,
    triggerCharacter: params.triggerCharacter || undefined,
  };
  return Promise.resolve(entry.provider.provideCompletionItems(doc, position, token, context)).then((result) => {
    const items = Array.isArray(result) ? result : (result && Array.isArray(result.items) ? result.items : []);
    return {
      items: items.map((item) => ({
        label: typeof item.label === 'string'
          ? item.label
          : (item.label && item.label.label ? String(item.label.label) : String(item.label)),
        kind: typeof item.kind === 'number' ? item.kind : CompletionItemKind.Text,
        detail: item.detail === undefined ? null : String(item.detail),
        documentation: documentationText(item.documentation),
        insertText: item.insertText === undefined ? null : (item.insertText instanceof SnippetString ? item.insertText.value : String(item.insertText)),
        isSnippet: item.insertText instanceof SnippetString || (item.insertText && typeof item.insertText === 'object' && 'value' in item.insertText),
        filterText: item.filterText === undefined ? null : String(item.filterText),
        sortText: item.sortText === undefined ? null : String(item.sortText),
        commitCharacters: Array.isArray(item.commitCharacters) ? item.commitCharacters.slice() : null,
      })),
      isIncomplete: !!(result && result.isIncomplete),
    };
  }).catch((err) => {
    notifyHost('log', { level: 'error', text: '补全 provider 抛错: ' + String((err && err.stack) || err) });
    return { items: [] };
  });
});

onRequest('deactivate', () => deactivateExtension().then(() => ({ ok: true })));
onRequest('ping', () => ({ pong: true, active: activated }));

// ==== WebView 通道（宿主 → 扩展） ============================================
onRequest('webview.deliver', (params) => {
  const id = String((params && params.id) || '');
  const entry = webviewPanels.get(id);
  if (!entry) {
    notifyHost('log', { level: 'warn', text: '界面消息无处投递（面板已关闭或被收敛回收）：' + id });
    return { ok: false, error: 'WebView 面板不存在或已关闭：' + id };
  }
  const started = Date.now();
  try {
    entry.receive.fire((params && params.message) === undefined ? null : params.message);
    // 只记「异常」的那几条：正常投递每次界面交互都会发生，全量记录会把日志冲爆；
    // 而恰恰是慢/失败的那几条能解释「面板打开了却一直空白」。
    const cost = Date.now() - started;
    if (cost > 500) {
      notifyHost('log', { level: 'warn', text: '界面消息处理偏慢：' + cost + 'ms（面板 ' + id + '）' });
    }
    return { ok: true };
  } catch (err) {
    notifyHost('log', { level: 'error', text: '界面消息处理异常（面板 ' + id + '）：' + errText(err) });
    return { ok: false, error: errText(err) };
  }
});

onRequest('webview.dispose', (params) => {
  const id = String((params && params.id) || '');
  const entry = webviewPanels.get(id);
  if (entry) webviewPanels.delete(id);
  return { ok: true };
});

/** 侧边栏视图提供者（registerWebviewViewProvider）按需解析成真实面板。 */
onRequest('webview.resolveView', async (params) => {
  const viewId = String((params && params.viewId) || '');
  const provider = webviewViewProviders.get(viewId);
  if (!provider || typeof provider.resolveWebviewView !== 'function') {
    return { ok: false, error: '没有可解析的视图提供者：' + viewId };
  }
  const id = 'view-' + viewId;
  const { facade: webview } = createWebviewFacade(id, { enableScripts: true });
  const view = {
    viewType: viewId,
    title: viewId,
    description: undefined,
    visible: true,
    onDidDispose: new EventEmitter().event,
    onDidChangeVisibility: new EventEmitter().event,
    webview,
    show: () => { notifyHost('webview.reveal', { id }); },
  };
  try {
    await provider.resolveWebviewView(view, { state: undefined }, new CancellationTokenSource().token);
    notifyHost('webview.create', {
      id, viewType: viewId, title: view.title || viewId, rootDir: extensionDir,
      enableScripts: true, kind: 'view',
    });
    return { ok: true, id };
  } catch (err) {
    return { ok: false, error: errText(err) };
  }
});

// 宿主推来的编辑器状态：扩展据此看到真实文档内容。
onRequest('documentOpened', (params) => {
  const uri = String(params.uri);
  documents.set(uri, { text: params.text || '', languageId: params.languageId || 'plaintext', version: params.version || 1 });
  const doc = documentFor(uri);
  if (doc) events.didOpenTextDocument.fire(doc);
  return { ok: true };
});

onRequest('documentChanged', (params) => {
  const uri = String(params.uri);
  const entry = documents.get(uri);
  const text = typeof params.text === 'string' ? params.text : (entry ? entry.text : '');
  const version = params.version || ((entry ? entry.version : 0) + 1);
  documents.set(uri, { text, languageId: params.languageId || (entry ? entry.languageId : 'plaintext'), version });
  const doc = documentFor(uri);
  if (doc) events.didChangeTextDocument.fire({ document: doc, contentChanges: [{ text }], reason: undefined });
  return { ok: true };
});

onRequest('documentSaved', (params) => {
  const doc = documentFor(String(params.uri));
  if (doc) events.didSaveTextDocument.fire(doc);
  return { ok: true };
});

onRequest('documentClosed', (params) => {
  const uri = String(params.uri);
  const doc = documentFor(uri);
  documents.delete(uri);
  if (doc) events.didCloseTextDocument.fire(doc);
  return { ok: true };
});

onRequest('setActiveEditor', (params) => {
  const uri = params && params.uri ? String(params.uri) : null;
  const entry = uri ? documents.get(uri) : undefined;
  activeEditorRef = entry ? new TextEditor(new TextDocument(uri, entry.languageId, entry.text, entry.version)) : null;
  events.didChangeActiveTextEditor.fire(activeEditorRef);
  return { ok: true };
});

// 未捕获异常：先隔离保活，再按次数升级。
//
// 为什么不再「一异常就退出」：VS Code 官方宿主会连带重启，但在手机端表现为
// 「点一下插件命令，宿主机直接没了」——用户体感就是闪退，而且一条插件命令的
// 异步回调异常会把其它已激活插件一起带走。这里改成：如实上报 + 隔离该异常 +
// 保持宿主与其它插件存活；只有**反复**失败（疑似状态已不可信）才重启进程。
let uncaughtCount = 0;
process.on('uncaughtException', (err) => {
  uncaughtCount += 1;
  const text = String((err && err.stack) || err);
  notifyHost('log', { level: 'error', text: '扩展宿主未捕获异常（已隔离，宿主保持运行）: ' + text });
  notifyHost('extensionCrash', { count: uncaughtCount, stack: text.slice(0, 4000) });
  if (uncaughtCount >= 20) {
    notifyHost('log', { level: 'error', text: '未捕获异常累计 ' + uncaughtCount + ' 次，判定状态不可信，重启扩展宿主' });
    setTimeout(() => process.exit(1), 50);
  }
});
process.on('unhandledRejection', (reason) => {
  notifyHost('log', { level: 'warn', text: '未处理的 Promise 拒绝: ' + String((reason && reason.stack) || reason) });
});

// ==== 扩展目录「本地覆盖文件」自动恢复 ========================================
// 有些插件必须靠宿主补一个扩展包本身没有的文件。最典型的是 Claude Code：扩展只会去
//   <ext>/resources/native-binaries/<platform>-<arch>/claude
// 找 CLI，而它的 VSIX 里并不带这个文件，于是在 Android 上必然报
//   "Unsupported platform: android-arm64. No compatible Claude Code binary found."
// 这类文件以「本地投放」清单记录在 ~/.nebulaforge/native-drops.json：
//   { "vscode.anthropic.claude-code": { "resources/native-binaries/android-arm64/claude": "/绝对/源文件" } }
// 扩展升级/重装会清掉扩展目录，这里每次启动做一次幂等恢复（按大小判断是否需要复制）。
function syncNativeDrops(dir) {
  try {
    const home = process.env.HOME || '';
    if (!home) return;
    const mapPath = path.join(home, '.nebulaforge', 'native-drops.json');
    if (!fs.existsSync(mapPath)) return;
    const drops = JSON.parse(fs.readFileSync(mapPath, 'utf8')) || {};
    const entries = drops[path.basename(dir)];
    if (!entries || typeof entries !== 'object') return;
    let restored = 0;
    for (const rel of Object.keys(entries)) {
      const src = String(entries[rel] || '');
      if (!src) continue;
      const dst = path.join(dir, rel);
      try {
        const srcStat = fs.statSync(src);
        if (fs.existsSync(dst) && fs.statSync(dst).size === srcStat.size) continue;
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.copyFileSync(src, dst);
        fs.chmodSync(dst, 0o700);
        restored += 1;
      } catch (err) {
        notifyHost('log', { level: 'warn', text: '本地覆盖文件恢复失败 ' + rel + '：' + String((err && err.message) || err) });
      }
    }
    if (restored > 0) {
      notifyHost('log', { level: 'info', text: '已从本地投放恢复 ' + restored + ' 个扩展文件（native-drops）' });
    }
  } catch (err) {
    notifyHost('log', { level: 'warn', text: 'native-drops 处理失败：' + String((err && err.message) || err) });
  }
}

syncNativeDrops(extensionDir);
notifyHost('ready', { node: process.version, extensionDir, main: packageJson().main || null });
