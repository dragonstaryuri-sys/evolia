package me.rerere.rikkahub.data.extensions

/**
 * Tool Runtime SDK 前导脚本（详设 9.4 受控 SDK 注入）。
 *
 * 原生函数以 JSON 字符串进出（{ok, value} / {ok:false, code, message}），
 * 本脚本包装为友好的同步 `evolia` API；原生调用失败时抛出 Error。
 *
 * **工具代码运行于受限同步环境：不支持 Promise/setTimeout/事件循环**
 * （wrapper 3.2.3 无事件循环泵，Spike 结论），SDK 全部为同步阻塞 API。
 */
object ExtensionSdkPrelude {

    const val SDK_PRELUDE: String = """
;(function() {
  'use strict';
  var evolia = { version: 'phase1', mode: 'tool' };

  function __call(name, args) {
    var raw = globalThis[name](typeof args === 'string' ? args : JSON.stringify(args));
    var parsed;
    try { parsed = JSON.parse(raw); } catch (e) {
      throw new Error('SDK_CALL_FAILED: 原生返回不是合法 JSON');
    }
    if (!parsed.ok) {
      var err = new Error(parsed.code + ': ' + (parsed.message || 'SDK 调用失败'));
      err.code = parsed.code;
      throw err;
    }
    return parsed.value;
  }

  /** 受控网络：{url, method?, headers?, body?} → {status, body, contentType?} */
  evolia.network = {
    request: function(params) { return __call('__evoliaRequest', params || {}); },
    get: function(url, headers) {
      return __call('__evoliaRequest', { url: url, method: 'GET', headers: headers || {} });
    },
    postJson: function(url, data, headers) {
      var h = headers || {};
      h['Content-Type'] = 'application/json';
      return __call('__evoliaRequest', { url: url, method: 'POST', headers: h, body: JSON.stringify(data) });
    }
  };

  /** 私有数据 CRUD（自动绑定本扩展 app_id） */
  evolia.storage = {
    get: function(collection, id) { return __call('__evoliaStorageGet', { collection: collection, id: id }); },
    list: function(collection, page, pageSize) {
      return __call('__evoliaStorageList', { collection: collection, page: page || 1, pageSize: pageSize || 20 });
    },
    insert: function(collection, data) { return __call('__evoliaStorageInsert', { collection: collection, data: data }); },
    update: function(collection, id, data) { return __call('__evoliaStorageUpdate', { collection: collection, id: id, data: data }); },
    'delete': function(collection, id) { return __call('__evoliaStorageDelete', { collection: collection, id: id }); }
  };

  evolia.ui = {
    toast: function(message) { return __call('__evoliaToast', { message: String(message) }); }
  };

  Object.freeze(evolia.network);
  Object.freeze(evolia.storage);
  Object.freeze(evolia.ui);
  Object.freeze(evolia);
  globalThis.evolia = evolia;
})();
"""
}
