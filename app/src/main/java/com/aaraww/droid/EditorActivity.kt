package com.aaraww.droid

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
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

        editorView.typeface = android.graphics.Typeface.MONOSPACE
        terminalView.typeface = android.graphics.Typeface.MONOSPACE

        when {
            intent.getBooleanExtra("NEW_FILE", false) -> {
                editorView.setText(DEFAULT_TEMPLATE)
                supportActionBar?.subtitle = "new_file.arw"
            }
            intent.hasExtra("FILE_URI") -> loadFromUri(Uri.parse(intent.getStringExtra("FILE_URI")))
            intent.data != null -> loadFromUri(intent.data!!)
            else -> editorView.setText(DEFAULT_TEMPLATE)
        }
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
        interpreter.inputHandler = { prompt ->
            var result = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                val input = EditText(this)
                input.hint = "Enter input..."
                AlertDialog.Builder(this)
                    .setTitle(prompt.ifEmpty { "Input" })
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result = input.text.toString(); latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
            latch.await()
            result
        }
        executor.execute {
            val output = try { interpreter.interpret(code) } catch (e: Exception) { "[ERROR]: ${e.message}" }
            mainHandler.post {
                terminalView.text = "> Output:\n$output\n> Done."
                terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun getRawwDir(): File {
        // Save to /sdcard/raww/ (Environment.getExternalStorageDirectory)
        val sdcard = Environment.getExternalStorageDirectory()
        val dir = File(sdcard, "raww")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveFile() {
        val file = currentFile
        if (file == null) saveFileAs() else writeToFile(file)
    }

    private fun saveFileAs() {
        val input = EditText(this)
        input.hint = "filename.arw"
        currentFile?.let { input.setText(it.nameWithoutExtension) }

        AlertDialog.Builder(this)
            .setTitle("Save to /sdcard/raww/")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (!name.endsWith(".arw")) name += ".arw"
                val file = File(getRawwDir(), name)
                writeToFile(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeToFile(file: File) {
        try {
            // Ensure parent dir exists
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(editorView.text.toString().toByteArray()) }
            currentFile = file
            supportActionBar?.subtitle = file.name
            Toast.makeText(this, "Saved to /sdcard/raww/${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            editorView.setText(text)
            supportActionBar?.subtitle = uri.lastPathSegment ?: "file.arw"
            if (uri.scheme == "file") currentFile = File(uri.path ?: "")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        val DEFAULT_TEMPLATE = """# AarRaw - Hello World
on start:
    print.newline('Hello, World!')

after line.2:
    end"""
    }
}
