package com.aaraww.droid

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class EditorActivity : AppCompatActivity() {

    private lateinit var editorView: EditText
    private lateinit var terminalView: TextView
    private lateinit var terminalScroll: ScrollView
    private var currentFile: File? = null

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
        // Buka terminal popup fullscreen
        TerminalDialog(this, code).show()
    }

    private fun getRawwDir(): File {
        val sdcard = Environment.getExternalStorageDirectory()
        val dir = File(sdcard, "raww")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveFile() {
        currentFile?.let { writeToFile(it) } ?: saveFileAs()
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
                writeToFile(File(getRawwDir(), name))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeToFile(file: File) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(editorView.text.toString().toByteArray()) }
            currentFile = file
            supportActionBar?.subtitle = file.name
            Toast.makeText(this, "Saved: /sdcard/raww/${file.name}", Toast.LENGTH_SHORT).show()
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
