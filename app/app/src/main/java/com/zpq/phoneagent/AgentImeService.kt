package com.zpq.phoneagent

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout

/**
 * 极简输入法：存在的唯一目的，是**零 adb 也能往任意输入框写字**。
 *
 * 为什么需要它：自绘/WebView 输入框不暴露 EditableText，`ACTION_SET_TEXT` 写不进去；
 * 而且它们往往只监听"输入法提交的文字"来更新按钮状态——光改显示值（粘贴）不会触发回调，
 * 按钮点了没反应（实测拼多多绑定页 `submit-btn`：clickable=true、performAction 返回成功、页面不动）。
 * 输入法的 `InputConnection.commitText()` 会正常走 OnTextChanged，是唯一能同时改值 + 触发业务回调的通道。
 *
 * 两个实测坑（v16 踩过）：
 *   1. 单纯切默认输入法不会把 `InputConnection` 交给新 IME —— 已聚焦的输入框不会重新请求输入法，
 *      必须由 IME 自己调 `requestShowSelf()` 逼系统重新绑定；
 *   2. `currentInputConnection` 在切换瞬间经常还是 null，所以要在 `onStartInput` 里把连接存下来，
 *      commit 时优先用存下的那个。
 */
class AgentImeService : InputMethodService() {

    companion object {
        @Volatile
        var instance: AgentImeService? = null
            private set

        @Volatile
        private var conn: InputConnection? = null

        /** 逼系统把输入连接交给本输入法。必须在切默认输入法之后调。 */
        fun prepare(): Boolean {
            val ime = instance ?: return false
            return try {
                ime.requestShowSelf(0)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun commit(text: String): Boolean {
            val c = conn ?: instance?.currentInputConnection ?: return false
            return try {
                c.commitText(text, 1)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun connected(): Boolean = conn != null || instance?.currentInputConnection != null

        /**
         * 通过输入法通道发一次回车。
         *
         * 为什么需要：`dispatchGesture` 注入的触摸在 Android 12+ 会被标记为非真实输入
         * （`MotionEvent.FLAG_TAINTED`），部分 App 的提交按钮校验这个标记 ⇒ 点了没反应
         * （实测拼多多「下一步」「立即登录」全是这样，而返回/选择类按钮正常）。
         * 输入法发按键走的是系统输入通道，**不带这个标记**；配合无障碍 `ACTION_FOCUS`
         * 把焦点移到按钮上，回车就能触发按钮的点击回调。全程不需要 adb。
         */
        fun sendKey(code: Int): Boolean {
            val ime = instance ?: return false
            return try {
                ime.sendDownUpKeyEvents(code)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            conn = null
        }
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        conn = currentInputConnection
    }

    override fun onFinishInput() {
        conn = null
        super.onFinishInput()
    }

    /** 不显示软键盘：注入文字不需要界面，弹出来只会挡住目标输入框 */
    override fun onCreateInputView(): View = FrameLayout(this)

    override fun onEvaluateInputViewShown(): Boolean = true
}
