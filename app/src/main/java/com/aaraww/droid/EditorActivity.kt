package com.aaraww.droid

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class EditorActivity : AppCompatActivity() {

    private lateinit var editorView: EditText
    private lateinit var terminalView: TextView
    private lateinit var terminalScroll: ScrollView

    private var currentFile: File? = null
    private var pendingInputCallback: ((String) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AaRaww Editor"

        editorView = findViewById(R.id.editorView)
        terminalView = findViewById(R.id.terminalView)
        terminalScroll = findViewById(R.id.terminalScroll)

        // Handle file open via intent
        when {
            intent.getBooleanExtra("NEW_FILE", false) -> {
                editorView.setText(DEFAULT_TEMPLATE)
                supportActionBar?.subtitle = "new_file.arw"
            }
            intent.hasExtra("FILE_URI") -> {
                val uri = Uri.parse(intent.getStringExtra("FILE_URI"))
                loadFromUri(uri)
            }
            intent.data != null -> {
                loadFromUri(intent.data!!)
            }
            else -> {
                editorView.setText(DEFAULT_TEMPLATE)
            }
        }

        // Syntax highlighting hint (simple monospace)
        editorView.typeface = android.graphics.Typeface.MONOSPACE
        terminalView.typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.menu_run -> { runCode(); true }
            R.id.menu_save -> { saveFile(); true }
            R.id.menu_save_as -> { saveFileAs(); true }
            R.id.menu_clear_terminal -> { terminalView.text = ""; true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun runCode() {
        val code = editorView.text.toString()
        terminalView.text = "> Running...\n"

        val interpreter = AarRawInterpreter()

        // Input handler - shows dialog and waits
        interpreter.inputHandler = { prompt ->
            var result = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                val input = EditText(this)
                input.hint = "Enter input..."
                AlertDialog.Builder(this)
                    .setTitle(prompt.ifEmpty { "Input" })
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        result = input.text.toString()
                        latch.countDown()
                    }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
            latch.await()
            result
        }

        executor.execute {
            val output = try {
                interpreter.interpret(code)
            } catch (e: Exception) {
                "[ERROR]: ${e.message}"
            }

            mainHandler.post {
                terminalView.text = "> Output:\n$output\n> Done."
                terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun saveFile() {
        val file = currentFile
        if (file == null) {
            saveFileAs()
            return
        }
        writeToFile(file)
    }

    private fun saveFileAs() {
        val input = EditText(this)
        input.hint = "filename.arw"
        currentFile?.let { input.setText(it.name) }

        AlertDialog.Builder(this)
            .setTitle("Save As")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var name = input.text.toString().trim()
                if (!name.endsWith(".arw")) name += ".arw"
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, name)
                writeToFile(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeToFile(file: File) {
        try {
            FileOutputStream(file).use { fos ->
                fos.write(editorView.text.toString().toByteArray())
            }
            currentFile = file
            supportActionBar?.subtitle = file.name
            Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
            val text = stream?.bufferedReader()?.readText() ?: ""
            stream?.close()
            editorView.setText(text)

            // Try to get filename
            val path = uri.lastPathSegment ?: "file.arw"
            supportActionBar?.subtitle = path

            // If it's a local file, track it
            if (uri.scheme == "file") {
                currentFile = File(uri.path ?: "")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        val DEFAULT_TEMPLATE = """# AarRaw - Hello World
on start:
    print.newline('Hello, World!')

after line.2:
    end
""".trimIndent()
    }
}
