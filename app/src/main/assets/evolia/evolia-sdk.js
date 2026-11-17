/**
 * Evolia Mini App SDK（phase 1）— 由 addDocumentStartJavaScript 注入。
 *
 * 通信协议：postMessage({type:"evolia_request", id, method, params})，
 * 原生回复 {type:"evolia_response", id, ok, value|code, message}。
 * 仅在受控资源域（https://appassets.androidplatform.net/app/{app_id}/...）
 * 下注入，外部网页无法获得 evoliaNativeBridge 对象。
 *
 * app_id 由原生端绑定，前端不可指定（数据隔离硬约束）。
 */
(function () {
  'use strict';
  if (typeof evoliaNativeBridge === 'undefined') {
    // 不在受控来源下运行（不应发生）：不提供任何 SDK
    return;
  }

  var pending = {};
  var seq = 0;
  var lifecycleListeners = { ready: [], pause: [], resume: [], destroy: [] };

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      seq += 1;
      var id = seq;
      pending[id] = { resolve: resolve, reject: reject };
      var payload;
      try {
        payload = JSON.stringify({ type: 'evolia_request', id: id, method: method, params: params || {} });
      } catch (e) {
        delete pending[id];
        reject(new Error('INVALID_ARGUMENTS: 参数无法序列化为 JSON'));
        return;
      }
      try {
        evoliaNativeBridge.postMessage(payload);
      } catch (e) {
        delete pending[id];
        reject(new Error('BRIDGE_UNAVAILABLE: ' + (e && e.message ? e.message : '桥接不可用')));
      }
      // 30s 超时兜底
      setTimeout(function () {
        if (pending[id]) {
          var p = pending[id];
          delete pending[id];
          p.reject(new Error('EXECUTION_TIMEOUT: SDK 调用超时'));
        }
      }, 30000);
    });
  }

  function unwrapError(code, message) {
    var err = new Error((code || 'SDK_CALL_FAILED') + ': ' + (message || 'SDK 调用失败'));
    err.code = code;
    return err;
  }

  // 原生回复经 replyProxy.postMessage 回传，投递到注入对象的 onmessage
  evoliaNativeBridge.onmessage = function (event) {
    var data = event && event.data;
    if (!data || typeof data === 'string') {
      try { data = JSON.parse(data); } catch (e) { return; }
    }
    if (!data || data.type !== 'evolia_response' || data.id === undefined) return;
    var p = pending[data.id];
    if (!p) return;
    delete pending[data.id];
    if (data.ok) {
      p.resolve(data.value);
    } else {
      p.reject(unwrapError(data.code, data.message));
    }
  };

  var Evolia = {
    version: 'phase1',
    mode: 'mini_app',

    /** Mini App 私有数据 CRUD（自动绑定当前 app_id） */
    storage: {
      get: function (collection, id) { return call('storage.get', { collection: collection, id: id }); },
      list: function (collection, page, pageSize) {
        return call('storage.list', { collection: collection, page: page || 1, pageSize: pageSize || 20 });
      },
      insert: function (collection, data) { return call('storage.insert', { collection: collection, data: data }); },
      update: function (collection, id, data) {
        return call('storage.update', { collection: collection, id: id, data: data });
      },
      'delete': function (collection, id) { return call('storage.delete', { collection: collection, id: id }); }
    },

    /** 原生 UI 提示 */
    ui: {
      toast: function (message) { return call('ui.toast', { message: String(message) }); },
      dialog: function (options) {
        options = options || {};
        return call('ui.dialog', {
          title: String(options.title || '提示'),
          message: String(options.message || ''),
          confirmText: String(options.confirmText || '确定'),
          cancelText: String(options.cancelText || '取消')
        });
      }
    },

    /** 导航：关闭 Mini App 页面（回到 Evolia） */
    navigation: {
      close: function () { return call('navigation.close', {}); }
    },

    /** 生命周期事件注册 */
    lifecycle: {
      onReady: function (fn) { lifecycleListeners.ready.push(fn); fn(); },
      onPause: function (fn) { lifecycleListeners.pause.push(fn); },
      onResume: function (fn) { lifecycleListeners.resume.push(fn); },
      onDestroy: function (fn) { lifecycleListeners.destroy.push(fn); }
    }
  };

  Object.freeze(Evolia.storage);
  Object.freeze(Evolia.ui);
  Object.freeze(Evolia.navigation);
  Object.freeze(Evolia.lifecycle);
  Object.freeze(Evolia);
  window.Evolia = Evolia;

  function dispatch(name) {
    lifecycleListeners[name].forEach(function (fn) {
      try { fn(); } catch (e) { console.error(e); }
    });
  }
  dispatch('ready');
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { dispatch('pause'); } else { dispatch('resume'); }
  });
  window.addEventListener('beforeunload', function () { dispatch('destroy'); });
})();
