'use strict';
/*
 * 内置 TypeScript/JavaScript 语言能力 —— 占位实现。
 *
 * 为什么需要这个文件真实存在于磁盘上：
 *  Vue Volar 在 require 阶段会做
 *    require.resolve('./dist/extension.js', { paths: [tsExtension.extensionPath] })
 *  并把该文件读进来做字符串补丁（给 tsserver 注册 Vue 相关的 TS 插件）。
 *  文件不存在 → require.resolve 抛错 → 整个 Volar 直接加载失败。
 *
 * 本宿主不运行 tsserver，所以这里只需要保证：
 *  1) 路径可解析；
 *  2) 内容可被安全读取（Volar 的正则替换不匹配即 no-op，不会破坏任何东西）；
 *  3) activate/deactivate 被调用时不会崩。
 * 后续若要真正支持 TS 语言能力，只需要把真实实现替换进本文件即可，接口不用变。
 */

const api = {
  /** 与 VS Code 内置 TS 扩展同名 API：调用方一般会 `await getAPI(0)`。 */
  getAPI() {
    return {
      configurePlugin() { /* 无 tsserver：忽略 */ },
      projectInfo() { return undefined; },
    };
  },
};

function activate() {
  // 导出给第三方（Volar 等）读 `.exports`，但宿主不主动做语言功能。
  return api;
}

function deactivate() {
  return undefined;
}

module.exports = { activate, deactivate };
