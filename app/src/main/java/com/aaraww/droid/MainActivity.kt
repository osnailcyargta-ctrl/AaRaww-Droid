package com.aaraww.droid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnNewFile).setOnClickListener {
            val intent = Intent(this, EditorActivity::class.java)
            intent.putExtra("NEW_FILE", true)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnOpenFile).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, REQUEST_OPEN_FILE)
        }

        // Show recent files
        loadRecentFiles()
    }

    private fun loadRecentFiles() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val files = dir.listFiles { f -> f.extension == "arw" } ?: emptyArray()
        val tv = findViewById<TextView>(R.id.tvRecentFiles)
        if (files.isEmpty()) {
            tv.text = "No .arw files yet"
        } else {
            tv.text = "Recent files:\n" + files.joinToString("\n") { "• ${it.name}" }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val intent = Intent(this, EditorActivity::class.java)
            intent.putExtra("FILE_URI", uri.toString())
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecentFiles()
    }

    companion object {
        const val REQUEST_OPEN_FILE = 1001
    }
}
