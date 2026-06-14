package com.aaraww.droid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TerminalDialog(private val context: Context, private val code: String) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var dialog: Dialog
    private lateinit var termOutput: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var inputBar: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var inputPrompt: TextView
    private lateinit var btnSend: Button
    private lateinit var btnClose: Button

    private var inputLatch: CountDownLatch? = null
    private var inputResult = ""

    fun show() {
        dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_terminal)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setCancelable(false)

        termOutput = dialog.findViewById(R.id.termOutput)
        termScroll = dialog.findViewById(R.id.termScroll)
        inputBar = dialog.findViewById(R.id.inputBar)
        inputField = dialog.findViewById(R.id.inputField)
        inputPrompt = dialog.findViewById(R.id.inputPrompt)
        btnSend = dialog.findViewById(R.id.btnSend)
        btnClose = dialog.findViewById(R.id.btnClose)

        btnClose.setOnClickListener {
            executor.shutdownNow()
            dialog.dismiss()
        }

        btnSend.setOnClickListener { submitInput() }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitInput(); true } else false
        }

        dialog.show()
        startExecution()
    }

    private fun submitInput() {
        inputResult = inputField.text.toString()
        inputField.text.clear()
        hideInput()
        inputLatch?.countDown()
    }

    private fun showInput(prompt: String) {
        mainHandler.post {
            inputBar.visibility = View.VISIBLE
            inputPrompt.text = if (prompt.isNotEmpty()) "$prompt " else "> "
            inputField.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputField.postDelayed({ imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT) }, 100)
        }
    }

    private fun hideInput() {
        mainHandler.post {
            inputBar.visibility = View.GONE
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(inputField.windowToken, 0)
        }
    }

    private fun appendOutput(text: String) {
        mainHandler.post {
            termOutput.append(text)
            termScroll.post { termScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startExecution() {
        val interpreter = AarRawInterpreter()

        interpreter.outputHandler = { text ->
            appendOutput(text)
        }

        interpreter.inputHandler = { prompt ->
            val latch = CountDownLatch(1)
            inputLatch = latch
            inputResult = ""
            showInput(prompt)
            latch.await()
            inputResult
        }

        interpreter.delayHandler = { ms ->
            if (ms > 0) Thread.sleep(ms)
        }

        executor.execute {
            try {
                interpreter.interpret(code)
                mainHandler.post {
                    appendOutput("\n[Program selesai]")
                    btnClose.text = "✕ Close"
                    btnClose.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#1a2a1a")
                    )
                    btnClose.setTextColor(android.graphics.Color.parseColor("#00ff88"))
                }
            } catch (e: InterruptedException) {
                // Dialog closed mid-run
            } catch (e: Exception) {
                mainHandler.post { appendOutput("\n[ERROR]: ${e.message}") }
            }
        }
    }
}
