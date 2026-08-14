import { createVNode } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { ExclamationCircleFilled } from '@ant-design/icons-vue'

/**
 * 危险操作二次确认。结束演练、重置演练、强制撤单、批量停用机器人一律走这里。
 *
 * @param {object} opts
 * @param {string} opts.title      弹窗标题
 * @param {string|import('vue').VNode} opts.content 正文，可传 VNode 做多行警示
 * @param {string} [opts.okText]   确认按钮文案，默认「确认执行」
 * @param {string} [opts.success]  成功后的 toast 文案
 * @param {() => Promise<any>} opts.onOk 真正的动作
 */
export function dangerConfirm(opts) {
  return new Promise((resolve) => {
    Modal.confirm({
      title: opts.title,
      icon: createVNode(ExclamationCircleFilled, { style: 'color:#f5222d' }),
      content: opts.content,
      okText: opts.okText || '确认执行',
      okType: 'danger',
      cancelText: '取消',
      centered: true,
      width: opts.width || 460,
      class: 'ets-danger-modal',
      async onOk() {
        try {
          const r = await opts.onOk()
          if (opts.success) message.success(opts.success)
          resolve(r ?? true)
        } catch (e) {
          // 业务错误提示已由 axios 拦截器统一弹出，这里只负责不让弹窗卡住
          resolve(false)
          throw e
        }
      },
      onCancel() {
        resolve(false)
      },
    })
  })
}

/** 普通确认，用于开市/停牌这类可逆动作 */
export function askConfirm(opts) {
  return new Promise((resolve) => {
    Modal.confirm({
      title: opts.title,
      content: opts.content,
      okText: opts.okText || '确定',
      cancelText: '取消',
      centered: true,
      async onOk() {
        try {
          const r = await opts.onOk()
          if (opts.success) message.success(opts.success)
          resolve(r ?? true)
        } catch (e) {
          resolve(false)
          throw e
        }
      },
      onCancel: () => resolve(false),
    })
  })
}
